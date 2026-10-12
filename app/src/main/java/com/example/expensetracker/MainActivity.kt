package com.example.expensetracker

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var db                  : ExpenseDbHelper
    private lateinit var tvGreeting          : TextView
    private lateinit var tvTotalSpent        : TextView
    private lateinit var pieChart            : PieChartView
    private lateinit var legendContainer     : LinearLayout
    private lateinit var transactionContainer: LinearLayout
    private lateinit var tvNoTxn             : TextView

    private var currentFilter = "day"
    private var customFrom    = 0L
    private var customTo      = 0L
    private var currentEventId: String? = null
    private val currency get() = AppPreferences.getCurrencySymbol(this)

    // ── Backup / restore ──
    private var backupBusy = false      // a picker is open (this instance)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply night mode
        AppCompatDelegate.setDefaultNightMode(
            if (AppPreferences.isNightMode(this)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        // If NOT onboarded → go to onboarding, stop here
        if (!AppPreferences.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        // Set layout
        setContentView(R.layout.activity_main)
        live = this

        // Bind views
        db                   = ExpenseDbHelper(this)
        tvGreeting           = findViewById(R.id.tvGreeting)
        tvTotalSpent         = findViewById(R.id.tvTotalSpent)
        pieChart             = findViewById(R.id.pieChart)
        legendContainer      = findViewById(R.id.legendContainer)
        transactionContainer = findViewById(R.id.transactionContainer)
        tvNoTxn              = findViewById(R.id.tvNoTransactions)

        // Init categories & events
        try { CategoryManager.initialize(this) } catch (e: Exception) { e.printStackTrace() }
        try { EventManager.initialize(this) } catch (e: Exception) { e.printStackTrace() }

        // Run cleanup off the main thread to avoid ANR
        Thread {
            try { TransactionDeduplicator(applicationContext).cleanup() }
            catch (e: Exception) { e.printStackTrace() }
        }.start()

        setupGreeting()
        setupFilterTabs()
        setupEventFilter()
        setupBottomNav()
        setupSeeAllButtons()
        refreshDashboard()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { finishAffinity() }

    override fun onResume() {
        super.onResume()
        if (!AppPreferences.isOnboarded(this)) return

        // Start OverlayService (hosts SmsObserver) — safe after permissions granted
        try {
            val svc = Intent(this, OverlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                startForegroundService(svc)
            else
                startService(svc)
        } catch (e: Exception) { e.printStackTrace() }

        try {
            EventManager.initialize(this)
            // Selected event may have been deleted elsewhere
            if (currentEventId != null && EventManager.getEventById(currentEventId) == null) currentEventId = null
            EventDialogs.styleEventPill(this, findViewById(R.id.btnEventFilter), currentEventId)
            setupGreeting()
            refreshDashboard()
        } catch (e: Exception) { e.printStackTrace() }

        runBackupStartup()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Backup & restore
    // ═══════════════════════════════════════════════════════════════════

    private companion object {
        const val REQ_RESTORE_PICK = 4101
        const val REQ_DRIVE_CREATE = 4102
        const val REQ_LEGACY_STORAGE = 4103
        const val MAX_BACKUP_BYTES = 20L * 1024 * 1024

        /** True while a restore runs; survives rotation / recreation of the activity. */
        @Volatile var restoreRunning = false
        /** The visible Home screen, so a finished restore can refresh whichever instance is alive. */
        @Volatile var live: MainActivity? = null
    }

    override fun onDestroy() {
        if (live === this) live = null
        BackupUi.dismissCurrent()
        super.onDestroy()
    }

    /** Keeps the daily backups scheduled and shows the restore / Drive dialogs when due. */
    private fun runBackupStartup() {
        try { BackupManager.schedule(this) } catch (e: Exception) { e.printStackTrace() }
        if (backupBusy || restoreRunning || BackupUi.isShowing()) return
        if (!AppPreferences.isRestorePromptPending(this)) requestLegacyStorageIfNeeded()

        // Restore dialog: only right after onboarding, and only on an empty install
        if (AppPreferences.isRestorePromptPending(this)) {
            if (db.countAll() > 0) {
                AppPreferences.setRestorePromptPending(this, false)
            } else {
                showRestoreDialog()
                return
            }
        }
        maybeShowDrivePrompt()
    }

    private fun showRestoreDialog() {
        BackupUi.showRestoreDialog(
            this,
            onChoose = { launchRestorePicker() },
            onSkipConfirmed = {
                AppPreferences.setRestorePromptPending(this, false)
                maybeShowDrivePrompt()
            }
        )
    }

    private fun launchRestorePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            // open the picker in the backup folder
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2F" + BackupManager.FOLDER)
                )
            }
        }
        try {
            backupBusy = true
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_RESTORE_PICK)
        } catch (e: Exception) {
            backupBusy = false
            Toast.makeText(this, "No file picker available on this phone", Toast.LENGTH_LONG).show()
            showRestoreDialog()
        }
    }

    private fun maybeShowDrivePrompt() {
        if (backupBusy || restoreRunning || BackupUi.isShowing()) return
        val kind = BackupManager.drivePromptKind(this)
        if (kind == 0) { requestLegacyStorageIfNeeded(); return }
        BackupManager.notePromptShown(this, kind)
        BackupUi.showDrivePrompt(this, kind) { launchDriveCreate() }
    }

    private fun launchDriveCreate() {
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
            backupBusy = true
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_DRIVE_CREATE)
        } catch (e: Exception) {
            backupBusy = false
            Toast.makeText(this, "No file picker available on this phone", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            REQ_RESTORE_PICK -> {
                if (resultCode != RESULT_OK || uri == null) {
                    backupBusy = false
                    showRestoreDialog()          // nothing chosen: back to the dialog
                } else {
                    restoreFrom(uri)
                }
            }
            REQ_DRIVE_CREATE -> {
                backupBusy = false
                if (resultCode == RESULT_OK && uri != null) {
                    if (BackupManager.setDriveTarget(this, uri)) {
                        BackupManager.enqueueDriveNow(this)
                        Toast.makeText(this, "Drive backup is on", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Couldn't turn on Drive backup. Please try again later.", Toast.LENGTH_LONG).show()
                    }
                }
                requestLegacyStorageIfNeeded()
            }
        }
    }

    /** Reads the chosen backup file (at most [MAX_BACKUP_BYTES]); null when unreadable or too big. */
    private fun readBackupText(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BACKUP_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
        } catch (e: Throwable) { e.printStackTrace(); null }
    }

    /** Reads the chosen file and replaces all data with it. */
    private fun restoreFrom(uri: Uri) {
        backupBusy = false
        restoreRunning = true
        val appCtx = applicationContext
        Thread {
            val content = readBackupText(uri)
            val result = if (content == null) BackupManager.RestoreResult(false, "Couldn't read that file.")
                         else BackupManager.restoreFromJson(appCtx, content)
            // Back on the main thread: use whichever Home screen is alive now (it may have been
            // recreated by a rotation while the restore ran).
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                restoreRunning = false
                val screen = live
                if (result.ok) {
                    AppPreferences.setRestorePromptPending(appCtx, false)
                    BackupManager.adoptRestoredDriveFile(appCtx, uri)
                    Toast.makeText(appCtx, "Your data has been restored", Toast.LENGTH_LONG).show()
                    screen?.recreate()
                } else {
                    Toast.makeText(appCtx, result.message, Toast.LENGTH_LONG).show()
                    screen?.showRestoreDialog()
                }
            }
        }.start()
    }

    /** Android 9 and below need the storage permission to write into Documents. Asked once. */
    private fun requestLegacyStorageIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT > 28) return
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (BackupManager.legacyStoragePermissionAsked(this)) return
        BackupManager.markLegacyStoragePermissionAsked(this)
        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_LEGACY_STORAGE)
    }

    private fun setupGreeting() {
        val name = AppPreferences.getUsername(this)
        tvGreeting.text = if (name.isNotEmpty()) "Hello, $name" else "Hello!"
    }

    private fun setupFilterTabs() {
        mapOf(
            R.id.tabDay to "day", R.id.tabWeek to "week",
            R.id.tabMonth to "month", R.id.tabCustom to "custom"
        ).forEach { (id, f) ->
            findViewById<TextView>(id).setOnClickListener {
                if (f == "custom") showDatePicker()
                else { currentFilter = f; updateTabUI(); refreshDashboard() }
            }
        }
        updateTabUI()
    }

    private fun updateTabUI() {
        listOf(
            R.id.tabDay to "day", R.id.tabWeek to "week",
            R.id.tabMonth to "month", R.id.tabCustom to "custom"
        ).forEach { (id, f) ->
            val tv = findViewById<TextView>(id)
            if (f == currentFilter) {
                tv.setBackgroundResource(R.drawable.bg_filter_active)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.setBackgroundResource(R.drawable.bg_filter_inactive)
                tv.setTextColor(ThemeColors.secondary(this))
            }
        }
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val from = Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            DatePickerDialog(this, { _, y2, m2, d2 ->
                val to = Calendar.getInstance().apply {
                    set(y2, m2, d2, 23, 59, 59); set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                customFrom = from; customTo = to; currentFilter = "custom"
                updateTabUI(); refreshDashboard()
            }, y, m, d).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun getFromTs(): Long {
        val cal = Calendar.getInstance()
        return when (currentFilter) {
            "day"    -> { cal.set(Calendar.HOUR_OF_DAY,0); cal.set(Calendar.MINUTE,0); cal.set(Calendar.SECOND,0); cal.set(Calendar.MILLISECOND,0); cal.timeInMillis }
            "week"   -> { cal.add(Calendar.DAY_OF_YEAR,-7); cal.timeInMillis }
            "month"  -> { cal.set(Calendar.DAY_OF_MONTH,1); cal.set(Calendar.HOUR_OF_DAY,0); cal.set(Calendar.MINUTE,0); cal.set(Calendar.SECOND,0); cal.set(Calendar.MILLISECOND,0); cal.timeInMillis }
            "custom" -> customFrom
            else     -> 0L
        }
    }

    private fun getToTs() =
        if (currentFilter == "custom") customTo else System.currentTimeMillis()

    private fun setupEventFilter() {
        val pill = findViewById<LinearLayout>(R.id.btnEventFilter)
        EventDialogs.styleEventPill(this, pill, currentEventId)
        pill.setOnClickListener {
            EventDialogs.showEventFilterMenu(this, pill, currentEventId) { id ->
                currentEventId = id
                EventDialogs.styleEventPill(this, pill, currentEventId)
                refreshDashboard()
            }
        }
    }

    private fun refreshDashboard() {
        try {
            val expenses  = db.getExpenses(getFromTs(), getToTs(), currentEventId)
            val catTotals = db.getTotalByCategory(getFromTs(), getToTs(), currentEventId)
            val total     = expenses.sumOf { it.amount }

            tvTotalSpent.text = "$currency${String.format("%.0f", total)}"

            val chartData = ChartDataHelper.buildChartData(catTotals) { CategoryManager.getCategoryByName(it) }
            val slices = chartData.map { PieSlice(it.name, it.amount.toFloat(), it.chartColor) }
            pieChart.setData(slices, currency)

            // Legend — show all chart entries (top 6 + Others)
            legendContainer.removeAllViews()
            chartData.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity     = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0,0,0,8) }
                }
                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(12,12).apply { setMargins(0,0,10,0) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(entry.chartColor)
                    }
                }
                val label = TextView(this).apply {
                    text = "${entry.name}: $currency${String.format("%.0f", entry.amount)}"
                    textSize = 12f
                    setTextColor(ThemeColors.secondary(this))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                row.addView(dot); row.addView(label)
                legendContainer.addView(row)
            }

            // Transactions
            transactionContainer.removeAllViews()
            if (expenses.isEmpty()) {
                tvNoTxn.visibility = View.VISIBLE
            } else {
                tvNoTxn.visibility = View.GONE
                expenses.take(7).forEach { addTransactionRow(it) }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun addTransactionRow(expense: Expense) {
        val cat      = try { CategoryManager.getCategoryByName(expense.category) } catch (e: Exception) { null }
        val colorHex = cat?.colorHex ?: "#F0F2F5"
        val sdf      = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 14, 0, 14)
        }

        val iconBg = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(48, 48).apply { setMargins(0, 0, 14, 0) }
        }
        CategoryIcon.applyIcon(iconBg, expense.category,
            cat?.colorHex ?: "#E2E8F0",
            cat?.iconTint ?: "#475569"
        )

        val info = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = expense.category; textSize = 15f
            setTextColor(ThemeColors.primary(this)); typeface = Typeface.DEFAULT_BOLD
        })
        info.addView(TextView(this).apply {
            text = sdf.format(Date(expense.timestamp)); textSize = 11f
            setTextColor(ThemeColors.hint(this))
        })

        val amt = TextView(this).apply {
            text = if (expense.amount > 0) "$currency${String.format("%.0f", expense.amount)}" else "$currency-"
            textSize = 15f; setTextColor(ThemeColors.amountNeg(this)); typeface = Typeface.DEFAULT_BOLD
        }

        row.addView(iconBg); row.addView(info); row.addView(amt)
        transactionContainer.addView(row)
        transactionContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(ThemeColors.divider(this))
        })
    }

    private fun setupSeeAllButtons() {
        findViewById<LinearLayout>(R.id.btnSeeAllChart).setOnClickListener {
            startActivity(Intent(this, ExpenseBreakdownActivity::class.java).apply {
                putExtra("filter", currentFilter); putExtra("from", customFrom); putExtra("to", customTo)
                putExtra("eventId", currentEventId)
            })
        }
        findViewById<TextView>(R.id.btnSeeAllTxn).setOnClickListener {
            startActivity(Intent(this, TransactionsActivity::class.java).apply {
                putExtra("entry", "stack")
            })
        }
    }

    private fun setupBottomNav() {
        listOf(
            Triple(R.id.navHomePill, R.id.navHomeIcon, R.id.navHomeLabel),
            Triple(R.id.navTransactionsPill, R.id.navTransactionsIcon, R.id.navTransactionsLabel),
            Triple(R.id.navCategoryPill, R.id.navCategoryIcon, R.id.navCategoryLabel),
            Triple(R.id.navSettingsPill, R.id.navSettingsIcon, R.id.navSettingsLabel)
        ).forEach { (p, i, l) ->
            findViewById<LinearLayout>(p).background = null
            findViewById<ImageView>(i).apply { alpha = 0.5f; clearColorFilter() }
            findViewById<TextView>(l).setTextColor(ThemeColors.hint(this))
        }
        // Home active
        findViewById<LinearLayout>(R.id.navHomePill).setBackgroundResource(R.drawable.bg_nav_active_pill)
        findViewById<ImageView>(R.id.navHomeIcon).apply { alpha = 1f; setColorFilter(ThemeColors.brand(this)) }
        findViewById<TextView>(R.id.navHomeLabel).setTextColor(ThemeColors.brand(this))

        findViewById<LinearLayout>(R.id.navHome).setOnClickListener {}
        findViewById<LinearLayout>(R.id.navTransactions).setOnClickListener {
            startActivity(Intent(this, TransactionsActivity::class.java)); finish(); overridePendingTransition(0, 0)
        }
        findViewById<LinearLayout>(R.id.navCategory).setOnClickListener {
            startActivity(Intent(this, ManageCategoriesActivity::class.java)); finish(); overridePendingTransition(0, 0)
        }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)); finish(); overridePendingTransition(0, 0)
        }
    }


}
