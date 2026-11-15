package com.aiwa.asb060

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class IrCodeTest {
    /** Relit une trame NEC comme le ferait un récepteur. */
    private fun decode(frame: IntArray): List<Int> {
        assertEquals(9000, frame[0])
        assertEquals(4500, frame[1])
        val bytes = ArrayList<Int>()
        for (b in 0 until 4) {
            var v = 0
            for (bit in 0 until 8) {
                val mark = frame[2 + (b * 8 + bit) * 2]
                val space = frame[3 + (b * 8 + bit) * 2]
                assertTrue(abs(mark - 562) < 60)
                if (space > 1100) v = v or (1 shl bit)
            }
            bytes += v
        }
        return bytes
    }

    @Test
    fun standardNecFrame() {
        val code = IrCode.parse("NEC:20:41")!!
        val frame = code.frame()
        assertEquals(68, frame.size)
        assertEquals(108000, frame.sum())
        assertEquals(listOf(0x20, 0xDF, 0x41, 0xBE), decode(frame))
        assertEquals(38000, code.frequency)
        assertEquals("NEC:20:41", code.format())
    }

    @Test
    fun repeatFrameIsTheNecHoldSignal() {
        val r = IrCode.parse("NEC:20:42")!!.repeatFrame()
        assertArrayEquals(intArrayOf(9000, 2250, 562, 96188), r)
        assertEquals(108000, r.sum())
    }

    @Test
    fun extendedAddressSendsLowByteFirst() {
        val code = IrCode.parse("nec:b683:4d")!!
        assertEquals(listOf(0x83, 0xB6, 0x4D, 0xB2), decode(code.frame()))
        assertEquals("NEC:B683:4D", code.format())
    }

    @Test
    fun sixteenBitCommand() {
        val code = IrCode.parse("NEC:0B00:3DBB")!!
        assertEquals(listOf(0x00, 0x0B, 0xBB, 0x3D), decode(code.frame()))
        assertEquals("NEC:0B00:3DBB", code.format())
        assertTrue(code.frame().sum() <= 108000 + 20000)
    }

    @Test
    fun shortAndLongAddressesAreDifferentCodes() {
        assertNotEquals(IrCode.parse("NEC:20:41"), IrCode.parse("NEC:0020:41"))
        assertEquals(IrCode.parse("NEC:20:41"), IrCode.parse("NEC:DF20:41"))
        assertEquals("NEC:20:41", IrCode.parse("NEC:DF20:41")!!.format())
        assertEquals(IrCode.Nec.std(0x20, 0x41), IrCode.Nec.of("20", 0x41))
        assertEquals(IrCode.parse("NEC:E710:15"), IrCode.Nec.of("E710", 0x15))
    }

    @Test
    fun rawAndProntoCodes() {
        val raw = IrCode.parse("RAW:38000:9000,4500,560,560,560")!!
        assertEquals(38000, raw.frequency)
        assertEquals(6, raw.frame().size)
        assertEquals("RAW:38000:9000,4500,560,560,560", raw.format())

        // Le même code NEC écrit en Pronto doit donner les mêmes durées.
        val frame = IrCode.parse("NEC:20:41")!!.frame()
        val unit = 1_000_000.0 / 38028
        val words = frame.joinToString(" ") { String.format(Locale.US, "%04X", Math.round(it / unit)) }
        val pronto = IrCode.parse("0000 006D 0022 0000 $words")!!
        assertTrue(abs(pronto.frequency - 38000) < 100)
        val back = pronto.frame()
        assertEquals(frame.size, back.size)
        for (i in frame.indices) assertTrue("durée $i", abs(frame[i] - back[i]) < 40)
    }

    @Test
    fun invalidCodesAreRejected() {
        for (bad in listOf("", "NEC", "NEC:20", "NEC:20:41:00", "NEC:ZZ:41", "NEC:12345:41", "NEC:20:12345", "RAW:38000:", "RAW:5:100,100", "hello", "0000 006D 0001")) {
            assertNull(bad, IrCode.parse(bad))
        }
    }
}
