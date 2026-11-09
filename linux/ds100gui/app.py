"""DS100 datalogger – Linux GUI (PySide6 + pyqtgraph)."""
import argparse
import bisect
import sys
import time

import pyqtgraph as pg
from PySide6.QtCore import QDateTime, QObject, QPoint, QSettings, Qt, QThread, QTimer, Signal, Slot
from PySide6.QtGui import QAction, QActionGroup, QStandardItem, QStandardItemModel
from PySide6.QtWidgets import (
    QApplication, QComboBox, QDateTimeEdit, QDialog, QDialogButtonBox, QFileDialog,
    QFormLayout, QGridLayout, QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit,
    QMainWindow, QMessageBox, QProgressDialog, QPushButton, QSpinBox, QTableView,
    QVBoxLayout, QWidget,
)

from . import device as devmod
from . import protocol
from .db import DB, day_range
from .i18n import LANGS, default_lang, set_lang, tr

PERM_HINT = ('If this is a permission error, create /etc/udev/rules.d/70-ds100.rules with the line\n'
             'SUBSYSTEM=="hidraw", ATTRS{idVendor}=="1941", ATTRS{idProduct}=="8021", MODE="0666"\n'
             'then run "sudo udevadm control --reload-rules", close the program, '
             'unplug the logger and plug it in again.')


