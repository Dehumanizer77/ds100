"""Local history database. Same tables as DataLogger.mdb of the Windows app."""
import csv
import os
import sqlite3
import time

from .device import dew_point

DEFAULT_PATH = os.path.join(os.path.expanduser("~"), ".local", "share", "ds100", "datalogger.db")

SCHEMA = """
CREATE TABLE IF NOT EXISTS Device (
  DevID TEXT PRIMARY KEY, DevTitle TEXT, Version TEXT, DevInterval INTEGER,
  DevLastAddr INTEGER DEFAULT 0, DevLastTime INTEGER, DevTempUnit TEXT,
  DevDateFormat TEXT, DevnRecords INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS Records (
  ID TEXT, Time INTEGER, Temp REAL, Humi REAL, DewPoint REAL,
  PRIMARY KEY (ID, Time));
CREATE INDEX IF NOT EXISTS rec_time ON Records(ID, Time);
"""


class DB:
    def __init__(self, path=DEFAULT_PATH):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.c = sqlite3.connect(path)
        self.c.executescript(SCHEMA)
        have = {r[1] for r in self.c.execute("PRAGMA table_info(Device)")}
        for col, typ in (("DevLastRec", "INTEGER"), ("CurTemp", "REAL"), ("CurHumi", "REAL"),
                         ("CurTime", "INTEGER")):
            if col not in have:
                self.c.execute("ALTER TABLE Device ADD COLUMN %s %s" % (col, typ))
        self.c.commit()

    # --- devices ---------------------------------------------------------
    def devices(self):
        return [r[0] for r in self.c.execute("SELECT DevID FROM Device ORDER BY DevID")]

    def device(self, dev_id):
        cur = self.c.execute("SELECT * FROM Device WHERE DevID=?", (dev_id,))
        row = cur.fetchone()
        return dict(zip([d[0] for d in cur.description], row)) if row else None

    def upsert_device(self, dev_id, **kw):
        self.c.execute("INSERT OR IGNORE INTO Device(DevID) VALUES (?)", (dev_id,))
        for k, v in kw.items():
            self.c.execute("UPDATE Device SET %s=? WHERE DevID=?" % k, (v, dev_id))
        self.c.commit()

    def delete_device(self, dev_id):
        self.c.execute("DELETE FROM Records WHERE ID=?", (dev_id,))
        self.c.execute("DELETE FROM Device WHERE DevID=?", (dev_id,))
        self.c.commit()

    def delete_history(self, dev_id):
        self.c.execute("DELETE FROM Records WHERE ID=?", (dev_id,))
        self.c.execute("UPDATE Device SET DevLastAddr=NULL, DevLastRec=NULL, DevnRecords=0 "
                       "WHERE DevID=?", (dev_id,))
        self.c.commit()

    # --- records ---------------------------------------------------------
    def add_records(self, dev_id, records):
        rows = [(dev_id, r.time, r.temp, r.humi, round(dew_point(r.temp, r.humi), 1))
                for r in records]
        before = self.c.total_changes
        self.c.executemany("INSERT OR IGNORE INTO Records VALUES (?,?,?,?,?)", rows)
        self.c.commit()
        return self.c.total_changes - before

    def records(self, dev_id, t0=None, t1=None):
        q, a = "SELECT Time, Temp, Humi, DewPoint FROM Records WHERE ID=?", [dev_id]
        if t0 is not None:
            q += " AND Time>=?"
            a.append(int(t0))
        if t1 is not None:
            q += " AND Time<=?"
            a.append(int(t1))
        return self.c.execute(q + " ORDER BY Time", a).fetchall()

    def last(self, dev_id):
        return self.c.execute("SELECT Time, Temp, Humi, DewPoint FROM Records WHERE ID=? "
                              "ORDER BY Time DESC LIMIT 1", (dev_id,)).fetchone()

    def maxmin(self, dev_id, t0, t1):
        """((tmax, time), (tmin, time), (hmax, time), (hmin, time)) or None."""
        out = []
        for col, agg in (("Temp", "DESC"), ("Temp", "ASC"), ("Humi", "DESC"), ("Humi", "ASC")):
            r = self.c.execute("SELECT %s, Time FROM Records WHERE ID=? AND Time BETWEEN ? AND ? "
                               "ORDER BY %s %s LIMIT 1" % (col, col, agg),
                               (dev_id, int(t0), int(t1))).fetchone()
            if r is None:
                return None
            out.append(r)
        return out

    def export_csv(self, path, dev_id, rows, fmt_time, fmt_temp, unit):
        with open(path, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["Number", "Device ID", "Time", "Temperature (%s)" % unit,
                        "Humidity (%)", "Dew point (%s)" % unit])
            for i, (t, temp, humi, dp) in enumerate(rows, 1):
                w.writerow([i, dev_id, fmt_time(t), fmt_temp(temp), "%g" % humi, fmt_temp(dp)])


def day_range(offset_days=0):
    lt = time.localtime()
    start = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday + offset_days, 0, 0, 0, 0, 0, -1))
    return start, start + 86399
