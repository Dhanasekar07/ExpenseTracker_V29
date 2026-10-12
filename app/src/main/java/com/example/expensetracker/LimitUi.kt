package com.example.expensetracker

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** All the limit-related views: row bars/panels, dialog sections, search dialog and the popup alert. */
object LimitUi {

    // ── small helpers ───────────────────────────────────────────────────────

    private fun dpf(ctx: Context) = ctx.resources.displayMetrics.density
    private fun px(ctx: Context, v: Float) = (v * dpf(ctx)).toInt()

    private fun col(hex: String, fallback: Int = Color.parseColor("#E5E7EB")): Int =
        try { Color.parseColor(hex) } catch (e: Exception) { fallback }

    private fun rounded(ctx: Context, hex: String, radiusDp: Float, strokeHex: String? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * dpf(ctx)
            setColor(col(hex))
            if (strokeHex != null) setStroke(maxOf(1, (1.2f * dpf(ctx)).toInt()), col(strokeHex))
        }

    private fun barColor(tone: Int) = when (tone) { 2 -> "#EF4444"; 1 -> "#F59E0B"; else -> "#2D6A4F" }
    private fun textColor(tone: Int) = when (tone) { 2 -> "#B91C1C"; 1 -> "#B45309"; else -> "#166534" }
    private fun chipBg(tone: Int) = when (tone) { 2 -> "#FEE2E2"; 1 -> "#FEF3C7"; else -> "#DCFCE7" }

    private fun tv(ctx: Context, text: String, sp: Float, hex: String, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = sp
            setTextColor(Color.parseColor(hex))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** Progress bar: grey track + coloured fill. */
    fun bar(ctx: Context, pct: Double, tone: Int, heightDp: Float, trackHex: String = "#E5E7EB"): View {
        val p = when {
            pct.isNaN() -> 0
            pct >= 100.0 -> 100
            pct > 0.0 -> maxOf(2, pct.toInt())
            else -> 0
        }
        val h = maxOf(1, px(ctx, heightDp))
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
            background = rounded(ctx, trackHex, heightDp / 2f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        }
        if (p > 0) {
            track.addView(View(ctx).apply {
                background = rounded(ctx, barColor(tone), heightDp / 2f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, p.toFloat())
            })
        }
        return track
    }

    fun iconBox(ctx: Context, name: String, bgHex: String, tintHex: String, sizeDp: Int, iconDp: Int, radiusDp: Float = 8f): FrameLayout {
        val box = FrameLayout(ctx)
        box.layoutParams = LinearLayout.LayoutParams(px(ctx, sizeDp.toFloat()), px(ctx, sizeDp.toFloat()))
        CategoryIcon.applyIcon(box, name, bgHex, tintHex.ifEmpty { "#666666" }, iconDp)
        box.background = rounded(ctx, bgHex, radiusDp)
        return box
    }

    /** Wraps a dialog card so tall content (limit sections, keyboard) can scroll. */
    fun scrollWrap(ctx: Context, card: View): ScrollView =
        ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(card)
        }

    private val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    private val dateFmtYear = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    fun periodChipText(l: Limit): String =
        if (l.period == "custom") "Custom · ${dateFmt.format(l.startDay)} – ${dateFmt.format(l.endDay)}"
        else LimitManager.periodTitle(l.period)

    // ── Row: thin bar ───────────────────────────────────────────────────────

    /** Fills [holder] (an empty FrameLayout at the bottom of a row) with the thin bar, or hides it. */
    fun bindThinBar(holder: FrameLayout, st: LimitStatus?, visible: Boolean, onTintedRow: Boolean = false) {
        holder.removeAllViews()
        if (st == null || !visible) { holder.visibility = View.GONE; return }
        holder.visibility = View.VISIBLE
        holder.addView(bar(holder.context, st.pct, st.tone, 4f, trackFor(onTintedRow)))
    }

    /** Empty part of the bar: white on the green highlighted rows (grey would blend in), light grey elsewhere. */
    private fun trackFor(onTintedRow: Boolean) = if (onTintedRow) "#FFFFFF" else "#E5E7EB"

    // ── Row: expanded panel (design C) ──────────────────────────────────────

