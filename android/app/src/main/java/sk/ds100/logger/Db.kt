package sk.ds100.logger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import sk.ds100.logger.core.Record
import sk.ds100.logger.core.dewPoint

/** Local history, same tables as DataLogger.mdb of the Windows app / the Linux app. */
class Db(context: Context) : SQLiteOpenHelper(context, "datalogger.db", null, 1) {

    data class Device(
        val id: String, val title: String?, val interval: Int?, val lastAddr: Int?, val lastRec: Long?,
        val lastTime: Long?, val nRecords: Int, val curTemp: Double?, val curHumi: Double?, val curTime: Long?,
    )

    data class Row(val time: Long, val temp: Double, val humi: Double, val dew: Double)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE Device (DevID TEXT PRIMARY KEY, DevTitle TEXT, Version TEXT,
               DevInterval INTEGER, DevLastAddr INTEGER, DevLastTime INTEGER, DevTempUnit TEXT,
               DevDateFormat TEXT, DevnRecords INTEGER DEFAULT 0, DevLastRec INTEGER,
               CurTemp REAL, CurHumi REAL, CurTime INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE Records (ID TEXT, Time INTEGER, Temp REAL, Humi REAL, DewPoint REAL,
               PRIMARY KEY (ID, Time))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun devices(): List<String> = readableDatabase.rawQuery("SELECT DevID FROM Device ORDER BY DevID", null)
        .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    fun device(id: String): Device? = readableDatabase.rawQuery(
        "SELECT DevID, DevTitle, DevInterval, DevLastAddr, DevLastRec, DevLastTime, " +
            "(SELECT COUNT(*) FROM Records WHERE ID=DevID), CurTemp, CurHumi, CurTime FROM Device WHERE DevID=?",
        arrayOf(id)
    ).use { c ->
        if (!c.moveToFirst()) null else Device(
            c.getString(0), c.getStringOrNull(1), c.getIntOrNull(2), c.getIntOrNull(3), c.getLongOrNull(4),
            c.getLongOrNull(5), c.getInt(6), c.getDoubleOrNull(7), c.getDoubleOrNull(8), c.getLongOrNull(9),
        )
    }

    fun upsertDevice(id: String, values: ContentValues) {
        val db = writableDatabase
        db.insertWithOnConflict("Device", null, ContentValues().apply { put("DevID", id) },
            SQLiteDatabase.CONFLICT_IGNORE)
        if (values.size() > 0) db.update("Device", values, "DevID=?", arrayOf(id))
    }

    fun addRecords(id: String, records: List<Record>): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT OR IGNORE INTO Records VALUES (?,?,?,?,?)")
            for (r in records) {
                st.bindString(1, id); st.bindLong(2, r.time); st.bindDouble(3, r.temp)
                st.bindDouble(4, r.humi.toDouble())
                st.bindDouble(5, Math.round(dewPoint(r.temp, r.humi.toDouble()) * 10) / 10.0)
                if (st.executeInsert() != -1L) n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun records(id: String, t0: Long? = null, t1: Long? = null): List<Row> {
        val q = StringBuilder("SELECT Time, Temp, Humi, DewPoint FROM Records WHERE ID=?")
        val args = mutableListOf(id)
        if (t0 != null) { q.append(" AND Time>=?"); args += t0.toString() }
        if (t1 != null) { q.append(" AND Time<=?"); args += t1.toString() }
        q.append(" ORDER BY Time")
        return readableDatabase.rawQuery(q.toString(), args.toTypedArray()).use { c ->
            val out = ArrayList<Row>(c.count)
            while (c.moveToNext()) out += Row(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3))
            out
        }
    }

    fun lastRecord(id: String): Row? = readableDatabase.rawQuery(
        "SELECT Time, Temp, Humi, DewPoint FROM Records WHERE ID=? ORDER BY Time DESC LIMIT 1", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) Row(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)) else null }

    /** (value, time) for max temp, min temp, max humidity, min humidity, or null without data. */
    fun maxMin(id: String, t0: Long, t1: Long): List<Pair<Double, Long>>? {
        val out = mutableListOf<Pair<Double, Long>>()
        for ((col, dir) in listOf("Temp" to "DESC", "Temp" to "ASC", "Humi" to "DESC", "Humi" to "ASC")) {
            readableDatabase.rawQuery(
                "SELECT $col, Time FROM Records WHERE ID=? AND Time BETWEEN ? AND ? ORDER BY $col $dir LIMIT 1",
                arrayOf(id, t0.toString(), t1.toString())
            ).use { c -> if (!c.moveToFirst()) return null; out += c.getDouble(0) to c.getLong(1) }
        }
        return out
    }

    fun deleteDevice(id: String) {
        writableDatabase.delete("Records", "ID=?", arrayOf(id))
        writableDatabase.delete("Device", "DevID=?", arrayOf(id))
    }

    fun deleteHistory(id: String) {
        writableDatabase.delete("Records", "ID=?", arrayOf(id))
        writableDatabase.execSQL("UPDATE Device SET DevLastAddr=NULL, DevLastRec=NULL WHERE DevID=?", arrayOf(id))
    }

    fun forgetPosition(id: String) =
        writableDatabase.execSQL("UPDATE Device SET DevLastAddr=NULL, DevLastRec=NULL WHERE DevID=?", arrayOf(id))
}

private fun android.database.Cursor.getStringOrNull(i: Int) = if (isNull(i)) null else getString(i)
private fun android.database.Cursor.getIntOrNull(i: Int) = if (isNull(i)) null else getInt(i)
private fun android.database.Cursor.getLongOrNull(i: Int) = if (isNull(i)) null else getLong(i)
private fun android.database.Cursor.getDoubleOrNull(i: Int) = if (isNull(i)) null else getDouble(i)
