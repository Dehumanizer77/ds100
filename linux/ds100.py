#!/usr/bin/env python3
"""DS100 logger (USB 1941:8021) – command line tool.

  ds100.py info               settings and device ID
  ds100.py read ADDR          hex dump of 32 bytes
  ds100.py dump FILE          raw memory dump (64 KiB, read only)
  ds100.py csv FILE           download the whole history to CSV
  ds100.py set --interval N [--unit C|F] [--date dmy|mdy]
  ds100.py sync               set the logger clock to the PC clock
"""
import argparse
import csv
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ds100gui import device as D  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dev", help="hidraw node (default: auto-detect)")
    ap.add_argument("--simulate", action="store_true", help="use the built-in simulator")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("info")
    r = sub.add_parser("read")
    r.add_argument("addr", type=lambda x: int(x, 0))
    d = sub.add_parser("dump")
    d.add_argument("out")
    c = sub.add_parser("csv")
    c.add_argument("out")
    s = sub.add_parser("set")
    s.add_argument("--interval", type=int)
    s.add_argument("--unit", choices=["C", "F"])
    s.add_argument("--date", choices=["dmy", "mdy"])
    sub.add_parser("sync")
    a = ap.parse_args()

    lg = D.open_device(simulate=a.simulate, path=a.dev)
    try:
        if a.cmd == "info":
            i = lg.info()
            st = i.settings
            print("device ID   :", i.dev_id)
            print("interval    : %d min" % st.interval)
            print("unit (LCD)  :", "°C" if st.unit_c else "°F")
            print("date (LCD)  :", "dd-mm-yy" if st.date_dmy else "mm-dd-yy")
            print("write page  : 0x%02X  wrapped=%s" % (i.page, i.wrapped))
            print("header      :", i.raw_header.hex(" "))
        elif a.cmd == "read":
            print("%04X: %s" % (a.addr, lg.t.v_read32(a.addr).hex(" ")))
        elif a.cmd == "dump":
            with open(a.out, "wb") as f:
                for addr in range(0, 0x10000, 32):
                    f.write(lg.t.v_read32(addr))
                    if addr % 0x1000 == 0:
                        print("\r0x%04X" % addr, end="", file=sys.stderr)
            print("\nwritten", a.out, file=sys.stderr)
        elif a.cmd == "csv":
            i = lg.info()
            res = lg.read_history(i, progress=lambda n: print("\r%d" % n, end="", file=sys.stderr))
            with open(a.out, "w", newline="") as f:
                w = csv.writer(f)
                w.writerow(["time", "temperature_C", "humidity_pct", "dewpoint_C"])
                for x in res.records:
                    w.writerow([time.strftime("%Y-%m-%d %H:%M", time.localtime(x.time)),
                                x.temp, x.humi, round(D.dew_point(x.temp, x.humi), 1)])
            print("\n%d records -> %s" % (len(res.records), a.out), file=sys.stderr)
        elif a.cmd == "set":
            st = lg.info().settings
            if a.interval is not None:
                st.interval = a.interval
            if a.unit:
                st.unit_c = a.unit == "C"
            if a.date:
                st.date_dmy = a.date == "dmy"
            lg.apply_settings(st)
            print("ok")
        elif a.cmd == "sync":
            d = lg.sync_time(progress=lambda s: print("\rwaiting for the logger... %d s" % s,
                                                      end="", file=sys.stderr))
            print(file=sys.stderr)
            print("ok" + ("" if d is None else ", logger clock differs by %+.0f s" % d))
    finally:
        lg.close()


if __name__ == "__main__":
    main()
