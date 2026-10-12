package com.example.expensetracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/**
 * Spending limits.
 *  - KIND_CATEGORY : a category limit            (categoryId set)
 *  - KIND_EVENT    : an event limit              (eventId set)
 *  - KIND_EVENT_CAT: a category limit inside an event (both set)
 * Periods reset at 6 AM (not midnight) following the phone's clock.
 */
data class Limit(
    val id           : String,
    val kind         : String,
    val categoryId   : String?,
    val eventId      : String?,
    val period       : String,          // daily | weekly | monthly | yearly | custom
    val amount       : Double,
    val startDay     : Long = 0L,       // custom only: midnight of the first day
    val endDay       : Long = 0L,       // custom only: midnight of the last day
    val excludeEvents: Boolean = false  // category limits only
)

class LimitDbHelper(context: Context) : SQLiteOpenHelper(context, "limits.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS limits (
                id             TEXT PRIMARY KEY,
                kind           TEXT NOT NULL,
                category_id    TEXT,
                event_id       TEXT,
                period         TEXT NOT NULL,
                amount         REAL NOT NULL,
                start_day      INTEGER NOT NULL DEFAULT 0,
                end_day        INTEGER NOT NULL DEFAULT 0,
                exclude_events INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { /* no prior versions */ }

    private fun values(l: Limit) = ContentValues().apply {
        put("id", l.id); put("kind", l.kind)
        if (l.categoryId == null) putNull("category_id") else put("category_id", l.categoryId)
        if (l.eventId == null) putNull("event_id") else put("event_id", l.eventId)
        put("period", l.period); put("amount", l.amount)
        put("start_day", l.startDay); put("end_day", l.endDay)
        put("exclude_events", if (l.excludeEvents) 1 else 0)
    }

    fun insert(l: Limit) {
        writableDatabase.insertWithOnConflict("limits", null, values(l), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAll(): List<Limit> {
        val list = mutableListOf<Limit>()
        try {
            readableDatabase.query("limits", null, null, null, null, null, null).use {
                while (it.moveToNext()) {
                    list.add(Limit(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        kind = it.getString(it.getColumnIndexOrThrow("kind")),
                        categoryId = it.getString(it.getColumnIndexOrThrow("category_id")),
                        eventId = it.getString(it.getColumnIndexOrThrow("event_id")),
                        period = it.getString(it.getColumnIndexOrThrow("period")),
                        amount = it.getDouble(it.getColumnIndexOrThrow("amount")),
                        startDay = it.getLong(it.getColumnIndexOrThrow("start_day")),
                        endDay = it.getLong(it.getColumnIndexOrThrow("end_day")),
                        excludeEvents = it.getInt(it.getColumnIndexOrThrow("exclude_events")) == 1
                    ))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    fun delete(id: String) {
        try { writableDatabase.delete("limits", "id = ?", arrayOf(id)) } catch (e: Exception) { e.printStackTrace() }
    }

    fun deleteForCategory(categoryId: String) {
        try { writableDatabase.delete("limits", "category_id = ?", arrayOf(categoryId)) } catch (e: Exception) { e.printStackTrace() }
    }

    fun deleteForEvent(eventId: String) {
        try { writableDatabase.delete("limits", "event_id = ?", arrayOf(eventId)) } catch (e: Exception) { e.printStackTrace() }
    }

    /** Backup restore: replaces every limit with [list] in one transaction. */
    fun replaceAll(list: List<Limit>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("limits", null, null)
            list.forEach { db.insertWithOnConflict("limits", null, values(it), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

/** A limit together with how much has been spent in its current period. */
data class LimitStatus(
    val limit : Limit,
    val spent : Double,
    val start : Long,
    val end   : Long
) {
    val pct: Double get() = if (limit.amount <= 0) 0.0 else spent * 100.0 / limit.amount
    val left: Double get() = limit.amount - spent
    /** 0 = fine, 80 / 90 / 100 = threshold reached. */
    val level: Int get() = when {
        pct >= 100.0 -> 100
        pct >= 90.0  -> 90
        pct >= 80.0  -> 80
        else         -> 0
    }
    /** 0 green, 1 amber, 2 red */
    val tone: Int get() = when { pct >= 100.0 -> 2; pct >= 80.0 -> 1; else -> 0 }
}

object LimitManager {

    const val KIND_CATEGORY = "category"
    const val KIND_EVENT = "event"
    const val KIND_EVENT_CAT = "eventcat"

    val PERIODS = listOf("daily", "weekly", "monthly", "yearly", "custom")
    fun periodTitle(p: String) = when (p) {
        "daily" -> "Daily"; "weekly" -> "Weekly"; "monthly" -> "Monthly"
        "yearly" -> "Yearly"; else -> "Custom"
    }

    const val GREEN = "#16A34A"
    const val AMBER = "#D97706"
    const val RED = "#DC2626"
    fun toneColor(tone: Int) = when (tone) { 2 -> RED; 1 -> AMBER; else -> GREEN }

    private const val SIX_AM = 6

    // ── Periods ─────────────────────────────────────────────────────────────

    /** [start, end] (both inclusive, millis) of the current daily/weekly/monthly/yearly period. */
    fun periodRange(period: String, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        if (cal.get(Calendar.HOUR_OF_DAY) < SIX_AM) cal.add(Calendar.DAY_OF_YEAR, -1)   // before 6 AM still belongs to the previous day
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        when (period) {
            "weekly" -> {
                val diff = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Monday = 0
                cal.add(Calendar.DAY_OF_YEAR, -diff)
            }
            "monthly" -> cal.set(Calendar.DAY_OF_MONTH, 1)
            "yearly"  -> cal.set(Calendar.DAY_OF_YEAR, 1)
        }
        val startCal = cal.clone() as Calendar
        startCal.set(Calendar.HOUR_OF_DAY, SIX_AM)
        val endCal = cal.clone() as Calendar
        when (period) {
            "weekly"  -> endCal.add(Calendar.DAY_OF_YEAR, 7)
            "monthly" -> endCal.add(Calendar.MONTH, 1)
            "yearly"  -> endCal.add(Calendar.YEAR, 1)
            else      -> endCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        endCal.set(Calendar.HOUR_OF_DAY, SIX_AM)
        return startCal.timeInMillis to (endCal.timeInMillis - 1)
    }

    private fun customRange(l: Limit): Pair<Long, Long> {
        val s = Calendar.getInstance().apply {
            timeInMillis = l.startDay; set(Calendar.HOUR_OF_DAY, SIX_AM)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val e = Calendar.getInstance().apply {
            timeInMillis = l.endDay; add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, SIX_AM)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return s.timeInMillis to (e.timeInMillis - 1)
    }

    private fun rangeOf(l: Limit, all: List<Limit>, now: Long): Pair<Long, Long> {
        if (l.kind == KIND_EVENT_CAT) {
            // Covers the whole event; if the event's own limit is Custom, only that range.
            val ev = all.firstOrNull { it.kind == KIND_EVENT && it.eventId == l.eventId }
            return if (ev != null && ev.period == "custom") customRange(ev) else (0L to Long.MAX_VALUE)
        }
        return if (l.period == "custom") customRange(l) else periodRange(l.period, now)
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    fun all(ctx: Context): List<Limit> {
        val h = LimitDbHelper(ctx)
        return try { h.getAll() } finally { h.close() }
    }

    fun categoryLimit(all: List<Limit>, categoryId: String) =
        all.firstOrNull { it.kind == KIND_CATEGORY && it.categoryId == categoryId }

    fun eventLimit(all: List<Limit>, eventId: String) =
        all.firstOrNull { it.kind == KIND_EVENT && it.eventId == eventId }

    fun eventCategoryLimits(all: List<Limit>, eventId: String) =
        all.filter { it.kind == KIND_EVENT_CAT && it.eventId == eventId }

    fun eventCategoryLimit(all: List<Limit>, eventId: String, categoryId: String) =
        all.firstOrNull { it.kind == KIND_EVENT_CAT && it.eventId == eventId && it.categoryId == categoryId }

    /** Saves a limit, replacing any existing limit for the same target. */
    fun save(ctx: Context, l: Limit) {
        val h = LimitDbHelper(ctx)
        try {
            all(ctx).filter { it.kind == l.kind && it.categoryId == l.categoryId && it.eventId == l.eventId && it.id != l.id }
                .forEach { h.delete(it.id) }
            h.insert(l)
        } finally { h.close() }
        LimitAlerts.forget(ctx, l.id)      // a changed limit starts its alert steps afresh
    }

    fun remove(ctx: Context, id: String) {
        val h = LimitDbHelper(ctx)
        try { h.delete(id) } finally { h.close() }
        LimitAlerts.forget(ctx, id)
    }

    fun removeForCategory(ctx: Context, categoryId: String) {
        val ids = all(ctx).filter { it.categoryId == categoryId }.map { it.id }
        val h = LimitDbHelper(ctx)
        try { h.deleteForCategory(categoryId) } finally { h.close() }
        ids.forEach { LimitAlerts.forget(ctx, it) }
    }

    fun removeForEvent(ctx: Context, eventId: String) {
        val ids = all(ctx).filter { it.eventId == eventId }.map { it.id }
        val h = LimitDbHelper(ctx)
        try { h.deleteForEvent(eventId) } finally { h.close() }
        ids.forEach { LimitAlerts.forget(ctx, it) }
    }

    fun newId(): String = "limit_" + java.util.UUID.randomUUID().toString()

    // ── Status ──────────────────────────────────────────────────────────────

    private fun categoryName(categoryId: String?): String? =
        if (categoryId == null) null else CategoryManager.allCategories.firstOrNull { it.id == categoryId }?.name

    fun statusOf(expenses: ExpenseDbHelper, l: Limit, all: List<Limit>, now: Long = System.currentTimeMillis()): LimitStatus {
        val (from, to) = rangeOf(l, all, now)
        val spent = when (l.kind) {
            KIND_CATEGORY -> {
                val name = categoryName(l.categoryId)
                if (name == null) 0.0 else expenses.sumSpent(from, to, name, null, l.excludeEvents)
            }
            KIND_EVENT -> expenses.sumSpent(from, to, null, l.eventId, false)
            else -> {
                val name = categoryName(l.categoryId)
                if (name == null) 0.0 else expenses.sumSpent(from, to, name, l.eventId, false)
            }
        }
        return LimitStatus(l, spent, from, to)
    }

    /** Status of every limit, keyed by limit id. */
    fun allStatuses(ctx: Context): Map<String, LimitStatus> {
        val limits = all(ctx)
        if (limits.isEmpty()) return emptyMap()
        val exp = ExpenseDbHelper(ctx)
        return try {
            val now = System.currentTimeMillis()
            limits.associate { it.id to statusOf(exp, it, limits, now) }
        } finally { exp.close() }
    }

    // ── Text ────────────────────────────────────────────────────────────────

    fun money(ctx: Context, v: Double): String {
        val sym = try { AppPreferences.getCurrencySymbol(ctx) } catch (_: Exception) { "₹" }
        val r = Math.round(Math.abs(v))
        val digits = r.toString()
        val grouped = if (sym == "₹") indianGroup(digits) else westernGroup(digits)
        return (if (v < 0 && r > 0) "-" else "") + sym + grouped
    }

    private fun westernGroup(s: String): String {
        val sb = StringBuilder()
        s.forEachIndexed { i, c ->
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun indianGroup(s: String): String {
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val parts = mutableListOf<String>()
        while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }

    /** "₹1,600 left" or "₹600 over". */
    fun leftText(ctx: Context, st: LimitStatus): String =
        if (st.left >= 0) "${money(ctx, st.left)} left" else "${money(ctx, -st.left)} over"

    fun periodLabel(l: Limit): String = when (l.kind) {
        KIND_EVENT_CAT -> "event"
        else -> if (l.period == "custom") "custom" else l.period
    }
}

/** One section of the popup alert. */
data class LimitAlertItem(
    val title   : String,
    val sub     : String,
    val isEvent : Boolean,
    val iconName: String,   // name used to pick the icon / letter
    val bgHex   : String,
    val tintHex : String,
    val status  : LimitStatus
)

/** Decides which limits should alert after an expense is saved (80% / 90% / 100% / next 3). */
object LimitAlerts {

    private const val PREFS = "limit_alert_state"
    private const val EXTRA_AFTER_FULL = 3

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun forget(ctx: Context, limitId: String) {
        try { prefs(ctx).edit().remove("lim_$limitId").apply() } catch (_: Exception) {}
    }

    /** Returns up to two alert sections (worst first); empty if nothing needs attention. */
    fun evaluate(ctx: Context, categoryName: String, eventId: String?): List<LimitAlertItem> {
        val limits = LimitManager.all(ctx)
        if (limits.isEmpty()) return emptyList()
        val cat = CategoryManager.allCategories.firstOrNull { it.name.equals(categoryName, ignoreCase = true) }
        val event = EventManager.getEventById(eventId)

        val candidates = mutableListOf<Pair<Limit, Boolean>>()   // limit, isEventCategory
        if (cat != null && eventId != null) {
            LimitManager.eventCategoryLimit(limits, eventId, cat.id)?.let { candidates.add(it to true) }
        }
        if (eventId != null) LimitManager.eventLimit(limits, eventId)?.let { candidates.add(it to false) }
        if (cat != null && candidates.none { it.second }) {
            val cl = LimitManager.categoryLimit(limits, cat.id)
            // A category limit that ignores event spending is not affected by an event expense.
            if (cl != null && !(cl.excludeEvents && eventId != null)) candidates.add(cl to false)
        }
        if (candidates.isEmpty()) return emptyList()

        val exp = ExpenseDbHelper(ctx)
        val items = mutableListOf<LimitAlertItem>()
        try {
            val now = System.currentTimeMillis()
            for ((l, isEC) in candidates) {
                val st = LimitManager.statusOf(exp, l, limits, now)
                if (now < st.start || now > st.end) continue          // custom range not active
                if (!shouldAlert(ctx, st)) continue
                items.add(when {
                    isEC && cat != null && event != null -> LimitAlertItem(
                        "${event.name} › ${cat.name}", "event", false,
                        cat.name, cat.colorHex, cat.iconTint.ifEmpty { "#666666" }, st)
                    l.kind == LimitManager.KIND_EVENT && event != null -> LimitAlertItem(
                        event.name, "event", true, event.name, event.colorHex, event.iconTint, st)
                    cat != null -> LimitAlertItem(
                        cat.name, LimitManager.periodLabel(l), false,
                        cat.name, cat.colorHex, cat.iconTint.ifEmpty { "#666666" }, st)
                    else -> continue
                })
            }
        } finally { exp.close() }
        return items.sortedByDescending { it.status.pct }.take(2)
    }

    /** Cadence per limit and period: 80%, 90%, 100%, then the next 3 transactions, then silence. */
    private fun shouldAlert(ctx: Context, st: LimitStatus): Boolean {
        val key = "lim_${st.limit.id}"
        val p = prefs(ctx)
        var lastLevel = 0
        var post = 0
        val raw = p.getString(key, null)
        if (raw != null) {
            val parts = raw.split("|")
            val sameP = parts.getOrNull(0)?.toLongOrNull() == st.start
            if (sameP) {
                lastLevel = parts.getOrNull(1)?.toIntOrNull() ?: 0
                post = parts.getOrNull(2)?.toIntOrNull() ?: 0
            }
        }
        val level = st.level
        var alert = false
        when {
            level < lastLevel -> { lastLevel = level; post = 0 }          // fell back: re-arm
            level == 0 -> { lastLevel = 0; post = 0 }
            level > lastLevel -> { lastLevel = level; post = 0; alert = true }
            level == 100 && post < EXTRA_AFTER_FULL -> { post++; alert = true }
        }
        try { p.edit().putString(key, "${st.start}|$lastLevel|$post").apply() } catch (_: Exception) {}
        return alert
    }
}
