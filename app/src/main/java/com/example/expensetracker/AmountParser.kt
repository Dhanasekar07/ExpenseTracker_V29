package com.example.expensetracker

/**
 * Shared utility for extracting monetary amounts from SMS/notification text.
 * Used by SmsObserver for SMS payment detection.
 */
object AmountParser {

    private val PATTERNS = listOf(
        Regex("""[+]?(?:₹\s*|Rs\.?\s*|INR\s+)([0-9,]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""([0-9,]+(?:\.[0-9]{1,2})?)\s*/-"""),
        // "debited by 250.0" (some bank SMS carry no currency sign)
        Regex("""(?:debited|spent|paid|withdrawn)\s+(?:by|for|of|with)\s+([0-9,]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    )

    /** Words just before an amount that mean it is a balance/limit, not the payment itself. */
    private val BALANCE_BEFORE = Regex("(?:bal|avl|available|limit|outstanding)[^0-9]{0,14}$", RegexOption.IGNORE_CASE)

    /**
     * Extract the first monetary amount found in the text.
     * Supports ₹, Rs, Rs., INR prefixes and trailing /- format.
     * Returns 0.0 if no amount found.
     */
    fun parse(text: String): Double {
        if (text.isBlank()) return 0.0
        for (p in PATTERNS) {
            val m = p.find(text) ?: continue
            val amt = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (amt > 0.0) return amt
        }
        return 0.0
    }

    /**
     * Amount of the payment: skips balances/limits and, of the rest, takes the amount closest to
     * the debit word at [debitIdx] (or the first one when [debitIdx] < 0). Falls back to [parse].
     */
    fun parseDebit(text: String, debitIdx: Int): Double {
        if (text.isBlank()) return 0.0
        data class Hit(val start: Int, val amount: Double, val balance: Boolean)
        val hits = ArrayList<Hit>()
        for (p in PATTERNS) {
            for (m in p.findAll(text)) {
                val amt = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
                if (amt <= 0.0) continue
                val before = text.substring(maxOf(0, m.range.first - 24), m.range.first)
                hits.add(Hit(m.range.first, amt, BALANCE_BEFORE.containsMatchIn(before)))
            }
        }
        if (hits.isEmpty()) return 0.0
        val pool = hits.filter { !it.balance }.ifEmpty { return parse(text) }
        if (debitIdx < 0) return pool.minByOrNull { it.start }!!.amount
        return pool.minByOrNull { Math.abs(it.start - debitIdx) }!!.amount
    }
}
