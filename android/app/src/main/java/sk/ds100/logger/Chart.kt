package sk.ds100.logger

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

enum class ChartKind { TEMP, HUMI, DEW, BOTH }

/** Values already in display units (°C or °F). */
class ChartData(val times: LongArray, val temp: DoubleArray, val humi: DoubleArray, val dew: DoubleArray) {
    val size get() = times.size

    /** Index of the record closest to time t. */
    fun nearest(t: Double): Int {
        var i = java.util.Arrays.binarySearch(times, t.toLong())
        if (i < 0) i = -i - 1
        if (i >= size) return size - 1
        if (i > 0 && abs(times[i - 1] - t) < abs(times[i] - t)) return i - 1
        return i
    }
}

class ChartText(
    val title: String, val temp: String, val humi: String, val dew: String,
    val unit: String, val timeAxis: String, val noData: String, val dateDmy: Boolean,
)

/** Draws the graph with android.graphics; used on screen and for PNG/PDF export. */
object ChartRenderer {
    private const val RED = 0xFFD62728.toInt()
    private const val BLUE = 0xFF1F77B4.toInt()
    private const val GREEN = 0xFF2CA02C.toInt()

    class Layout(val plot: RectF, val t0: Long, val t1: Long) {
        fun xOf(t: Long) = plot.left + (t - t0).toFloat() / max(1L, t1 - t0) * plot.width()
        fun tOf(x: Float) = t0 + ((x - plot.left) / plot.width()).toDouble() * (t1 - t0)
    }

