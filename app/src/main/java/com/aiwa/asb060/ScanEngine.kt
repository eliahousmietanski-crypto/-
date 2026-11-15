package com.aiwa.asb060

/** Ce que la recherche a remarqué, et les codes qui peuvent en être la cause. */
class Detection(
    val type: Type,
    /** Codes suspects, du plus probable au moins probable. Un seul si [confirmed]. */
    val candidates: List<Step>,
    /** Vrai si le code a été renvoyé une seconde fois et a refait le même effet. */
    val confirmed: Boolean,
) {
    enum class Type {
        /** La musique du téléphone s'est mise en pause (ou a redémarré). */
        PAUSE,

        /** Le Bluetooth s'est coupé : la barre s'est éteinte ou a changé de source. */
        DISCONNECT,
        VOLUME_UP,
        VOLUME_DOWN,

        /** Signalé par l'utilisateur. */
        MANUAL,
    }
}

interface ScanListener {
    /** Un code vient d'être envoyé. */
    fun onSent(step: Step, verifying: Boolean)

    /** L'état de la recherche a changé : rafraîchir l'affichage. */
    fun onChanged()
    fun onDetected(detection: Detection)

    /** La barre semblait avoir réagi, mais aucun code ne refait l'effet : la recherche continue. */
    fun onFalseAlarm()
    fun onFinished()
}

/**
 * Déroule la recherche : envoie les codes de [plan] un par un, surveille le téléphone
 * ([probe]) pour repérer une réaction de la barre de son, puis vérifie tout seul quel code
 * en est la cause en le renvoyant.
 *
 * [tick] doit être appelé toutes les 100 ms environ tant que [running] est vrai.
 */
