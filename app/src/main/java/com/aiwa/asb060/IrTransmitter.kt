package com.aiwa.asb060

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Envoi via l'émetteur infrarouge intégré (Poco X5 5G : bord supérieur du téléphone). */
class IrTransmitter(context: Context) {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var repeating: Runnable? = null

    val available: Boolean get() = ir?.hasIrEmitter() == true

    fun send(code: IrCode) = transmit(code.frequency, code.frame())

    /** Envoie la trame puis répète tant que [stopRepeat] n'est pas appelé (touche maintenue). */
    fun startRepeat(code: IrCode) {
        stopRepeat()
        send(code)
        val r = object : Runnable {
            override fun run() {
                transmit(code.frequency, code.repeatFrame())
                handler.postDelayed(this, 110)
            }
        }
        repeating = r
        handler.postDelayed(r, 400)
    }

    fun stopRepeat() {
        repeating?.let { handler.removeCallbacks(it) }
        repeating = null
    }

    private fun transmit(freq: Int, pattern: IntArray) {
        val mgr = ir ?: return
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                mgr.transmit(freq, pattern)
            } catch (_: Exception) {
            } finally {
                busy.set(false)
            }
        }
    }

    fun release() {
        stopRepeat()
        executor.shutdown()
    }
}
