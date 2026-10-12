package com.example.expensetracker

import android.util.Log

/**
 * Filter for bank/UPI SMS text. A message is a legitimate debit only when it
 *   1. contains a debit word as a WHOLE word ("recharge" no longer matches "charge"),
 *   2. is not credit-only,
 *   3. contains no false-positive / promo / reminder wording, and
 *   4. looks like a bank or transaction message (account, card, UPI, reference, balance ...).
 * [skipSender] drops promotional sender IDs (those ending in "-P").
 */
object TransactionFilter {

    private const val TAG = "TransactionFilter"

    private fun words(vararg w: String) =
        Regex("\\b(?:" + w.joinToString("|") { Regex.escape(it) } + ")\\b")

    /** Debit action words (whole words). */
    private val DEBIT = words(
        "debited", "debit", "deducted", "spent", "paid", "purchase", "purchased",
        "withdrawn", "withdrawal", "charged", "sent", "renewed", "auto-renewed"
    )

    /** Subset that is unambiguous even when a credit word is also present. */
    private val STRONG_DEBIT = words(
        "debited", "debit", "spent", "purchase", "purchased", "withdrawn", "charged", "deducted"
    )

    private val CREDIT = words(
        "credited", "credit", "received", "deposited", "refund", "refunded",
        "reversed", "reversal", "added to", "cashback received"
    )

    /** Phrases that mean "not a completed debit" or "marketing". Matched from a word start. */
    private val FALSE_POSITIVE_TERMS = listOf(
        // Refunds & reversals
        "refund", "reversed", "reversal",
        // Failed transactions
        "failed", "failure", "unsuccessful", "declined", "rejected", "could not", "unable to",
        // Future tense / reminders / bills
        "will be deducted", "will be debited", "will be charged", "upcoming", "due on", "is due",
        "payment due", "reminder", "scheduled", "auto-pay is set", "is pending", "pending approval",
        "overdue", "pay now", "pay by", "min due", "minimum due", "total due", "bill generated",
        "bill is generated",
        // Requests
        "requested", "request for", "has requested",
        // One-time passwords are never a completed debit
        "otp", "one time password", "one-time password",
        // Promotional
        "offer", "reward", "earn", "get up to", "apply", "eligible", "promo", "discount",
        "cashback when", "cashback on", "validity", "click here", "t&c", "tnc", "limited period",
        "limited time", "extra free", "save rs", "download the app",
        // Conditional
        "if debited", "if charged", "not yet",
        // Self transfers
        "own account", "self transfer", "your savings a/c", "between your", "to your own"
    )
    private val FALSE_POSITIVES = FALSE_POSITIVE_TERMS.map { it to Regex("\\b" + Regex.escape(it)) }

    /** Marks a bank / card / UPI style message. Promos almost never carry any of these. */
    private val EVIDENCE = listOf(
        Regex("\\ba/?c\\b"), Regex("\\bacct\\b"), Regex("\\baccount\\b"), Regex("\\bcard\\b"),
        Regex("\\bx{1,}\\d{3,}"), Regex("\\*{1,}\\d{3,}"), Regex("\\bending\\s*\\d{3,}"),
        Regex("\\bupi\\b"), Regex("\\bvpa\\b"), Regex("\\bimps\\b"), Regex("\\bneft\\b"), Regex("\\brtgs\\b"),
        Regex("\\bnach\\b"), Regex("\\bref(?:erence)?\\b"), Regex("\\btxn\\b"),
        Regex("\\bavl\\b"), Regex("\\bbal(?:ance)?\\b"), Regex("\\bwallet\\b"), Regex("\\bfastag\\b")
    )

    /** Indian promotional sender IDs look like "JX-JIOHOM-P". */
    private val PROMO_SENDER = Regex("^[A-Za-z]{2}-[A-Za-z0-9]+-P$")

    fun skipSender(sender: String): Boolean = PROMO_SENDER.matches(sender.trim())

    /** Index of the first debit word in [text], or -1. Used to pick the amount next to it. */
    fun debitIndex(text: String): Int = DEBIT.find(text.lowercase())?.range?.first ?: -1

    /** Returns null when the text is a legitimate debit, otherwise the reason it was rejected. */
    fun rejectReason(text: String): String? {
        val lower = text.lowercase()

        if (!DEBIT.containsMatchIn(lower)) return "no debit word"

        if (CREDIT.containsMatchIn(lower) && !STRONG_DEBIT.containsMatchIn(lower)) return "credit without strong debit"

        val fp = FALSE_POSITIVES.firstOrNull { it.second.containsMatchIn(lower) }
        if (fp != null) return "false-positive word '" + fp.first + "'"

        if (EVIDENCE.none { it.containsMatchIn(lower) }) return "no bank/transaction evidence"

        return null
    }

    /** True if the message text represents a legitimate completed debit. */
    fun isLegitimateDebit(text: String): Boolean {
        val reason = rejectReason(text)
        if (reason != null) {
            try { Log.d(TAG, "Skipped: $reason") } catch (t: Throwable) { }
            return false
        }
        return true
    }
}
