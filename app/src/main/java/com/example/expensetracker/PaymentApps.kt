package com.example.expensetracker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri

/**
 * The apps whose payment-success screen the screen reader may look at.
 *
 *   watched = (apps Android reports as UPI payment apps  +  apps the user added)  -  apps the user removed
 *
 * Android knows the UPI apps because each one declares that it opens upi://pay links.
 * Added / removed apps are kept in their own prefs file; apps that are no longer installed are ignored.
 */
object PaymentApps {

    private const val PREFS = "payment_apps"
    private const val KEY_ADDED = "added"
    private const val KEY_IGNORED = "ignored"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getSet(ctx: Context, key: String): Set<String> =
        try { sp(ctx).getStringSet(key, emptySet())?.toSet() ?: emptySet() } catch (t: Throwable) { emptySet() }

    private fun putSet(ctx: Context, key: String, v: Set<String>) {
        try { sp(ctx).edit().putStringSet(key, HashSet(v)).apply() } catch (t: Throwable) { }
    }

    /** Installed apps that handle a upi://pay link (GPay, PhonePe, Paytm, BHIM, bank apps ...). */
    @Suppress("DEPRECATION")
    fun detectedUpiApps(ctx: Context): List<String> = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=test@upi&pn=Test&am=1&cu=INR"))
        ctx.packageManager.queryIntentActivities(i, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != ctx.packageName }
            .distinct()
    } catch (t: Throwable) { emptyList() }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0); true
    } catch (t: Throwable) { false }

    /** The apps to watch, in a stable order: detected ones first, then the ones the user added. */
    fun watched(ctx: Context): List<String> {
        val ignored = getSet(ctx, KEY_IGNORED)
        val added = getSet(ctx, KEY_ADDED)
        val out = LinkedHashSet<String>()
        // GPay and PhonePe have tuned readers: always offered when installed, even if detection misses them
        listOf(PaymentScreenParser.PKG_GPAY, PaymentScreenParser.PKG_PHONEPE).forEach { if (it !in ignored) out.add(it) }
        detectedUpiApps(ctx).sorted().forEach { if (it !in ignored) out.add(it) }
        added.sorted().forEach { out.add(it) }
        return out.filter { it != ctx.packageName && isInstalled(ctx, it) }
    }

    fun add(ctx: Context, pkg: String) {
        putSet(ctx, KEY_ADDED, getSet(ctx, KEY_ADDED) + pkg)
        putSet(ctx, KEY_IGNORED, getSet(ctx, KEY_IGNORED) - pkg)
        PaymentAccessibilityService.refreshWatched()
    }

    /** Removing a detected app puts it on the ignore list so detection does not bring it back. */
    fun remove(ctx: Context, pkg: String) {
        putSet(ctx, KEY_ADDED, getSet(ctx, KEY_ADDED) - pkg)
        putSet(ctx, KEY_IGNORED, getSet(ctx, KEY_IGNORED) + pkg)
        PaymentAccessibilityService.refreshWatched()
    }

    /** Launchable apps for the "+" picker, sorted by name. */
    class Candidate(val pkg: String, val label: String)

    @Suppress("DEPRECATION")
    fun launchableApps(ctx: Context): List<Candidate> = try {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(i, 0)
            .mapNotNull { ri ->
                val p = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (p == ctx.packageName) null else Candidate(p, label(ctx, p))
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    } catch (t: Throwable) { emptyList() }

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) { pkg }

    /** The app's own launcher icon. */
    fun icon(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (t: Throwable) { null }
}
