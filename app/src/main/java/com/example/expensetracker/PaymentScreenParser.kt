package com.example.expensetracker

import java.util.Calendar
import java.util.Locale

data class ParsedPayment(
    val amount: Double,
    val payee: String,
    val app: String,
    val screenTimeMillis: Long
)

/**
 * Pure Kotlin (no Android types) parser for payment-success screens: tuned for GPay and PhonePe, generic for other apps.
 * Input is the ordered list of visible texts of the screen. Returns null for anything uncertain.
 */
object PaymentScreenParser {

    const val PKG_GPAY = "com.google.android.apps.nbu.paisa.user"
    const val PKG_PHONEPE = "com.phonepe.app"

    private const val MAX_SKEW_MS = 5 * 60 * 1000L

    private val NEGATIVE = listOf(
        "failed", "failure", "declined", "unsuccessful", "pending", "in progress",
        "processing", "cancelled", "canceled", "reversed", "refund", "credited back",
        "try again", "went wrong", "timed out", "could not", "couldn't"
    )
    private val GLOBAL_NEGATIVE = listOf("payment failed", "has failed", "transaction failed")
    private val SELF = listOf("payment to own account", "own account", "self transfer")
    private val UI_LABELS = setOf(
        "split expense", "view details", "share receipt", "done", "payment successful",
        "check balance", "powered by", "add note"
    )

    private val AMOUNT_WHOLE = Regex("""^(?:₹|rs\.?|inr)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*\*?$""", RegexOption.IGNORE_CASE)
    private val HAS_CURRENCY = Regex("""₹|\brs\.?\s*\d|\binr\s*\d""", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("""^\+?[0-9][0-9\s\-]{6,}$""")
    private val DATE = Regex(
        """(\d{1,2})\s+([A-Za-z]{3,9})\s+(\d{4})\s*(?:,|\bat\b)\s*(\d{1,2}):(\d{2})\s*([ap])\.?\s*m\.?""",
        RegexOption.IGNORE_CASE
    )
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"
    )

