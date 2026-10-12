package com.example.expensetracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    @Suppress("DEPRECATION")
    override fun onBackPressed() { finishAffinity() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnBack     = findViewById<android.widget.ImageView>(R.id.btnBack)
        val etUsername  = findViewById<EditText>(R.id.etUsername)
        val etMin       = findViewById<EditText>(R.id.etMinAmount)
        val etMax       = findViewById<EditText>(R.id.etMaxAmount)

        // Load saved values
        etUsername.setText(AppPreferences.getUsername(this))
        val minVal = AppPreferences.getMinAmount(this).toInt()
        etMin.setText(if (minVal > 0) minVal.toString() else "")
        val maxAmt = AppPreferences.getMaxAmount(this)
        if (maxAmt > 0) etMax.setText(maxAmt.toInt().toString())

        btnBack.setOnClickListener { finish() }
        setupBackupSection()
        InstantDetectionUi.setup(this)

        etUsername.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val name = etUsername.text.toString().trim()
                if (name.isNotEmpty()) AppPreferences.setUsername(this, name)
            }
        }

        etMin.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) AppPreferences.setMinAmount(this, etMin.text.toString().toFloatOrNull() ?: 0f)
        }
        etMax.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) AppPreferences.setMaxAmount(this, etMax.text.toString().toFloatOrNull() ?: -1f)
        }

        // Popup max categories slider
        val seekPopup = findViewById<SeekBar>(R.id.seekPopupMax)
        val tvPopupVal = findViewById<TextView>(R.id.tvPopupMaxValue)
        val currentMax = AppPreferences.getPopupMaxCategories(this)
        seekPopup.progress = currentMax
        tvPopupVal.text = currentMax.toString()
        seekPopup.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceIn(2, 9)
                tvPopupVal.text = value.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val value = (sb?.progress ?: 4).coerceIn(2, 9)
                AppPreferences.setPopupMaxCategories(this@SettingsActivity, value)
            }
        })

        // Popup alive time chips
        val chipsContainer = findViewById<LinearLayout>(R.id.aliveChipsContainer)
        val currentAlive = AppPreferences.getPopupAliveTime(this)
        buildAliveChips(chipsContainer, currentAlive)

        // Bottom nav - settings active
        setupBottomNav()
    }

    private fun buildAliveChips(container: LinearLayout, selectedValue: Int) {
        container.removeAllViews()
        val d = resources.displayMetrics.density

        AppPreferences.ALIVE_TIME_OPTIONS.forEachIndexed { index, value ->
            val label = AppPreferences.ALIVE_TIME_LABELS[index]
            val isSelected = (value == selectedValue)

            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setPadding((14*d).toInt(), (8*d).toInt(), (14*d).toInt(), (8*d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginEnd = if (index < AppPreferences.ALIVE_TIME_OPTIONS.size - 1) (6*d).toInt() else 0
                }

                if (isSelected) {
                    setTextColor(android.graphics.Color.WHITE)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 16 * d
                        setColor(android.graphics.Color.parseColor("#2D6A4F"))
                    }
                } else {
                    setTextColor(android.graphics.Color.parseColor("#6B7280"))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 16 * d
                        setColor(android.graphics.Color.parseColor("#F3F4F6"))
                    }
                }

                setOnClickListener {
                    AppPreferences.setPopupAliveTime(this@SettingsActivity, value)
                    buildAliveChips(container, value)
                }
            }
            container.addView(chip)
        }
    }

    private fun setupBottomNav() {
        // Highlight Settings
        listOf(
            Triple(R.id.navHomePill, R.id.navHomeIcon, R.id.navHomeLabel),
            Triple(R.id.navTransactionsPill, R.id.navTransactionsIcon, R.id.navTransactionsLabel),
            Triple(R.id.navCategoryPill, R.id.navCategoryIcon, R.id.navCategoryLabel),
            Triple(R.id.navSettingsPill, R.id.navSettingsIcon, R.id.navSettingsLabel)
        ).forEach { (pillId, iconId, labelId) ->
            findViewById<LinearLayout>(pillId).background = null
            findViewById<android.widget.ImageView>(iconId).alpha = 0.5f
            findViewById<TextView>(labelId).setTextColor(ThemeColors.hint(this))
        }
        findViewById<LinearLayout>(R.id.navSettingsPill).setBackgroundResource(R.drawable.bg_nav_active_pill)
        findViewById<android.widget.ImageView>(R.id.navSettingsIcon).apply {
            alpha = 1f; setColorFilter(ThemeColors.brand(this))
        }
        findViewById<TextView>(R.id.navSettingsLabel).setTextColor(ThemeColors.brand(this))

        // Nav clicks
        findViewById<LinearLayout>(R.id.navHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java)); finish(); overridePendingTransition(0, 0)
        }
        findViewById<LinearLayout>(R.id.navTransactions).setOnClickListener {
            startActivity(Intent(this, TransactionsActivity::class.java)); finish(); overridePendingTransition(0, 0)
        }
        findViewById<LinearLayout>(R.id.navCategory).setOnClickListener {
            startActivity(Intent(this, ManageCategoriesActivity::class.java)); finish(); overridePendingTransition(0, 0)
        }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener { /* already here */ }
    }

    // ── Backup section ──
    private val REQ_DRIVE_SETUP = 4201
    private var backupRunning = false

    override fun onResume() {
        super.onResume()
        refreshBackupStatus()
        InstantDetectionUi.refresh(this)
    }

    private fun formatWhen(ts: Long): String =
        java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ts))

    private fun refreshBackupStatus() {
        val drive = findViewById<TextView>(R.id.tvDriveStatus)
        val btn = findViewById<TextView>(R.id.btnDriveSetup)
        val hint = ThemeColors.hint(this)
        if (!BackupManager.hasDriveTarget(this)) {
            drive.text = "Not set up · Choose your account"
            drive.setTextColor(Color.parseColor("#EF4444"))
            btn.text = "Set up"
        } else if (BackupManager.driveNeedsAttention(this)) {
            drive.text = "Stopped · Choose again"
            drive.setTextColor(Color.parseColor("#EF4444"))
            btn.text = "Change"
        } else {
            val ld = BackupManager.lastDriveBackup(this)
            drive.text = if (ld > 0) "Last upload: ${formatWhen(ld)}" else "On · First upload pending"
            drive.setTextColor(hint)
            btn.text = "Change"
        }
    }

    private fun setupBackupSection() {
        findViewById<TextView>(R.id.btnDriveSetup).setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "ExpenseTracker_backup.json")
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_DRIVE_SETUP)
            } catch (e: Exception) {
                Toast.makeText(this, "No file picker available on this phone", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<TextView>(R.id.btnBackupNow).setOnClickListener {
            if (backupRunning) return@setOnClickListener
            backupRunning = true
            val app = applicationContext
            Thread {
                val result = try { BackupManager.backupNow(app) } catch (e: Throwable) { null }
                runOnUiThread {
                    backupRunning = false
                    if (isFinishing) return@runOnUiThread
                    refreshBackupStatus()
                    val msg = when {
                        result == null -> "Backup failed. Please try again."
                        !result.first && result.second == BackupManager.DriveOutcome.NOT_SET ->
                            "Nothing to back up yet, or the phone copy couldn't be saved."
                        !result.first -> "Phone backup failed. Please try again."
                        result.second == BackupManager.DriveOutcome.RETRY ->
                            "Saved to phone. Drive upload will retry later."
                        result.second == BackupManager.DriveOutcome.BROKEN ->
                            "Saved to phone. Drive needs to be set up again."
                        else -> "Backup saved"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == InstantDetectionUi.REQ_CAPTURE_SAVE && resultCode == RESULT_OK) {
            data?.data?.let { InstantDetectionUi.writeCapture(this, it) }
            return
        }
        if (requestCode == REQ_DRIVE_SETUP && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            if (BackupManager.setDriveTarget(this, uri)) {
                BackupManager.enqueueDriveNow(this)
                Toast.makeText(this, "Drive backup is on", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Couldn't turn on Drive backup. Please try again later.", Toast.LENGTH_LONG).show()
            }
            refreshBackupStatus()
        }
    }
}
