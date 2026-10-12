package com.example.expensetracker

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Automatic, silent backups.
 *
 *  - Phone copy: every night (~3 AM) into Documents/ExpenseTracker (survives uninstall).
 *    The last 7 files of THIS install are kept; files of any earlier install are never touched.
 *  - Drive copy: every morning (~7 AM) into the file the user picked once (persisted SAF uri).
 *    The upload waits for an internet connection (WorkManager network constraint) and retries
 *    by itself, so a missing connection never produces an error.
 *
 * Restore replaces all data with the contents of a backup file.
 */
object BackupManager {

    const val FORMAT_VERSION = 1
    const val FOLDER = "ExpenseTracker"
    const val KEEP_FILES = 7

    private const val TAG = "BackupManager"
    private const val WORK_PHONE = "daily_phone_backup"
    private const val WORK_DRIVE = "daily_drive_backup"
    private const val WORK_DRIVE_NOW = "drive_backup_now"
    private const val PHONE_HOUR = 3
    private const val DRIVE_HOUR = 7
    const val MAX_RETRIES_PER_RUN = 4            // then give up until the next daily run
    private const val FAILS_BEFORE_NOTICE = 3        // days in a row, non-network failures only
    private const val DAY_MS = 24L * 60 * 60 * 1000

    // ── Backup state (kept OUT of Android's own auto-backup, see xml/backup_rules) ──
    private const val STATE_PREFS = "backup_state"
    private const val K_INSTALL_ID = "install_id"
    private const val K_DRIVE_URI = "drive_uri"
    private const val K_DRIVE_ATTENTION = "drive_attention"
    private const val K_DRIVE_FAILS = "drive_fail_streak"
    private const val K_PROMPTS = "drive_prompts_shown"
    private const val K_LAST_PROMPT = "drive_last_prompt"
    private const val K_LEGACY_ASKED = "legacy_storage_asked"
    private const val K_LAST_PHONE = "last_phone_ok"
    private const val K_LAST_DRIVE = "last_drive_ok"

