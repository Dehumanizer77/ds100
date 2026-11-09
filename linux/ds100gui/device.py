"""Device layer: logger memory map and the operations of DataLogger V2.70.

Memory map (reverse engineered from DataLogger.exe / USBDrive.dll):

  0x00 header (32 B)
       [0]  temperature unit on the LCD   0xFF = °C, 0x00 = °F
       [1]  logging interval, minutes 1..240
       [2]  page of the write pointer (records end before (page+1)*0x100)
       [3]  0xFF = ring buffer has wrapped
       [4]  number of enabled schedules (no UI in the Windows app)
       [5]  date format on the LCD        0xFF = dd-mm-yy, 0x00 = mm-dd-yy
       [8:12]  device ID, big endian time_t written by the PC software
       [12:16] bitwise NOT of the ID
  0x10 clock-set trigger: write 0x55 after 0x11..0x16 = YY MM DD hh mm ss
  0x20 clock-set answer: 0x55, then 0x21..0x26 = YY MM DD hh mm ss of the logger
  0x30..0x6F schedules (not used by the Windows UI)
  0x100..0xFFFF ring buffer of 4-byte records, FF FF FF FF = empty
       b0 bit7 sign, ((b0&0x7F)<<8 | b1) = temperature*10 + 400
       b2 humidity %, b3 minutes since the previous record (1..240)
       The last record is the one in progress: b3 still counts up.

Record times are not stored. Like the Windows app, the newest (in progress)
record is anchored to the PC clock, or the new records continue from the time
of the last record stored on the previous download.
"""
import math
import random
import time
from dataclasses import dataclass

from . import protocol

RING_START, RING_END = 0x100, 0x10000
EMPTY = b"\xff\xff\xff\xff"


class DeviceError(Exception):
    pass


@dataclass
class Record:
    time: int          # unix time
    temp: float        # °C
    humi: int          # % RH


@dataclass
class Settings:
    interval: int = 15             # minutes
    unit_c: bool = True            # LCD shows °C
    date_dmy: bool = True          # LCD date dd-mm-yy


@dataclass
class DeviceInfo:
    dev_id: str
    settings: Settings
    page: int = 0
    wrapped: bool = False
    raw_header: bytes = b""


@dataclass
class ReadResult:
    records: list                  # finished records, oldest first
    current: Record | None         # in-progress record (latest values)
    last_addr: int                 # address of the in-progress record
    last_time: int | None          # time of the newest finished record


def dew_point(t, rh):
    """Magnus formula, °C."""
    if rh <= 0:
        return float("nan")
    a, b = 17.62, 243.12
    g = math.log(rh / 100.0) + a * t / (b + t)
    return b * g / (a - g)


def decode_temp(b0, b1):
    neg = b0 & 0x80
    raw = (b0 & 0x7F) << 8 | b1
    if raw < 1051:
        t10 = raw - 400
        if neg:
            t10 = -t10
    else:
        t10 = -400 if neg else 650
    return t10 / 10.0


def encode_temp(t):
    raw = int(round(t * 10)) + 400
    return raw >> 8 & 0x7F, raw & 0xFF