class ScanEngine(
    val plan: ScanPlan,
    private val sender: IrSender,
    private val probe: Probe,
    private val clock: () -> Long,
    private val listener: ScanListener,
) {
    enum class State { IDLE, SCANNING, SETTLING, PREPARING, VERIFYING }

    var state = State.IDLE
        private set

    /** Indice du prochain essai. */
    var index = 0
    var intervalMs = DEFAULT_INTERVAL_MS
    var autoDetect = true

    private class Sent(val step: Step, val at: Long)

    private val history = ArrayDeque<Sent>()

    val running: Boolean get() = state != State.IDLE
    val checking: Boolean get() = state != State.IDLE && state != State.SCANNING

    /** Derniers codes envoyés par la recherche, du plus récent au plus ancien. */
    val recent: List<Step> get() = history.map { it.step }
    val lastSent: Step? get() = history.firstOrNull()?.step

    /** Vrai si la détection automatique peut fonctionner (barre connectée en Bluetooth). */
    val canDetect: Boolean get() = autoDetect && btBase

    val statusText: String?
        get() = when (state) {
            State.SETTLING -> "La barre a peut-être réagi… je vérifie."
            State.PREPARING ->
                if (type == Detection.Type.PAUSE) "Je relance la musique pour vérifier…" else "Vérification…"
            State.VERIFYING ->
                "Vérification : je renvoie ${candidates[verifyIdx].code.format()} (${verifyIdx + 1} sur ${candidates.size})…"
            else -> null
        }

    // État de référence du téléphone pendant la recherche.
    private var btBase = false
    private var musicBase = false
    private var volumeBase = 0
    private var changedSince = -1L
    private var btLostSince = -1L
    private var lastSentAt = 0L
    private var firstSend = true

    // Réaction en cours de vérification.
    private var type = Detection.Type.PAUSE
    private var candidates: List<Step> = emptyList()
    private var volumeBefore = 0
    private var detectedAt = 0L
    private var stateSince = 0L
    private var conditionSince = -1L
    private var lastResumeAt = 0L
    private var verifyIdx = 0
    private var verifyRound = 0
    private var verifySentAt = 0L
    private var verifyVolumeBase = 0

    fun start() {
        if (running) return
        if (index >= plan.size) {
            listener.onFinished()
            return
        }
        rebase()
        firstSend = true
        state = State.SCANNING
        listener.onChanged()
    }

    fun stop() {
        if (!running) return
        state = State.IDLE
        listener.onChanged()
    }

    /** Envoie l'essai numéro [i] à la main (Précédent / Renvoyer / Suivant). */
    fun sendManual(i: Int) {
        if (running) stop()
        if (plan.size == 0) return
        val k = i.coerceIn(0, plan.size - 1)
        index = k + 1
        transmit(plan[k], clock(), verifying = false)
        listener.onChanged()
    }

    /** L'utilisateur signale lui-même que la barre a réagi. */
    fun manualDetection(): Detection {
        stop()
        return Detection(Detection.Type.MANUAL, recent.distinctBy { it.code.format() }.take(4), false)
    }

    fun tick() {
        val now = clock()
        when (state) {
            State.IDLE -> return
            State.SCANNING -> scanning(now)
            State.SETTLING -> settling(now)
            State.PREPARING -> preparing(now)
            State.VERIFYING -> verifying(now)
        }
    }

    private fun rebase() {
        btBase = probe.btConnected()
        musicBase = probe.musicActive()
        volumeBase = probe.volume()
        changedSince = -1
        btLostSince = -1
    }

    private fun transmit(step: Step, now: Long, verifying: Boolean) {
        sender.send(step.code)
        if (!verifying) {
            history.addFirst(Sent(step, now))
            while (history.size > HISTORY) history.removeLast()
            lastSentAt = now
        }
        listener.onSent(step, verifying)
    }

    private fun scanning(now: Long) {
        if (autoDetect) {
            if (!btBase && probe.btConnected()) rebase()
            val found = if (btBase) detect(now) else null
            if (found == Detection.Type.DISCONNECT) {
                // Barre éteinte ou sur une autre source : rien à renvoyer, l'utilisateur tranche.
                finish(Detection(Detection.Type.DISCONNECT, candidates, false))
                return
            }
            if (found != null) {
                type = found
                state = State.SETTLING
                stateSince = now
                listener.onChanged()
                return
            }
        }
        if (firstSend || now - lastSentAt >= intervalMs) {
            if (index >= plan.size) {
                state = State.IDLE
                listener.onChanged()
                listener.onFinished()
                return
            }
            firstSend = false
            val step = plan[index]
            index++
            transmit(step, now, verifying = false)
        }
    }

    /** Compare le téléphone à son état de référence. @return la réaction repérée, ou null. */
    private fun detect(now: Long): Detection.Type? {
        if (probe.btConnected()) {
            btLostSince = -1
        } else {
            if (btLostSince < 0) btLostSince = now
            if (now - btLostSince >= STABLE_MS) {
                candidates = disconnectSuspects(now)
                if (candidates.isNotEmpty()) return Detection.Type.DISCONNECT
                // Bluetooth coupé sans qu'aucun code vienne d'être envoyé : sans rapport.
                rebase()
                return null
            }
        }

        var found: Detection.Type? = null
        if (probe.musicActive() != musicBase) {
            if (changedSince < 0) changedSince = now
            // Le changement doit durer : certains lecteurs ont un blanc entre deux morceaux.
            if (now - changedSince >= STABLE_MS) found = Detection.Type.PAUSE
        } else {
            changedSince = -1
        }
        val volume = probe.volume()
        if (found == null && volume != volumeBase) {
            found = if (volume > volumeBase) Detection.Type.VOLUME_UP else Detection.Type.VOLUME_DOWN
        }
        if (found == null) return null

        // Les codes envoyés juste avant : la barre met un peu de temps à réagir.
        val minAge = if (found == Detection.Type.PAUSE) STABLE_MS else 0L
        val window = maxOf(MIN_WINDOW_MS, 2 * intervalMs + 700)
        val suspects = history.filter { now - it.at in minAge..window }
            .map { it.step }.distinctBy { it.code.format() }.take(MAX_CANDIDATES)
        if (suspects.isEmpty()) {
            // Changement sans rapport avec la recherche (musique lancée à la main, etc.).
            rebase()
            return null
        }
        candidates = suspects
        volumeBefore = volumeBase
        detectedAt = now
        return found
    }

    /**
     * Codes suspects quand le Bluetooth se coupe. On remonte plus loin que pour une pause :
     * une barre qui s'éteint peut mettre plusieurs secondes à couper la liaison.
     */
    private fun disconnectSuspects(at: Long): List<Step> {
        val window = maxOf(DISCONNECT_WINDOW_MS, 5 * intervalMs + 500)
        return history.filter { at - it.at in 0..window }
            .map { it.step }.distinctBy { it.code.format() }.take(DISCONNECT_CANDIDATES)
    }

    private fun settling(now: Long) {
        if (now - stateSince < SETTLE_MS) return
        if (type == Detection.Type.PAUSE && !probe.btConnected()) {
            finish(Detection(Detection.Type.DISCONNECT, disconnectSuspects(detectedAt), false))
            return
        }
        verifyIdx = 0
        verifyRound = 0
        prepare(now)
    }

    /** Remet le téléphone dans l'état voulu avant de renvoyer un code suspect. */
    private fun prepare(now: Long) {
        state = State.PREPARING
        stateSince = now
        conditionSince = -1
        lastResumeAt = now
        if (type == Detection.Type.PAUSE && !probe.musicActive()) probe.resumeMusic()
        listener.onChanged()
    }

    private fun preparing(now: Long) {
        if (type != Detection.Type.PAUSE) {
            if (now - stateSince >= VOLUME_PREP_MS) sendVerify(now)
            return
        }
        if (probe.musicActive()) {
            if (!probe.btConnected()) {
                // La musique sortirait par le haut-parleur du téléphone : on arrête là.
                probe.pauseMusic()
                finish(Detection(Detection.Type.DISCONNECT, disconnectSuspects(detectedAt), false))
                return
            }
            if (conditionSince < 0) conditionSince = now
            if (now - conditionSince >= PLAYING_STABLE_MS) sendVerify(now)
        } else {
            conditionSince = -1
            if (now - stateSince > RESUME_TIMEOUT_MS) {
                // Impossible de relancer la musique : à l'utilisateur de vérifier.
                finish(Detection(Detection.Type.PAUSE, candidates, false))
            } else if (now - lastResumeAt >= RESUME_RETRY_MS) {
                probe.resumeMusic()
                lastResumeAt = now
            }
        }
    }

    private fun sendVerify(now: Long) {
        verifyVolumeBase = probe.volume()
        conditionSince = -1
        verifySentAt = now
        state = State.VERIFYING
        transmit(candidates[verifyIdx], now, verifying = true)
        listener.onChanged()
    }

    private fun verifying(now: Long) {
        if (type == Detection.Type.PAUSE) {
            if (!probe.musicActive()) {
                if (conditionSince < 0) conditionSince = now
                if (now - conditionSince >= STABLE_MS) confirm()
                return
            }
            conditionSince = -1
            if (now - verifySentAt > VERIFY_PAUSE_MS) nextCandidate(now)
        } else {
            val volume = probe.volume()
            val same = if (type == Detection.Type.VOLUME_UP) volume > verifyVolumeBase else volume < verifyVolumeBase
            if (same) confirm() else if (now - verifySentAt > VERIFY_VOLUME_MS) nextCandidate(now)
        }
    }

    private fun confirm() {
        val step = candidates[verifyIdx]
        if (type == Detection.Type.PAUSE) {
            if (!probe.btConnected()) {
                finish(Detection(Detection.Type.DISCONNECT, listOf(step), true))
                return
            }
            probe.resumeMusic()
        } else {
            // Annule les deux crans de volume ajoutés par la recherche.
            probe.setVolume(volumeBefore)
        }
        finish(Detection(type, listOf(step), true))
    }

    private fun nextCandidate(now: Long) {
        verifyIdx++
        if (verifyIdx >= candidates.size) {
            verifyIdx = 0
            verifyRound++
        }
        if (verifyRound >= VERIFY_ROUNDS) {
            rebase()
            state = State.SCANNING
            lastSentAt = now
            listener.onFalseAlarm()
            listener.onChanged()
            return
        }
        prepare(now)
    }

    private fun finish(detection: Detection) {
        state = State.IDLE
        listener.onChanged()
        listener.onDetected(detection)
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 800L
        const val MIN_INTERVAL_MS = 300L
        const val MAX_INTERVAL_MS = 10000L
        const val TICK_MS = 100L

        const val HISTORY = 8
        const val MAX_CANDIDATES = 3
        const val DISCONNECT_CANDIDATES = 5
        const val DISCONNECT_WINDOW_MS = 4500L
        const val STABLE_MS = 300L
        const val MIN_WINDOW_MS = 2500L
        const val SETTLE_MS = 1500L
        const val PLAYING_STABLE_MS = 800L
        const val RESUME_TIMEOUT_MS = 6000L
        const val RESUME_RETRY_MS = 2500L
        const val VERIFY_PAUSE_MS = 2500L
        const val VERIFY_VOLUME_MS = 1800L
        const val VOLUME_PREP_MS = 600L
        const val VERIFY_ROUNDS = 2
    }
}
