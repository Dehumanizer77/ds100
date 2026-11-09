package sk.ds100.logger

import android.app.Application
import android.content.ContentValues
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.ds100.logger.core.DeviceInfo
import sk.ds100.logger.core.Logger
import sk.ds100.logger.core.Settings
import sk.ds100.logger.core.SimTransport
import sk.ds100.logger.core.Transport
import java.io.IOException
import java.util.Calendar

/** A text for the UI: string resource + format args, resolved in the current app language. */
data class Msg(@StringRes val res: Int, val args: List<Any> = emptyList(), val detail: String? = null)

data class Busy(val msg: Msg, val cancellable: Boolean = false)

class AppModel(app: Application) : AndroidViewModel(app) {
    val db = Db(app)
    private val prefs = app.getSharedPreferences("ds100", 0)

    var logger by mutableStateOf<Logger?>(null); private set
    var info by mutableStateOf<DeviceInfo?>(null); private set
    var devId by mutableStateOf(prefs.getString("last_dev", "") ?: ""); private set
    var busy by mutableStateOf<Busy?>(null); private set
    var message by mutableStateOf<Msg?>(null)
    var dataVersion by mutableIntStateOf(0); private set

    var title by mutableStateOf(prefs.getString("title", "") ?: ""); private set
    var unitC by mutableStateOf(prefs.getBoolean("unit_c", true)); private set
    var dateDmy by mutableStateOf(prefs.getBoolean("date_dmy", true)); private set
    var demo by mutableStateOf(prefs.getBoolean("demo", false)); private set

    // range shown in History and Graph (unix seconds)
    var rangeStart by mutableStateOf(dayStart(-6))
    var rangeEnd by mutableStateOf(dayStart(1) - 1)

    private var cancelFlag = false

    val connected get() = logger != null
    val fmt get() = Fmt(unitC, dateDmy)

    init {
        if (demo) attach(SimTransport(), "Simulator")
    }

    // ------------------------------------------------------- connection --
    fun attach(t: Transport, name: String = "USB") {
        logger?.close()
        logger = Logger(t, name)
        read()
    }

    /** Open a transport in the background (the USB test read may take a few seconds). */
    fun attachAsync(name: String = "USB", open: () -> Transport) {
        if (busy != null) return
        busy = Busy(Msg(R.string.connecting))
        viewModelScope.launch {
            try {
                val t = withContext(Dispatchers.IO) { open() }
                busy = null
                attach(t, name)
            } catch (e: Exception) {
                busy = null
                message = Msg(R.string.error, detail = "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun detach() {
        logger?.close()
        logger = null
        info = null
    }

    fun setDemoMode(on: Boolean) {
        demo = on
        prefs.edit().putBoolean("demo", on).apply()
        if (on) attach(SimTransport(), "Simulator") else if (logger?.name == "Simulator") detach()
    }

    // ------------------------------------------------------- operations --
    private fun <T> job(busyMsg: Busy, work: suspend (Logger) -> T, done: (T) -> Unit) {
        val lg = logger ?: run { message = Msg(R.string.no_device); return }
        if (busy != null) return
        busy = busyMsg
        cancelFlag = false
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { work(lg) }
                busy = null
                done(r)
            } catch (e: Exception) {
                busy = null
                // a USB error drops the connection; the logger refusing something does not
                if (e is IOException && lg.name == "USB") detach()
                message = Msg(R.string.error, detail = "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun cancel() { cancelFlag = true }

    fun read() {
        val state = db.devices().associateWith { db.device(it) }
        job(Busy(Msg(R.string.reading, listOf(0))), { lg ->
            val inf = lg.info()
            val d = state[inf.devId]
            val res = lg.readHistory(inf, d?.lastAddr, d?.lastRec) { n ->
                busy = Busy(Msg(R.string.reading, listOf(n)))
            }
            val n = db.addRecords(inf.devId, res.records)
            val v = ContentValues().apply {
                put("DevLastTime", System.currentTimeMillis() / 1000)
                put("DevInterval", inf.settings.interval)
                put("DevLastAddr", res.lastAddr)
                put("DevTempUnit", if (inf.settings.unitC) "C" else "F")
                put("DevDateFormat", if (inf.settings.dateDmy) "dmy" else "mdy")
                put("DevTitle", title)
                res.lastTime?.let { put("DevLastRec", it) }
                res.current?.let { put("CurTemp", it.temp); put("CurHumi", it.humi); put("CurTime", it.time) }
            }
            db.upsertDevice(inf.devId, v)
            inf to n
        }) { (inf, n) ->
            info = inf
            devId = inf.devId
            unitC = inf.settings.unitC
            dateDmy = inf.settings.dateDmy
            prefs.edit().putString("last_dev", devId).putBoolean("unit_c", unitC)
                .putBoolean("date_dmy", dateDmy).apply()
            dataVersion++
            message = Msg(R.string.new_records, listOf(n))
        }
    }

    fun syncTime() = job(Busy(Msg(R.string.sync_wait, listOf(0)), cancellable = true), { lg ->
        lg.syncTime(progress = { s -> busy = Busy(Msg(R.string.sync_wait, listOf(s)), true) },
            cancelled = { cancelFlag })
    }) { diff ->
        message = if (diff == null) Msg(R.string.sync_ok)
        else Msg(R.string.sync_ok_diff, listOf(diff.toInt()))
    }

    /** Title is local; unit, date format and interval are written to the logger. */
    fun saveSetup(newTitle: String, s: Settings) {
        title = newTitle
        prefs.edit().putString("title", newTitle).apply()
        if (devId.isNotEmpty()) db.upsertDevice(devId, ContentValues().apply { put("DevTitle", newTitle) })
        val cur = info?.settings
        if (cur == null || cur == s) { dataVersion++; return }
        job(Busy(Msg(R.string.writing)), { lg -> lg.applySettings(s) }) {
            info = info?.copy(settings = s)
            unitC = s.unitC; dateDmy = s.dateDmy
            prefs.edit().putBoolean("unit_c", unitC).putBoolean("date_dmy", dateDmy).apply()
            db.upsertDevice(devId, ContentValues().apply { put("DevInterval", s.interval) })
            dataVersion++
            message = Msg(R.string.saved_to_device)
        }
    }

    fun clearDevice() {
        val id = devId
        job(Busy(Msg(R.string.clearing)), { lg -> lg.clearHistory() }) {
            if (id.isNotEmpty()) db.forgetPosition(id)
            message = Msg(R.string.cleared)
            if (logger?.name == "USB") detach()       // header and ID are wiped: replug
        }
    }

    fun deleteDevice(id: String) {
        db.deleteDevice(id)
        if (id == devId) devId = ""
        dataVersion++
        message = Msg(R.string.deleted)
    }

    fun deleteHistory(id: String) {
        db.deleteHistory(id)
        dataVersion++
        message = Msg(R.string.deleted)
    }

    fun selectDevice(id: String) {
        devId = id
        prefs.edit().putString("last_dev", id).apply()
    }

    fun setRangeDays(fromOffset: Int, toOffset: Int) {
        rangeStart = dayStart(fromOffset)
        rangeEnd = dayStart(toOffset + 1) - 1
    }

    fun setRangeAll() {
        val rows = db.records(devId)
        if (rows.isNotEmpty()) { rangeStart = rows.first().time; rangeEnd = rows.last().time }
    }

    companion object {
        fun dayStart(offsetDays: Int): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, offsetDays)
        }.timeInMillis / 1000
    }
}
