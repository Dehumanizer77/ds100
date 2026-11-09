"""USB transport for the DS100 logger (USB 1941:8021).

Reverse engineered from USBDrive.dll (DataLogger V2.70). The logger behaves like
an addressable memory. Every HID report is report-ID 0 + 8 bytes.

  read 32 B   : A1 hi lo 20 A1 00 00 20     -> 4 x 8 B data
  write 32 B  : A0 hi lo 20 A0 00 00 20, then 4 x 8 B data -> ack A5 x 8
  write 1 B   : A2 hi lo 20 A2 vv 00 20                     -> ack A5 x 8

The "verified" variants behave like the V_* functions of the DLL: after a write
the block is read back and compared, retrying up to `tries` times.
"""
import glob
import os
import select
import time

VID, PID = 0x1941, 0x8021
ACK = bytes([0xA5] * 8)


class DeviceNotFound(Exception):
    pass


def find_hidraw():
    want = "HID_ID=0003:%08X:%08X" % (VID, PID)
    for p in sorted(glob.glob("/sys/class/hidraw/hidraw*")):
        try:
            with open(p + "/device/uevent") as f:
                if want in f.read().upper():
                    return "/dev/" + os.path.basename(p)
        except OSError:
            pass
    return None


class Transport:
    def __init__(self, path=None):
        path = path or find_hidraw()
        if not path:
            raise DeviceNotFound("DS100 (USB 1941:8021) is not connected")
        self.path = path
        self.fd = os.open(path, os.O_RDWR)

    def close(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None

    # --- raw reports -----------------------------------------------------
    def _send(self, eight):
        os.write(self.fd, b"\x00" + bytes(eight))

    def _recv(self, timeout=1.2):
        r, _, _ = select.select([self.fd], [], [], timeout)
        if not r:
            raise TimeoutError("no answer from device")
        d = os.read(self.fd, 64)
        if len(d) == 9:
            d = d[1:]
        return d[:8]

    # --- memory primitives (= ReadData / WriteData / WriteByte) ----------
    def read32(self, addr):
        self._send([0xA1, addr >> 8 & 0xFF, addr & 0xFF, 0x20, 0xA1, 0, 0, 0x20])
        return b"".join(self._recv() for _ in range(4))

    def write32(self, addr, data):
        assert len(data) == 32
        self._send([0xA0, addr >> 8 & 0xFF, addr & 0xFF, 0x20, 0xA0, 0, 0, 0x20])
        for i in range(0, 32, 8):
            self._send(data[i:i + 8])
        return self._recv() == ACK

    def write_byte(self, addr, value):
        self._send([0xA2, addr >> 8 & 0xFF, addr & 0xFF, 0x20, 0xA2, value & 0xFF, 0, 0x20])
        return self._recv() == ACK

    # --- verified variants (= V_ReadData / V_WriteData / V_WriteByte) ----
    def v_read32(self, addr, tries=10):
        for _ in range(tries + 1):
            a = self.read32(addr)
            if a == self.read32(addr):
                return a
            time.sleep(0.1)
        raise IOError("unstable read at 0x%04X" % addr)

    def v_write32(self, addr, data, tries=10):
        for _ in range(tries + 1):
            if self.write32(addr, data) and self.read32(addr) == bytes(data):
                return
            time.sleep(0.1)
        raise IOError("write failed at 0x%04X" % addr)

    def v_write_byte(self, addr, value, tries=10):
        for _ in range(tries + 1):
            if self.write_byte(addr, value) and self.read32(addr)[0] == value:
                return
            time.sleep(0.1)
        raise IOError("write failed at 0x%04X" % addr)
