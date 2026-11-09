package sk.ds100.logger.core

import java.util.Calendar
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Memory map (reverse engineered from DataLogger.exe / USBDrive.dll, same as the Linux app):
 *
 *  0x00 header: [0] LCD unit 0xFF=°C 0x00=°F   [1] interval 1..240 min
 *               [2] page of the write pointer  [3] 0xFF = ring has wrapped
 *               [5] LCD date 0xFF=dd-mm-yy 0x00=mm-dd-yy
 *               [8..11] device ID (big endian time_t), [12..15] its bitwise NOT
 *  0x10 clock-set trigger 0x55 after 0x11..0x16 = YY MM DD hh mm ss
 *  0x20 clock-set answer 0x55, 0x21..0x26 = logger clock
 *  0x100..0xFFFF ring of 4-byte records, FF FF FF FF = empty:
 *       b0 bit7 sign, ((b0&0x7F)<<8 | b1) = temp*10 + 400, b2 = RH %, b3 = minutes since previous
 *       The last record is in progress (b3 still counts up).
 */

class DeviceException(msg: String) : Exception(msg)

data class Record(val time: Long, val temp: Double, val humi: Int)

data class Settings(val interval: Int = 15, val unitC: Boolean = true, val dateDmy: Boolean = true)

data class DeviceInfo(
    val devId: String, val settings: Settings, val page: Int, val wrapped: Boolean, val rawHeader: IntArray,
)

data class ReadResult(
    val records: List<Record>,      // finished records, oldest first
    val current: Record?,           // in-progress record (latest values)
    val lastAddr: Int,              // address of the in-progress record
    val lastTime: Long?,            // time of the newest finished record
)

fun dewPoint(t: Double, rh: Double): Double {
    if (rh <= 0) return Double.NaN
    val a = 17.62
    val b = 243.12
    val g = ln(rh / 100.0) + a * t / (b + t)
    return b * g / (a - g)
}

