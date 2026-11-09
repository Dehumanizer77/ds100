package sk.ds100.logger

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Units and date format chosen in Setup (same as written to the logger LCD). */
class Fmt(val unitC: Boolean, val dateDmy: Boolean) {
    val unit get() = if (unitC) "°C" else "°F"
    private val dt = SimpleDateFormat(if (dateDmy) "dd.MM.yyyy HH:mm" else "MM/dd/yyyy HH:mm", Locale.ROOT)
    private val hm = SimpleDateFormat("HH:mm", Locale.ROOT)
    private val day = SimpleDateFormat(if (dateDmy) "dd.MM.yyyy" else "MM/dd/yyyy", Locale.ROOT)
    private val file = SimpleDateFormat("yyyyMMdd", Locale.ROOT)

    fun temp(c: Double) = if (unitC) c else c * 9 / 5 + 32
    fun fTemp(c: Double?) = if (c == null || c.isNaN()) "–" else "%.1f".format(Locale.ROOT, temp(c))
    fun fTempU(c: Double?) = if (c == null || c.isNaN()) "–" else fTemp(c) + " " + unit
    fun fHumi(h: Double?) = if (h == null) "–" else "%.0f %%".format(Locale.ROOT, h)
    fun time(t: Long?) = if (t == null || t == 0L) "–" else dt.format(Date(t * 1000))
    fun hhmm(t: Long) = hm.format(Date(t * 1000))
    fun date(t: Long) = day.format(Date(t * 1000))
    fun fileDate(t: Long) = file.format(Date(t * 1000))
}

object Export {
    fun csv(out: OutputStream, devId: String, rows: List<Db.Row>, f: Fmt) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("Number,Device ID,Time,Temperature (${f.unit}),Humidity (%),Dew point (${f.unit})\n")
        rows.forEachIndexed { i, r ->
            w.write("${i + 1},$devId,${f.time(r.time)},${f.fTemp(r.temp)},${"%.0f".format(Locale.ROOT, r.humi)},${f.fTemp(r.dew)}\n")
        }
        w.flush()
    }

    fun png(out: OutputStream, d: ChartData?, kind: ChartKind, tx: ChartText) {
        val scale = 2f
        val bmp = Bitmap.createBitmap((900 * scale).toInt(), (520 * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        ChartRenderer.draw(c, bmp.width.toFloat(), bmp.height.toFloat(), d, kind, tx, scale)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }

    /** A4 landscape, vector graphics. */
    fun pdf(out: OutputStream, d: ChartData?, kind: ChartKind, tx: ChartText) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(842, 595, 1).create())
        val c = page.canvas
        c.translate(28f, 28f)
        ChartRenderer.draw(c, 842f - 56, 595f - 56, d, kind, tx, 1f)
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}