    private fun state(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    fun installId(ctx: Context): String {
        val p = state(ctx)
        p.getString(K_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(K_INSTALL_ID, id).apply()
        return id
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scheduling
    // ═══════════════════════════════════════════════════════════════════

    /** Milliseconds from now until the next [hour]:00 local time. */
    private fun delayUntilHour(hour: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    /** Safe to call on every app start: existing schedules are kept. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)

        val phone = PeriodicWorkRequest.Builder(PhoneBackupWorker::class.java, 24, TimeUnit.HOURS)
            .setInitialDelay(delayUntilHour(PHONE_HOUR), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PHONE, ExistingPeriodicWorkPolicy.KEEP, phone)

        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val drive = PeriodicWorkRequest.Builder(DriveBackupWorker::class.java, 24, TimeUnit.HOURS)
            .setInitialDelay(delayUntilHour(DRIVE_HOUR), TimeUnit.MILLISECONDS)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_DRIVE, ExistingPeriodicWorkPolicy.KEEP, drive)
    }

    /** One upload as soon as there is internet (used right after the Drive file is chosen). */
    fun enqueueDriveNow(ctx: Context) {
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val once = OneTimeWorkRequest.Builder(DriveBackupWorker::class.java)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork(WORK_DRIVE_NOW, ExistingWorkPolicy.REPLACE, once)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Backup file (JSON)
    // ═══════════════════════════════════════════════════════════════════

    fun buildJson(ctx: Context): String {
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appVersion", try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" })
        root.put("installId", installId(ctx))

        val expenses = JSONArray()
        ExpenseDbHelper(ctx).getExpenses().forEach { e ->
            expenses.put(JSONObject().apply {
                put("id", e.id)
                put("category", e.category)
                put("amount", if (e.amount.isNaN() || e.amount.isInfinite()) 0.0 else e.amount)
                put("source", e.source)
                put("channel", e.channel)
                put("timestamp", e.timestamp)
                put("event_id", e.eventId ?: JSONObject.NULL)
            })
        }
        root.put("expenses", expenses)

        val cats = JSONArray()
        CategoryDbHelper(ctx).getAllCategories().forEach { c ->
            cats.put(JSONObject().apply {
                put("id", c.id); put("name", c.name); put("icon_name", c.iconName)
                put("color_hex", c.colorHex); put("chart_color", c.chartColor)
                put("icon_tint", c.iconTint); put("is_active", c.isActive)
                put("sort_order", c.sortOrder)
            })
        }
        root.put("categories", cats)

        val evs = JSONArray()
        EventDbHelper(ctx).getAllEvents().forEach { ev ->
            evs.put(JSONObject().apply {
                put("id", ev.id); put("name", ev.name); put("color_hex", ev.colorHex)
                put("icon_tint", ev.iconTint); put("is_active", ev.isActive)
                put("sort_order", ev.sortOrder)
            })
        }
        root.put("events", evs)

        val lims = JSONArray()
        LimitManager.all(ctx).forEach { l ->
            lims.put(JSONObject().apply {
                put("id", l.id); put("kind", l.kind)
                put("category_id", l.categoryId ?: JSONObject.NULL)
                put("event_id", l.eventId ?: JSONObject.NULL)
                put("period", l.period); put("amount", l.amount)
                put("start_day", l.startDay); put("end_day", l.endDay)
                put("exclude_events", l.excludeEvents)
            })
        }
        root.put("limits", lims)

        val prefs = JSONObject()
        AppPreferences.exportAll(ctx).forEach { (k, v) ->
            val entry = JSONObject()
            when (v) {
                is Boolean -> { entry.put("t", "b"); entry.put("v", v) }
                is Int     -> { entry.put("t", "i"); entry.put("v", v) }
                is Long    -> { entry.put("t", "l"); entry.put("v", v) }
                is Float   -> { entry.put("t", "f"); entry.put("v", v.toDouble()) }
                is String  -> { entry.put("t", "s"); entry.put("v", v) }
                else       -> return@forEach
            }
            prefs.put(k, entry)
        }
        root.put("prefs", prefs)
        return root.toString()
    }

    /**
     * Same as [buildJson] but returns null when the transactions read from the database don't
     * match the stored count (a read error): such a file must never replace a good backup.
     */
    private fun buildJsonChecked(ctx: Context): String? {
        val json = buildJson(ctx)
        val written = JSONObject(json).getJSONArray("expenses").length()
        return if (written == ExpenseDbHelper(ctx).countAll()) json else null
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phone copy (Documents/ExpenseTracker)
    // ═══════════════════════════════════════════════════════════════════

    private fun filePrefix(ctx: Context) = "ExpenseTracker_${installId(ctx).take(8)}_"

    private fun todayFileName(ctx: Context): String =
        filePrefix(ctx) + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    /** Returns true when a file was written. Nothing is written while there are no transactions. */
    fun backupToPhone(ctx: Context): Boolean {
        return try {
            if (ExpenseDbHelper(ctx).countAll() == 0) return false
            val bytes = (buildJsonChecked(ctx) ?: return false).toByteArray(Charsets.UTF_8)
            val name = todayFileName(ctx)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writePhoneQ(ctx, name, bytes)
            else writePhoneLegacy(ctx, name, bytes)
            if (ok) state(ctx).edit().putLong(K_LAST_PHONE, System.currentTimeMillis()).apply()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Phone backup failed", e)
            false
        }
    }

    private fun writePhoneQ(ctx: Context, name: String, bytes: ByteArray): Boolean {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER + "/"

        var found: Uri? = null
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
            arrayOf(name, relPath), null
        )?.use { c -> if (c.moveToFirst()) found = ContentUris.withAppendedId(collection, c.getLong(0)) }

        fun insertNew(): Uri? = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        })

        var target = found ?: insertNew() ?: return false
        try {
            (resolver.openOutputStream(target, "wt") ?: return false).use { it.write(bytes) }
        } catch (e: java.io.FileNotFoundException) {
            // stale database row whose file is gone: drop it and create the file again
            if (found == null) throw e
            try { resolver.delete(target, null, null) } catch (_: Exception) {}
            target = insertNew() ?: return false
            (resolver.openOutputStream(target, "wt") ?: return false).use { it.write(bytes) }
        }

        // keep only the newest KEEP_FILES files of THIS install (names sort by date)
        val own = mutableListOf<Pair<String, Long>>()
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
            arrayOf(filePrefix(ctx) + "%.json", relPath), null
        )?.use { c ->
            while (c.moveToNext()) own.add(Pair(c.getString(1) ?: "", c.getLong(0)))
        }
        own.sortedByDescending { it.first }.drop(KEEP_FILES).forEach { (_, id) ->
            try { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
            catch (e: Exception) { Log.w(TAG, "Could not prune old backup", e) }
        }
        return true
    }

    /** Android 9 and below: plain file access (needs the storage permission). */
    @Suppress("DEPRECATION")
    private fun writePhoneLegacy(ctx: Context, name: String, bytes: ByteArray): Boolean {
        if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return false
        File(dir, name).writeBytes(bytes)
        val prefix = filePrefix(ctx)
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }
            .sortedByDescending { it.name }
            .drop(KEEP_FILES)
            .forEach { it.delete() }
        return true
    }

