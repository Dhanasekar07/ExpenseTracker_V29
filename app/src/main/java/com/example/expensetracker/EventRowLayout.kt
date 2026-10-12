package com.example.expensetracker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Event row used by the popup and the Category-page popup preview.
 * Children (in order): a scrollable chip area and the "+ Event" pill.
 * The pill is always on the LEFT, followed by the chips.
 *
 * While everything fits, the pill and the chips are centred together as one group
 * (an empty row shows just the centred pill). Once the chips no longer fit, the chip
 * area takes the remaining width and scrolls, and the pill stays pinned at the left.
 */
class EventRowLayout(context: Context) : ViewGroup(context) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val scroll = getChildAt(0)
        val pill = getChildAt(1)
        val mode = MeasureSpec.getMode(widthSpec)
        val avail = MeasureSpec.getSize(widthSpec)

        pill.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val pw = pill.measuredWidth

        val gap = (6 * resources.displayMetrics.density).toInt()
        val scrollWidthSpec = if (mode == MeasureSpec.UNSPECIFIED)
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        else MeasureSpec.makeMeasureSpec((avail - pw - gap).coerceAtLeast(0), MeasureSpec.AT_MOST)
        scroll.measure(scrollWidthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

        val h = maxOf(scroll.measuredHeight, pill.measuredHeight)
        val w = if (mode == MeasureSpec.UNSPECIFIED) scroll.measuredWidth + pw + gap else avail
        setMeasuredDimension(w, resolveSize(h, heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val scroll = getChildAt(0)
        val pill = getChildAt(1)
        val width = r - l
        val height = b - t
        val sw = scroll.measuredWidth
        val pw = pill.measuredWidth
        // gap only when there are chips next to the pill
        val gap = if (sw > 0) (6 * resources.displayMetrics.density).toInt() else 0
        val left = ((width - pw - gap - sw) / 2).coerceAtLeast(0)
        pill.layout(left, (height - pill.measuredHeight) / 2,
            left + pw, (height - pill.measuredHeight) / 2 + pill.measuredHeight)
        scroll.layout(left + pw + gap, (height - scroll.measuredHeight) / 2,
            left + pw + gap + sw, (height - scroll.measuredHeight) / 2 + scroll.measuredHeight)
    }

    companion object {
        /** "+ Event" pill (or "– Event" in the red "open" state). */
        fun makePill(context: Context, open: Boolean, onClick: (() -> Unit)?): TextView {
            val d = context.resources.displayMetrics.density
            return TextView(context).apply {
                text = if (open) "–  Event" else "+  Event"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(Color.parseColor(if (open) "#EF4444" else "#4F46E5"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16 * d
                    setColor(Color.parseColor(if (open) "#FEE2E2" else "#E0E7FF"))
                }
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                if (onClick != null) setOnClickListener { onClick() }
            }
        }
    }
}
