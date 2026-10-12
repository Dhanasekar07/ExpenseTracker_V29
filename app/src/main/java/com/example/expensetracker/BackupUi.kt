package com.example.expensetracker

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Dialogs for restore and Drive backup. Same look as the other dialogs: white rounded card. */
object BackupUi {

    /** The dialog currently on screen (restore, skip warning or Drive prompt). */
    private var current: AlertDialog? = null

    fun isShowing(): Boolean = current?.isShowing == true

    /** Called when the host screen is destroyed so no dialog leaks. */
    fun dismissCurrent() {
        try { current?.dismiss() } catch (_: Exception) {}
        current = null
    }

    /**
     * Card dialog with a primary button and a plain secondary button.
     * Back / tapping outside calls [onCancel].
     */
    private fun cardDialog(
        activity: Activity,
        title: String,
        message: String,
        primaryText: String,
        secondaryText: String,
        secondaryColor: String,
        onPrimary: () -> Unit,
        onSecondary: () -> Unit,
        onCancel: () -> Unit
    ): AlertDialog {
        val d = activity.resources.displayMetrics.density
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22 * d
                setColor(Color.WHITE)
            }
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
        }
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog)
            .setView(card)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        card.addView(TextView(activity).apply {
            text = title
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1A2E"))
        })
        card.addView(TextView(activity).apply {
            text = message
            textSize = 14f
            setTextColor(Color.parseColor("#6B7280"))
            setLineSpacing(0f, 1.15f)
            setPadding(0, (10 * d).toInt(), 0, (20 * d).toInt())
        })
        card.addView(TextView(activity).apply {
            text = primaryText
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * d
                setColor(Color.parseColor("#2D6A4F"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt()
            )
            setOnClickListener { dialog.setOnCancelListener(null); dialog.dismiss(); onPrimary() }
        })
        card.addView(TextView(activity).apply {
            text = secondaryText
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(secondaryColor))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (44 * d).toInt()
            ).apply { topMargin = (4 * d).toInt() }
            setOnClickListener { dialog.setOnCancelListener(null); dialog.dismiss(); onSecondary() }
        })
        dialog.setOnCancelListener { onCancel() }

        current = dialog
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        return dialog
    }

    /**
     * First Home screen after onboarding. "Skip", tapping outside or Back all lead to
     * the "are you sure" warning; only "Skip anyway" there actually skips.
     */
    fun showRestoreDialog(activity: Activity, onChoose: () -> Unit, onSkipConfirmed: () -> Unit): AlertDialog {
        fun askSkip() {
            showSkipWarning(activity,
                onGoBack = { showRestoreDialog(activity, onChoose, onSkipConfirmed) },
                onSkipAnyway = onSkipConfirmed)
        }
        return cardDialog(
            activity,
            title = "Restore your data?",
            message = "Used Expense Tracker before? Choose your backup file to bring back your transactions, categories and events.",
            primaryText = "Choose backup",
            secondaryText = "Skip",
            secondaryColor = "#6B7280",
            onPrimary = onChoose,
            onSecondary = { askSkip() },
            onCancel = { askSkip() }
        )
    }

    private fun showSkipWarning(activity: Activity, onGoBack: () -> Unit, onSkipAnyway: () -> Unit): AlertDialog =
        cardDialog(
            activity,
            title = "Skip restore?",
            message = "Without restoring, your previous data won't be in the app. Skip anyway?",
            primaryText = "Go back",
            secondaryText = "Skip anyway",
            secondaryColor = "#EF4444",
            onPrimary = onGoBack,
            onSecondary = onSkipAnyway,
            onCancel = onGoBack
        )

    /** kind 1 = first-time setup, kind 2 = the Drive backup stopped working. */
    fun showDrivePrompt(activity: Activity, kind: Int, onChoose: () -> Unit): AlertDialog =
        cardDialog(
            activity,
            title = if (kind == 2) "Drive backup needs attention" else "Back up to Google Drive?",
            message = if (kind == 2)
                "Your daily Drive backup has stopped. Choose the file again to turn it back on."
            else
                "Pick your Google account and save the file once. After that your data is backed up to Drive automatically every day, so it survives reinstalls and new phones.\n\nChoose Google Drive, then tap Save.",
            primaryText = if (kind == 2) "Choose again" else "Choose account",
            secondaryText = "Not now",
            secondaryColor = "#6B7280",
            onPrimary = onChoose,
            onSecondary = {},
            onCancel = {}
        )
}
