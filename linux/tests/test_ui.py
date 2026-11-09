"""Offscreen UI checks for the export problems seen on GNOME."""
import os
import sys
import types

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from PySide6.QtCore import QTimer  # noqa: E402
from PySide6.QtWidgets import QApplication, QFileDialog, QMessageBox  # noqa: E402

from ds100gui import app as A  # noqa: E402
from ds100gui import device as D  # noqa: E402

out = os.path.abspath(sys.argv[1])
os.makedirs(out, exist_ok=True)
msgs = []
QMessageBox.information = staticmethod(lambda *a, **k: msgs.append(a[2]))
qa = QApplication(sys.argv)
w = A.Main(types.SimpleNamespace(simulate=True, dev=None, db=os.path.join(out, "ui.db")))
w.show()
w.prefs.set("last_dir", out)
seen = {}
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print("%-4s %s %s" % ("ok" if ok else "FAIL", name, detail))


def accept_save_dialog():
    dlg = qa.activeModalWidget()
    seen["dlg"] = dlg
    seen["parent"] = dlg.parent() if dlg else None
    if isinstance(dlg, QFileDialog):
        seen["native"] = not dlg.testOption(QFileDialog.DontUseNativeDialog)
        if seen.get("want"):
            name, filt = seen.pop("want")
            dlg.selectNameFilter(filt)
            dlg.selectFile(name)
        dlg.accept()


def go():
    if w.thread or w.info is None:
        return QTimer.singleShot(100, go)
    scr = w.screen().availableGeometry()

    g = w.open_graph()
    qa.processEvents()
    check("graph window is not modal", not g.isModal() and qa.activeModalWidget() is None)
    check("graph fits the screen", g.width() <= scr.width() and g.height() <= scr.height(),
          "%dx%d on %dx%d" % (g.width(), g.height(), scr.width(), scr.height()))
    check("no pyqtgraph Export... in right-click menu", g.plot.scene().contextMenu == [])
    check("main window usable while graph is open", w.isEnabled() and w.btn_read.isVisible())

    title = g.plot.getPlotItem().titleLabel.text
    check("graph title has device ID and time range", "ID " in title and " – " in title, title)
    ax = g.plot.getAxis("bottom")
    labels = ax.tickStrings([0, g.rows[-1][0] - g.rows[0][0]], 1, 86400)  # plot x is relative
    check("x axis shows day.month", all(len(x) == 6 and x[2] == "." for x in labels), str(labels))
    for ext, filt, magic in (("png", "PNG (*.png)", b"\x89PNG"), ("pdf", "PDF (*.pdf)", b"%PDF"),
                             ("svg", "SVG (*.svg)", b"<?xml"), ("csv", "CSV (*.csv)", b"Number")):
        seen["want"] = ("graf_" + ext, filt)
        QTimer.singleShot(300, accept_save_dialog)
        g.export()
        import glob
        found = sorted(glob.glob(os.path.join(out, "*." + ext)), key=os.path.getmtime)
        f = found[-1] if found else ""
        head = open(f, "rb").read(16) if os.path.exists(f) else b""
        check("graph export " + ext.upper(), head.startswith(magic),
              "%s %d B" % (os.path.basename(f), os.path.getsize(f)) if head else "missing")
    check("save dialog is Qt's own and child of the graph",
          seen.get("native") is False and seen.get("parent") is g)
    g.close()

    seen.clear()
    h = w.open_history()
    qa.processEvents()
    check("history window is not modal", not h.isModal())
    check("history fits the screen", h.width() <= scr.width() and h.height() <= scr.height())
    QTimer.singleShot(300, accept_save_dialog)
    h.export()
    csv = os.path.join(out, "ds100.csv")
    check("CSV written", os.path.exists(csv) and os.path.getsize(csv) > 100)
    check("save dialog started in last folder", w.prefs.get("last_dir", "") == out)
    h.close()

    warn = []
    QMessageBox.warning = staticmethod(lambda *a, **k: warn.append(a[2]))
    real_wb = w.dev.t.write_byte
    w.dev.t.write_byte = lambda addr, v: True         # logger ignores the clock trigger
    D.time.sleep = lambda s: None
    real_sync = w.dev.sync_time
    w.dev.sync_time = lambda **k: real_sync(timeout=0, **k)
    w.sync_time()
    while w.thread:
        qa.processEvents()
    w.dev.t.write_byte = real_wb
    w.dev.sync_time = real_sync
    check("failed sync shows what the logger returned", warn and "0x20:" in warn[-1],
          warn[-1].splitlines()[0] if warn else "no warning")
    check("logger stays connected after failed sync", w.dev is not None and not w.blocked)

    from PySide6.QtWidgets import QLabel
    for code in ("en", "sk", "en", "sk", "en"):
        w.set_language(code)
    labels = [c for c in w.statusBar().findChildren(QLabel) if c.text().startswith(("●", "○"))]
    check("one status label after 5 language switches", len(labels) == 1, str(len(labels)))
    w.dev = None
    w.args.simulate = False
    w.blocked = True
    w.poll()
    check("English status text", labels and labels[0].text() == "○ Disconnected",
          labels[0].text() if labels else "")
    w.set_language("sk")
    w.poll()
    check("Slovak status text", labels and labels[0].text() == "○ Nepripojené",
          labels[0].text() if labels else "")
    qa.quit()


QTimer.singleShot(100, go)
QTimer.singleShot(30000, qa.quit)
qa.exec()
sys.exit(0 if results and all(results) else 1)
