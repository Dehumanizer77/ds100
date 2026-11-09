@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package sk.ds100.logger.ui

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.ds100.logger.AppModel
import sk.ds100.logger.ChartData
import sk.ds100.logger.ChartKind
import sk.ds100.logger.ChartRenderer
import sk.ds100.logger.ChartText
import sk.ds100.logger.Db
import sk.ds100.logger.Export
import sk.ds100.logger.Msg
import sk.ds100.logger.R
import sk.ds100.logger.core.Settings
import sk.ds100.logger.core.dewPoint
import java.util.Calendar

@Composable
fun msgText(m: Msg): String {
    val base = stringResource(m.res, *m.args.toTypedArray())
    return if (m.detail != null) base + "\n\n" + m.detail else base
}

@Composable
fun App(model: AppModel) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        var tab by rememberSaveable { mutableStateOf(0) }
        var menu by remember { mutableStateOf(false) }
        var dialog by remember { mutableStateOf("") }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.app_name))
                            if (model.demo) Text(stringResource(R.string.demo_badge), fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                    },
                    actions = {
                        IconButton(onClick = { menu = true }, Modifier.testTag("menu")) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_management)) },
                                onClick = { menu = false; dialog = "management" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_language)) },
                                onClick = { menu = false; dialog = "language" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_demo)) },
                                leadingIcon = { Checkbox(model.demo, null) },
                                onClick = { menu = false; model.setDemoMode(!model.demo) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_about)) },
                                onClick = { menu = false; dialog = "about" })
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    val items = listOf(
                        R.string.tab_overview to @Composable { Icon(Icons.Filled.Home, null) },
                        R.string.tab_history to @Composable { Icon(Icons.AutoMirrored.Filled.List, null) },
                        R.string.tab_graph to @Composable { Icon(painterResource(R.drawable.ic_chart), null) },
                        R.string.tab_setup to @Composable { Icon(Icons.Filled.Settings, null) },
                    )
                    items.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = icon,
                            label = { Text(stringResource(label)) })
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> OverviewScreen(model)
                    1 -> HistoryScreen(model)
                    2 -> GraphScreen(model, dark)
                    else -> SetupScreen(model)
                }
            }
        }

        when (dialog) {
            "management" -> ManagementDialog(model) { dialog = "" }
            "language" -> LanguageDialog { dialog = "" }
            "about" -> AlertDialog(onDismissRequest = { dialog = "" },
                confirmButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.ok)) } },
                title = { Text(stringResource(R.string.app_name)) },
                text = { Text(stringResource(R.string.about_text)) })
        }
        model.busy?.let { b ->
            AlertDialog(onDismissRequest = {}, confirmButton = {
                if (b.cancellable) TextButton(onClick = { model.cancel() }) { Text(stringResource(R.string.cancel)) }
            }, text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(msgText(b.msg))
                }
            })
        }
        model.message?.let { m ->
            AlertDialog(onDismissRequest = { model.message = null },
                confirmButton = { TextButton(onClick = { model.message = null }) { Text(stringResource(R.string.ok)) } },
                text = {
                    SelectionContainer {      // long-press to copy an error report
                        Text(msgText(m), modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                })
        }
    }
}

