package com.example.expensetracker

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared dialogs used by the Events feature.
 * Both dialogs follow the same layout: title + "✕" cancel at the top right,
 * a short explanation, and two clearly different choices.
 */
object EventDialogs {

    /** Dialog header: [title] on the left, round "✕" cancel button at the top right. */
    fun addHeader(activity: Activity, card: LinearLayout, title: String, dialog: AlertDialog) {
        val d = activity.resources.displayMetrics.density
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(activity).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1A2E"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(FrameLayout(activity).apply {
            val size = (32 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F3F4F6"))
            }
            addView(TextView(activity).apply {
                text = "✕"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#6B7280"))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            setOnClickListener { dialog.dismiss() }
        })
        card.addView(header)

    }

    /** Styles the Home/Breakdown "Event ▾" pill: outlined when empty, green with the name when selected. */
    fun styleEventPill(activity: Activity, pill: LinearLayout, eventId: String?) {
        val label = pill.findViewById<TextView>(R.id.tvEventFilterLabel)
        val arrow = pill.findViewById<ImageView>(R.id.ivEventFilterArrow)
        val ev = EventManager.getEventById(eventId)
        if (ev == null) {
            label.text = "Event"
            label.setTextColor(ThemeColors.secondary(activity))
            pill.setBackgroundResource(R.drawable.bg_filter_inactive)
            arrow.clearColorFilter()
        } else {
            label.text = ev.name
            label.setTextColor(Color.WHITE)
            pill.setBackgroundResource(R.drawable.bg_filter_active)
            arrow.setColorFilter(Color.WHITE)
        }
    }

    /** All events, most used first (active before archived, ties alphabetical). */
    fun sortByUsage(activity: Activity): List<Event> {
        val usage = try {
            val helper = ExpenseDbHelper(activity)
            try { helper.getEventUsageCounts() } finally { helper.close() }
        } catch (e: Exception) { emptyMap() }
        return EventManager.allEvents.sortedWith(
            compareByDescending<Event> { it.isActive }
                .thenByDescending { usage[it.id] ?: 0 }
                .thenBy { it.name.lowercase() }
        )
    }

    /**
     * Minimal floating dropdown: "All events" + every event (colour dot, name, tick on the
     * selected one; archived ones carry a small "Archived" tag).
     */
    fun showEventFilterMenu(
        activity: Activity,
        anchor: android.view.View,
        selectedId: String? = null,
        onPicked: (String?) -> Unit
    ) {
        val d = activity.resources.displayMetrics.density
        val sorted = sortByUsage(activity)

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        }
        val scroll = android.widget.ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        val popup = android.widget.PopupWindow(scroll, 0, 0, true)