    /**
     * Fills [panel] with the expanded view. [st] is this row's own limit (null for an event that only
     * has category limits); [catStatuses] are the event's category limits (empty for category rows).
     */
    fun bindPanel(
        panel: LinearLayout,
        st: LimitStatus?,
        catStatuses: List<LimitStatus>,
        catListOpen: Boolean,
        onToggleCatList: () -> Unit,
        onTintedRow: Boolean = false     // row has the green highlight: chips need a white background to stand out
    ) {
        val ctx = panel.context
        panel.isClickable = true      // taps inside the open panel must not collapse the row
        panel.removeAllViews()
        if (st != null) {
            val chips = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            chips.addView(tv(ctx, LimitManager.leftText(ctx, st), 12f, textColor(st.tone), true).apply {
                background = rounded(ctx, if (onTintedRow) "#FFFFFF" else chipBg(st.tone), 10f)
                setPadding(px(ctx, 9f), px(ctx, 3f), px(ctx, 9f), px(ctx, 3f))
            })
            chips.addView(tv(ctx, periodChipText(st.limit), 11f, "#374151").apply {
                background = rounded(ctx, if (onTintedRow) "#FFFFFF" else "#F3F4F6", 10f)
                setPadding(px(ctx, 8f), px(ctx, 3f), px(ctx, 8f), px(ctx, 3f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = px(ctx, 6f) }
            })
            chips.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            chips.addView(tv(ctx, "${Math.round(st.pct)}%", 12f, textColor(st.tone), true))
            panel.addView(chips)

            panel.addView(bar(ctx, st.pct, st.tone, 8f, trackFor(onTintedRow)).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = px(ctx, 8f)
            })

            val nums = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(ctx, 5f) }
            }
            nums.addView(tv(ctx, "${LimitManager.money(ctx, st.spent)} spent", 12f, "#374151").apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            nums.addView(tv(ctx, "${LimitManager.money(ctx, st.limit.amount)} limit", 12f, "#374151").apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            panel.addView(nums)
        }

        if (catStatuses.isNotEmpty()) {
            val sorted = catStatuses.sortedByDescending { it.pct }
            panel.addView(catLimitsButton(ctx, sorted, catListOpen, onToggleCatList).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = px(ctx, if (st != null) 10f else 0f)
            })
            if (catListOpen) panel.addView(catLimitsList(ctx, sorted))
        }
    }

    private fun catLimitsButton(ctx: Context, sorted: List<LimitStatus>, open: Boolean, onClick: () -> Unit): View {
        val btn = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ctx, "#FFFFFF", 10f, "#D1D5DB")
            setPadding(px(ctx, 10f), px(ctx, 7f), px(ctx, 10f), px(ctx, 7f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
        btn.addView(tv(ctx, "Category limits · ${sorted.size}", 12f, "#374151", true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        sorted.take(6).forEach { s ->
            btn.addView(View(ctx).apply {
                val sz = px(ctx, 8f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = px(ctx, 4f) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(barColor(s.tone)))
                }
            })
        }
        btn.addView(tv(ctx, if (open) "▴" else "▾", 12f, "#6B7280", true).apply {
            setPadding(px(ctx, 4f), 0, 0, 0)
        })
        return btn
    }

    private fun catLimitsList(ctx: Context, sorted: List<LimitStatus>): View {
        val rowH = px(ctx, 44f)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        sorted.forEach { s ->
            val cat = CategoryManager.allCategories.firstOrNull { it.id == s.limit.categoryId }
            val name = cat?.name ?: "Category"
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH)
            }
            row.addView(iconBox(ctx, name, cat?.colorHex ?: "#E5E7EB", cat?.iconTint ?: "#666666", 26, 14).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = px(ctx, 10f)
            })
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(tv(ctx, name, 13f, "#111111").apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(tv(ctx, LimitManager.leftText(ctx, s), 12f, textColor(s.tone), true))
            col.addView(top)
            col.addView(bar(ctx, s.pct, s.tone, 4f).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = px(ctx, 4f)
            })
            row.addView(col)
            list.addView(row)
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(ctx, 6f) }
        }
        if (sorted.size > 3) {
            val scroll = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH * 3)
                setOnTouchListener { v, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                addView(list)
            }
            wrap.addView(scroll)
            wrap.addView(tv(ctx, "3 of ${sorted.size} · scroll", 10.5f, "#9CA3AF").apply {
                gravity = Gravity.CENTER
                setPadding(0, px(ctx, 4f), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        } else {
            wrap.addView(list)
        }
        return wrap
    }

    // ── Dialog section: one limit (period + amount) ─────────────────────────

    sealed class FormResult {
        /** Empty amount: no limit (an existing one is removed). */
        object NoLimit : FormResult()
        data class Valid(val period: String, val amount: Double, val startDay: Long, val endDay: Long, val exclude: Boolean) : FormResult()
        data class Invalid(val message: String) : FormResult()
    }

    /** "Set a limit (optional)" section for the create / edit dialogs of a category or an event. */
    class LimitForm(
        private val activity: Activity,
        private val existing: Limit?,
        private val showExcludeBox: Boolean
    ) {
        private var period = existing?.period?.takeIf { it in LimitManager.PERIODS } ?: "monthly"
        private var startDay = existing?.startDay ?: 0L
        private var endDay = existing?.endDay ?: 0L
        private lateinit var amountInput: EditText
        private var excludeBox: CheckBox? = null

        private fun midnight(y: Int, m: Int, d: Int): Long =
            Calendar.getInstance().apply {
                clear(); set(y, m, d, 0, 0, 0)
            }.timeInMillis

        fun addTo(card: LinearLayout) {
            val ctx = activity
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (existing != null) View.VISIBLE else View.GONE
            }

            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(ctx, "#F9FAFB", 12f, "#E5E7EB")
                setPadding(px(ctx, 14f), px(ctx, 12f), px(ctx, 14f), px(ctx, 12f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(ctx, 16f) }
            }
            val arrow = tv(ctx, if (existing != null) "▴" else "▾", 13f, "#6B7280", true)
            header.addView(tv(ctx, "Set a limit (optional)", 14f, "#1A1A2E", true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(arrow)
            header.setOnClickListener {
                val show = body.visibility != View.VISIBLE
                body.visibility = if (show) View.VISIBLE else View.GONE
                arrow.text = if (show) "▴" else "▾"
            }
            card.addView(header)

            // Period dropdown
            body.addView(tv(ctx, "Period", 13f, "#6B7280", true).apply {
                setPadding(px(ctx, 4f), px(ctx, 12f), 0, px(ctx, 6f))
            })
            val periodBtn = tv(ctx, "", 15f, "#1A1A2E").apply {
                background = rounded(ctx, "#F3F4F6", 12f)
                setPadding(px(ctx, 14f), px(ctx, 14f), px(ctx, 14f), px(ctx, 14f))
            }
            body.addView(periodBtn)

            // Custom range
            val customRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(ctx, 8f) }
            }
            fun dateBox(label: String, getter: () -> Long, setter: (Long) -> Unit, first: Boolean): TextView {
                val t = tv(ctx, "", 14f, "#1A1A2E").apply {
                    background = rounded(ctx, "#F3F4F6", 12f)
                    setPadding(px(ctx, 12f), px(ctx, 12f), px(ctx, 12f), px(ctx, 12f))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (!first) marginStart = px(ctx, 8f)
                    }
                }
                fun refresh() {
                    val v = getter()
                    t.text = if (v == 0L) label else dateFmtYear.format(v)
                    t.setTextColor(Color.parseColor(if (v == 0L) "#9CA3AF" else "#1A1A2E"))
                }
                refresh()
                t.setOnClickListener {
                    val c = Calendar.getInstance()
                    if (getter() != 0L) c.timeInMillis = getter()
                    DatePickerDialog(ctx, { _, y, m, dd -> setter(midnight(y, m, dd)); refresh() },
                        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }
                return t
            }
            customRow.addView(dateBox("Start date", { startDay }, { startDay = it }, true))
            customRow.addView(dateBox("End date", { endDay }, { endDay = it }, false))
            body.addView(customRow)

            fun applyPeriod() {
                periodBtn.text = LimitManager.periodTitle(period) + "  ▾"
                customRow.visibility = if (period == "custom") View.VISIBLE else View.GONE
            }
            applyPeriod()
            periodBtn.setOnClickListener {
                val pm = PopupMenu(ctx, periodBtn)
                LimitManager.PERIODS.forEachIndexed { i, p -> pm.menu.add(0, i, i, LimitManager.periodTitle(p)) }
                pm.setOnMenuItemClickListener { item ->
                    period = LimitManager.PERIODS[item.itemId]; applyPeriod(); true
                }
                pm.show()
            }

            // Amount
            body.addView(tv(ctx, "Amount", 13f, "#6B7280", true).apply {
                setPadding(px(ctx, 4f), px(ctx, 12f), 0, px(ctx, 6f))
            })
            amountInput = EditText(ctx).apply {
                hint = "Leave empty for no limit"
                textSize = 15f
                setTextColor(Color.parseColor("#1A1A2E"))
                setHintTextColor(Color.parseColor("#9CA3AF"))
                background = rounded(ctx, "#F3F4F6", 12f)
                setPadding(px(ctx, 14f), px(ctx, 14f), px(ctx, 14f), px(ctx, 14f))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                isSingleLine = true
                if (existing != null) {
                    val a = existing.amount
                    setText(if (a == Math.floor(a)) a.toLong().toString() else a.toString())
                }
            }
            body.addView(amountInput)

            if (showExcludeBox) {
                excludeBox = CheckBox(ctx).apply {
                    text = "Don't count spending tagged to an event"
                    textSize = 13f
                    setTextColor(Color.parseColor("#4B5563"))
                    isChecked = existing?.excludeEvents == true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = px(ctx, 8f) }
                }
                body.addView(excludeBox)
            }
            body.addView(tv(ctx, "Resets at 6 AM, following your phone's clock.", 11f, "#9CA3AF").apply {
                setPadding(px(ctx, 4f), px(ctx, 8f), 0, 0)
            })
            card.addView(body)
        }

        fun read(): FormResult {
            val txt = amountInput.text.toString().trim().replace(",", "")
            if (txt.isEmpty()) return FormResult.NoLimit
            val amount = txt.toDoubleOrNull()
            if (amount == null || amount <= 0.0 || amount.isNaN() || amount.isInfinite()) {
                return FormResult.Invalid("Enter a limit amount greater than 0")
            }
            if (period == "custom") {
                if (startDay == 0L || endDay == 0L) return FormResult.Invalid("Pick a start and end date")
                if (endDay < startDay) return FormResult.Invalid("End date can't be before the start date")
            }
            return FormResult.Valid(
                period, amount,
                if (period == "custom") startDay else 0L,
                if (period == "custom") endDay else 0L,
                excludeBox?.isChecked == true
            )
        }

        /** Applies the result to storage for a category or an event. */
        fun save(ctx: Context, kind: String, categoryId: String?, eventId: String?, r: FormResult) {
            when (r) {
                is FormResult.NoLimit -> existing?.let { LimitManager.remove(ctx, it.id) }
                is FormResult.Valid -> LimitManager.save(ctx, Limit(
                    id = existing?.id ?: LimitManager.newId(),
                    kind = kind, categoryId = categoryId, eventId = eventId,
                    period = r.period, amount = r.amount,
                    startDay = r.startDay, endDay = r.endDay,
                    excludeEvents = kind == LimitManager.KIND_CATEGORY && r.exclude
                ))
                is FormResult.Invalid -> {}
            }
        }
    }

    // ── Dialog section: category limits inside an event ─────────────────────

    class EventCategoryLimitsForm(private val activity: Activity, private val eventId: String?) {
        private val items = mutableListOf<Pair<Category, Double>>()
        private var listBox: LinearLayout? = null
        private val originalIds = mutableMapOf<String, String>()   // categoryId -> limit id

        init {
            if (eventId != null) {
                val all = LimitManager.all(activity)
                LimitManager.eventCategoryLimits(all, eventId).forEach { l ->
                    val cat = CategoryManager.allCategories.firstOrNull { it.id == l.categoryId }
                    if (cat != null) { items.add(cat to l.amount); originalIds[cat.id] = l.id }
                }
            }
        }

        fun addTo(card: LinearLayout) {
            val ctx = activity
            card.addView(tv(ctx, "Category limits in this event", 13f, "#6B7280", true).apply {
                setPadding(px(ctx, 4f), px(ctx, 16f), 0, px(ctx, 6f))
            })
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            listBox = box
            card.addView(box)
            card.addView(tv(ctx, "+ Add category limit", 14f, "#2D6A4F", true).apply {
                gravity = Gravity.CENTER
                background = rounded(ctx, "#F0FDF4", 12f, "#BBF7D0")
                setPadding(px(ctx, 14f), px(ctx, 12f), px(ctx, 14f), px(ctx, 12f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(ctx, 6f) }
                setOnClickListener {
                    showCategorySearch(activity, items.map { it.first.id }.toSet()) { cat, amount ->
                        items.removeAll { it.first.id == cat.id }
                        items.add(cat to amount)
                        render()
                    }
                }
            })
            card.addView(tv(ctx, "Covers the whole event.", 11f, "#9CA3AF").apply {
                setPadding(px(ctx, 4f), px(ctx, 6f), 0, 0)
            })
            render()
        }

        private fun render() {
            val ctx = activity
            val box = listBox ?: return
            box.removeAllViews()
            items.forEach { (cat, amount) ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(ctx, "#F9FAFB", 12f)
                    setPadding(px(ctx, 10f), px(ctx, 8f), px(ctx, 6f), px(ctx, 8f))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = px(ctx, 6f) }
                }
                row.addView(iconBox(ctx, cat.name, cat.colorHex, cat.iconTint, 28, 15).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = px(ctx, 10f)
                })
                row.addView(tv(ctx, cat.name, 14f, "#1A1A2E").apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(tv(ctx, LimitManager.money(ctx, amount), 14f, "#374151", true))
                row.addView(tv(ctx, "✕", 14f, "#9CA3AF", true).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(px(ctx, 36f), px(ctx, 32f))
                    setOnClickListener { items.removeAll { it.first.id == cat.id }; render() }
                })
                box.addView(row)
            }
        }

        /** Writes the list for [targetEventId] (replaces what it had). */
        fun save(ctx: Context, targetEventId: String) {
            val keepIds = items.map { it.first.id }.toSet()
            originalIds.forEach { (catId, limitId) -> if (catId !in keepIds) LimitManager.remove(ctx, limitId) }
            items.forEach { (cat, amount) ->
                LimitManager.save(ctx, Limit(
                    id = originalIds[cat.id] ?: LimitManager.newId(),
                    kind = LimitManager.KIND_EVENT_CAT, categoryId = cat.id, eventId = targetEventId,
                    period = "event", amount = amount
                ))
            }
        }
    }

    /** Search dialog: type to filter categories, pick one, enter an amount. */
    fun showCategorySearch(activity: Activity, excludeIds: Set<String>, onAdd: (Category, Double) -> Unit) {
        val ctx = activity
        val all = CategoryManager.allCategories
            .filter { it.isActive && it.id !in excludeIds }
            .sortedBy { it.name.lowercase() }
        var selected: Category? = null

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, "#FFFFFF", 20f)
            setPadding(px(ctx, 22f), px(ctx, 18f), px(ctx, 22f), px(ctx, 22f))
        }
        val dialog = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Light_Dialog)
            .setView(scrollWrap(ctx, card)).setCancelable(true).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        EventDialogs.addHeader(activity, card, "Add category limit", dialog)

        val search = EditText(ctx).apply {
            hint = "Search categories"
            textSize = 15f
            setTextColor(Color.parseColor("#1A1A2E"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            background = rounded(ctx, "#F3F4F6", 12f)
            setPadding(px(ctx, 14f), px(ctx, 12f), px(ctx, 14f), px(ctx, 12f))
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(ctx, 12f) }
        }
        card.addView(search)

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(ctx, 190f)
            ).apply { topMargin = px(ctx, 8f) }
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
            addView(list)
        }
        card.addView(listScroll)

        val amount = EditText(ctx).apply {
            hint = "Limit amount"
            textSize = 15f
            setTextColor(Color.parseColor("#1A1A2E"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            background = rounded(ctx, "#F3F4F6", 12f)
            setPadding(px(ctx, 14f), px(ctx, 14f), px(ctx, 14f), px(ctx, 14f))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(ctx, 10f) }
        }
        card.addView(amount)

        val addBtn = tv(ctx, "Add limit", 15f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            background = rounded(ctx, "#2D6A4F", 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(ctx, 50f)
            ).apply { topMargin = px(ctx, 14f) }
        }
        card.addView(addBtn)

        fun parsedAmount(): Double? {
            val v = amount.text.toString().trim().replace(",", "").toDoubleOrNull()
            return if (v != null && v > 0.0 && !v.isNaN() && !v.isInfinite()) v else null
        }
        fun updateAdd() {
            val ok = selected != null && parsedAmount() != null
            addBtn.alpha = if (ok) 1f else 0.4f
            addBtn.isEnabled = ok
        }
        fun renderList() {
            list.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            val shown = all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            if (shown.isEmpty()) {
                list.addView(tv(ctx, "No matching category", 13f, "#9CA3AF").apply {
                    setPadding(px(ctx, 6f), px(ctx, 14f), 0, 0)
                })
            }
            shown.forEach { cat ->
                val isSel = selected?.id == cat.id
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(ctx, if (isSel) "#F0FDF4" else "#FFFFFF", 10f)
                    setPadding(px(ctx, 8f), 0, px(ctx, 8f), 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(ctx, 46f))
                    setOnClickListener { selected = cat; renderList(); updateAdd() }
                }
                row.addView(iconBox(ctx, cat.name, cat.colorHex, cat.iconTint, 30, 16).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = px(ctx, 12f)
                })
                row.addView(tv(ctx, cat.name, 14f, if (isSel) "#2D6A4F" else "#1A1A2E", isSel).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (isSel) row.addView(tv(ctx, "✓", 16f, "#2D6A4F", true))
                list.addView(row)
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { renderList(); updateAdd() }
        }
        search.addTextChangedListener(watcher)
        amount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateAdd() }
        })
        addBtn.setOnClickListener {
            val cat = selected
            val v = parsedAmount()
            if (cat == null || v == null) return@setOnClickListener
            dialog.dismiss()
            onAdd(cat, v)
        }
        renderList(); updateAdd()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.90).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    // ── Popup alert (design B) ──────────────────────────────────────────────

    /** The "Limit alert" card shown over other apps after an expense is saved. */
    fun buildAlert(ctx: Context, savedAmount: Double, items: List<LimitAlertItem>, onGotIt: () -> Unit): View {
        val anyRed = items.any { it.status.level >= 100 }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(ctx, "#FFFFFF", 20f)
            setPadding(px(ctx, 20f), px(ctx, 22f), px(ctx, 20f), px(ctx, 18f))
            elevation = 12f * dpf(ctx)
        }
        card.addView(FrameLayout(ctx).apply {
            val sz = px(ctx, 56f)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(if (anyRed) "#FEE2E2" else "#FEF3C7"))
            }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_warning)
                setColorFilter(Color.parseColor(if (anyRed) "#DC2626" else "#D97706"))
                layoutParams = FrameLayout.LayoutParams(px(ctx, 30f), px(ctx, 30f), Gravity.CENTER)
            })
        })
        card.addView(tv(ctx, "Limit alert", 19f, "#1A1A2E", true).apply {
            gravity = Gravity.CENTER
            setPadding(0, px(ctx, 12f), 0, 0)
        })
        val n = items.size
        card.addView(tv(ctx, "${LimitManager.money(ctx, savedAmount)} saved · $n limit${if (n == 1) "" else "s"} need${if (n == 1) "s" else ""} attention",
            13f, "#6B7280").apply {
            gravity = Gravity.CENTER
            setPadding(0, px(ctx, 3f), 0, px(ctx, 14f))
        })

        items.forEach { it0 ->
            val red = it0.status.level >= 100
            val tone = if (red) 2 else 1
            val sec = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(ctx, if (red) "#FEF2F2" else "#FFFBEB", 14f)
                setPadding(px(ctx, 14f), px(ctx, 12f), px(ctx, 14f), px(ctx, 14f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = px(ctx, 10f) }
            }
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(iconBox(ctx, it0.iconName, it0.bgHex, it0.tintHex, 34, 18, 10f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = px(ctx, 10f)
            })
            head.addView(tv(ctx, it0.title, 15f, "#1A1A2E", true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            head.addView(tv(ctx, it0.sub, 11f, "#9CA3AF").apply { setPadding(px(ctx, 6f), 0, 0, 0) })
            sec.addView(head)

            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(ctx, 10f) }
            }
            val lvText = when (it0.status.level) {
                100 -> "Limit reached"
                90 -> "90% reached"
                else -> "80% reached"
            }
            line.addView(tv(ctx, lvText, 13f, textColor(tone), true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            line.addView(tv(ctx, LimitManager.leftText(ctx, it0.status), 13f, textColor(tone), true))
            sec.addView(line)
            sec.addView(bar(ctx, it0.status.pct, tone, 6f, if (red) "#FECACA" else "#FDE68A").apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = px(ctx, 8f)
            })
            card.addView(sec)
        }

        card.addView(tv(ctx, "Got it", 15f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            background = rounded(ctx, "#1A1A2E", 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(ctx, 48f)
            ).apply { topMargin = px(ctx, 4f) }
            setOnClickListener { onGotIt() }
        })
        return card
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