class Logger:
    """Operations on a logger reachable through a Transport-like object."""

    def __init__(self, transport, name="USB"):
        self.t = transport
        self.name = name

    def close(self):
        self.t.close()

    # ---------------------------------------------------------- header --
    def info(self):
        """Read the header; give the logger an ID if it has none (as the Windows app does)."""
        for _ in range(10):
            h = bytearray(self.t.v_read32(0x00))
            ident = int.from_bytes(h[8:12], "big")
            inv = int.from_bytes(h[12:16], "big")
            if ident not in (0, 0xFFFFFFFF) and ident ^ inv == 0xFFFFFFFF:
                break
        else:
            ident = int(time.time())
            h[8:12] = ident.to_bytes(4, "big")
            h[12:16] = (ident ^ 0xFFFFFFFF).to_bytes(4, "big")
            self.t.v_write32(0x00, bytes(h))
        s = Settings(interval=h[1], unit_c=h[0] != 0, date_dmy=h[5] != 0)
        return DeviceInfo(dev_id="%08X" % ident, settings=s, page=h[2],
                          wrapped=h[3] == 0xFF and h[2] != 0xFF, raw_header=bytes(h))

    def apply_settings(self, s: Settings):
        if not 1 <= s.interval <= 240:
            raise ValueError("interval must be 1..240")
        self.t.v_write_byte(0x00, 0xFF if s.unit_c else 0x00)
        self.t.v_write_byte(0x01, s.interval)
        self.t.v_write_byte(0x05, 0xFF if s.date_dmy else 0x00)

    # ----------------------------------------------------------- clock --
    def sync_time(self, timeout=180, progress=None, cancelled=None):
        """Set the logger clock to the PC clock (procedure of the Windows app).

        The logger picks up the trigger at 0x10 only now and then, so - like the
        Windows app - the current time and the trigger are written again every 5 s
        until the logger confirms with 0x55 at 0x20 (at most `timeout` seconds).
        progress(seconds_waited) is called every round; cancelled() may stop it.
        Returns the logger clock minus the PC clock in seconds (None if unreadable)."""
        log = []
        t0 = time.time()
        attempt = 0
        while True:
            attempt += 1
            lt = time.localtime()
            vals = (lt.tm_year - 2000, lt.tm_mon, lt.tm_mday, lt.tm_hour, lt.tm_min, lt.tm_sec)
            acks = [self.t.write_byte(a, v) for a, v in zip(range(0x11, 0x17), vals)]
            acks.append(self.t.write_byte(0x10, 0x55))
            if attempt <= 2:
                log.append("#%d sent %s acks %s" % (attempt, " ".join("%02X" % v for v in vals),
                                                    "".join("1" if a else "0" for a in acks)))
            for _ in range(2):
                time.sleep(2.5)
                b = self.t.read32(0x20)
                if attempt <= 2:
                    log.append("   0x20: %s" % b[:8].hex(" "))
                if b[0] != 0x55:
                    continue
                self.t.write_byte(0x20, 0xFF)
                try:
                    dev = time.mktime((2000 + b[1], b[2], b[3], b[4], b[5], b[6], 0, 0, -1))
                except (OverflowError, ValueError):
                    return None
                return dev - time.time()
            waited = time.time() - t0
            if progress:
                progress(int(waited))
            if cancelled and cancelled():
                raise DeviceError("cancelled after %d s" % waited)
            if waited >= timeout:
                break
        log.append("#%d (last)  0x10: %s" % (attempt, self.t.read32(0x10)[:8].hex(" ")))
        log.append("            0x20: %s" % self.t.read32(0x20)[:8].hex(" "))
        raise DeviceError("the logger did not confirm the new time within %d s\n" % timeout
                          + "\n".join(log))

    # ------------------------------------------------------- clearing --
    def clear_history(self):
        """Same as the Windows app: wipe 0x000..0x1FF. The logger must be repowered."""
        for addr in range(0, 0x200, 0x20):
            self.t.v_write32(addr, b"\xff" * 32)

    # -------------------------------------------------------- history --
    def _scan(self, start, stop, out, progress):
        """Read records from start (4-byte steps) until stop/0xFFFF/empty.
        Returns (address where reading stopped, hit_empty)."""
        addr = start
        while True:
            block = self.t.v_read32(addr)
            for i in range(0, 32, 4):
                rec = block[i:i + 4]
                if rec == EMPTY:
                    return addr, True
                b3 = rec[3]
                if 1 <= b3 <= 240:
                    if rec[2] > 100:
                        raise DeviceError("corrupted record at 0x%04X" % addr)
                    out.append((decode_temp(rec[0], rec[1]), rec[2], b3))
                addr += 4
                if addr > 0xFFFE or addr >= stop:
                    return addr, False
            if progress:
                progress(len(out))

    def read_history(self, info: DeviceInfo, last_addr=None, last_time=None, progress=None):
        end = (info.page + 1) * 0x100
        raw = []
        if last_addr is None:
            if info.wrapped:
                a, empty = self._scan(end, RING_END, raw, progress)
                if not empty or a > 0xFFFE:
                    a, _ = self._scan(RING_START, end, raw, progress)
            else:
                a, _ = self._scan(RING_START, end, raw, progress)
        elif end < last_addr:
            if info.wrapped:
                a, empty = self._scan((last_addr // 0x100 + 1) * 0x100, RING_END, raw, progress)
                if not empty or a > 0xFFFE:
                    a, _ = self._scan(RING_START, end, raw, progress)
            else:
                a, _ = self._scan(RING_START, end, raw, progress)
        else:
            start = last_addr
            if last_addr // 0x100 == info.page and self.t.v_read32(last_addr)[:4] == EMPTY:
                start = RING_START
            a, _ = self._scan(start, end, raw, progress)

        new_last_addr = max(a - 4, RING_START)
        if not raw:
            return ReadResult([], None, new_last_addr, last_time)
        now = int(time.time())
        cur_t, cur_h, _ = raw[-1]
        current = Record(now, cur_t, cur_h)
        if len(raw) == 1:
            return ReadResult([], current, new_last_addr, last_time)
        total = sum(r[2] for r in raw) * 60
        if last_time and total <= now - last_time:
            t = last_time
        else:
            t = now - total
        recs = []
        for temp, humi, b3 in raw[:-1]:
            t += b3 * 60
            recs.append(Record(t, temp, humi))
        return ReadResult(recs, current, new_last_addr, t)


# ----------------------------------------------------------- simulator --
class SimTransport:
    """In-memory logger with the same memory layout, for trying the GUI."""

    def __init__(self, days=10, interval=15):
        m = bytearray(b"\xff" * 0x10000)
        m[0], m[1], m[5] = 0xFF, interval, 0xFF
        rnd = random.Random(42)
        n = days * 1440 // interval
        t0 = time.time() - n * interval * 60
        addr = RING_START
        for i in range(n + 1):
            ts = t0 + i * interval * 60
            h = (time.localtime(ts).tm_hour + time.localtime(ts).tm_min / 60.0)
            temp = 21 + 3 * math.sin((h - 9) / 24 * 2 * math.pi) + rnd.gauss(0, 0.2)
            humi = int(round(max(1, min(99, 50 - 8 * math.sin((h - 9) / 24 * 2 * math.pi)
                                        + rnd.gauss(0, 1)))))
            b0, b1 = encode_temp(temp)
            m[addr:addr + 4] = bytes([b0, b1, humi, interval if i < n else 3])
            addr += 4
        m[2] = (addr + 0xFF) // 0x100 - 1        # records end before (page+1)*0x100
        m[3] = 0
        self.m = m

    def close(self):
        pass

    def read32(self, addr):
        return bytes(self.m[addr:addr + 32].ljust(32, b"\xff"))

    def write32(self, addr, data):
        self.m[addr:addr + 32] = data
        return True

    def write_byte(self, addr, v):
        self.m[addr] = v
        if addr == 0x10 and v == 0x55:
            self.m[0x20] = 0x55
            self.m[0x21:0x27] = self.m[0x11:0x17]
        return True

    v_read32 = read32

    def v_write32(self, addr, data, tries=10):
        self.write32(addr, data)

    def v_write_byte(self, addr, v, tries=10):
        self.write_byte(addr, v)


def open_device(simulate=False, path=None):
    if simulate:
        return Logger(SimTransport(), "Simulator")
    return Logger(protocol.Transport(path), "USB")
