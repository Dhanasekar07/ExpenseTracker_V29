package com.example.expensetracker

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** UI helpers for the optional Instant payment detection (accessibility) feature. */
object InstantDetectionUi {

    const val REQ_CAPTURE_SAVE = 4301

    fun isServiceOn(activity: Activity): Boolean = try {
        val cr = activity.contentResolver
        val enabledFlag = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!enabledFlag) false else {
            val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val me = ComponentName(activity, PaymentAccessibilityService::class.java)
            val full = me.flattenToString()
            val short = me.flattenToShortString()
            if (list.isNullOrEmpty()) false else {
                val sp = TextUtils.SimpleStringSplitter(':')
                sp.setString(list)
                var found = false
                while (sp.hasNext()) {
                    val e = sp.next()
                    if (e.equals(full, true) || e.equals(short, true)) { found = true; break }
                    val c = ComponentName.unflattenFromString(e)
                    if (c != null && c == me) { found = true; break }
                }
                found
            }
        }
    } catch (t: Throwable) { false }

    fun refresh(activity: Activity) {
        val btn = activity.findViewById<TextView>(R.id.btnInstantSetup) ?: return
        val on = isServiceOn(activity)
        btn.text = if (on) "Disable" else "Enable"
        btn.setTextColor(Color.parseColor(if (on) "#B91C1C" else "#FFFFFF"))
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * activity.resources.displayMetrics.density
            setColor(Color.parseColor(if (on) "#FEE2E2" else "#2D6A4F"))
        }
        bindApps(activity, on)
    }

    // ── Payment apps row ────────────────────────────────────────────────────

    /** Icons only: the apps being watched, then a "+" as the last item of the same scrolling row. */
    private fun bindApps(activity: Activity, on: Boolean) {
        val box = activity.findViewById<View>(R.id.paymentAppsBox) ?: return
        box.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        val strip = activity.findViewById<LinearLayout>(R.id.paymentAppsStrip) ?: return
        strip.removeAllViews()
        for (pkg in PaymentApps.watched(activity)) strip.addView(appIcon(activity, pkg))
        strip.addView(plusButton(activity))
    }

    private fun appIcon(activity: Activity, pkg: String): View {
        val size = dp(activity, 44)
        return ImageView(activity).apply {
            setImageDrawable(PaymentApps.icon(activity, pkg))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(activity, 10) }
            setOnClickListener {
                Toast.makeText(activity, PaymentApps.label(activity, pkg), Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener { v ->
                showRemoveChip(activity, v, pkg); true
            }
        }
    }

    private fun plusButton(activity: Activity): View {
        val size = dp(activity, 44)
        return TextView(activity).apply {
            text = "+"; textSize = 24f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6B7280"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * activity.resources.displayMetrics.density
                setColor(Color.WHITE)
                setStroke(dp(activity, 1), Color.parseColor("#9CA3AF"), dp(activity, 4).toFloat(), dp(activity, 3).toFloat())
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { showPicker(activity) }
        }
    }

    /** Small triangle under the chip, pointing at the pressed icon. */
    private class Arrow(ctx: Activity, private val colorHex: String) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(colorHex) }
        override fun onDraw(canvas: Canvas) {
            val p = Path()
            p.moveTo(0f, 0f); p.lineTo(width.toFloat(), 0f); p.lineTo(width / 2f, height.toFloat()); p.close()
            canvas.drawPath(p, paint)
        }
    }

    /** Dark chip above the long-pressed icon: app name and a round red trash button. */
    private fun showRemoveChip(activity: Activity, anchor: View, pkg: String) {
        val d = activity.resources.displayMetrics.density
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22 * d
                setColor(Color.parseColor("#1F2937"))
            }
            setPadding(dp(activity, 14), dp(activity, 5), dp(activity, 5), dp(activity, 5))
        }
        pill.addView(TextView(activity).apply {
            text = PaymentApps.label(activity, pkg)
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(activity, 170)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(activity, 10) }
        })
        val trash = FrameLayout(activity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#EF4444")) }
            layoutParams = LinearLayout.LayoutParams(dp(activity, 30), dp(activity, 30))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_trash)
                layoutParams = FrameLayout.LayoutParams(dp(activity, 16), dp(activity, 16), Gravity.CENTER)
            })
        }
        pill.addView(trash)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pill)
            addView(Arrow(activity, "#1F2937").apply {
                layoutParams = LinearLayout.LayoutParams(dp(activity, 12), dp(activity, 6))
            })
        }
        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.elevation = 8 * d
        trash.setOnClickListener {
            PaymentApps.remove(activity, pkg)
            popup.dismiss()
            refresh(activity)
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val xOff = (anchor.width - content.measuredWidth) / 2
        val yOff = -(anchor.height + content.measuredHeight + dp(activity, 2))
        try { popup.showAsDropDown(anchor, xOff, yOff) } catch (t: Throwable) { }
    }

    /** Bottom sheet with every launchable app (real icons) and a search box. */
    private fun showPicker(activity: Activity) {
        Thread {
            val all = PaymentApps.launchableApps(activity)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                openPicker(activity, all)
            }
        }.start()
    }

    private fun openPicker(activity: Activity, all: List<PaymentApps.Candidate>) {
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_Dialog)
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(20 * d, 20 * d, 20 * d, 20 * d, 0f, 0f, 0f, 0f)
                setColor(Color.WHITE)
            }
            setPadding(dp(activity, 16), dp(activity, 18), dp(activity, 16), dp(activity, 8))
        }
        sheet.addView(TextView(activity).apply {
            text = "Add a payment app"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1A2E"))
        })
        val search = EditText(activity).apply {
            hint = "Search apps"; textSize = 14f; maxLines = 1; setSingleLine(true)
            setTextColor(Color.parseColor("#1A1A2E")); setHintTextColor(Color.parseColor("#9CA3AF"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * d
                setColor(Color.parseColor("#F3F4F6"))
            }
            setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 40))
                .apply { topMargin = dp(activity, 12); bottomMargin = dp(activity, 6) }
        }
        sheet.addView(search)

        val shown = ArrayList(all)
        val watchedNow = PaymentApps.watched(activity).toMutableSet()
        val iconCache = HashMap<String, android.graphics.drawable.Drawable?>()
        val list = ListView(activity).apply {
            divider = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val c = shown[position]
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = AbsListViewLP(dp(activity, 54))
                }
                row.addView(ImageView(activity).apply {
                    setImageDrawable(iconCache.getOrPut(c.pkg) { PaymentApps.icon(activity, c.pkg) })
                    layoutParams = LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 38)).apply { marginEnd = dp(activity, 12) }
                })
                row.addView(TextView(activity).apply {
                    text = c.label; textSize = 14f; setTextColor(Color.parseColor("#1A1A2E"))
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (c.pkg in watchedNow) row.addView(TextView(activity).apply {
                    text = "✓"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#2D6A4F"))
                })
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val c = shown[position]
            if (c.pkg in watchedNow) {
                Toast.makeText(activity, "${c.label} is already added", Toast.LENGTH_SHORT).show()
            } else {
                PaymentApps.add(activity, c.pkg)
                dialog.dismiss()
                refresh(activity)
                // scroll the row to the end so the newly added app is visible
                val hsv = activity.findViewById<LinearLayout>(R.id.paymentAppsStrip)?.parent as? HorizontalScrollView
                hsv?.post { hsv.fullScroll(View.FOCUS_RIGHT) }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                shown.clear()
                shown.addAll(if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) })
                adapter.notifyDataSetChanged()
            }
        })
        sheet.addView(list)
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.75).toInt())
        }
        dialog.show()
    }

    @Suppress("FunctionName")
    private fun AbsListViewLP(heightPx: Int) =
        android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)

    fun setup(activity: Activity) {
        // Android only lets the user switch an accessibility service on or off in its own settings page
        activity.findViewById<TextView>(R.id.btnInstantSetup)?.setOnClickListener {
            safeStart(activity, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        activity.findViewById<View>(R.id.rowInstantTitle)?.setOnLongClickListener {
            showCapture(activity); true
        }
        refresh(activity)
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun newCard(activity: Activity): Pair<AlertDialog, LinearLayout> {
        val d = activity.resources.displayMetrics.density
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * d
                setColor(Color.WHITE)
            }
            setPadding(dp(activity, 24), dp(activity, 24), dp(activity, 24), dp(activity, 16))
        }
        val scroll = ScrollView(activity).apply { addView(card) }
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog)
            .setView(scroll)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return Pair(dialog, card)
    }

    private fun title(activity: Activity, card: LinearLayout, t: String) {
        card.addView(TextView(activity).apply {
            text = t; textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1A2E"))
        })
    }

    private fun body(activity: Activity, card: LinearLayout, t: String, bold: Boolean = false) {
        card.addView(TextView(activity).apply {
            text = t; textSize = 14f
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (bold) Color.parseColor("#1A1A2E") else Color.parseColor("#6B7280"))
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(activity, 10), 0, 0)
        })
    }

    private fun primary(activity: Activity, card: LinearLayout, t: String, onClick: () -> Unit) {
        card.addView(TextView(activity).apply {
            text = t; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * activity.resources.displayMetrics.density
                setColor(Color.parseColor("#2D6A4F"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 46)
            ).apply { topMargin = dp(activity, 18) }
            setOnClickListener { onClick() }
        })
    }

    private fun secondary(activity: Activity, card: LinearLayout, t: String, color: String = "#2D6A4F", onClick: () -> Unit) {
        card.addView(TextView(activity).apply {
            text = t; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 40)
            ).apply { topMargin = dp(activity, 2) }
            setOnClickListener { onClick() }
        })
    }

    private fun safeStart(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(activity, "Couldn't open that screen on this phone", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBattery(activity: Activity) {
        try {
            activity.startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${activity.packageName}")
            ))
        } catch (e: Throwable) {
            safeStart(activity, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showCapture(activity: Activity) {
        val (dialog, card) = newCard(activity)
        title(activity, card, "Capture mode")
        body(activity, card, "Saves the readable text of your payment apps' screens on this phone so the patterns can be tuned. Contains amounts and names. Turn off when done.")

        val sw = Switch(activity).apply {
            text = "Capture"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A2E"))
            isChecked = PaymentCapture.isOn(activity)
            setPadding(0, dp(activity, 14), 0, 0)
        }
        card.addView(sw)
        val info = TextView(activity).apply {
            textSize = 12f; setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, dp(activity, 6), 0, 0)
        }
        card.addView(info)
        fun updateInfo() {
            info.text = (if (sw.isChecked) "ON (turns off automatically after 60 minutes). " else "OFF. ") +
                "Saved: ${PaymentCapture.size(activity) / 1024} KB"
        }
        updateInfo()
        sw.setOnCheckedChangeListener { _, checked ->
            PaymentCapture.setOn(activity, checked); updateInfo()
        }

        primary(activity, card, "Save capture file") {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "ExpenseTracker_capture.txt")
            }
            try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQ_CAPTURE_SAVE)
                dialog.dismiss()
            } catch (e: Throwable) {
                Toast.makeText(activity, "No file picker available on this phone", Toast.LENGTH_LONG).show()
            }
        }
        secondary(activity, card, "Clear", "#EF4444") {
            PaymentCapture.clear(activity); updateInfo()
            Toast.makeText(activity, "Capture file cleared", Toast.LENGTH_SHORT).show()
        }
        secondary(activity, card, "Close", "#6B7280") { dialog.dismiss() }
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.90).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** Called from SettingsActivity.onActivityResult for REQ_CAPTURE_SAVE. */
    fun writeCapture(activity: Activity, uri: Uri) {
        try {
            val f = PaymentCapture.file(activity)
            val bytes = if (f.exists()) f.readBytes() else ByteArray(0)
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw IllegalStateException("no stream")
            Toast.makeText(activity,
                if (bytes.isEmpty()) "Saved (the capture file is empty so far)" else "Capture file saved",
                Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(activity, "Couldn't save the capture file", Toast.LENGTH_LONG).show()
        }
    }
}