    fun legacyStoragePermissionAsked(ctx: Context): Boolean = state(ctx).getBoolean(K_LEGACY_ASKED, false)
    fun markLegacyStoragePermissionAsked(ctx: Context) = state(ctx).edit().putBoolean(K_LEGACY_ASKED, true).apply()

    // ═══════════════════════════════════════════════════════════════════
    // Google Drive copy (one persisted SAF file)
    // ═══════════════════════════════════════════════════════════════════

    enum class DriveOutcome { OK, NOT_SET, RETRY, BROKEN }

    fun lastPhoneBackup(ctx: Context): Long = state(ctx).getLong(K_LAST_PHONE, 0L)
    fun lastDriveBackup(ctx: Context): Long = state(ctx).getLong(K_LAST_DRIVE, 0L)
    fun driveNeedsAttention(ctx: Context): Boolean = state(ctx).getBoolean(K_DRIVE_ATTENTION, false)

    /** "Back up now" from Settings: phone copy plus Drive file (if set up). Blocking: call off the main thread. */
    fun backupNow(ctx: Context): Pair<Boolean, DriveOutcome> {
        val phone = backupToPhone(ctx)
        val drive = backupToDrive(ctx)
        return Pair(phone, drive)
    }

    fun hasDriveTarget(ctx: Context): Boolean = state(ctx).getString(K_DRIVE_URI, null) != null

