"""Tests of the device layer against the in-memory logger."""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from ds100gui import device as D  # noqa: E402

D.time.sleep = lambda s: None          # sync_time waits 2.5 s on real hardware


def put(m, addr, temp, humi, b3):
    b0, b1 = D.encode_temp(temp)
    m[addr:addr + 4] = bytes([b0, b1, humi, b3])


def test_temp():
    for t in (-40.0, -12.3, 0.0, 21.5, 65.0):
        assert D.decode_temp(*D.encode_temp(t)) == t, t
    assert D.decode_temp(0x7F, 0xFF) == 65.0
    assert D.decode_temp(0xFF, 0xFF) == -40.0


def test_first_and_incremental():
    st = D.SimTransport(days=1, interval=10)
    lg = D.Logger(st, "Simulator")
    info = lg.info()
    assert info.settings.interval == 10 and info.settings.unit_c and info.settings.date_dmy
    assert len(info.dev_id) == 8
    r = lg.read_history(info)
    n = 1 * 1440 // 10
    assert len(r.records) == n, len(r.records)
    now = time.time()
    # in-progress record has b3 = 3, so the newest finished record is 3 min ago
    assert abs(r.records[-1].time - (now - 180)) < 5
    assert all(b.time - a.time == 600 for a, b in zip(r.records, r.records[1:]))
    assert r.current is not None
    # logger keeps logging: in-progress record finishes, two more appear
    a = r.last_addr
    st.m[a + 3] = 10
    put(st.m, a + 4, 22.0, 40, 10)
    put(st.m, a + 8, 22.5, 41, 1)
    end = a + 12
    st.m[2] = (end + 0xFF) // 0x100 - 1
    # previous newest record 30 min ago (>= 21 min of new data)
    last_time = r.last_time - 27 * 60
    r2 = lg.read_history(lg.info(), r.last_addr, last_time)
    assert [x.temp for x in r2.records][-1] == 22.0
    assert len(r2.records) == 2, r2.records
    assert r2.records[0].time == last_time + 600
    assert r2.records[1].time == last_time + 1200
    assert r2.current.temp == 22.5
    # nothing new: only the in-progress record is read again
    r3 = lg.read_history(lg.info(), r2.last_addr, r2.last_time)
    assert r3.records == [] and r3.last_addr == r2.last_addr


def test_wrapped():
    st = D.SimTransport(days=0, interval=5)
    m = st.m
    m[0x100:] = b"\xff" * (0x10000 - 0x100)
    page = 0x20
    end = (page + 1) * 0x100
    temps = []
    addr = end
    i = 0
    while addr < 0x10000:                  # oldest data: after the write pointer
        put(m, addr, 10 + (i % 50) / 10, 50, 5)
        temps.append(10 + (i % 50) / 10)
        addr += 4
        i += 1
    addr = 0x100
    while addr < end - 0x40:               # newest data: from the ring start
        put(m, addr, 30 + (i % 50) / 10, 60, 5)
        temps.append(30 + (i % 50) / 10)
        addr += 4
        i += 1
    m[2], m[3] = page, 0xFF
    lg = D.Logger(st)
    r = lg.read_history(lg.info())
    got = [x.temp for x in r.records] + [r.current.temp]
    assert got == temps, (len(got), len(temps))
    assert r.last_addr == end - 0x44


def test_settings_sync_clear():
    st = D.SimTransport(days=0)
    lg = D.Logger(st)
    lg.apply_settings(D.Settings(interval=60, unit_c=False, date_dmy=False))
    s = lg.info().settings
    assert (s.interval, s.unit_c, s.date_dmy) == (60, False, False)
    assert st.m[0] == 0 and st.m[1] == 60 and st.m[5] == 0
    try:
        lg.apply_settings(D.Settings(interval=0))
        raise AssertionError("interval 0 accepted")
    except ValueError:
        pass
    assert abs(lg.sync_time()) <= 5
    lt = time.localtime()
    assert st.m[0x11] == lt.tm_year - 2000 and st.m[0x10] == 0x55 and st.m[0x20] == 0xFF
    old = lg.info().dev_id
    lg.clear_history()
    assert st.m[0:0x200] == b"\xff" * 0x200
    assert lg.info().dev_id != "FFFFFFFF"   # new ID written, like the Windows app
    _ = old


def test_sync_not_confirmed():
    st = D.SimTransport(days=0)
    st.write_byte = lambda addr, v: True          # logger ignores the clock trigger
    lg = D.Logger(st)
    try:
        lg.sync_time(timeout=0)
        raise AssertionError("no error")
    except D.DeviceError as e:
        msg = str(e)
        assert "did not confirm" in msg and "0x20:" in msg and "acks 1111111" in msg, msg


def test_sync_delayed_answer():
    """Logger takes the trigger only on the 4th try (seen on real hardware)."""
    st = D.SimTransport(days=0)
    orig = st.write_byte
    n = [0]

    def wb(addr, v):
        if addr == 0x10 and v == 0x55:
            n[0] += 1
            if n[0] < 4:
                st.m[0x10] = v                    # trigger stored, not processed yet
                return True
        return orig(addr, v)
    st.write_byte = wb
    seen = []
    d = D.Logger(st).sync_time(progress=seen.append)
    assert n[0] == 4 and len(seen) == 3 and abs(d) <= 5, (n, seen, d)


def test_sync_cancel():
    st = D.SimTransport(days=0)
    st.write_byte = lambda addr, v: True
    try:
        D.Logger(st).sync_time(cancelled=lambda: True)
        raise AssertionError("no error")
    except D.DeviceError as e:
        assert "cancelled" in str(e)


def test_sync_minute_clock():
    st = D.SimTransport(days=0)
    orig = st.write_byte

    def wb(addr, v):                              # logger keeps whole minutes only
        r = orig(addr, v)
        if addr == 0x10:
            st.m[0x26] = 0
        return r
    st.write_byte = wb
    d = D.Logger(st).sync_time()
    assert d is not None and -61 < d <= 1, d


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