// ------------------------------------------------------------------ overview --
@Composable
private fun OverviewScreen(model: AppModel) {
    val v = model.dataVersion
    val id = model.devId
    val f = model.fmt
    val d = remember(v, id) { if (id.isEmpty()) null else model.db.device(id) }
    val last = remember(v, id) { if (id.isEmpty()) null else model.db.lastRecord(id) }
    val today = remember(v, id) { if (id.isEmpty()) null else model.db.maxMin(id, AppModel.dayStart(0), AppModel.dayStart(1) - 1) }
    val yest = remember(v, id) { if (id.isEmpty()) null else model.db.maxMin(id, AppModel.dayStart(-1), AppModel.dayStart(0) - 1) }
    val temp = d?.curTemp ?: last?.temp
    val humi = d?.curHumi ?: last?.humi

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(if (model.connected) Color(0xFF2AA02A) else Color(0xFFAA2222), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (model.connected) R.string.connected else R.string.disconnected),
                        fontWeight = FontWeight.Bold)
                }
                if (!model.connected) Text(stringResource(R.string.plug_hint), Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall)
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val enabled = model.connected && model.busy == null
                    Button(onClick = { model.read() }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.read_device))
                    }
                    OutlinedButton(onClick = { model.syncTime() }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sync_time))
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Big(stringResource(R.string.temperature), f.fTempU(temp))
                Big(stringResource(R.string.humidity), f.fHumi(humi))
            }
            val dp = if (temp != null && humi != null) dewPoint(temp, humi) else null
            Text("${stringResource(R.string.dewpoint)}: ${f.fTempU(dp)}", Modifier.fillMaxWidth().padding(bottom = 12.dp),
                textAlign = TextAlign.Center)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.device_info), fontWeight = FontWeight.Bold)
                Info(R.string.dev_id, id.ifEmpty { "–" })
                Info(R.string.title, model.title.ifEmpty { "–" })
                Info(R.string.last_download, f.time(d?.lastTime))
                Info(R.string.last_record, f.time(last?.time))
                Info(R.string.interval, d?.interval?.let { "$it " + stringResource(R.string.minutes) } ?: "–")
                Info(R.string.total_records, d?.nRecords?.toString() ?: "–")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.maxmin), fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 6.dp)) {
                    Cell("", true)
                    for (r in listOf(R.string.max_t, R.string.min_t, R.string.max_h, R.string.min_h)) Cell(stringResource(r), true)
                }
                for ((label, mm) in listOf(R.string.today to today, R.string.yesterday to yest)) {
                    HorizontalDivider()
                    Row {
                        Cell(stringResource(label), true)
                        for (j in 0 until 4) {
                            val p = mm?.get(j)
                            Cell(if (p == null) "–" else (if (j < 2) f.fTempU(p.first) else f.fHumi(p.first)) + "\n" + f.hhmm(p.second))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Big(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Info(label: Int, value: String) {
    Row {
        Text(stringResource(label) + ":", Modifier.weight(1.25f))
        Text(value, Modifier.weight(1f))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(s: String, bold: Boolean = false) =
    Text(s, Modifier.weight(1f).padding(vertical = 4.dp), fontSize = 12.sp, textAlign = TextAlign.Center,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

// ------------------------------------------------------------- range selector --
@Composable
private fun RangeBar(model: AppModel) {
    val ctx = LocalContext.current
    val f = model.fmt
    val devices = remember(model.dataVersion) { model.db.devices() }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        val compact = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { open = true }, contentPadding = compact) {
                    Text(model.devId.ifEmpty { stringResource(R.string.device) } + " ▾", maxLines = 1)
                }
                DropdownMenu(open, { open = false }) {
                    devices.forEach { d -> DropdownMenuItem(text = { Text(d) }, onClick = { open = false; model.selectDevice(d) }) }
                }
            }
            Spacer(Modifier.weight(1f))
            fun pick(t: Long, set: (Long) -> Unit, endOfDay: Boolean) {
                val c = Calendar.getInstance().apply { timeInMillis = t * 1000 }
                DatePickerDialog(ctx, { _, y, m, dd ->
                    val r = Calendar.getInstance().apply {
                        clear(); set(y, m, dd)
                        if (endOfDay) { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59) }
                    }
                    set(r.timeInMillis / 1000)
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
            TextButton(onClick = { pick(model.rangeStart, { model.rangeStart = it }, false) }, contentPadding = compact) {
                Text(f.date(model.rangeStart), maxLines = 1)
            }
            Text("–")
            TextButton(onClick = { pick(model.rangeEnd, { model.rangeEnd = it }, true) }, contentPadding = compact) {
                Text(f.date(model.rangeEnd), maxLines = 1)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(false, { model.setRangeDays(0, 0) }, { Text(stringResource(R.string.today)) })
            FilterChip(false, { model.setRangeDays(-1, -1) }, { Text(stringResource(R.string.yesterday)) })
            FilterChip(false, { model.setRangeDays(-6, 0) }, { Text(stringResource(R.string.last7)) })
            FilterChip(false, { model.setRangeDays(-29, 0) }, { Text(stringResource(R.string.last30)) })
            FilterChip(false, { model.setRangeAll() }, { Text(stringResource(R.string.all)) })
        }
    }
}

@Composable
private fun rows(model: AppModel): List<Db.Row> {
    val r by produceState(emptyList<Db.Row>(), model.dataVersion, model.devId, model.rangeStart, model.rangeEnd) {
        val id = model.devId
        value = if (id.isEmpty()) emptyList()
        else withContext(Dispatchers.IO) { model.db.records(id, model.rangeStart, model.rangeEnd) }
    }
    return r
}

// ------------------------------------------------------------------- history --
@Composable
private fun HistoryScreen(model: AppModel) {
    val ctx = LocalContext.current
    val f = model.fmt
    val data = rows(model)
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { Export.csv(it, model.devId, data, f) }
            ?.also { model.message = Msg(R.string.exported) }
    }
    Column(Modifier.fillMaxSize()) {
        RangeBar(model)
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.records_n, data.size), Modifier.weight(1f))
            OutlinedButton(onClick = { csv.launch(fileName(model, data, "csv")) }, enabled = data.isNotEmpty()) {
                Text(stringResource(R.string.export_csv))
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            for (h in listOf(stringResource(R.string.time), "${stringResource(R.string.temperature_short)} (${f.unit})",
                "${stringResource(R.string.humidity_short)} (%)", "${stringResource(R.string.dewpoint_short)} (${f.unit})"))
                Text(h, Modifier.weight(if (h == stringResource(R.string.time)) 1.6f else 1f), fontWeight = FontWeight.Bold,
                    fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(data) { r ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(f.time(r.time), Modifier.weight(1.6f), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text(f.fTemp(r.temp), Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text("%.0f".format(r.humi), Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text(f.fTemp(r.dew), Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun fileName(model: AppModel, data: List<Db.Row>, ext: String): String {
    val f = model.fmt
    return if (data.isEmpty()) "ds100.$ext"
    else "ds100_${f.fileDate(data.first().time)}_${f.fileDate(data.last().time)}.$ext"
}

// --------------------------------------------------------------------- graph --
@Composable
private fun GraphScreen(model: AppModel, dark: Boolean) {
    val ctx = LocalContext.current
    val f = model.fmt
    val data = rows(model)
    var kind by rememberSaveable { mutableStateOf(ChartKind.BOTH) }
    var cursor by remember(data) { mutableStateOf<Int?>(null) }
    val chart = remember(data, f.unitC) {
        if (data.isEmpty()) null else ChartData(
            data.map { it.time }.toLongArray(), data.map { f.temp(it.temp) }.toDoubleArray(),
            data.map { it.humi }.toDoubleArray(), data.map { f.temp(it.dew) }.toDoubleArray(),
        )
    }
    val title = listOf(model.title, if (model.devId.isEmpty()) "" else "ID ${model.devId}",
        if (data.isEmpty()) "" else "${f.time(data.first().time)} – ${f.time(data.last().time)}")
        .filter { it.isNotEmpty() }.joinToString("  ·  ")
    val tx = ChartText(title, stringResource(R.string.temperature), stringResource(R.string.humidity),
        stringResource(R.string.dewpoint), f.unit, stringResource(R.string.time_axis), stringResource(R.string.no_data),
        model.dateDmy)

    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { Export.png(it, chart, kind, tx) }
            ?.also { model.message = Msg(R.string.exported) }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { Export.pdf(it, chart, kind, tx) }
            ?.also { model.message = Msg(R.string.exported) }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { Export.csv(it, model.devId, data, f) }
            ?.also { model.message = Msg(R.string.exported) }
    }
    val density = LocalDensity.current.density

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((k, label) in listOf(ChartKind.BOTH to R.string.both, ChartKind.TEMP to R.string.temperature,
                ChartKind.HUMI to R.string.humidity, ChartKind.DEW to R.string.dewpoint))
                FilterChip(kind == k, { kind = k }, { Text(stringResource(label)) })
        }
        RangeBar(model)
        Canvas(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp).testTag("chart")
                .pointerInput(chart, kind) {
                    detectTapGestures { p ->
                        val l = ChartRenderer.layout(size.width.toFloat(), size.height.toFloat(), chart, kind, density)
                        if (chart != null && l != null) cursor = chart.nearest(l.tOf(p.x))
                    }
                }
                .pointerInput(chart, kind) {
                    detectDragGestures { change, _ ->
                        val l = ChartRenderer.layout(size.width.toFloat(), size.height.toFloat(), chart, kind, density)
                        if (chart != null && l != null) cursor = chart.nearest(l.tOf(change.position.x))
                    }
                }
        ) {
            drawIntoCanvas { c ->
                ChartRenderer.draw(c.nativeCanvas, size.width, size.height, chart, kind, tx, density, cursor, dark)
            }
        }
        val c = cursor
        Text(
            if (c != null && c < data.size) {
                val r = data[c]
                "${f.time(r.time)}   ${f.fTempU(r.temp)}   ${f.fHumi(r.humi)}   ${stringResource(R.string.dewpoint_short)} ${f.fTempU(r.dew)}"
            } else stringResource(R.string.tap_hint),
            Modifier.fillMaxWidth().padding(4.dp), textAlign = TextAlign.Center, fontSize = 13.sp,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            val ok = data.isNotEmpty()
            OutlinedButton(onClick = { png.launch(fileName(model, data, "png")) }, enabled = ok) { Text("PNG") }
            OutlinedButton(onClick = { pdf.launch(fileName(model, data, "pdf")) }, enabled = ok) { Text("PDF") }
            OutlinedButton(onClick = { csv.launch(fileName(model, data, "csv")) }, enabled = ok) { Text("CSV") }
        }
    }
}

// --------------------------------------------------------------------- setup --
@Composable
private fun SetupScreen(model: AppModel) {
    val connected = model.info != null && model.busy == null
    val cur = model.info?.settings ?: Settings(
        interval = (if (model.devId.isEmpty()) null else model.db.device(model.devId)?.interval) ?: 15,
        unitC = model.unitC, dateDmy = model.dateDmy,
    )
    var title by remember(model.title) { mutableStateOf(model.title) }
    var unitC by remember(cur) { mutableStateOf(cur.unitC) }
    var dateDmy by remember(cur) { mutableStateOf(cur.dateDmy) }
    var interval by remember(cur) { mutableStateOf(cur.interval.coerceIn(1, 240).toString()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(title, { title = it.take(31) }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.title)) }, singleLine = true)
        Section(stringResource(R.string.display), connected) {
            Text(stringResource(R.string.temp_unit))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(unitC, { unitC = true }, enabled = connected); Text("°C")
                Spacer(Modifier.width(16.dp))
                RadioButton(!unitC, { unitC = false }, enabled = connected); Text("°F")
            }
            Text(stringResource(R.string.date_format))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(dateDmy, { dateDmy = true }, enabled = connected); Text("dd-mm-yy")
                Spacer(Modifier.width(16.dp))
                RadioButton(!dateDmy, { dateDmy = false }, enabled = connected); Text("mm-dd-yy")
            }
        }
        Section(stringResource(R.string.interval), connected) {
            val n = interval.toIntOrNull()
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(interval, { s -> interval = s.filter { it.isDigit() }.take(3) }, Modifier.width(110.dp),
                    enabled = connected, singleLine = true, isError = n == null || n !in 1..240,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.minutes))
            }
            Slider((n ?: 15).coerceIn(1, 240).toFloat(), { interval = it.toInt().toString() }, enabled = connected,
                valueRange = 1f..240f)
        }
        if (!connected) Text(stringResource(R.string.settings_need_device), style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            val n = interval.toIntOrNull()
            if (connected && (n == null || n !in 1..240)) model.message = Msg(R.string.interval_range)
            else model.saveSetup(title, Settings(n ?: cur.interval, unitC, dateDmy))
        }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun Section(title: String, enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline)
            content()
        }
    }
}

// ---------------------------------------------------------------- management --
@Composable
private fun ManagementDialog(model: AppModel, close: () -> Unit) {
    val devices = remember(model.dataVersion) { model.db.devices() }
    var sel by remember { mutableStateOf(model.devId.ifEmpty { devices.firstOrNull() ?: "" }) }
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val count = remember(model.dataVersion, sel) { if (sel.isEmpty()) 0 else model.db.device(sel)?.nRecords ?: 0 }

    val qDev = stringResource(R.string.q_del_dev, sel)
    val qHist = stringResource(R.string.q_del_hist, sel)
    val qClear = stringResource(R.string.q_clear)
    AlertDialog(onDismissRequest = close,
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.ok)) } },
        title = { Text(stringResource(R.string.menu_management)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.database), fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.device) + ":")
                    Box {
                        TextButton(onClick = { open = true }) { Text(sel.ifEmpty { "–" }) }
                        DropdownMenu(open, { open = false }) {
                            devices.forEach { d -> DropdownMenuItem(text = { Text(d) }, onClick = { open = false; sel = d }) }
                        }
                    }
                }
                Text(stringResource(R.string.records_n, count), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirm = qDev to { model.deleteDevice(sel); sel = "" } },
                    enabled = sel.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.del_dev)) }
                OutlinedButton(onClick = { confirm = qHist to { model.deleteHistory(sel) } },
                    enabled = sel.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.del_hist)) }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.logger), fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { confirm = qClear to { model.clearDevice(); close() } },
                    enabled = model.connected && model.busy == null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.clear_dev))
                }
            }
        })
    confirm?.let { (q, action) ->
        AlertDialog(onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.no)) } },
            text = { Text(q) })
    }
}

// ------------------------------------------------------------------ language --
@Composable
private fun LanguageDialog(close: () -> Unit) {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    AlertDialog(onDismissRequest = close,
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.menu_language)) },
        text = {
            Column {
                for ((tag, label) in listOf("" to stringResource(R.string.lang_system), "sk" to "Slovenčina", "en" to "English")) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(current.startsWith(tag) && (tag.isNotEmpty() || current.isEmpty()), {
                            close()
                            AppCompatDelegate.setApplicationLocales(
                                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                                else LocaleListCompat.forLanguageTags(tag))
                        })
                        Text(label)
                    }
                }
            }
        })
}
