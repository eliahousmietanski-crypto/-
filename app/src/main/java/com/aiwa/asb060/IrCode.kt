package com.aiwa.asb060

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Un code infrarouge. Trois formats texte sont acceptés :
 *  - NEC:AA:CC      protocole NEC, adresse 8 bits AA, commande CC (hex)
 *  - NEC:AAAA:CC    NEC étendu, adresse 16 bits (hex)
 *  - PRONTO:0000 006D 0022 0002 ...   code Pronto Hex (bases de codes en ligne)
 *  - RAW:38000:9000,4500,560,...      fréquence + durées en microsecondes
 */
sealed class IrCode {
    abstract val frequency: Int
    abstract fun frame(): IntArray
    /** Trame envoyée en boucle quand la touche reste appuyée. */
    open fun repeatFrame(): IntArray = frame()
    abstract fun format(): String

    data class Nec(val address: Int, val extended: Boolean, val command: Int) : IrCode() {
        override val frequency = 38000

        override fun frame(): IntArray {
            val out = ArrayList<Int>(68)
            out += 9000; out += 4500
            val addrLow: Int
            val addrHigh: Int
            if (extended) {
                addrLow = address and 0xFF
                addrHigh = (address shr 8) and 0xFF
            } else {
                addrLow = address and 0xFF
                addrHigh = addrLow.inv() and 0xFF
            }
            for (byte in intArrayOf(addrLow, addrHigh, command and 0xFF, command.inv() and 0xFF)) {
                for (bit in 0 until 8) {
                    out += BIT_MARK
                    out += if ((byte shr bit) and 1 == 1) ONE_SPACE else ZERO_SPACE
                }
            }
            out += BIT_MARK
            // Silence de fin pour que la trame complète dure ~108 ms.
            val used = out.sum()
            out += (FRAME_PERIOD - used).coerceAtLeast(10000)
            return out.toIntArray()
        }

        override fun repeatFrame(): IntArray =
            intArrayOf(9000, 2250, BIT_MARK, FRAME_PERIOD - 9000 - 2250 - BIT_MARK)

        override fun format(): String = if (extended)
            String.format(Locale.US, "NEC:%04X:%02X", address and 0xFFFF, command and 0xFF)
        else
            String.format(Locale.US, "NEC:%02X:%02X", address and 0xFF, command and 0xFF)

        companion object {
            const val BIT_MARK = 562
            const val ZERO_SPACE = 562
            const val ONE_SPACE = 1687
            const val FRAME_PERIOD = 108000
        }
    }

    data class Raw(override val frequency: Int, val pattern: IntArray, val source: String) : IrCode() {
        override fun frame(): IntArray {
            // ConsumerIrManager attend une alternance allumé/éteint ; on ajoute un silence final.
            return if (pattern.size % 2 == 1) pattern + 40000 else pattern
        }
        override fun format(): String = source
        override fun equals(other: Any?) = other is Raw && other.source == source
        override fun hashCode() = source.hashCode()
    }

    companion object {
        fun parse(text: String): IrCode? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val upper = t.uppercase(Locale.US)
            return try {
                when {
                    upper.startsWith("NEC:") -> parseNec(upper)
                    upper.startsWith("PRONTO:") -> parsePronto(t.substring(7), t)
                    upper.startsWith("RAW:") -> parseRaw(t)
                    // Code Pronto collé tel quel ("0000 006D ...")
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
            val cmd = parts[2].trim().toInt(16)
            if (cmd !in 0..0xFF) return null
            return when (addr.length) {
                1, 2 -> Nec(addr.toInt(16), false, cmd)
                3, 4 -> Nec(addr.toInt(16), true, cmd)
                else -> null
            }
        }

        private fun parseRaw(s: String): IrCode? {
            val parts = s.split(":", limit = 3)
            if (parts.size != 3) return null
            val freq = parts[1].trim().toInt()
            val pattern = parts[2].split(',', ' ', ';').filter { it.isNotBlank() }
                .map { kotlin.math.abs(it.trim().toInt()) }.toIntArray()
            if (pattern.isEmpty() || freq !in 15000..100000) return null
            return Raw(freq, pattern, s.trim())
        }

        private fun parsePronto(body: String, source: String): IrCode? {
            val w = body.trim().split(Regex("\\s+")).map { it.toInt(16) }
            if (w.size < 6 || w[0] != 0) return null
            val freq = (1_000_000.0 / (w[1] * 0.241246)).roundToInt()
            val once = w[2]
            val rep = w[3]
            val start: Int
            val pairs: Int
            if (once > 0) { start = 4; pairs = once } else { start = 4; pairs = rep }
            if (pairs == 0 || w.size < start + pairs * 2) return null
            val usPerCycle = 1_000_000.0 / freq
            val pattern = IntArray(pairs * 2) { (w[start + it] * usPerCycle).roundToInt() }
            return Raw(freq, pattern, source.trim())
        }
    }
}
