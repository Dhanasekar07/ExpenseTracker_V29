package com.example.expensetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pairs a screen detection with the bank SMS of the same payment, one-to-one, so a single
 * payment shows ONE popup while two separate payments of the same amount show two.
 * State lives in its own prefs file (not part of backups) and is pruned after 15 minutes.
 */
object PaymentPairing {

    private const val PREFS = "payment_pairing"
    private const val KEY = "signals"
    private const val MATCH_WINDOW_MS = 10 * 60 * 1000L
    private const val PRUNE_MS = 15 * 60 * 1000L
    private const val SMS_SNIPPET = 200

    private val lock = Any()

    private data class Signal(
        val id: String,
        val source: String,      // "screen" | "sms"
        val amount: Double,
        val timeMillis: Long,
        val payeeLower: String,  // screen: payee; sms: short lowercase body snippet
        var consumed: Boolean
    )

    /** true = show the popup; false = an SMS already showed it for this payment. */
    fun onScreenSignal(ctx: Context, amount: Double, payee: String): Boolean =
        handle(ctx, "screen", "sms", amount, payee.trim().lowercase(Locale.ROOT))

    /** true = show the popup; false = a screen detection already showed it for this payment. */
    fun onSmsSignal(ctx: Context, amount: Double, body: String): Boolean =
        handle(ctx, "sms", "screen", amount, body.take(SMS_SNIPPET).lowercase(Locale.ROOT))

    private fun handle(ctx: Context, mySource: String, otherSource: String, amount: Double, text: String): Boolean {
        return try {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val list = load(sp).filter { now - it.timeMillis <= PRUNE_MS }.toMutableList()

                val candidates = list.filter {
                    it.source == otherSource && !it.consumed &&
                        Math.abs(it.amount - amount) < 0.01 &&
                        Math.abs(now - it.timeMillis) <= MATCH_WINDOW_MS
                }.sortedBy { it.timeMillis }

                val match = if (candidates.isEmpty()) null else {
                    // payee text belongs to the screen signal; the body snippet to the sms signal
                    candidates.firstOrNull { c ->
                        if (mySource == "screen") mentionsPayee(c.payeeLower, text)
                        else mentionsPayee(text, c.payeeLower)
                    } ?: candidates.first()
                }

                val show: Boolean
                if (match != null) {
                    match.consumed = true
                    show = false
                } else {
                    list.add(Signal("$now-${list.size}-${(Math.random() * 1e6).toInt()}", mySource, amount, now, text, false))
                    show = true
                }
                save(sp, list)
                show
            }
        } catch (t: Throwable) {
            true // never swallow a payment popup because of bookkeeping problems
        }
    }

    /** true when [body] mentions any token (>= 3 letters/digits) of [payee]. */
    private fun mentionsPayee(body: String, payee: String): Boolean {
        if (body.isEmpty() || payee.isEmpty()) return false
        return payee.split(Regex("[^a-z0-9]+")).any { it.length >= 3 && body.contains(it) }
    }

    private fun load(sp: android.content.SharedPreferences): List<Signal> {
        val out = ArrayList<Signal>()
        try {
            val arr = JSONArray(sp.getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Signal(
                    o.getString("id"), o.getString("source"), o.getDouble("amount"),
                    o.getLong("time"), o.optString("text", ""), o.optBoolean("consumed", false)
                ))
            }
        } catch (t: Throwable) { /* corrupt state: start fresh */ }
        return out
    }

    private fun save(sp: android.content.SharedPreferences, list: List<Signal>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("id", s.id); put("source", s.source); put("amount", s.amount)
                put("time", s.timeMillis); put("text", s.payeeLower); put("consumed", s.consumed)
            })
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }
}
