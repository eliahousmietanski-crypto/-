package com.aiwa.asb060

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Un code infrarouge. Formats texte acceptés :
 *  - NEC:AA:CC        protocole NEC, adresse 8 bits AA, commande CC (hex)
 *  - NEC:AAAA:CC      NEC étendu, adresse 16 bits (hex)
 *  - NEC:AAAA:CCCC    NEC étendu avec commande 16 bits
 *  - PRONTO:0000 006D 0022 0002 ...   code Pronto Hex (bases de codes en ligne)
 *  - RAW:38000:9000,4500,560,...      fréquence + durées en microsecondes
 */
sealed class IrCode {
    abstract val frequency: Int

    /** Trame envoyée pour un appui. */
    abstract fun frame(): IntArray

    /** Trame envoyée en boucle tant que la touche reste appuyée. */
    open fun repeatFrame(): IntArray = frame()

    abstract fun format(): String

    override fun equals(other: Any?): Boolean = other is IrCode && other.format() == format()
    override fun hashCode(): Int = format().hashCode()
    override fun toString(): String = format()

    /**
     * Code NEC : quatre octets, envoyés bit de poids faible en premier.
     * En NEC standard, [a1] est l'inverse de [a0] et [c1] l'inverse de [c0].
     */
    class Nec(val a0: Int, val a1: Int, val c0: Int, val c1: Int) : IrCode() {
        override val frequency = 38000

        val standardAddress: Boolean get() = a1 == (a0 xor 0xFF)
        val standardCommand: Boolean get() = c1 == (c0 xor 0xFF)

        /** Adresse telle qu'elle s'écrit : « 20 » (8 bits) ou « B683 » (16 bits). */
        val addressText: String
            get() = if (standardAddress) hex2(a0) else hex2(a1) + hex2(a0)

        val commandText: String
            get() = if (standardCommand) hex2(c0) else hex2(c1) + hex2(c0)

        override fun frame(): IntArray {
            val out = IntArray(68)
            var i = 0
            out[i++] = LEADER_MARK
            out[i++] = LEADER_SPACE
            for (byte in intArrayOf(a0, a1, c0, c1)) {
                for (bit in 0 until 8) {
                    out[i++] = BIT_MARK
                    out[i++] = if ((byte shr bit) and 1 == 1) ONE_SPACE else ZERO_SPACE
                }
            }
            out[i++] = BIT_MARK
            // Silence de fin : la trame complète dure 108 ms, comme sur une vraie télécommande.
            out[i] = (FRAME_PERIOD - out.sum()).coerceAtLeast(20000)
            return out
        }

        /** Trame « touche maintenue » du protocole NEC. */
        override fun repeatFrame(): IntArray =
            intArrayOf(LEADER_MARK, REPEAT_SPACE, BIT_MARK, FRAME_PERIOD - LEADER_MARK - REPEAT_SPACE - BIT_MARK)

        override fun format(): String = "NEC:$addressText:$commandText"

        companion object {
            const val LEADER_MARK = 9000
            const val LEADER_SPACE = 4500
            const val REPEAT_SPACE = 2250
            const val BIT_MARK = 562
            const val ZERO_SPACE = 562
            const val ONE_SPACE = 1687
            const val FRAME_PERIOD = 108000

            /** Adresse et commande 8 bits (NEC standard). */
            fun std(address: Int, command: Int): Nec {
                val a = address and 0xFF
                val c = command and 0xFF
                return Nec(a, a xor 0xFF, c, c xor 0xFF)
            }

            /**
             * [addressText] : 1 ou 2 chiffres hexa (8 bits) ou 3 à 4 (16 bits).
             * La commande est sur 8 bits.
             */
            fun of(addressText: String, command: Int): Nec? {
                val t = addressText.trim()
                val v = t.toIntOrNull(16) ?: return null
                val c = command and 0xFF
                return when (t.length) {
                    1, 2 -> std(v, c)
                    3, 4 -> Nec(v and 0xFF, (v shr 8) and 0xFF, c, c xor 0xFF)
                    else -> null
                }
            }

            private fun hex2(v: Int) = String.format(Locale.US, "%02X", v and 0xFF)
        }
    }

    class Raw(override val frequency: Int, val pattern: IntArray, private val source: String) : IrCode() {
        // ConsumerIrManager attend une alternance allumé/éteint : on termine par un silence.
        override fun frame(): IntArray = if (pattern.size % 2 == 1) pattern + 40000 else pattern
        override fun format(): String = source
    }

    companion object {
        fun parse(text: String): IrCode? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val upper = t.uppercase(Locale.US)
            return try {
                when {
                    upper.startsWith("NEC:") -> parseNec(upper)
                    upper.startsWith("PRONTO:") -> parsePronto(t.substring(7), "PRONTO:" + t.substring(7).trim())
                    upper.startsWith("RAW:") -> parseRaw(t)
                    // Code Pronto collé tel quel (« 0000 006D ... »)
                    upper.startsWith("0000 ") -> parsePronto(t, "PRONTO:$t")
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun parseNec(s: String): IrCode? {
            val parts = s.split(":")
            if (parts.size != 3) return null
            val addr = parts[1].trim()
            val cmd = parts[2].trim()
            val a = addr.toInt(16)
            val c = cmd.toInt(16)
            val (a0, a1) = when (addr.length) {
                1, 2 -> Pair(a and 0xFF, (a and 0xFF) xor 0xFF)
                3, 4 -> Pair(a and 0xFF, (a shr 8) and 0xFF)
                else -> return null
            }
            val (c0, c1) = when (cmd.length) {
                1, 2 -> Pair(c and 0xFF, (c and 0xFF) xor 0xFF)
                3, 4 -> Pair(c and 0xFF, (c shr 8) and 0xFF)
                else -> return null
            }
            return Nec(a0, a1, c0, c1)
        }

        private fun parseRaw(s: String): IrCode? {
            val parts = s.split(":", limit = 3)
            if (parts.size != 3) return null
            val freq = parts[1].trim().toInt()
            val pattern = parts[2].split(',', ' ', ';').filter { it.isNotBlank() }
                .map { abs(it.trim().toInt()) }.toIntArray()
            if (pattern.isEmpty() || pattern.any { it == 0 } || freq !in 15000..100000) return null
            // Android refuse d'émettre plus de 2 secondes d'affilée.
            if (pattern.sum() > 1_900_000) return null
            return Raw(freq, pattern, "RAW:$freq:" + pattern.joinToString(","))
        }

        private fun parsePronto(body: String, source: String): IrCode? {
            val w = body.trim().split(Regex("\\s+")).map { it.toInt(16) }
            if (w.size < 6 || w[0] != 0 || w[1] == 0) return null
            val freq = (1_000_000.0 / (w[1] * 0.241246)).roundToInt()
            val pairs = if (w[2] > 0) w[2] else w[3]
            if (pairs == 0 || w.size < 4 + pairs * 2 || freq !in 15000..100000) return null
            val usPerCycle = 1_000_000.0 / freq
            val pattern = IntArray(pairs * 2) { (w[4 + it] * usPerCycle).roundToInt().coerceAtLeast(1) }
            if (pattern.sum() > 1_900_000) return null
            return Raw(freq, pattern, source.trim())
        }
    }
}