    fun parse(rawTexts: List<String>, nowMillis: Long, packageName: String, appLabel: String? = null): ParsedPayment? {
        return try {
            val texts = rawTexts.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
            if (texts.isEmpty()) return null
            val lower = texts.map { it.lowercase(Locale.ENGLISH) }

            if (lower.any { n -> SELF.any { n.contains(it) } }) return null
            if (lower.any { n -> GLOBAL_NEGATIVE.any { n.contains(it) } }) return null

            when (packageName) {
                PKG_GPAY -> parseGPay(texts, lower, nowMillis)
                PKG_PHONEPE -> parsePhonePe(texts, lower, nowMillis)
                else -> parseGeneric(texts, lower, nowMillis, appLabel ?: packageName)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun regionBlocked(lower: List<String>, markerIdx: Int): Boolean {
        val end = minOf(lower.size - 1, markerIdx + 6)
        for (i in 0..end) {
            if (NEGATIVE.any { lower[i].contains(it) }) return true
        }
        return false
    }

    private fun parseGPay(texts: List<String>, lower: List<String>, now: Long): ParsedPayment? {
        val marker = lower.indexOfFirst { it == "paid to" || it.startsWith("paid to ") }
        if (marker < 0) return null
        if (regionBlocked(lower, marker)) return null

        // Payee: merged "Paid to vidya b" or the next suitable node
        var payee = ""
        if (lower[marker] != "paid to") {
            payee = texts[marker].substring(7).trim()
        } else {
            for (i in marker + 1..minOf(texts.size - 1, marker + 3)) {
                if (amountOf(texts[i]) != null) continue
                if (lower[i].startsWith("banking name")) continue
                if (PHONE.matches(texts[i])) continue
                payee = texts[i]; break
            }
        }
        if (payee.isEmpty() || payee.length > 60) return null

        // Amount: nearest standalone amount before the marker, else shortly after it
        var amount: Double? = null
        for (i in marker - 1 downTo maxOf(0, marker - 4)) {
            amount = amountOf(texts[i]); if (amount != null) break
        }
        if (amount == null) {
            for (i in marker + 1..minOf(texts.size - 1, marker + 5)) {
                amount = amountOf(texts[i]); if (amount != null) break
            }
        }
        if (amount == null || amount <= 0.0) return null

        val dateIdx = nearestDateIdx(texts, marker, requireAfter = false)
        if (dateIdx < 0) return null
        val t = parseDate(texts[dateIdx]) ?: return null
        if (Math.abs(now - t) > MAX_SKEW_MS) return null
        return ParsedPayment(amount, payee, "GPay", t)
    }

    private fun parsePhonePe(texts: List<String>, lower: List<String>, now: Long): ParsedPayment? {
        val marker = lower.indexOfFirst { it == "payment successful" }
        if (marker < 0) return null
        if (regionBlocked(lower, marker)) return null

        val dateIdx = nearestDateIdx(texts, marker, requireAfter = true)
        if (dateIdx < 0) return null
        val t = parseDate(texts[dateIdx]) ?: return null
        if (Math.abs(now - t) > MAX_SKEW_MS) return null

        // First ₹ amount after the marker/date node; later ones are advert prices.
        val start = maxOf(marker, dateIdx)
        var amount: Double? = null
        var amountIdx = -1
        for (i in start + 1 until texts.size) {
            val a = amountOf(texts[i], requireCurrency = true)
            if (a != null) { amount = a; amountIdx = i; break }
        }
        if (amount == null || amount <= 0.0) return null

        var payee = ""
        var payeeIdx = -1
        for (i in start + 1 until texts.size) {
            if (amountOf(texts[i], true) != null) continue
            if (PHONE.matches(texts[i])) continue
            payee = texts[i]; payeeIdx = i; break
        }
        if (payeeIdx < 0 || payee.length > 60 || lower[payeeIdx] in UI_LABELS) return null
        // The payee must belong to the payment card, i.e. appear close to the amount
        if (Math.abs(payeeIdx - amountIdx) > 4) return null
        return ParsedPayment(amount, payee, "PhonePe", t)
    }


    // ── Generic reader for any other payment app ────────────────────────────

    private val SUCCESS_MARKERS = listOf(
        "payment successful", "payment success", "payment completed", "payment done", "paid successfully",
        "transaction successful", "transaction completed", "transaction success", "booking confirmed",
        "booked successfully", "ticket booked", "order placed", "order confirmed", "money sent"
    )
    private val ADVERT_WORDS = listOf(
        "balance", "cashback", "reward", "coupon", "% off", "save ", "earn", "get up to", "offer", "discount", "scratch"
    )
    private val CURRENCY_AMOUNT = Regex("""(?:₹\s*|\brs\.?\s*|\binr\s*)([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)

    /**
     * Works without any per-app tuning: needs ONE clear success message and a rupee amount close to it.
     * Several success messages on one screen (a history list), a stale date, or any failure /
     * pending wording means the screen is not a fresh payment, so nothing is returned.
     */
    private fun parseGeneric(texts: List<String>, lower: List<String>, now: Long, appLabel: String): ParsedPayment? {
        val markers = lower.indices.filter { i -> SUCCESS_MARKERS.any { lower[i].contains(it) } }
        if (markers.size != 1) return null
        val marker = markers[0]
        if (regionBlocked(lower, marker)) return null

        // A date on the screen must be fresh; a payment screen with an old date is a history view.
        for (t in texts) {
            if (DATE.containsMatchIn(t)) {
                val d = parseDate(t) ?: return null
                if (Math.abs(now - d) > MAX_SKEW_MS) return null
            }
        }

        // Nearest rupee amount to the marker (ties go to the one after it), skipping advert text
        var amount = 0.0
        var best = Int.MAX_VALUE
        for (i in texts.indices) {
            if (Math.abs(i - marker) > 8) continue
            if (ADVERT_WORDS.any { lower[i].contains(it) }) continue
            val whole = amountOf(texts[i], requireCurrency = true)
            val v = whole ?: CURRENCY_AMOUNT.find(texts[i])?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            if (v == null || v <= 0.0) continue
            val dist = Math.abs(i - marker) * 2 + (if (i < marker) 1 else 0)
            if (dist < best) { best = dist; amount = v }
        }
        if (amount <= 0.0) return null

        // Payee: "Paid to X" / "Sent to X" / "To X", else the app name
        var payee = appLabel
        for (i in texts.indices) {
            val l = lower[i]
            val rest = when {
                l.startsWith("paid to ") -> texts[i].substring(8)
                l.startsWith("sent to ") -> texts[i].substring(8)
                l == "paid to" || l == "sent to" || l == "to" ->
                    if (i + 1 < texts.size && amountOf(texts[i + 1], false) == null) texts[i + 1] else ""
                else -> ""
            }.trim()
            if (rest.isNotEmpty() && rest.length <= 60 && !PHONE.matches(rest)) { payee = rest; break }
        }
        // screenTime 0: the same screen read again (or re-opened) is one payment
        return ParsedPayment(amount, payee, appLabel, 0L)
    }

    private fun nearestDateIdx(texts: List<String>, marker: Int, requireAfter: Boolean): Int {
        var best = -1
        var bestDist = Int.MAX_VALUE
        for (i in texts.indices) {
            if (requireAfter && i <= marker) continue
            if (!DATE.containsMatchIn(texts[i])) continue
            val d = Math.abs(i - marker)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    /** Whole-node amount like "₹1.00", "₹10,590", "₹5,999*", "Rs 20", "INR 20". */
    internal fun amountOf(text: String, requireCurrency: Boolean = true): Double? {
        val m = AMOUNT_WHOLE.matchEntire(text.trim()) ?: return null
        if (requireCurrency && !HAS_CURRENCY.containsMatchIn(text)) return null
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return if (v > 0.0) v else null
    }

    internal fun parseDate(text: String): Long? {
        val m = DATE.find(text) ?: return null
        val day = m.groupValues[1].toInt()
        val mon = m.groupValues[2].lowercase(Locale.ENGLISH)
        val year = m.groupValues[3].toInt()
        var hour = m.groupValues[4].toInt()
        val min = m.groupValues[5].toInt()
        val pm = m.groupValues[6].equals("p", ignoreCase = true)
        var monthIdx = MONTHS.indexOf(mon)
        if (monthIdx < 0 && mon.length == 3) monthIdx = MONTHS.indexOfFirst { it.startsWith(mon) }
        if (monthIdx < 0 || hour !in 1..12 || min !in 0..59 || day !in 1..31) return null
        hour %= 12
        if (pm) hour += 12
        val cal = Calendar.getInstance()
        cal.isLenient = false
        cal.clear()
        cal.set(year, monthIdx, day, hour, min, 0)
        return try { cal.timeInMillis } catch (e: Exception) { null }
    }
}
