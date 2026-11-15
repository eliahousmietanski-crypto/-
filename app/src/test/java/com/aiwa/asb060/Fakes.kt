package com.aiwa.asb060

import java.io.File

/**
 * Faux téléphone + fausse barre de son, pour essayer la recherche sans matériel.
 * La barre réagit aux codes déclarés dans [codes] ; le téléphone voit la musique,
 * le Bluetooth et le volume changer comme avec une vraie barre.
 */
class FakeBar : IrSender, Probe {
    var now = 0L

    // Téléphone
    var music = true
    var bt = true
    var phoneVolume = 8
    var playerAnswers = true

    // Barre
    var barOn = true
    var latency = 400L

    /** Temps que met le téléphone à voir que la barre s'est éteinte. */
    var disconnectDelay = 0L

    /** Barre qui alterne « pause » puis « lecture » sans regarder l'état du téléphone. */
    var alternating = false
    private var barThinksPlaying = true

    /** code -> "play", "power", "mode", "volup" ou "voldown" */
    val codes = HashMap<String, String>()
    val sent = ArrayList<String>()
    val held = ArrayList<String>()
    var holds = 0
    var stops = 0
    private val events = ArrayList<Pair<Long, () -> Unit>>()

    override val available = true

    override fun send(code: IrCode) {
        sent += code.format()
        val action = codes[code.format()] ?: return
        events += Pair(now + latency) { react(action) }
    }

    override fun startHold(code: IrCode) {
        holds++
        held += code.format()
        send(code)
    }

    override fun stopHold() {
        stops++
    }

    override fun release() {}

    fun at(time: Long, action: () -> Unit) {
        events += Pair(time, action)
    }

    private fun react(action: String) {
        when (action) {
            "play" -> if (barOn && bt) {
                if (alternating) {
                    music = !barThinksPlaying
                    barThinksPlaying = !barThinksPlaying
                } else {
                    music = !music
                }
            }
            "power" -> {
                barOn = !barOn
                if (!barOn) {
                    val wasPlaying = music
                    events += Pair(now + disconnectDelay) {
                        bt = false
                        if (wasPlaying) music = false
                    }
                }
            }
            "mode" -> if (barOn) {
                bt = false
                music = false
            }
            "volup" -> if (barOn && bt) phoneVolume++
            "voldown" -> if (barOn && bt) phoneVolume--
        }
    }

    /** Avance le temps de 100 ms : les effets en attente se produisent. */
    fun step() {
        now += 100
        // Un effet peut en déclencher un autre au même instant.
        while (true) {
            val due = events.filter { it.first <= now }
            if (due.isEmpty()) break
            events.removeAll(due)
            due.forEach { it.second() }
        }
    }

    fun run(engine: ScanEngine, ms: Long) {
        repeat((ms / 100).toInt()) {
            step()
            engine.tick()
        }
    }

    override fun musicActive() = music
    override fun btConnected() = bt
    override fun volume() = phoneVolume
    override fun setVolume(v: Int) {
        phoneVolume = v
    }

    override fun resumeMusic() {
        if (playerAnswers) music = true
    }

    override fun pauseMusic() {
        music = false
    }
}

class Recorder : ScanListener {
    val detections = ArrayList<Detection>()
    var falseAlarms = 0
    var finished = 0
    var verifySends = 0
    override fun onSent(step: Step, verifying: Boolean) {
        if (verifying) verifySends++
    }

    override fun onChanged() {}
    override fun onDetected(detection: Detection) {
        detections += detection
    }

    override fun onFalseAlarm() {
        falseAlarms++
    }

    override fun onFinished() {
        finished++
    }
}

/** La vraie base livrée dans l'appli. */
fun realDb(): ProfileDb {
    val file = listOf("src/main/assets/profiles.txt", "app/src/main/assets/profiles.txt").map { File(it) }.first { it.exists() }
    return file.useLines { ProfileDb.parse(it) }
}