    /** Plot area and time scale, without drawing (also used to map a touch to a record). */
    fun layout(w: Float, h: Float, d: ChartData?, kind: ChartKind, dp: Float): Layout? {
        if (d == null || d.size == 0) return null
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * dp }
        val left = small.measureText("-00.0") + 26 * dp
        val right = if (kind == ChartKind.BOTH) small.measureText("100") + 26 * dp else 14 * dp
        val t0 = d.times.first()
        return Layout(RectF(left, 30 * dp, w - right, h - 44 * dp), t0, max(d.times.last(), t0 + 60))
    }

    fun draw(c: Canvas, w: Float, h: Float, d: ChartData?, kind: ChartKind, tx: ChartText,
             dp: Float, cursor: Int? = null, dark: Boolean = false): Layout? {
        val fg = if (dark) 0xFFE0E0E0.toInt() else 0xFF333333.toInt()
        val grid = if (dark) 0x33FFFFFF else 0x22000000
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = 12 * dp }
        val small = Paint(text).apply { textSize = 11 * dp }
        val titleP = Paint(text).apply { textSize = 14 * dp; textAlign = Paint.Align.CENTER }
        val gridP = Paint().apply { color = grid; strokeWidth = max(1f, dp * 0.7f) }
        val axisP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; strokeWidth = max(1f, dp) }

        c.drawText(tx.title, w / 2, 18 * dp, titleP)
        if (d == null || d.size == 0) {
            c.drawText(tx.noData, w / 2, h / 2, titleP)
            return null
        }
        val both = kind == ChartKind.BOTH
        val lay = layout(w, h, d, kind, dp)!!
        val plot = lay.plot
        val t0 = lay.t0
        val t1 = lay.t1

        // y ranges
        val main = when (kind) { ChartKind.HUMI -> d.humi; ChartKind.DEW -> d.dew; else -> d.temp }
        val (y0, y1, ystep) = niceRange(main)
        val (h0, h1, hstep) = niceRange(d.humi)
        fun yOf(v: Double, a: Double, b: Double) = plot.bottom - ((v - a) / (b - a)).toFloat() * plot.height()

        // horizontal grid + left labels
        small.textAlign = Paint.Align.RIGHT
        var v = y0
        while (v <= y1 + ystep / 2) {
            val y = yOf(v, y0, y1)
            c.drawLine(plot.left, y, plot.right, y, gridP)
            c.drawText(fmtNum(v, ystep), plot.left - 5 * dp, y + 4 * dp, small)
            v += ystep
        }
        if (both) {
            small.textAlign = Paint.Align.LEFT
            var hv = h0
            while (hv <= h1 + hstep / 2) {
                c.drawText(fmtNum(hv, hstep), plot.right + 5 * dp, yOf(hv, h0, h1) + 4 * dp, small)
                hv += hstep
            }
        }

        // x ticks
        val labelW = small.measureText("00.00.") + 18 * dp
        val step = pickStep(t1 - t0, plot.width() / labelW)
        small.textAlign = Paint.Align.CENTER
        for (t in ticks(t0, t1, step)) {
            val x = lay.xOf(t)
            c.drawLine(x, plot.top, x, plot.bottom, gridP)
            c.drawLine(x, plot.bottom, x, plot.bottom + 4 * dp, axisP)
            c.drawText(tickLabel(t, step, tx.dateDmy), x, plot.bottom + 17 * dp, small)
        }

        // axes and titles
        c.drawLine(plot.left, plot.top, plot.left, plot.bottom, axisP)
        c.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axisP)
        if (both) c.drawLine(plot.right, plot.top, plot.right, plot.bottom, axisP)
        text.textAlign = Paint.Align.CENTER
        c.drawText(tx.timeAxis, plot.centerX(), h - 8 * dp, text)
        val leftTitle = when (kind) {
            ChartKind.HUMI -> "${tx.humi} (%)"; ChartKind.DEW -> "${tx.dew} (${tx.unit})"
            else -> "${tx.temp} (${tx.unit})"
        }
        c.save(); c.rotate(-90f, 13 * dp, plot.centerY())
        c.drawText(leftTitle, 13 * dp, plot.centerY(), text); c.restore()
        if (both) {
            c.save(); c.rotate(90f, w - 9 * dp, plot.centerY())
            c.drawText("${tx.humi} (%)", w - 9 * dp, plot.centerY(), text); c.restore()
        }

        // curves
        c.save()
        c.clipRect(plot)
        fun curve(vals: DoubleArray, a: Double, b: Double, color: Int) {
            val p = Path()
            for (i in 0 until d.size) {
                val x = lay.xOf(d.times[i]); val y = yOf(vals[i], a, b)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.6f * dp
                strokeJoin = Paint.Join.ROUND
            })
        }
        val legend = mutableListOf<Pair<String, Int>>()
        when (kind) {
            ChartKind.TEMP -> { curve(d.temp, y0, y1, RED); legend += tx.temp to RED }
            ChartKind.HUMI -> { curve(d.humi, y0, y1, BLUE); legend += tx.humi to BLUE }
            ChartKind.DEW -> { curve(d.dew, y0, y1, GREEN); legend += tx.dew to GREEN }
            ChartKind.BOTH -> {
                curve(d.temp, y0, y1, RED); curve(d.humi, h0, h1, BLUE)
                legend += tx.temp to RED; legend += tx.humi to BLUE
            }
        }
        if (cursor != null && cursor in 0 until d.size) {
            val x = lay.xOf(d.times[cursor])
            c.drawLine(x, plot.top, x, plot.bottom, Paint(axisP).apply {
                color = 0xFF888888.toInt(); pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
            })
        }
        c.restore()

        // legend
        small.textAlign = Paint.Align.LEFT
        var ly = plot.top + 16 * dp
        val bg = Paint().apply { color = if (dark) 0xCC202020.toInt() else 0xCCFFFFFF.toInt() }
        val lw = legend.maxOf { small.measureText(it.first) } + 36 * dp
        c.drawRect(plot.left + 6 * dp, plot.top + 4 * dp, plot.left + 6 * dp + lw, plot.top + 8 * dp + 16 * dp * legend.size, bg)
        for ((name, col) in legend) {
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; strokeWidth = 2 * dp }
            c.drawLine(plot.left + 12 * dp, ly - 4 * dp, plot.left + 30 * dp, ly - 4 * dp, lp)
            c.drawText(name, plot.left + 36 * dp, ly, small)
            ly += 16 * dp
        }
        return lay
    }

    private fun niceRange(vals: DoubleArray): Triple<Double, Double, Double> {
        var lo = vals.filter { !it.isNaN() }.minOrNull() ?: 0.0
        var hi = vals.filter { !it.isNaN() }.maxOrNull() ?: 1.0
        if (hi - lo < 1) { lo -= 0.5; hi += 0.5 }
        val raw = (hi - lo) / 5
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
        return Triple(floor(lo / step) * step, ceil(hi / step) * step, step)
    }

    private fun fmtNum(v: Double, step: Double) =
        if (step >= 1) "%.0f".format(v) else "%.1f".format(v)

    private val STEPS = longArrayOf(600, 1800, 3600, 2 * 3600, 3 * 3600, 6 * 3600, 12 * 3600,
        86400, 2 * 86400, 7 * 86400, 14 * 86400, 30 * 86400, 61 * 86400)

    private fun pickStep(span: Long, maxTicks: Float): Long =
        STEPS.firstOrNull { span / it <= maxTicks } ?: STEPS.last()

    /** Tick times aligned to local midnight. */
    private fun ticks(t0: Long, t1: Long, step: Long): List<Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = t0 * 1000
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val out = mutableListOf<Long>()
        if (step < 86400) {
            var t = cal.timeInMillis / 1000
            while (t <= t1) { if (t >= t0) out += t; t += step }
        } else {
            val days = (step / 86400).toInt()
            while (cal.timeInMillis / 1000 <= t1) {
                val t = cal.timeInMillis / 1000
                if (t >= t0) out += t
                cal.add(Calendar.DAY_OF_MONTH, days)
            }
        }
        return out
    }

    private fun tickLabel(t: Long, step: Long, dmy: Boolean): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t * 1000 }
        val midnight = cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) == 0
        return if (step >= 86400 || midnight) {
            val d = cal.get(Calendar.DAY_OF_MONTH); val m = cal.get(Calendar.MONTH) + 1
            if (dmy) "%02d.%02d.".format(d, m) else "%02d/%02d".format(m, d)
        } else {
            "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
    }
}