        fun row(dotColor: Int?, text: String, tag: String?, selected: Boolean, onClick: () -> Unit) {
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10 * d
                    setColor(if (selected) Color.parseColor("#F0FDF4") else Color.TRANSPARENT)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (44 * d).toInt()
                )
                setOnClickListener { popup.dismiss(); onClick() }
            }
            r.addView(android.view.View(activity).apply {
                val sz = (10 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (10 * d).toInt() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (dotColor != null) setColor(dotColor)
                    else { setColor(Color.TRANSPARENT); setStroke((1.5f * d).toInt(), Color.parseColor("#9CA3AF")) }
                }
            })
            r.addView(TextView(activity).apply {
                this.text = text
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(Color.parseColor(if (selected) "#2D6A4F" else "#1A1A2E"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (tag != null) {
                r.addView(TextView(activity).apply {
                    this.text = tag
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding((6 * d).toInt(), 0, 0, 0)
                })
            }
            if (selected) {
                r.addView(TextView(activity).apply {
                    this.text = "✓"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#2D6A4F"))
                    setPadding((8 * d).toInt(), 0, 0, 0)
                })
            }
            list.addView(r)
        }

        row(null, "All events", null, selectedId == null) { onPicked(null) }
        if (sorted.isEmpty()) {
            list.addView(TextView(activity).apply {
                text = "No events yet"
                textSize = 12.5f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            })
        } else {
            list.addView(android.view.View(activity).apply {
                setBackgroundColor(Color.parseColor("#F1F1F5"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()
                ).apply { setMargins((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt()) }
            })
            sorted.forEach { ev ->
                row(ev.tintCol(), ev.name, if (ev.isActive) null else "Archived", ev.id == selectedId) {
                    onPicked(ev.id)
                }
            }
        }

        val screenW = activity.resources.displayMetrics.widthPixels
        popup.width = minOf((230 * d).toInt(), screenW - (32 * d).toInt())
        val contentH = (list.childCount * 44 * d).toInt() + (16 * d).toInt()
        popup.height = if (contentH > (300 * d).toInt()) (300 * d).toInt()
                       else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        popup.setBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * d
            setColor(Color.WHITE)
        })
        popup.elevation = 14 * d
        popup.isOutsideTouchable = true
        popup.showAsDropDown(anchor, 0, (6 * d).toInt())
    }

    /**
     * Two-choice dialog with a cancel "✕" at the top right.
     * The second choice is styled as the destructive one.
     */
    fun showTwoOptionDialog(
        activity: Activity,
        title: String,
        message: String,
        firstTitle: String,
        firstSub: String,
        secondTitle: String,
        secondSub: String,
        onFirst: () -> Unit,
        onSecond: () -> Unit
    ) {
        val d = activity.resources.displayMetrics.density

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * d
                setColor(Color.WHITE)
            }
            setPadding((22 * d).toInt(), (18 * d).toInt(), (22 * d).toInt(), (22 * d).toInt())
        }

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog)
            .setView(card)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        addHeader(activity, card, title, dialog)

        card.addView(TextView(activity).apply {
            text = message
            textSize = 13.5f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, (10 * d).toInt(), 0, (14 * d).toInt())
        })

        fun option(titleText: String, subText: String, bg: String, titleColor: String, onClick: () -> Unit) {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14 * d
                    setColor(Color.parseColor(bg))
                }
                setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * d).toInt() }
                setOnClickListener { dialog.dismiss(); onClick() }
            }
            box.addView(TextView(activity).apply {
                text = titleText
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(titleColor))
            })
            box.addView(TextView(activity).apply {
                text = subText
                textSize = 12.5f
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(0, (2 * d).toInt(), 0, 0)
            })
            card.addView(box)
        }

        option(firstTitle, firstSub, "#F0FDF4", "#2D6A4F", onFirst)
        option(secondTitle, secondSub, "#FEF2F2", "#B91C1C", onSecond)

        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.90).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Minimal confirm dialog for a destructive action: red icon circle, centred title and
     * summary, then [Cancel] [Delete] side by side.
     */
    fun showConfirmDeleteDialog(
        activity: Activity,
        title: String,
        summary: String,
        confirmText: String = "Delete",
        onConfirm: () -> Unit
    ) {
        val d = activity.resources.displayMetrics.density
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22 * d
                setColor(Color.WHITE)
            }
            setPadding((22 * d).toInt(), (24 * d).toInt(), (22 * d).toInt(), (20 * d).toInt())
        }
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog)
            .setView(card)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        card.addView(FrameLayout(activity).apply {
            val size = (52 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FEE2E2"))
            }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_delete)
                setColorFilter(Color.parseColor("#EF4444"))
                layoutParams = FrameLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt(), Gravity.CENTER)
            })
        })
        card.addView(TextView(activity).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A1A2E"))
            setPadding(0, (14 * d).toInt(), 0, 0)
        })
        card.addView(TextView(activity).apply {
            text = summary
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, (6 * d).toInt(), 0, (20 * d).toInt())
        })

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun button(label: String, bg: String, fg: String, first: Boolean, onClick: () -> Unit) {
            buttons.addView(TextView(activity).apply {
                text = label
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(fg))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14 * d
                    setColor(Color.parseColor(bg))
                }
                layoutParams = LinearLayout.LayoutParams(0, (46 * d).toInt(), 1f).apply {
                    if (!first) marginStart = (10 * d).toInt()
                }
                setOnClickListener { dialog.dismiss(); onClick() }
            })
        }
        button("Cancel", "#F3F4F6", "#4B5563", true) {}
        button(confirmText, "#EF4444", "#FFFFFF", false, onConfirm)
        card.addView(buttons)

        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.86).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Delete an event: "Event only" keeps its transactions (they just lose the tag),
     * "Event and Transactions" deletes everything. ✕ cancels.
     */
    fun showDeleteEventDialog(activity: Activity, ev: Event, onDone: () -> Unit) {
        val count = ExpenseDbHelper(activity).getTransactionCountForEvent(ev.id)
        val countText = when (count) {
            0 -> "It has no transactions."
            1 -> "It has 1 transaction."
            else -> "It has $count transactions."
        }
        showTwoOptionDialog(
            activity,
            title = "Delete \"${ev.name}\"?",
            message = "$countText Choose what should happen to them.",
            firstTitle = "Event only",
            firstSub = if (count == 0) "Delete the event" else "Delete the event, keep the transactions (they just lose the event tag)",
            secondTitle = "Event and Transactions",
            secondSub = if (count == 0) "Delete the event" else "Permanently delete the event and its $count transaction${if (count == 1) "" else "s"}",
            onFirst = {
                EventManager.deleteEvent(activity, ev.id, deleteTransactions = false)
                onDone()
            },
            onSecond = {
                EventManager.deleteEvent(activity, ev.id, deleteTransactions = true)
                onDone()
            }
        )
    }
}
