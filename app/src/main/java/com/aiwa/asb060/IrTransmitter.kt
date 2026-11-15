package com.aiwa.asb060

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.SystemClock
import java.util.concurrent.Executors

/** Ce qui envoie les codes infrarouges (remplaçable par un faux émetteur dans les tests). */
interface IrSender {
    val available: Boolean

    /** Un appui bref : une seule trame. */
    fun send(code: IrCode)

    /** Touche maintenue : la trame, puis les trames de répétition jusqu'à [stopHold]. */
    fun startHold(code: IrCode)
    fun stopHold()
    fun release()
}

/** Envoi via l'émetteur infrarouge intégré (Poco X5 5G : bord supérieur du téléphone). */
class IrTransmitter(context: Context) : IrSender {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val executor = Executors.newSingleThreadExecutor()

    // Change à chaque nouvel appui ou relâchement : la boucle de répétition s'arrête d'elle-même.
    @Volatile
    private var holdToken = 0

    override val available: Boolean
        get() = try {
            ir?.hasIrEmitter() == true
        } catch (e: Exception) {
            false
        }

    override fun send(code: IrCode) {
        holdToken++
        executor.execute { transmit(code.frequency, code.frame()) }
    }

    override fun startHold(code: IrCode) {
        val token = ++holdToken
        executor.execute {
            // Comme la télécommande d'origine : une trame complète, puis une trame de
            // répétition toutes les 108 ms tant que le doigt reste sur la touche.
            val started = SystemClock.elapsedRealtime()
            transmit(code.frequency, code.frame())
            while (holdToken == token && SystemClock.elapsedRealtime() - started < MAX_HOLD_MS) {
                transmit(code.frequency, code.repeatFrame())
            }
        }
    }

    override fun stopHold() {
        holdToken++
    }

    /** Émet le motif et attend sa durée complète, même si le téléphone rend la main plus tôt. */
    private fun transmit(freq: Int, pattern: IntArray) {
        val mgr = ir ?: return
        val start = SystemClock.elapsedRealtime()
        try {
            mgr.transmit(freq, pattern)
        } catch (e: Exception) {
            // Émetteur occupé ou refus du système : on ignore cet envoi.
        }
        val remaining = pattern.sum() / 1000 - (SystemClock.elapsedRealtime() - start)
        if (remaining > 0) {
            try {
                Thread.sleep(remaining)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun release() {
        holdToken++
        executor.shutdown()
    }

    companion object {
        const val MAX_HOLD_MS = 15000L
    }
}

/** Fabriques remplaçables dans les tests. */
object Deps {
    var sender: (Context) -> IrSender = { IrTransmitter(it) }
    var probe: (Context) -> Probe = { AudioProbe(it) }
    var clock: () -> Long = { SystemClock.elapsedRealtime() }
}