def fit_to_screen(dlg, w, h):
    """Size a dialog to at most ~90 % of the screen and center it there."""
    scr = (dlg.parentWidget() or dlg).screen().availableGeometry()
    w, h = min(w, int(scr.width() * 0.92)), min(h, int(scr.height() * 0.85))
    dlg.resize(w, h)
    dlg.move(scr.x() + (scr.width() - w) // 2, scr.y() + (scr.height() - h) // 2)


def save_file(parent, prefs, title, name, filt):
    """Qt's own save dialog: the native GTK one may open behind a modal Qt dialog.
    filt is "PNG (*.png);;CSV (*.csv)"; the extension of the chosen filter is added
    when the user types a name without one."""
    import os
    import re
    folder = prefs.get("last_dir", os.path.expanduser("~"))
    if not os.path.isdir(folder):
        folder = os.path.expanduser("~")
    path, chosen = QFileDialog.getSaveFileName(parent, title, os.path.join(folder, name), filt,
                                               options=QFileDialog.DontUseNativeDialog)
    if not path:
        return ""
    m = re.search(r"\*(\.\w+)", chosen or "")
    if m and not os.path.splitext(path)[1]:
        path += m.group(1)
    prefs.set("last_dir", os.path.dirname(path))
    return path


class TimeAxis(pg.DateAxisItem):
    """Date axis with day.month labels (03.11.) instead of a bare day number."""

    def __init__(self, dmy=True):
        super().__init__(orientation="bottom")
        from pyqtgraph.graphicsItems import DateAxisItem as dai
        day = "%d.%m." if dmy else "%m/%d"
        month = "%m/%Y"
        D, H, M = dai.DAY_SPACING, dai.HOUR_SPACING, dai.MINUTE_SPACING
        self.zoomLevels[5 * 3600 * 24] = dai.ZoomLevel([
            dai.TickSpec(dai.MONTH_SPACING, dai.makeMStepper(1), month),
            dai.TickSpec(D, dai.makeSStepper(D), day, autoSkip=[1, 2, 5, 10])], "00.00.")
        self.zoomLevels[6 * 3600] = dai.ZoomLevel([
            dai.TickSpec(D, dai.makeSStepper(D), day),
            dai.TickSpec(H, dai.makeSStepper(H), "%H:%M", autoSkip=[1, 3, 6, 12])], "00.00. ")
        self.zoomLevels[15 * 60] = dai.ZoomLevel([
            dai.TickSpec(D, dai.makeSStepper(D), day),
            dai.TickSpec(M, dai.makeSStepper(M), "%H:%M", autoSkip=[1, 5, 15, 30])], "00.00. ")
        # x values in the plot are seconds since `offset`: keeps coordinates small,
        # which SVG export needs (it writes numbers with 6 significant digits)
        self.offset = 0

    def tickValues(self, minVal, maxVal, size):
        lv = super().tickValues(minVal + self.offset, maxVal + self.offset, size)
        return [(sp, [v - self.offset for v in vals]) for sp, vals in lv]

    def tickStrings(self, values, scale, spacing):
        return super().tickStrings([v + self.offset for v in values], scale, spacing)


class Prefs:
    def __init__(self):
        self.s = QSettings("ds100", "ds100gui")

    def get(self, k, default):
        v = self.s.value(k, default)
        if isinstance(default, bool):
            return v in (True, "true", "1", 1)
        return type(default)(v) if v is not None else default

    def set(self, k, v):
        self.s.setValue(k, v)

    @property
    def unit(self):
        return "°C" if self.get("unit_c", True) else "°F"

    @property
    def qdatefmt(self):
        return "dd.MM.yyyy" if self.get("date_dmy", True) else "MM/dd/yyyy"

    def temp(self, c):
        if c is None:
            return None
        return c * 9 / 5 + 32 if self.unit == "°F" else c

    def ftemp(self, c):
        return "–" if c is None else "%.1f" % self.temp(c)

    def ftime(self, t):
        if not t:
            return "–"
        f = "%d.%m.%Y" if self.get("date_dmy", True) else "%m/%d/%Y"
        return time.strftime(f + " %H:%M", time.localtime(t))


# ---------------------------------------------------------------- worker --
class Job(QObject):
    """Runs one device operation in a background thread."""
    progress = Signal(int)
    done = Signal(object)
    failed = Signal(object)        # the exception

    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    @Slot()
    def run(self):
        try:
            self.done.emit(self.fn(self.progress.emit))
        except Exception as e:
            self.failed.emit(e)


# --------------------------------------------------------------- helpers --
def dt_edit(ts, fmt):
    w = QDateTimeEdit(QDateTime.fromSecsSinceEpoch(int(ts)))
    w.setCalendarPopup(True)
    w.setDisplayFormat(fmt + " HH:mm")
    return w


class RangeBar(QWidget):
    """Device + start/end selector with Search / Today / Yesterday / Reset."""
    changed = Signal()

    def __init__(self, db, prefs, dev_id):
        super().__init__()
        self.db, self.prefs = db, prefs
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        lay = QHBoxLayout()
        buttons = QHBoxLayout()
        outer.addLayout(lay)
        outer.addLayout(buttons)
        self.dev = QComboBox()
        self.dev.addItems(db.devices())
        if dev_id:
            self.dev.setCurrentText(dev_id)
        t0, t1 = day_range(-6)[0], day_range(0)[1]
        self.t0, self.t1 = dt_edit(t0, prefs.qdatefmt), dt_edit(t1, prefs.qdatefmt)
        lay.addWidget(QLabel(tr("dev_id") + ":"))
        lay.addWidget(self.dev)
        lay.addWidget(QLabel(tr("start") + ":"))
        lay.addWidget(self.t0)
        lay.addWidget(QLabel(tr("end") + ":"))
        lay.addWidget(self.t1)
        for key, fn in (("search", self.changed.emit), ("today", lambda: self.day(0)),
                        ("yesterday", lambda: self.day(-1)), ("reset", self.reset)):
            b = QPushButton(tr(key))
            b.clicked.connect(fn)
            buttons.addWidget(b)
        lay.addStretch()
        buttons.addStretch()
        self.dev.currentTextChanged.connect(lambda _: self.changed.emit())

    def day(self, off):
        a, b = day_range(off)
        self.t0.setDateTime(QDateTime.fromSecsSinceEpoch(int(a)))
        self.t1.setDateTime(QDateTime.fromSecsSinceEpoch(int(b)))
        self.changed.emit()

    def reset(self):
        rows = self.db.records(self.dev.currentText())
        if rows:
            self.t0.setDateTime(QDateTime.fromSecsSinceEpoch(rows[0][0]))
            self.t1.setDateTime(QDateTime.fromSecsSinceEpoch(rows[-1][0]))
        self.changed.emit()

    def rows(self):
        return self.db.records(self.dev.currentText(), self.t0.dateTime().toSecsSinceEpoch(),
                               self.t1.dateTime().toSecsSinceEpoch())


# --------------------------------------------------------------- dialogs --
class HistoryDialog(QDialog):
    def __init__(self, parent, db, prefs, dev_id):
        super().__init__(parent)
        self.db, self.prefs = db, prefs
        self.setWindowTitle(tr("history"))
        lay = QVBoxLayout(self)
        self.bar = RangeBar(db, prefs, dev_id)
        self.bar.changed.connect(self.load)
        lay.addWidget(self.bar)
        row = QHBoxLayout()
        self.count = QLabel()
        row.addWidget(self.count)
        row.addStretch()
        b = QPushButton(tr("export_csv"))
        b.clicked.connect(self.export)
        row.addWidget(b)
        lay.addLayout(row)
        self.model = QStandardItemModel()
        self.view = QTableView()
        self.view.setModel(self.model)
        self.view.setEditTriggers(QTableView.NoEditTriggers)
        self.view.verticalHeader().hide()
        self.view.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        lay.addWidget(self.view)
        fit_to_screen(self, 900, 560)
        self.load()

    def load(self):
        self.rows = self.bar.rows()
        u = self.prefs.unit
        self.model.clear()
        self.model.setHorizontalHeaderLabels([tr("number"), tr("time"), "%s (%s)" % (tr("temperature"), u),
                                              tr("humidity") + " (%)", "%s (%s)" % (tr("dewpoint"), u)])
        for i, (t, temp, humi, dp) in enumerate(self.rows, 1):
            items = [QStandardItem(x) for x in (str(i), self.prefs.ftime(t), self.prefs.ftemp(temp),
                                                 "%g" % humi, self.prefs.ftemp(dp))]
            for it in items:
                it.setTextAlignment(Qt.AlignCenter)
            self.model.appendRow(items)
        self.count.setText("%s: %d" % (tr("total"), len(self.rows)))

    def export(self):
        path = save_file(self, self.prefs, tr("export_csv"), "ds100.csv", "CSV (*.csv)")
        if path:
            self.db.export_csv(path, self.bar.dev.currentText(), self.rows, self.prefs.ftime,
                               self.prefs.ftemp, self.prefs.unit)
            QMessageBox.information(self, tr("notice"), tr("exported"))


class GraphDialog(QDialog):
    def __init__(self, parent, db, prefs, dev_id):
        super().__init__(parent)
        self.db, self.prefs = db, prefs
        self.setWindowTitle(tr("graph"))
        lay = QVBoxLayout(self)
        top = QHBoxLayout()
        top.addWidget(QLabel(tr("type") + ":"))
        self.kind = QComboBox()
        self.kind.addItems([tr("temperature"), tr("humidity"), tr("dewpoint"), tr("all")])
        self.kind.setCurrentIndex(3)
        self.kind.currentIndexChanged.connect(self.load)
        top.addWidget(self.kind)
        self.bar = RangeBar(db, prefs, dev_id)
        self.bar.changed.connect(self.load)
        top.addWidget(self.bar, 1)
        lay.addLayout(top)
        row = QHBoxLayout()
        self.hover = QLabel(" ")
        row.addWidget(self.hover)
        row.addStretch()
        b = QPushButton(tr("export_graph"))
        b.clicked.connect(self.export)
        row.addWidget(b)
        lay.addLayout(row)

        pg.setConfigOptions(antialias=True, background="w", foreground="#333")
        self.plot = pg.PlotWidget(axisItems={"bottom": TimeAxis(prefs.get("date_dmy", True))})
        self.plot.setLabel("bottom", tr("time_axis"))
        self.plot.showGrid(x=True, y=True, alpha=0.25)
        self.plot.addLegend(offset=(10, 10))
        self.vb2 = pg.ViewBox()
        self.plot.scene().addItem(self.vb2)
        self.plot.getAxis("right").linkToView(self.vb2)
        self.vb2.setXLink(self.plot)
        self.plot.getViewBox().sigResized.connect(
            lambda: self.vb2.setGeometry(self.plot.getViewBox().sceneBoundingRect()))
        lay.addWidget(self.plot, 1)
        self.vline = pg.InfiniteLine(angle=90, pen=pg.mkPen("#999", style=Qt.DashLine))
        self.plot.addItem(self.vline, ignoreBounds=True)
        self.plot.scene().sigMouseMoved.connect(self.on_move)
        # pyqtgraph's own right-click "Export..." opens a separate window that ends up
        # behind the others; the export button above replaces it
        self.plot.scene().contextMenu = []
        fit_to_screen(self, 1100, 620)
        self.load()

    def load(self):
        self.rows = self.bar.rows()
        p = self.plot
        for it in list(p.listDataItems()):
            p.removeItem(it)
        self.vb2.clear()
        p.getPlotItem().legend.clear()
        u = self.prefs.unit
        k = self.kind.currentIndex()
        if not self.rows:
            p.setTitle(tr("no_data"))
            return
        parts = [self.prefs.get("title", ""), "ID " + self.bar.dev.currentText(),
                 "%s – %s" % (self.prefs.ftime(self.rows[0][0]), self.prefs.ftime(self.rows[-1][0]))]
        p.setTitle("  ·  ".join(x for x in parts if x), size="11pt")
        self.t0 = self.rows[0][0]
        self.plot.getAxis("bottom").offset = self.t0
        x = [r[0] - self.t0 for r in self.rows]
        temp = [self.prefs.temp(r[1]) for r in self.rows]
        humi = [r[2] for r in self.rows]
        dp = [self.prefs.temp(r[3]) for r in self.rows]
        red, blue, green = (pg.mkPen(c, width=2) for c in ("#d62728", "#1f77b4", "#2ca02c"))
        p.showAxis("right", k == 3)
        if k == 0:
            p.plot(x, temp, pen=red, name=tr("temperature"))
            p.setLabel("left", tr("temperature"), u)
        elif k == 1:
            p.plot(x, humi, pen=blue, name=tr("humidity"))
            p.setLabel("left", tr("humidity"), "%")
        elif k == 2:
            p.plot(x, dp, pen=green, name=tr("dewpoint"))
            p.setLabel("left", tr("dewpoint"), u)
        else:
            p.plot(x, temp, pen=red, name=tr("temperature"))
            p.setLabel("left", tr("temperature"), u)
            c = pg.PlotCurveItem(x, humi, pen=blue)
            self.vb2.addItem(c)
            p.getPlotItem().legend.addItem(c, tr("humidity"))
            p.setLabel("right", tr("humidity"), "%")
            self.vb2.enableAutoRange()
        p.enableAutoRange()

    def on_move(self, pos):
        if not self.rows or not self.plot.sceneBoundingRect().contains(pos):
            return
        xv = self.plot.getViewBox().mapSceneToView(pos).x() + self.t0
        xs = [r[0] for r in self.rows]
        i = min(max(bisect.bisect_left(xs, xv), 0), len(xs) - 1)
        t, temp, humi, dp = self.rows[i]
        self.vline.setPos(t - self.t0)
        u = self.prefs.unit
        self.hover.setText("%s   %s %s %s   %s %g %%   %s %s %s" % (
            self.prefs.ftime(t), tr("temperature"), self.prefs.ftemp(temp), u,
            tr("humidity"), humi, tr("dewpoint"), self.prefs.ftemp(dp), u))

    def export(self):
        name = "ds100_%s" % time.strftime("%Y%m%d")
        if self.rows:
            name = "ds100_%s_%s" % (time.strftime("%Y%m%d", time.localtime(self.rows[0][0])),
                                    time.strftime("%Y%m%d", time.localtime(self.rows[-1][0])))
        path = save_file(self, self.prefs, tr("export_graph"), name + ".png",
                         "PNG (*.png);;PDF (*.pdf);;SVG (*.svg);;CSV (*.csv)")
        if not path:
            return
        ext = path.rsplit(".", 1)[-1].lower()
        self.vline.hide()
        try:
            if ext == "csv":
                self.db.export_csv(path, self.bar.dev.currentText(), self.rows, self.prefs.ftime,
                                   self.prefs.ftemp, self.prefs.unit)
            elif ext in ("pdf", "svg"):
                self._render_vector(path, ext)
            else:
                self.plot.grab().save(path)
        finally:
            self.vline.show()
        QMessageBox.information(self, tr("notice"), tr("exported") + "\n" + path)

    def _render_vector(self, path, ext):
        from PySide6.QtCore import QMarginsF
        from PySide6.QtGui import QPageLayout, QPageSize, QPainter, QPdfWriter
        w, h = self.plot.width(), self.plot.height()
        if ext == "svg":
            from PySide6.QtCore import QRect, QSize
            from PySide6.QtSvg import QSvgGenerator
            dev = QSvgGenerator()
            dev.setFileName(path)
            dev.setSize(QSize(w, h))
            dev.setViewBox(QRect(0, 0, w, h))
            dev.setTitle("DS100")
            painter = QPainter(dev)
            QWidget.render(self.plot, painter, QPoint(0, 0))   # not QGraphicsView.render (rescales)
            painter.end()
            return
        # PDF: the plot rendered at 3x screen resolution, fitted onto an A4 landscape page
        from PySide6.QtCore import QRectF
        from PySide6.QtGui import QImage
        img = QImage(w * 3, h * 3, QImage.Format_ARGB32)
        img.setDevicePixelRatio(3)
        img.fill(Qt.white)
        p = QPainter(img)
        QWidget.render(self.plot, p, QPoint(0, 0))
        p.end()
        dev = QPdfWriter(path)
        dev.setPageLayout(QPageLayout(QPageSize(QPageSize.A4), QPageLayout.Landscape,
                                      QMarginsF(10, 10, 10, 10)))
        dev.setResolution(300)
        dev.setTitle("DS100")
        painter = QPainter(dev)
        page = dev.pageLayout().paintRectPixels(dev.resolution())
        scale = min(page.width() / w, page.height() / h)
        painter.drawImage(QRectF(0, 0, w * scale, h * scale), img)
        painter.end()


class SetupDialog(QDialog):
    """Same fields as the Windows Setup dialog. Unit, date format and interval are
    written to the logger, so they can only be changed while it is connected."""

    def __init__(self, parent, prefs, settings, connected):
        super().__init__(parent)
        self.prefs = prefs
        self.setWindowTitle(tr("setup"))
        f = QFormLayout(self)
        self.title = QLineEdit(prefs.get("title", ""))
        self.title.setMaxLength(31)
        f.addRow(tr("title"), self.title)
        g = QGroupBox(tr("display"))
        gf = QFormLayout(g)
        self.unit = QComboBox()
        self.unit.addItems(["°C", "°F"])
        self.unit.setCurrentIndex(0 if settings.unit_c else 1)
        gf.addRow(tr("temp_unit"), self.unit)
        self.datefmt = QComboBox()
        self.datefmt.addItems(["mm-dd-yy", "dd-mm-yy"])
        self.datefmt.setCurrentIndex(1 if settings.date_dmy else 0)
        gf.addRow(tr("date_fmt"), self.datefmt)
        f.addRow(g)
        g2 = QGroupBox(tr("interval"))
        gf2 = QFormLayout(g2)
        self.iv = QSpinBox()
        self.iv.setRange(1, 240)
        self.iv.setValue(settings.interval if 1 <= settings.interval <= 240 else 15)
        self.iv.setSuffix(" " + tr("minute"))
        gf2.addRow(tr("interval"), self.iv)
        f.addRow(g2)
        for w in (g, g2):
            w.setEnabled(connected)
        if not connected:
            f.addRow(QLabel(tr("no_device")))
        bb = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        bb.button(QDialogButtonBox.Save).setText(tr("save"))
        bb.button(QDialogButtonBox.Cancel).setText(tr("cancel"))
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        f.addRow(bb)

    def values(self):
        return self.title.text(), devmod.Settings(
            interval=self.iv.value(), unit_c=self.unit.currentIndex() == 0,
            date_dmy=self.datefmt.currentIndex() == 1)


class ManagementDialog(QDialog):
    def __init__(self, parent, db, dev_id, connected):
        super().__init__(parent)
        self.main, self.db, self.cur = parent, db, dev_id
        self.setWindowTitle(tr("management"))
        lay = QVBoxLayout(self)
        row = QHBoxLayout()
        row.addWidget(QLabel(tr("dev_id") + ":"))
        self.combo = QComboBox()
        self.combo.addItems(db.devices())
        row.addWidget(self.combo, 1)
        lay.addLayout(row)
        self.info = QLabel()
        lay.addWidget(self.info)
        self.combo.currentTextChanged.connect(self.show_info)
        g = QGroupBox(tr("database"))
        gl = QVBoxLayout(g)
        for key, fn in (("del_dev", self.del_dev), ("del_hist", self.del_hist)):
            b = QPushButton(tr(key))
            b.clicked.connect(fn)
            gl.addWidget(b)
        lay.addWidget(g)
        g2 = QGroupBox(tr("connected") + (": %s" % dev_id if connected and dev_id else ""))
        gl2 = QVBoxLayout(g2)
        b = QPushButton(tr("clear_dev"))
        b.clicked.connect(self.clear_dev)
        b.setEnabled(connected)
        gl2.addWidget(b)
        lay.addWidget(g2)
        self.show_info()

    def show_info(self, *_):
        d = self.db.device(self.combo.currentText())
        n = len(self.db.records(d["DevID"])) if d else 0
        self.info.setText("%s: %d" % (tr("total"), n) if d else "")

    def del_dev(self):
        i = self.combo.currentText()
        if i and QMessageBox.question(self, tr("notice"), tr("q_del_dev") % i) == QMessageBox.Yes:
            self.db.delete_device(i)
            self.combo.removeItem(self.combo.currentIndex())
            QMessageBox.information(self, tr("notice"), tr("deleted"))

    def del_hist(self):
        i = self.combo.currentText()
        if i and QMessageBox.question(self, tr("notice"), tr("q_del_hist") % i) == QMessageBox.Yes:
            self.db.delete_history(i)
            self.show_info()
            QMessageBox.information(self, tr("notice"), tr("deleted"))

    def clear_dev(self):
        if QMessageBox.question(self, tr("notice"), tr("q_clear")) == QMessageBox.Yes:
            self.main.clear_device()
            self.accept()


# ----------------------------------------------------------- main window --
class Main(QMainWindow):
    def __init__(self, args):
        super().__init__()
        self.args = args
        self.prefs = Prefs()
        set_lang(self.prefs.get("lang", default_lang()))
        self.db = DB(args.db) if args.db else DB()
        self.dev = None
        self.info = None
        self.blocked = False
        self.dev_id = self.prefs.get("last_dev", "")
        self.thread = None
        self.build()
        self.refresh()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.poll)
        self.timer.start(2000)
        QTimer.singleShot(200, self.poll)

    # ---- UI --------------------------------------------------------------
    def build(self):
        self.setWindowTitle(tr("app") + (" – " + tr("simulator") if self.args.simulate else ""))
        mb = self.menuBar()
        mb.clear()
        m = mb.addMenu(tr("menu"))
        for key, fn in (("setup", self.open_setup), ("history", self.open_history),
                        ("graph", self.open_graph)):
            m.addAction(tr(key), fn)
        m.addSeparator()
        m.addAction(tr("exit"), self.close)
        mb.addAction(tr("management"), self.open_management)
        lm = mb.addMenu(tr("language"))
        grp = QActionGroup(self)
        for code, name in LANGS.items():
            a = QAction(name, self, checkable=True)
            a.setChecked(self.prefs.get("lang", default_lang()) == code)
            a.triggered.connect(lambda _=False, c=code: self.set_language(c))
            grp.addAction(a)
            lm.addAction(a)
        hm = mb.addMenu(tr("help"))
        hm.addAction(tr("about"), self.about)

        w = QWidget()
        lay = QVBoxLayout(w)
        top = QHBoxLayout()
        for key, fn in (("history", self.open_history), ("graph", self.open_graph),
                        ("setup", self.open_setup)):
            b = QPushButton(tr(key))
            b.clicked.connect(fn)
            top.addWidget(b)
        top.addStretch()
        self.btn_sync = QPushButton(tr("sync"))
        self.btn_sync.clicked.connect(self.sync_time)
        self.btn_read = QPushButton(tr("connect"))
        self.btn_read.clicked.connect(self.read_device)
        top.addWidget(self.btn_sync)
        top.addWidget(self.btn_read)
        lay.addLayout(top)

        g = QGroupBox(tr("devinfo"))
        gl = QGridLayout(g)
        self.fields = {}
        keys = ["dev_id", "title", "update_time", "record_time", "temperature", "humidity",
                "dewpoint", "interval", "total"]
        for i, k in enumerate(keys):
            val = QLabel("–")
            val.setTextInteractionFlags(Qt.TextSelectableByMouse)
            if k in ("temperature", "humidity"):
                val.setStyleSheet("font-size: 22pt; font-weight: bold;")
            gl.addWidget(QLabel(tr(k) + ":"), i // 3, (i % 3) * 2)
            gl.addWidget(val, i // 3, (i % 3) * 2 + 1)
            self.fields[k] = val
        lay.addWidget(g)

        mg = QGroupBox(tr("maxmin"))
        ml = QGridLayout(mg)
        for j, k in enumerate(["max_t", "min_t", "max_h", "min_h"]):
            ml.addWidget(QLabel("<b>%s</b>" % tr(k)), 0, j + 1, alignment=Qt.AlignCenter)
        self.mm = {}
        for i, (k, off) in enumerate((("today", 0), ("yesterday", -1))):
            ml.addWidget(QLabel("<b>%s</b>" % tr(k)), i + 1, 0)
            self.mm[off] = []
            for j in range(4):
                lab = QLabel("–")
                lab.setAlignment(Qt.AlignCenter)
                ml.addWidget(lab, i + 1, j + 1)
                self.mm[off].append(lab)
        lay.addWidget(mg)
        lay.addStretch()
        self.setCentralWidget(w)
        if not hasattr(self, "status"):        # build() runs again on language change
            self.status = QLabel()
            self.statusBar().addPermanentWidget(self.status)
        self.resize(820, 480)

    def set_language(self, code):
        self.prefs.set("lang", code)
        set_lang(code)
        self.statusBar().clearMessage()
        self.build()
        self.refresh()
        self.poll()

    # ---- device connection -----------------------------------------------
    def poll(self):
        if self.thread:
            return
        if self.args.simulate:
            if self.dev is None and not self.blocked:
                self.dev = devmod.open_device(simulate=True)
                self.read_device()
        else:
            present = bool(self.args.dev or protocol.find_hidraw())
            if not present:
                self.blocked = False
            if present and self.dev is None and not self.blocked:
                try:
                    self.dev = devmod.open_device(path=self.args.dev)
                    self.read_device()
                except Exception as e:
                    self.blocked = True
                    QMessageBox.warning(self, tr("notice"), "%s\n\n%s" % (e, PERM_HINT))
            elif not present and self.dev is not None:
                self.dev.close()
                self.dev, self.info = None, None
        on = self.dev is not None
        self.status.setText(("● " + tr("connected")) if on else ("○ " + tr("unconnected")))
        self.status.setStyleSheet("color: %s;" % ("#2a2" if on else "#a22"))
        self.btn_read.setEnabled(on and self.thread is None)
        self.btn_sync.setEnabled(on and self.thread is None)

    def run_job(self, fn, on_done, label, progress_fmt=None, cancel=None):
        """Run fn(progress_cb) in a thread, then on_done(result) in the GUI thread.
        progress_fmt formats the progress number; cancel() is called by the Cancel button."""
        if self.thread:
            return
        self._progress_fmt = progress_fmt or (tr("reading") + " %d")
        self.progress = QProgressDialog(label, tr("cancel") if cancel else None, 0, 0, self)
        if cancel:
            self.progress.canceled.connect(cancel)
        self.progress.setWindowModality(Qt.WindowModal)
        self.progress.setMinimumDuration(300)
        self._on_done = on_done
        self.thread = QThread()
        self.job = Job(fn)
        self.job.moveToThread(self.thread)
        self.thread.started.connect(self.job.run)
        self.job.progress.connect(self.on_progress)
        self.job.done.connect(self.on_job_done)
        self.job.failed.connect(self.on_job_failed)
        self.thread.start()
        self.btn_read.setEnabled(False)
        self.btn_sync.setEnabled(False)

    @Slot(int)
    def on_progress(self, n):
        self.progress.setLabelText(self._progress_fmt % n)

    def _end_job(self):
        self.progress.close()
        self.thread.quit()
        self.thread.wait()
        self.thread = None

    @Slot(object)
    def on_job_done(self, res):
        self._end_job()
        self._on_done(res)
        self.poll()

    @Slot(object)
    def on_job_failed(self, e):
        self._end_job()
        # only a USB/communication error drops the connection (until the logger is replugged);
        # a logger that answers but refuses an operation stays connected
        if (isinstance(e, (OSError, TimeoutError)) and isinstance(self.dev, devmod.Logger)
                and self.dev.name == "USB"):
            self.dev.close()
            self.dev, self.info = None, None
            self.blocked = True
        QMessageBox.warning(self, tr("notice"), "%s: %s" % (type(e).__name__, e))
        self.poll()

    # ---- device operations -----------------------------------------------
    def read_device(self):
        if not self.dev:
            return
        dev = self.dev
        state = {}
        for i in self.db.devices():
            d = self.db.device(i)
            state[i] = (d["DevLastAddr"] or None, d["DevLastRec"] or None)

        def work(progress):
            info = dev.info()
            la, lt = state.get(info.dev_id, (None, None))
            return info, dev.read_history(info, la, lt, progress)

        self.run_job(work, self.read_done, tr("reading"))

    def read_done(self, res):
        info, r = res
        self.info = info
        self.dev_id = info.dev_id
        self.prefs.set("last_dev", info.dev_id)
        s = info.settings
        self.prefs.set("unit_c", s.unit_c)
        self.prefs.set("date_dmy", s.date_dmy)
        n = self.db.add_records(info.dev_id, r.records)
        kw = dict(DevLastTime=int(time.time()), DevInterval=s.interval, DevLastAddr=r.last_addr,
                  DevTempUnit="C" if s.unit_c else "F", DevDateFormat="dmy" if s.date_dmy else "mdy",
                  DevTitle=self.prefs.get("title", ""))
        if r.last_time:
            kw["DevLastRec"] = r.last_time
        if r.current:
            kw.update(CurTemp=r.current.temp, CurHumi=r.current.humi, CurTime=r.current.time)
        self.db.upsert_device(info.dev_id, **kw)
        self.db.upsert_device(info.dev_id, DevnRecords=len(self.db.records(info.dev_id)))
        self.refresh()
        self.statusBar().showMessage("%s: %d" % (tr("new_records"), n), 8000)

    def sync_time(self):
        if not self.dev:
            return
        dev = self.dev
        def done(diff):
            msg = tr("sync_ok")
            if diff is not None:
                msg += "\n" + tr("clock_diff") % diff
            QMessageBox.information(self, tr("notice"), msg)
        stop = []
        self.run_job(lambda p: dev.sync_time(progress=p, cancelled=lambda: bool(stop)), done,
                     tr("syncing"), progress_fmt=tr("sync_wait"), cancel=lambda: stop.append(1))

    def clear_device(self):
        dev, dev_id = self.dev, self.dev_id

        def done(_):
            if dev_id:
                self.db.upsert_device(dev_id, DevLastAddr=None, DevLastRec=None)
            QMessageBox.information(self, tr("notice"), tr("cleared"))
            # header and ID are wiped: wait for the user to replug the logger
            if dev.name == "USB":
                dev.close()
                self.dev, self.info = None, None
                self.blocked = True
        self.run_job(lambda p: dev.clear_history(), done, tr("clearing"))

    # ---- display ---------------------------------------------------------
    def refresh(self):
        f, p = self.fields, self.prefs
        d = self.db.device(self.dev_id) if self.dev_id else None
        last = self.db.last(self.dev_id) if self.dev_id else None
        u = p.unit
        f["dev_id"].setText(self.dev_id or "–")
        f["title"].setText(p.get("title", "") or "–")
        f["update_time"].setText(p.ftime(d["DevLastTime"]) if d else "–")
        f["record_time"].setText(p.ftime(last[0]) if last else "–")
        if d and d["CurTemp"] is not None:
            temp, humi = d["CurTemp"], d["CurHumi"]
        elif last:
            temp, humi = last[1], last[2]
        else:
            temp = humi = None
        f["temperature"].setText("%s %s" % (p.ftemp(temp), u) if temp is not None else "–")
        f["humidity"].setText("%g %%" % humi if humi is not None else "–")
        dp = devmod.dew_point(temp, humi) if temp is not None else None
        f["dewpoint"].setText("%s %s" % (p.ftemp(dp), u) if dp is not None else "–")
        f["interval"].setText("%s %s" % (d["DevInterval"], tr("minute")) if d and d["DevInterval"] else "–")
        f["total"].setText(str(d["DevnRecords"] or 0) if d else "–")
        for off, labels in self.mm.items():
            mm = self.db.maxmin(self.dev_id, *day_range(off)) if self.dev_id else None
            for j, lab in enumerate(labels):
                if not mm:
                    lab.setText("–")
                    continue
                v, t = mm[j]
                s = ("%s %s" % (p.ftemp(v), u)) if j < 2 else ("%g %%" % v)
                lab.setText("%s\n%s" % (s, time.strftime("%H:%M", time.localtime(t))))

    # ---- dialogs ---------------------------------------------------------
    def _show_window(self, dlg):
        """History and Graph are not modal: the main window stays usable."""
        dlg.setAttribute(Qt.WA_DeleteOnClose)
        dlg.setModal(False)
        dlg.show()
        dlg.raise_()
        dlg.activateWindow()
        return dlg

    def open_history(self):
        return self._show_window(HistoryDialog(self, self.db, self.prefs, self.dev_id))

    def open_graph(self):
        return self._show_window(GraphDialog(self, self.db, self.prefs, self.dev_id))

    def open_setup(self):
        connected = self.dev is not None and self.info is not None and self.thread is None
        cur = self.info.settings if self.info else devmod.Settings(
            interval=(self.db.device(self.dev_id) or {}).get("DevInterval") or 15,
            unit_c=self.prefs.get("unit_c", True), date_dmy=self.prefs.get("date_dmy", True))
        dlg = SetupDialog(self, self.prefs, cur, connected)
        if not dlg.exec():
            return
        title, new = dlg.values()
        self.prefs.set("title", title)
        if self.dev_id:
            self.db.upsert_device(self.dev_id, DevTitle=title)
        if not connected or new == cur:
            self.refresh()
            return
        dev = self.dev

        def done(_):
            self.info.settings = new
            self.prefs.set("unit_c", new.unit_c)
            self.prefs.set("date_dmy", new.date_dmy)
            self.db.upsert_device(self.dev_id, DevInterval=new.interval,
                                  DevTempUnit="C" if new.unit_c else "F",
                                  DevDateFormat="dmy" if new.date_dmy else "mdy")
            self.refresh()
            QMessageBox.information(self, tr("notice"), tr("saved"))
        self.run_job(lambda p: dev.apply_settings(new), done, tr("writing"))

    def open_management(self):
        connected = self.dev is not None and self.thread is None
        ManagementDialog(self, self.db, self.dev_id, connected).exec()
        if self.dev_id and not self.db.device(self.dev_id):
            self.dev_id = ""
        self.refresh()

    def about(self):
        QMessageBox.about(self, tr("about"), tr("about_text"))

    def closeEvent(self, e):
        if self.thread:
            self.thread.quit()
            self.thread.wait()
        if self.dev:
            self.dev.close()
        e.accept()


def main():
    ap = argparse.ArgumentParser(description="DS100 datalogger GUI")
    ap.add_argument("--simulate", action="store_true", help="use a simulated logger")
    ap.add_argument("--dev", help="hidraw node, default: auto-detect")
    ap.add_argument("--db", help="database file (default ~/.local/share/ds100/datalogger.db)")
    args = ap.parse_args()
    app = QApplication(sys.argv)
    app.setApplicationName("ds100gui")
    w = Main(args)
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
