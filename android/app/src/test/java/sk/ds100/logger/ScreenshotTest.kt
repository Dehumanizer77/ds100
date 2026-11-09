package sk.ds100.logger

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sk.ds100.logger.ui.App
import java.io.ByteArrayOutputStream

/** Runs the app in demo mode (simulated logger) and saves screenshots to build/shots/. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w392dp-h850dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<Application>().getString(id)

    private fun model(): AppModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("ds100", 0).edit().clear().putBoolean("demo", true).commit()
        return AppModel(app)
    }

    @Test
    fun screens() {
        val m = model()
        rule.setContent { App(m) }
        rule.waitUntil(20_000) { m.busy == null && m.dataVersion > 0 }
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/0_after_read.png")       // "new records" message
        m.message = null
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/1_overview.png")

        rule.onNodeWithText(str(R.string.tab_history)).performClick()
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/2_history.png")

        rule.onNodeWithText(str(R.string.tab_graph)).performClick()
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/3_graph.png")
        rule.onNodeWithText(str(R.string.last7)).performClick()
        rule.waitForIdle()

        rule.onNodeWithText(str(R.string.tab_setup)).performClick()
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/4_setup.png")

        rule.onNodeWithTag("menu").performClick()
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/5_menu.png")
        rule.onNodeWithText(str(R.string.menu_management)).performClick()
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/6_management.png")
    }

    @Test
    fun graphCursor() {
        val m = model()
        rule.setContent { App(m) }
        rule.waitUntil(20_000) { m.busy == null && m.dataVersion > 0 }
        m.message = null
        rule.onNodeWithText(str(R.string.tab_graph)).performClick()
        // graph data are loaded in the background: wait until the export buttons are enabled
        rule.waitUntil(10_000) {
            !rule.onNodeWithText("PNG").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        rule.onNodeWithTag("chart").performTouchInput { click(center) }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.tap_hint)).assertDoesNotExist()
        captureScreenRoboImage("build/shots/3b_graph_cursor.png")
    }

    @Test
    fun exports() {
        val m = model()
        rule.setContent { App(m) }
        rule.waitUntil(20_000) { m.busy == null && m.dataVersion > 0 }
        val rows = m.db.records(m.devId)
        assertTrue(rows.size > 900)
        val f = m.fmt
        val data = ChartData(rows.map { it.time }.toLongArray(), rows.map { f.temp(it.temp) }.toDoubleArray(),
            rows.map { it.humi }.toDoubleArray(), rows.map { f.temp(it.dew) }.toDoubleArray())
        val tx = ChartText("ID ${m.devId}  ·  ${f.time(rows.first().time)} – ${f.time(rows.last().time)}",
            str(R.string.temperature), str(R.string.humidity), str(R.string.dewpoint), f.unit,
            str(R.string.time_axis), str(R.string.no_data), true)
        val png = ByteArrayOutputStream().also { Export.png(it, data, ChartKind.BOTH, tx) }.toByteArray()
        assertTrue(png.size > 10_000 && png[1] == 'P'.code.toByte())
        java.io.File("build/shots").mkdirs()
        java.io.File("build/shots/7_export.png").writeBytes(png)
        // PDF: Robolectric has no native PdfDocument ("document is closed!"), so it is checked on a phone only
        try {
            val pdf = ByteArrayOutputStream().also { Export.pdf(it, data, ChartKind.BOTH, tx) }.toByteArray()
            assertTrue(String(pdf, 0, 4) == "%PDF")
            java.io.File("build/shots/8_export.pdf").writeBytes(pdf)
        } catch (e: IllegalStateException) {
            println("PDF export not testable under Robolectric: ${e.message}")
        }
        val csv = ByteArrayOutputStream().also { Export.csv(it, m.devId, rows, f) }.toString("UTF-8")
        assertTrue(csv.startsWith("Number,Device ID,Time,Temperature (°C)"))
        assertTrue(csv.lines().size > 900)
    }
}
