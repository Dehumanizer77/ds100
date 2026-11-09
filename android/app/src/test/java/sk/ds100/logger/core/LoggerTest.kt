package sk.ds100.logger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/** Same cases as tests/test_device.py of the Linux app. */
class LoggerTest {
    private fun put(m: IntArray, addr: Int, temp: Double, humi: Int, b3: Int) {
        val (b0, b1) = Logger.encodeTemp(temp)
        m[addr] = b0; m[addr + 1] = b1; m[addr + 2] = humi; m[addr + 3] = b3
    }

    private fun logger(st: Transport) = Logger(st, "Simulator", sleep = {})

    @Test
    fun temperature() {
        for (t in listOf(-40.0, -12.3, 0.0, 21.5, 65.0)) {
            val (a, b) = Logger.encodeTemp(t)
            assertEquals(t, Logger.decodeTemp(a, b), 1e-9)
        }
        assertEquals(65.0, Logger.decodeTemp(0x7F, 0xFF), 1e-9)
        assertEquals(-40.0, Logger.decodeTemp(0xFF, 0xFF), 1e-9)
    }

    @Test
    fun firstAndIncremental() {
        val st = SimTransport(days = 1, interval = 10)
        val lg = logger(st)
        val info = lg.info()
        assertEquals(10, info.settings.interval)
        assertTrue(info.settings.unitC && info.settings.dateDmy)
        assertEquals(8, info.devId.length)
        val r = lg.readHistory(info)
        assertEquals(1440 / 10, r.records.size)
        val now = System.currentTimeMillis() / 1000
        assertTrue(abs(r.records.last().time - (now - 180)) < 5)
        r.records.zipWithNext().forEach { (a, b) -> assertEquals(600L, b.time - a.time) }
        assertNotNull(r.current)

        val a = r.lastAddr
        st.m[a + 3] = 10
        put(st.m, a + 4, 22.0, 40, 10)
        put(st.m, a + 8, 22.5, 41, 1)
        st.m[2] = (a + 12 + 0xFF) / 0x100 - 1
        val lastTime = r.lastTime!! - 27 * 60          // previous newest record 30 min ago
        val r2 = lg.readHistory(lg.info(), r.lastAddr, lastTime)
        assertEquals(2, r2.records.size)
        assertEquals(22.0, r2.records.last().temp, 1e-9)
        assertEquals(lastTime + 600, r2.records[0].time)
        assertEquals(lastTime + 1200, r2.records[1].time)
        assertEquals(22.5, r2.current!!.temp, 1e-9)
        val r3 = lg.readHistory(lg.info(), r2.lastAddr, r2.lastTime)
        assertTrue(r3.records.isEmpty())
        assertEquals(r2.lastAddr, r3.lastAddr)
    }

    @Test
    fun wrapped() {
        val st = SimTransport(days = 0, interval = 5)
        val m = st.m
        for (i in 0x100 until 0x10000) m[i] = 0xFF
        val page = 0x20
        val end = (page + 1) * 0x100
        val temps = mutableListOf<Double>()
        var i = 0
        var addr = end
        while (addr < 0x10000) {
            val t = 10 + (i % 50) / 10.0; put(m, addr, t, 50, 5); temps += t; addr += 4; i++
        }
        addr = 0x100
        while (addr < end - 0x40) {
            val t = 30 + (i % 50) / 10.0; put(m, addr, t, 60, 5); temps += t; addr += 4; i++
        }
        m[2] = page; m[3] = 0xFF
        val lg = logger(st)
        val r = lg.readHistory(lg.info())
        val got = r.records.map { it.temp } + r.current!!.temp
        assertEquals(temps.size, got.size)
        temps.zip(got).forEach { (x, y) -> assertEquals(x, y, 1e-9) }
        assertEquals(end - 0x44, r.lastAddr)
    }

    @Test
    fun settingsSyncClear() {
        val st = SimTransport(days = 0)
        val lg = logger(st)
        lg.applySettings(Settings(interval = 60, unitC = false, dateDmy = false))
        assertEquals(Settings(60, false, false), lg.info().settings)
        assertEquals(listOf(0, 60, 0), listOf(st.m[0], st.m[1], st.m[5]))
        try { lg.applySettings(Settings(interval = 0)); fail() } catch (_: IllegalArgumentException) {}
        val d = lg.syncTime()
        assertTrue(d != null && abs(d) <= 5)
        assertEquals(0x55, st.m[0x10]); assertEquals(0xFF, st.m[0x20])
        lg.clearHistory()
        assertTrue((0 until 0x200).all { st.m[it] == 0xFF })
        assertTrue(lg.info().devId != "FFFFFFFF")
    }

    @Test
    fun syncNotConfirmed() {
        val st = object : Transport() {
            val sim = SimTransport(days = 0)
            override fun send(report: IntArray) {}
            override fun recv() = IntArray(8)
            override fun close() {}
            override fun read32(addr: Int) = sim.read32(addr)
            override fun writeByte(addr: Int, value: Int) = true     // ignores the trigger
        }
        try {
            logger(st).syncTime(timeoutS = 0)
            fail()
        } catch (e: DeviceException) {
            val msg = e.message!!
            assertTrue(msg, "did not confirm" in msg && "0x20:" in msg && "acks 1111111" in msg)
        }
    }

    @Test
    fun syncDelayedAnswer() {
        val sim = SimTransport(days = 0)
        var n = 0
        val st = object : Transport() {
            override fun send(report: IntArray) {}
            override fun recv() = IntArray(8)
            override fun close() {}
            override fun read32(addr: Int) = sim.read32(addr)
            override fun writeByte(addr: Int, value: Int): Boolean {
                if (addr == 0x10 && value == 0x55 && ++n < 4) { sim.m[0x10] = value; return true }
                return sim.writeByte(addr, value)
            }
        }
        val seen = mutableListOf<Int>()
        val d = logger(st).syncTime(progress = { seen += it })
        assertEquals(4, n)
        assertEquals(3, seen.size)
        assertTrue(d != null && abs(d) <= 5)
    }

    @Test
    fun syncCancel() {
        val st = object : Transport() {
            val sim = SimTransport(days = 0)
            override fun send(report: IntArray) {}
            override fun recv() = IntArray(8)
            override fun close() {}
            override fun read32(addr: Int) = sim.read32(addr)
            override fun writeByte(addr: Int, value: Int) = true
        }
        try {
            logger(st).syncTime(cancelled = { true }); fail()
        } catch (e: DeviceException) {
            assertTrue("cancelled" in e.message!!)
        }
    }
}