class Logger(
    val t: Transport,
    val name: String = "USB",
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val RING_START = 0x100
        const val RING_END = 0x10000

        fun decodeTemp(b0: Int, b1: Int): Double {
            val neg = b0 and 0x80 != 0
            val raw = (b0 and 0x7F) shl 8 or b1
            val t10 = if (raw < 1051) (if (neg) -(raw - 400) else raw - 400) else (if (neg) -400 else 650)
            return t10 / 10.0
        }

        fun encodeTemp(t: Double): Pair<Int, Int> {
            val raw = (t * 10).roundToInt() + 400
            return Pair(raw shr 8 and 0x7F, raw and 0xFF)
        }

        private fun isEmpty(b: IntArray, i: Int) = b[i] == 0xFF && b[i + 1] == 0xFF && b[i + 2] == 0xFF && b[i + 3] == 0xFF
    }

    private fun nowS() = clock() / 1000

    fun close() = t.close()

    // ------------------------------------------------------------ header --
    /** Read the header; give the logger an ID if it has none (as the Windows app does). */
    fun info(): DeviceInfo {
        var h = IntArray(32)
        var ident = 0L
        var ok = false
        for (i in 0 until 10) {
            h = t.vRead32(0x00)
            ident = be32(h, 8)
            val inv = be32(h, 12)
            if (ident != 0L && ident != 0xFFFFFFFFL && ident xor inv == 0xFFFFFFFFL) { ok = true; break }
        }
        if (!ok) {
            ident = nowS() and 0xFFFFFFFFL
            putBe32(h, 8, ident)
            putBe32(h, 12, ident xor 0xFFFFFFFFL)
            t.vWrite32(0x00, h)
        }
        val s = Settings(interval = h[1], unitC = h[0] != 0, dateDmy = h[5] != 0)
        return DeviceInfo("%08X".format(ident), s, h[2], h[3] == 0xFF && h[2] != 0xFF, h)
    }

    fun applySettings(s: Settings) {
        require(s.interval in 1..240) { "interval must be 1..240" }
        t.vWriteByte(0x00, if (s.unitC) 0xFF else 0x00)
        t.vWriteByte(0x01, s.interval)
        t.vWriteByte(0x05, if (s.dateDmy) 0xFF else 0x00)
    }

    // ------------------------------------------------------------- clock --
    /**
     * Set the logger clock (procedure of the Windows app). The logger picks up the trigger only
     * now and then, so the current time and the trigger are written again every 5 s until it
     * answers 0x55 at 0x20, at most [timeoutS] seconds. Returns logger clock minus PC clock in s.
     */
    fun syncTime(timeoutS: Int = 180, progress: (Int) -> Unit = {}, cancelled: () -> Boolean = { false }): Long? {
        val log = mutableListOf<String>()
        val t0 = clock()
        var attempt = 0
        while (true) {
            attempt++
            val c = Calendar.getInstance().apply { timeInMillis = clock() }
            val vals = intArrayOf(
                c.get(Calendar.YEAR) - 2000, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
            )
            val acks = vals.mapIndexed { i, v -> t.writeByte(0x11 + i, v) } + t.writeByte(0x10, 0x55)
            if (attempt <= 2) log += "#%d sent %s acks %s".format(
                attempt, vals.joinToString(" ") { "%02X".format(it) }, acks.joinToString("") { if (it) "1" else "0" })
            repeat(2) {
                sleep(2500)
                val b = t.read32(0x20)
                if (attempt <= 2) log += "   0x20: " + hex(b, 8)
                if (b[0] == 0x55) {
                    t.writeByte(0x20, 0xFF)
                    if (b[2] !in 1..12 || b[3] !in 1..31 || b[4] > 23 || b[5] > 59 || b[6] > 59) return null
                    val d = Calendar.getInstance().apply {
                        clear(); set(2000 + b[1], b[2] - 1, b[3], b[4], b[5], b[6])
                    }
                    return (d.timeInMillis - clock()) / 1000
                }
            }
            val waited = ((clock() - t0) / 1000).toInt()
            progress(waited)
            if (cancelled()) throw DeviceException("cancelled after $waited s")
            if (waited >= timeoutS) break
        }
        log += "#%d (last)  0x10: %s".format(attempt, hex(t.read32(0x10), 8))
        log += "            0x20: " + hex(t.read32(0x20), 8)
        throw DeviceException("the logger did not confirm the new time within $timeoutS s\n" + log.joinToString("\n"))
    }

    // ---------------------------------------------------------- clearing --
    /** Same as the Windows app: wipe 0x000..0x1FF. The logger must be repowered. */
    fun clearHistory() {
        for (addr in 0 until 0x200 step 0x20) t.vWrite32(addr, IntArray(32) { 0xFF })
    }

    // ----------------------------------------------------------- history --
    private class Raw(val temp: Double, val humi: Int, val minutes: Int)

    /** Returns (address where reading stopped, hit empty record). */
    private fun scan(start: Int, stop: Int, out: MutableList<Raw>, progress: (Int) -> Unit): Pair<Int, Boolean> {
        var addr = start
        while (true) {
            val block = t.vRead32(addr)
            for (i in 0 until 32 step 4) {
                if (isEmpty(block, i)) return Pair(addr, true)
                val b3 = block[i + 3]
                if (b3 in 1..240) {
                    if (block[i + 2] > 100) throw DeviceException("corrupted record at 0x%04X".format(addr))
                    out += Raw(decodeTemp(block[i], block[i + 1]), block[i + 2], b3)
                }
                addr += 4
                if (addr > 0xFFFE || addr >= stop) return Pair(addr, false)
            }
            progress(out.size)
        }
    }

    fun readHistory(info: DeviceInfo, lastAddr: Int? = null, lastTime: Long? = null,
                    progress: (Int) -> Unit = {}): ReadResult {
        val end = (info.page + 1) * 0x100
        val raw = mutableListOf<Raw>()
        var a: Int
        if (lastAddr == null) {
            if (info.wrapped) {
                val (a1, empty) = scan(end, RING_END, raw, progress)
                a = a1
                if (!empty || a > 0xFFFE) a = scan(RING_START, end, raw, progress).first
            } else {
                a = scan(RING_START, end, raw, progress).first
            }
        } else if (end < lastAddr) {
            if (info.wrapped) {
                val (a1, empty) = scan((lastAddr / 0x100 + 1) * 0x100, RING_END, raw, progress)
                a = a1
                if (!empty || a > 0xFFFE) a = scan(RING_START, end, raw, progress).first
            } else {
                a = scan(RING_START, end, raw, progress).first
            }
        } else {
            var start = lastAddr
            if (lastAddr / 0x100 == info.page && isEmpty(t.vRead32(lastAddr), 0)) start = RING_START
            a = scan(start, end, raw, progress).first
        }

        val newLastAddr = max(a - 4, RING_START)
        if (raw.isEmpty()) return ReadResult(emptyList(), null, newLastAddr, lastTime)
        val now = nowS()
        val cur = raw.last()
        val current = Record(now, cur.temp, cur.humi)
        if (raw.size == 1) return ReadResult(emptyList(), current, newLastAddr, lastTime)
        val total = raw.sumOf { it.minutes }.toLong() * 60
        var tm = if (lastTime != null && total <= now - lastTime) lastTime else now - total
        val recs = ArrayList<Record>(raw.size - 1)
        for (r in raw.subList(0, raw.size - 1)) {
            tm += r.minutes * 60L
            recs += Record(tm, r.temp, r.humi)
        }
        return ReadResult(recs, current, newLastAddr, tm)
    }

    private fun be32(b: IntArray, o: Int): Long =
        (b[o].toLong() shl 24) or (b[o + 1].toLong() shl 16) or (b[o + 2].toLong() shl 8) or b[o + 3].toLong()

    private fun putBe32(b: IntArray, o: Int, v: Long) {
        b[o] = (v shr 24 and 0xFF).toInt(); b[o + 1] = (v shr 16 and 0xFF).toInt()
        b[o + 2] = (v shr 8 and 0xFF).toInt(); b[o + 3] = (v and 0xFF).toInt()
    }

    private fun hex(b: IntArray, n: Int) = (0 until n).joinToString(" ") { "%02x".format(b[it]) }
}