    /** Remembers the file chosen by the user. Returns false when the permission could not be kept. */
    fun setDriveTarget(ctx: Context, uri: Uri): Boolean {
        return try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            state(ctx).edit()
                .putString(K_DRIVE_URI, uri.toString())
                .putBoolean(K_DRIVE_ATTENTION, false)
                .putInt(K_DRIVE_FAILS, 0)
                .apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not keep access to the Drive file", e)
            false
        }
    }

    fun backupToDrive(ctx: Context): DriveOutcome {
        val uriStr = state(ctx).getString(K_DRIVE_URI, null) ?: return DriveOutcome.NOT_SET
        // Never overwrite a backup with an empty install
        if (ExpenseDbHelper(ctx).countAll() == 0) return DriveOutcome.OK
        return try {
            // build the whole file BEFORE opening the target: "wt" truncates it immediately
            val bytes = (buildJsonChecked(ctx) ?: return DriveOutcome.RETRY).toByteArray(Charsets.UTF_8)
            val out = ctx.contentResolver.openOutputStream(Uri.parse(uriStr), "wt")
                ?: return DriveOutcome.RETRY
            out.use { it.write(bytes) }
            state(ctx).edit().putInt(K_DRIVE_FAILS, 0).putBoolean(K_DRIVE_ATTENTION, false)
                .putLong(K_LAST_DRIVE, System.currentTimeMillis()).apply()
            DriveOutcome.OK
        } catch (e: SecurityException) {
            // permission to the file is gone: only the user can fix this
            state(ctx).edit().putBoolean(K_DRIVE_ATTENTION, true).apply()
            DriveOutcome.BROKEN
        } catch (e: IOException) {
            DriveOutcome.RETRY
        } catch (e: Exception) {
            Log.e(TAG, "Drive backup failed", e)
            DriveOutcome.RETRY
        }
    }

    /** Retries for today are used up (not a connection problem: the work only runs online). */
    fun noteDriveGaveUp(ctx: Context) {
        val p = state(ctx)
        val streak = p.getInt(K_DRIVE_FAILS, 0) + 1
        p.edit().putInt(K_DRIVE_FAILS, streak)
            .putBoolean(K_DRIVE_ATTENTION, streak >= FAILS_BEFORE_NOTICE).apply()
    }

    /** 0 = nothing to show, 1 = ask the user to choose the Drive file, 2 = Drive backup stopped. */
    fun drivePromptKind(ctx: Context): Int {
        val p = state(ctx)
        val now = System.currentTimeMillis()
        val last = p.getLong(K_LAST_PROMPT, 0L)
        return if (p.getString(K_DRIVE_URI, null) == null) {
            if (p.getInt(K_PROMPTS, 0) < 3 && now - last >= 3 * DAY_MS) 1 else 0
        } else {
            if (p.getBoolean(K_DRIVE_ATTENTION, false) && now - last >= DAY_MS) 2 else 0
        }
    }

    fun notePromptShown(ctx: Context, kind: Int) {
        val p = state(ctx)
        val e = p.edit().putLong(K_LAST_PROMPT, System.currentTimeMillis())
        if (kind == 1) e.putInt(K_PROMPTS, p.getInt(K_PROMPTS, 0) + 1)
        e.apply()
    }

    /**
     * After a restore from a Drive file, that same file becomes the Drive backup target
     * (only when the picker granted write access to it).
     */
    fun adoptRestoredDriveFile(ctx: Context, uri: Uri) {
        val authority = uri.authority ?: return
        if (!authority.startsWith("com.google.android.apps.docs")) return
        if (setDriveTarget(ctx, uri)) enqueueDriveNow(ctx)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Restore
    // ═══════════════════════════════════════════════════════════════════

    class RestoreResult(val ok: Boolean, val message: String = "")

    private const val NOT_A_BACKUP = "This file isn't an Expense Tracker backup."

    /** Replaces ALL data with the contents of the backup. Nothing is changed unless the whole file is valid. */
    fun restoreFromJson(ctx: Context, text: String): RestoreResult {
        val root = try { JSONObject(text) } catch (e: Exception) { return RestoreResult(false, NOT_A_BACKUP) }
        val version = root.optInt("version", -1)
        if (version < 1 || !root.has("expenses")) return RestoreResult(false, NOT_A_BACKUP)
        if (version > FORMAT_VERSION) {
            return RestoreResult(false, "This backup was made by a newer version of the app. Please update the app and try again.")
        }

        val expenses: List<Expense>
        val categories: List<Category>
        val events: List<Event>
        val limits: List<Limit>
        val prefs = mutableMapOf<String, Any?>()
        try {
            val ea = root.getJSONArray("expenses")
            expenses = (0 until ea.length()).map { i ->
                val o = ea.getJSONObject(i)
                Expense(
                    id = o.optString("id", ""),
                    category = o.getString("category"),
                    amount = o.optDouble("amount", 0.0),
                    source = o.optString("source", ""),
                    channel = o.optString("channel", ""),
                    timestamp = o.getLong("timestamp"),
                    eventId = if (o.isNull("event_id")) null else o.getString("event_id")
                )
            }
            val ca = root.optJSONArray("categories") ?: JSONArray()
            categories = (0 until ca.length()).map { i ->
                val o = ca.getJSONObject(i)
                Category(
                    id = o.getString("id"), name = o.getString("name"),
                    iconName = o.optString("icon_name", ""), colorHex = o.optString("color_hex", "#E2E8F0"),
                    chartColor = o.optString("chart_color", ""), iconTint = o.optString("icon_tint", ""),
                    isActive = o.optBoolean("is_active", false), sortOrder = o.optInt("sort_order", 0)
                )
            }
            val va = root.optJSONArray("events") ?: JSONArray()
            events = (0 until va.length()).map { i ->
                val o = va.getJSONObject(i)
                Event(
                    id = o.getString("id"), name = o.getString("name"),
                    colorHex = o.optString("color_hex", "#E2E8F0"), iconTint = o.optString("icon_tint", "#666666"),
                    isActive = o.optBoolean("is_active", true), sortOrder = o.optInt("sort_order", 0)
                )
            }
            val la = root.optJSONArray("limits") ?: JSONArray()
            limits = (0 until la.length()).map { i ->
                val o = la.getJSONObject(i)
                Limit(
                    id = o.getString("id"), kind = o.getString("kind"),
                    categoryId = if (o.isNull("category_id")) null else o.getString("category_id"),
                    eventId = if (o.isNull("event_id")) null else o.getString("event_id"),
                    period = o.optString("period", "monthly"), amount = o.optDouble("amount", 0.0),
                    startDay = o.optLong("start_day", 0L), endDay = o.optLong("end_day", 0L),
                    excludeEvents = o.optBoolean("exclude_events", false)
                )
            }
            val po = root.optJSONObject("prefs")
            if (po != null) {
                val keys = po.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val entry = po.getJSONObject(k)
                    when (entry.getString("t")) {
                        "b" -> prefs[k] = entry.getBoolean("v")
                        "i" -> prefs[k] = entry.getInt("v")
                        "l" -> prefs[k] = entry.getLong("v")
                        "f" -> prefs[k] = entry.getDouble("v").toFloat()
                        "s" -> prefs[k] = entry.getString("v")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backup file could not be read", e)
            return RestoreResult(false, "This backup file looks damaged and couldn't be read.")
        }

        return try {
            ExpenseDbHelper(ctx).replaceAll(expenses)
            if (categories.isNotEmpty()) CategoryDbHelper(ctx).replaceAll(categories)
            EventDbHelper(ctx).replaceAll(events)
            if (root.has("limits")) {      // backups from before limits existed keep the current limits
                LimitDbHelper(ctx).replaceAll(limits)
                try { ctx.getSharedPreferences("limit_alert_state", Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
            }
            AppPreferences.importAll(ctx, prefs)
            try { CategoryManager.initialize(ctx) } catch (e: Exception) { e.printStackTrace() }
            try { EventManager.initialize(ctx) } catch (e: Exception) { e.printStackTrace() }
            RestoreResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            RestoreResult(false, "Restore didn't finish. Please try again.")
        }
    }
}
