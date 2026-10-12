package com.example.expensetracker

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hidden capture mode used to tune the screen patterns on a real phone.
 * State is kept in its own prefs file (not backed up through AppPreferences.exportAll()).
 */
object PaymentCapture {

    private const val PREFS = "payment_capture"
    private const val KEY_EXPIRY = "expiry_millis"
    private const val FILE_NAME = "capture_log.txt"
    private const val MAX_BYTES = 200 * 1024
    private const val AUTO_OFF_MS = 60 * 60 * 1000L

    private val lock = Any()
    private var lastBlockBody: String? = null

    fun isOn(ctx: Context): Boolean = try {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val exp = sp.getLong(KEY_EXPIRY, 0L)
        if (exp > 0L && System.currentTimeMillis() < exp) true
        else { if (exp != 0L) sp.edit().putLong(KEY_EXPIRY, 0L).apply(); false }
    } catch (t: Throwable) { false }

    fun setOn(ctx: Context, on: Boolean) {
        try {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_EXPIRY, if (on) System.currentTimeMillis() + AUTO_OFF_MS else 0L).apply()
        } catch (t: Throwable) { }
    }

    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    fun clear(ctx: Context) {
        synchronized(lock) {
            try { file(ctx).delete() } catch (t: Throwable) { }
            lastBlockBody = null
        }
    }

    fun size(ctx: Context): Long = try { file(ctx).length() } catch (t: Throwable) { 0L }

    /** Appends a one-line diagnostic note (e.g. "screen could not be read"), skipping repeats. */
    fun note(ctx: Context, packageName: String, message: String) {
        try {
            val line = "$packageName NOTE: $message"
            synchronized(lock) {
                if (line == lastBlockBody) return
                lastBlockBody = line
                val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
                val f = file(ctx)
                if (f.length() < MAX_BYTES) f.appendText(ts + "\n" + line + "\n---\n", Charsets.UTF_8)
            }
        } catch (t: Throwable) { }
    }

    /** Appends one block if [texts] contain a rupee sign and differ from the previous block. */
    fun append(ctx: Context, packageName: String, texts: List<String>, always: Boolean = false) {
        try {
            if (!always && texts.none { it.contains("₹") }) return
            val body = StringBuilder()
            body.append(packageName).append('\n')
            texts.forEachIndexed { i, t -> body.append(i).append(": ").append(t.replace('\n', ' ')).append('\n') }
            val bodyStr = body.toString()
            synchronized(lock) {
                if (bodyStr == lastBlockBody) return
                lastBlockBody = bodyStr
                val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
                val block = ts + "\n" + bodyStr + "---\n"
                val f = file(ctx)
                f.appendText(block, Charsets.UTF_8)
                if (f.length() > MAX_BYTES) {
                    val bytes = f.readBytes()
                    var start = bytes.size - MAX_BYTES
                    // drop the partial first block: start at the next block boundary
                    val s = String(bytes, start, bytes.size - start, Charsets.UTF_8)
                    val cut = s.indexOf("---\n")
                    val trimmed = if (cut >= 0) s.substring(cut + 4) else s
                    f.writeText(trimmed, Charsets.UTF_8)
                }
            }
        } catch (t: Throwable) { }
    }
}
