package com.aiwa.asb060

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    private val db = realDb()

    @Test
    fun twinRemoteHasAllTwelveKeys() {
        val twin = db.twin!!
        assertEquals("Majority K2 SoundBar", twin.name)
        assertEquals(twin, db.profiles.first())
        val expected = mapOf(
            Keys.POWER to "NEC:20:41", Keys.MODE to "NEC:20:50",
            Keys.VOL_UP to "NEC:20:42", Keys.VOL_DOWN to "NEC:20:43",
            Keys.PREV to "NEC:20:1A", Keys.PLAY to "NEC:20:1B", Keys.NEXT to "NEC:20:4F",
            Keys.BASS_UP to "NEC:20:14", Keys.BASS_DOWN to "NEC:20:56",
            Keys.TREBLE_UP to "NEC:20:4D", Keys.TREBLE_DOWN to "NEC:20:51", Keys.EQ to "NEC:20:5A",
        )
        assertEquals(Keys.all.toSet(), expected.keys)
        for ((key, code) in expected) assertEquals(key.id, code, twin.codeFor(key)?.format())
    }

    @Test
    fun databaseIsRankedAndUsable() {
        assertTrue(db.profiles.size > 80)
        assertEquals(1, db.profiles.count { it.twin })
        val scores = db.profiles.drop(1).map { it.score }
        assertEquals(scores.sortedDescending(), scores)
        assertTrue(db.profiles.all { it.codes.isNotEmpty() && it.name.isNotBlank() })
        assertTrue(db.addresses.size > 50)
        assertTrue(db.addresses.all { IrCode.Nec.of(it.address, 0) != null && it.commands.isNotEmpty() })
    }

    @Test
    fun knownCodesAreRecognised() {
        val (profile, function) = db.matches(IrCode.parse("NEC:20:1B")!!).first()
        assertEquals("Majority K2 SoundBar", profile.name)
        assertEquals("play", function)
        assertTrue(db.matches(IrCode.parse("NEC:F3:F3")!!).isEmpty())
    }

    @Test
    fun fullPlanGoesFromMostToLeastLikely() {
        val plan = ScanPlan.full(db)
        assertEquals(3, plan.phaseTitles.size)
        assertEquals(3, plan.phaseStarts.size)
        assertEquals(0, plan.phaseStarts[0])

        // D'abord la touche Lecture/Pause de la télécommande identique.
        assertEquals("NEC:20:1B", plan[0].code.format())
        assertEquals("play", plan[0].function)
        assertEquals("Majority K2 SoundBar", plan[0].profile?.name)

        // Étapes 1 et 2 : aucun code en double.
        val sweepStart = plan.phaseStarts[2]
        val listed = (0 until sweepStart).map { plan[it] }
        assertEquals(listed.size, listed.map { it.code.format() }.toSet().size)
        assertTrue(listed.take(plan.phaseStarts[1]).all { it.phase == 0 && it.profile != null })
        val twinPhase = listed.drop(plan.phaseStarts[1])
        assertTrue(twinPhase.size >= 255)
        assertTrue(twinPhase.all { it.phase == 1 && (it.code as IrCode.Nec).c0 == 0x1B })

        // Étape 3 : l'adresse de la télécommande identique en premier, ses commandes connues d'abord.
        val first = plan[sweepStart]
        assertEquals(2, first.phase)
        assertEquals("20", (first.code as IrCode.Nec).addressText)
        val address20 = (sweepStart until sweepStart + 256).map { plan[it].code.format() }
        assertEquals(256, address20.toSet().size)
        assertTrue(address20.all { it.startsWith("NEC:20:") })
        assertTrue(address20.indexOf("NEC:20:41") < 40)
        assertEquals(sweepStart + 256 * ((plan.size - sweepStart) / 256), plan.size)
        assertTrue(plan.size > 60000)
    }

    @Test
    fun phaseBoundsCoverEveryIndex() {
        val plan = ScanPlan.full(db)
        for (i in listOf(0, 1, plan.phaseStarts[1] - 1, plan.phaseStarts[1], plan.phaseStarts[2] - 1, plan.phaseStarts[2], plan.size / 2, plan.size - 1)) {
            val b = plan.phaseBounds(i)
            assertTrue("indice $i", i in b)
            assertEquals(plan.phaseOf(i), plan[i].phase)
            assertEquals(plan[b.first].phase, plan[b.last].phase)
            assertNotNull(plan[i].detail)
        }
    }

    @Test
    fun singleAddressPlanIsInOrder() {
        val plan = ScanPlan.singleAddress("20")!!
        assertEquals(256, plan.size)
        assertEquals("NEC:20:00", plan[0].code.format())
        assertEquals("NEC:20:41", plan[0x41].code.format())
        assertEquals(0..255, plan.phaseBounds(100))
        assertEquals("NEC:E710:05", ScanPlan.singleAddress("e710")!![5].code.format())
        assertNull(ScanPlan.singleAddress("zz"))
        assertNull(ScanPlan.singleAddress("12345"))
        assertNull(ScanPlan.singleAddress(""))
    }
}
