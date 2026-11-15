package com.aiwa.asb060

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanEngineTest {
    private val db = realDb()
    private val plan = ScanPlan.full(db)
    private val bar = FakeBar()
    private val rec = Recorder()
    private val engine = ScanEngine(plan, bar, bar, { bar.now }, rec)

    private fun indexOf(code: String) = (0 until 2000).first { plan[it].code.format() == code }

    @Test
    fun twinCodeIsFoundAtOnceAndConfirmed() {
        bar.codes["NEC:20:1B"] = "play"
        engine.start()
        bar.run(engine, 10_000)

        val d = rec.detections.single()
        assertEquals(Detection.Type.PAUSE, d.type)
        assertTrue(d.confirmed)
        assertEquals("NEC:20:1B", d.candidates.single().code.format())
        assertEquals("Majority K2 SoundBar", d.candidates.single().profile?.name)
        assertFalse(engine.running)
        assertTrue("la musique est relancée à la fin", bar.music)
        // Un seul essai, puis un seul renvoi pour vérifier.
        assertEquals(listOf("NEC:20:1B", "NEC:20:1B"), bar.sent)
    }

    @Test
    fun slowBarStillGivesTheRightCode() {
        val target = plan[40].code.format()
        bar.codes[target] = "play"
        bar.latency = 1100
        engine.start()
        bar.run(engine, 80_000)

        val d = rec.detections.single()
        assertTrue(d.confirmed)
        assertEquals(target, d.candidates.single().code.format())
        assertEquals(0, rec.falseAlarms)
        assertTrue(bar.music)
        // La recherche reprendra après les codes déjà essayés.
        assertTrue(engine.index in 41..43)
    }

    @Test
    fun barThatBlindlyAlternatesIsStillConfirmed() {
        val target = plan[12].code.format()
        bar.codes[target] = "play"
        bar.alternating = true
        engine.start()
        bar.run(engine, 80_000)

        val d = rec.detections.single()
        assertEquals(Detection.Type.PAUSE, d.type)
        assertTrue(d.confirmed)
        assertEquals(target, d.candidates.single().code.format())
    }

    @Test
    fun powerCodeIsReportedAsBluetoothLost() {
        val step = (0 until plan.phaseStarts[1]).map { plan[it] }.first { it.function == "power" }
        val target = step.code.format()
        bar.codes[target] = "power"
        engine.start()
        bar.run(engine, 120_000)

        val d = rec.detections.single()
        assertEquals(Detection.Type.DISCONNECT, d.type)
        assertFalse(d.confirmed)
        assertTrue(d.candidates.any { it.code.format() == target })
        assertFalse(engine.running)
        assertFalse("pas de musique sur le haut-parleur du téléphone", bar.music)
        // Aucun code n'est renvoyé à une barre éteinte.
        assertEquals(0, rec.verifySends)
    }

    @Test
    fun powerCodeIsNoticedEvenWithoutMusic() {
        val step = (0 until plan.phaseStarts[1]).map { plan[it] }.first { it.function == "power" }
        bar.music = false
        bar.codes[step.code.format()] = "power"
        engine.start()
        bar.run(engine, 120_000)

        val d = rec.detections.single()
        assertEquals(Detection.Type.DISCONNECT, d.type)
        assertTrue(d.candidates.any { it.code.format() == step.code.format() })
        assertTrue(d.candidates.size <= ScanEngine.DISCONNECT_CANDIDATES)
        assertEquals(0, rec.verifySends)
    }

    @Test
    fun barThatIsSlowToDropBluetoothStillListsTheRightCode() {
        val step = (0 until plan.phaseStarts[1]).map { plan[it] }.first { it.function == "power" }
        bar.codes[step.code.format()] = "power"
        bar.disconnectDelay = 2800
        engine.start()
        bar.run(engine, 120_000)

        val d = rec.detections.single()
        assertEquals(Detection.Type.DISCONNECT, d.type)
        assertTrue(d.candidates.map { it.code.format() }.toString(), d.candidates.any { it.code.format() == step.code.format() })
        assertFalse(bar.music)
    }

    @Test
    fun detectionStartsOnceBluetoothIsConnected() {
        bar.bt = false
        bar.music = false
        val target = plan[10].code.format()
        bar.codes[target] = "play"
        bar.at(2_000) {
            bar.bt = true
            bar.music = true
        }
        engine.start()
        assertFalse(engine.canDetect)
        bar.run(engine, 40_000)

        val d = rec.detections.single()
        assertEquals(Detection.Type.PAUSE, d.type)
        assertTrue(d.confirmed)
        assertEquals(target, d.candidates.single().code.format())
        assertEquals(0, rec.falseAlarms)
    }

    @Test
    fun musicStoppingByItselfIsAFalseAlarm() {
        bar.at(5_000) { bar.music = false }
        engine.start()
        bar.run(engine, 60_000)

        assertEquals(1, rec.falseAlarms)
        assertTrue(rec.detections.isEmpty())
        assertTrue(engine.running)
        assertEquals(ScanEngine.State.SCANNING, engine.state)
        assertTrue(bar.music)
        assertTrue(rec.verifySends in 2..6)
        assertTrue("la recherche a continué", engine.index > 30)
    }

    @Test
    fun volumeCodeIsFoundAndVolumeRestored() {
        val target = plan[5].code.format()
        bar.codes[target] = "volup"
        engine.start()
        bar.run(engine, 30_000)

        val d = rec.detections.single()
        assertEquals(Detection.Type.VOLUME_UP, d.type)
        assertTrue(d.confirmed)
        assertEquals(target, d.candidates.single().code.format())
        assertEquals(8, bar.phoneVolume)
    }

    @Test
    fun volumeDownIsRecognisedToo() {
        val target = plan[3].code.format()
        bar.codes[target] = "voldown"
        engine.start()
        bar.run(engine, 30_000)
        assertEquals(Detection.Type.VOLUME_DOWN, rec.detections.single().type)
        assertEquals(target, rec.detections.single().candidates.single().code.format())
        assertEquals(8, bar.phoneVolume)
    }

    @Test
    fun withoutBluetoothNothingIsDetected() {
        bar.bt = false
        bar.at(3_000) { bar.music = false }
        bar.at(6_000) { bar.phoneVolume = 3 }
        engine.start()
        bar.run(engine, 20_000)

        assertTrue(rec.detections.isEmpty())
        assertEquals(0, rec.falseAlarms)
        assertTrue(engine.running)
        assertFalse(engine.canDetect)
        assertTrue(bar.sent.size in 24..26)
    }

    @Test
    fun detectionCanBeSwitchedOff() {
        bar.codes["NEC:20:1B"] = "play"
        engine.autoDetect = false
        engine.start()
        bar.run(engine, 8_000)
        assertTrue(rec.detections.isEmpty())
        assertTrue(engine.running)
        assertEquals(10, bar.sent.size)
    }

    @Test
    fun whenMusicCannotBeResumedTheUserDecides() {
        val target = plan[2].code.format()
        bar.codes[target] = "play"
        bar.playerAnswers = false
        engine.start()
        bar.run(engine, 30_000)

        val d = rec.detections.single()
        assertEquals(Detection.Type.PAUSE, d.type)
        assertFalse(d.confirmed)
        assertTrue(d.candidates.any { it.code.format() == target })
    }

    @Test
    fun codeThatStartsPausedMusicIsFoundToo() {
        bar.music = false
        bar.codes["NEC:20:1B"] = "play"
        engine.start()
        bar.run(engine, 10_000)

        val d = rec.detections.single()
        assertTrue(d.confirmed)
        assertEquals("NEC:20:1B", d.candidates.single().code.format())
    }

    @Test
    fun searchContinuesAfterADetection() {
        bar.codes[plan[4].code.format()] = "play"
        engine.start()
        bar.run(engine, 30_000)
        assertEquals(1, rec.detections.size)
        val resumeAt = engine.index
        engine.start()
        bar.run(engine, 5_000)
        assertTrue(engine.running)
        assertTrue(engine.index > resumeAt)
        assertEquals(1, rec.detections.size)
    }

    @Test
    fun manualStepsAndManualReport() {
        engine.sendManual(engine.index)
        engine.sendManual(engine.index)
        engine.sendManual(engine.index)
        assertEquals((0..2).map { plan[it].code.format() }, bar.sent)
        engine.sendManual(engine.index - 2)
        assertEquals(plan[1].code.format(), bar.sent.last())
        assertEquals(plan[1].code.format(), engine.lastSent?.code?.format())
        engine.sendManual(-5)
        assertEquals(plan[0].code.format(), bar.sent.last())

        val d = engine.manualDetection()
        assertEquals(Detection.Type.MANUAL, d.type)
        assertEquals(listOf(0, 1, 2).map { plan[it].code.format() }, d.candidates.map { it.code.format() })
        assertFalse(engine.running)
    }

    @Test
    fun pauseAndResumeKeepThePosition() {
        engine.start()
        bar.run(engine, 4_000)
        engine.stop()
        val at = engine.index
        assertEquals(bar.sent.size, at)
        bar.run(engine, 4_000)
        assertEquals(at, engine.index)
        engine.start()
        bar.run(engine, 1_000)
        assertEquals(plan[at].code.format(), bar.sent[at])
    }

    @Test
    fun singleAddressSearchEndsCleanly() {
        val small = ScanPlan.singleAddress("20")!!
        val e = ScanEngine(small, bar, bar, { bar.now }, rec)
        e.intervalMs = 300
        e.start()
        bar.run(e, 120_000)
        assertEquals(1, rec.finished)
        assertFalse(e.running)
        assertEquals(256, bar.sent.size)
        assertEquals((0..255).map { small[it].code.format() }, bar.sent)
        e.start()
        assertEquals(2, rec.finished)
    }

    @Test
    fun codeIsFoundInTheAddressSweepOfTheLastPhase() {
        // Même adresse que la télécommande identique, mais Lecture/Pause sur une autre commande.
        val target = "NEC:20:99"
        bar.codes[target] = "play"
        engine.index = indexOf(target) - 3
        engine.start()
        bar.run(engine, 30_000)
        val d = rec.detections.single()
        assertTrue(d.confirmed)
        assertEquals(target, d.candidates.single().code.format())
    }
}
