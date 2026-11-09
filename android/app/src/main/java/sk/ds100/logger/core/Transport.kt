package sk.ds100.logger.core

import java.io.IOException
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Memory access to the DS100 logger (USB 1941:8021), as in USBDrive.dll of DataLogger V2.70.
 * Every HID report is 8 bytes.
 *
 *   read 32 B  : A1 hi lo 20 A1 00 00 20        -> 4 x 8 B data
 *   write 32 B : A0 hi lo 20 A0 00 00 20, 4 x 8 B data -> ack A5 x 8
 *   write 1 B  : A2 hi lo 20 A2 vv 00 20        -> ack A5 x 8
 *
 * Bytes are handled as Ints 0..255.
 */
abstract class Transport {
    abstract fun send(report: IntArray)
    abstract fun recv(): IntArray
    abstract fun close()

    open fun read32(addr: Int): IntArray {
        send(intArrayOf(0xA1, addr shr 8 and 0xFF, addr and 0xFF, 0x20, 0xA1, 0, 0, 0x20))
        val out = IntArray(32)
        for (i in 0 until 4) recv().copyInto(out, i * 8, 0, 8)
        return out
    }

    open fun write32(addr: Int, data: IntArray): Boolean {
        require(data.size == 32)
        send(intArrayOf(0xA0, addr shr 8 and 0xFF, addr and 0xFF, 0x20, 0xA0, 0, 0, 0x20))
        for (i in 0 until 32 step 8) send(data.copyOfRange(i, i + 8))
        return recv().all { it == 0xA5 }
    }

    open fun writeByte(addr: Int, value: Int): Boolean {
        send(intArrayOf(0xA2, addr shr 8 and 0xFF, addr and 0xFF, 0x20, 0xA2, value and 0xFF, 0, 0x20))
        return recv().all { it == 0xA5 }
    }

    /** Read twice, accept identical blocks (V_ReadData). */
    open fun vRead32(addr: Int, tries: Int = 10): IntArray {
        repeat(tries + 1) {
            val a = read32(addr)
            if (a.contentEquals(read32(addr))) return a
            Thread.sleep(100)
        }
        throw IOException("unstable read at 0x%04X".format(addr))
    }

    open fun vWrite32(addr: Int, data: IntArray, tries: Int = 10) {
        repeat(tries + 1) {
            if (write32(addr, data) && read32(addr).contentEquals(data)) return
            Thread.sleep(100)
        }
        throw IOException("write failed at 0x%04X".format(addr))
    }

    open fun vWriteByte(addr: Int, value: Int, tries: Int = 10) {
        repeat(tries + 1) {
            if (writeByte(addr, value) && read32(addr)[0] == value) return
            Thread.sleep(100)
        }
        throw IOException("write failed at 0x%04X".format(addr))
    }
}

/** In-memory logger with the real memory layout, for the demo mode and the tests. */
class SimTransport(days: Int = 10, interval: Int = 15, now: Long = System.currentTimeMillis() / 1000) : Transport() {
    val m = IntArray(0x10000) { 0xFF }

    init {
        m[0] = 0xFF; m[1] = interval; m[5] = 0xFF
        val rnd = Random(42)
        val n = days * 1440 / interval
        val t0 = now - n.toLong() * interval * 60
        var addr = Logger.RING_START
        val cal = java.util.Calendar.getInstance()
        for (i in 0..n) {
            cal.timeInMillis = (t0 + i.toLong() * interval * 60) * 1000
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60.0
            val s = sin((h - 9) / 24 * 2 * PI)
            val temp = 21 + 3 * s + rnd.nextGaussian() * 0.2
            val humi = max(1.0, min(99.0, 50 - 8 * s + rnd.nextGaussian())).roundToInt()
            val (b0, b1) = Logger.encodeTemp(temp)
            m[addr] = b0; m[addr + 1] = b1; m[addr + 2] = humi; m[addr + 3] = if (i < n) interval else 3
            addr += 4
        }
        m[2] = (addr + 0xFF) / 0x100 - 1
        m[3] = 0
    }

    override fun send(report: IntArray) = throw UnsupportedOperationException()
    override fun recv(): IntArray = throw UnsupportedOperationException()
    override fun close() {}

    override fun read32(addr: Int) = IntArray(32) { i -> if (addr + i < m.size) m[addr + i] else 0xFF }
    override fun write32(addr: Int, data: IntArray): Boolean {
        data.copyInto(m, addr); return true
    }

    override fun writeByte(addr: Int, value: Int): Boolean {
        m[addr] = value
        if (addr == 0x10 && value == 0x55) {
            m[0x20] = 0x55
            for (i in 0 until 6) m[0x21 + i] = m[0x11 + i]
        }
        return true
    }

    override fun vRead32(addr: Int, tries: Int) = read32(addr)
    override fun vWrite32(addr: Int, data: IntArray, tries: Int) { write32(addr, data) }
    override fun vWriteByte(addr: Int, value: Int, tries: Int) { writeByte(addr, value) }
}
