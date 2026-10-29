package com.tamdecorators.scanner.util

import java.util.Locale

data class InvoiceFields(
    val customer: String? = null,
    val date: String? = null,
    val invoiceNumber: String? = null,
    val total: String? = null,
    val vat: String? = null,
)

/**
 * Pulls the key invoice fields out of OCR text using simple rules (regular expressions).
 *
 * Rules rather than AI: it runs instantly, offline and for free, and every result
 * is shown in editable fields, so the user can correct anything it gets wrong.
 */
object InvoiceFieldExtractor {
    /**
     * An amount with an optional € or £ sign, optional thousands separators
     * and optional cents: "€1,234.56", "£ 85", "1234,50".
     */
    private const val AMOUNT = """[€£]?\s*(\d{1,3}(?:[ ,.]\d{3})*(?:[.,]\d{2})?|\d+(?:[.,]\d{2})?)(?!\d)"""

    private val DATE_NUMERIC = Regex("""\b(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4})\b""")
    private val DATE_WORDS = Regex("""\b(\d{1,2}\s+[A-Za-z]{3,9}\s+\d{2,4})\b""")

    private val INVOICE_NUMBER = Regex(
        """\bINV(?:OICE)?\s*(?:NO\.?|NUMBER|#)?[:\s#-]*([A-Z0-9][A-Z0-9\-/]*\d[A-Z0-9\-/]*)""",
        RegexOption.IGNORE_CASE,
    )

    // "SUBTOTAL" is excluded so the real total wins.
    private val TOTAL = Regex("""(?<!SUB)\b(?:GRAND\s*TOTAL|TOTAL\s*DUE|AMOUNT\s*DUE|TOTAL)[:\s]*$AMOUNT""", RegexOption.IGNORE_CASE)
    private val VAT = Regex("""\bVAT(?:\s*AMOUNT)?(?:\s*@?\s*\d{1,2}(?:\.\d)?\s*%)?[:\s]*$AMOUNT""", RegexOption.IGNORE_CASE)

    private val CUSTOMER_LABEL = Regex("""\b(?:BILL(?:ED)?\s*TO|CUSTOMER)[:\s]*([^\n]{3,60})""", RegexOption.IGNORE_CASE)
    private val STARTS_WITH_DIGIT = Regex("""^\d""")

    /** Words that mark a line as a heading or Tam Decorators' own name, not the customer. */
    private val NOT_CUSTOMER_WORDS = listOf(
        "INVOICE", "RECEIPT", "TOTAL", "VAT", "SUBTOTAL", "AMOUNT", "BALANCE", "DECORATORS",
    )
    private const val CUSTOMER_SEARCH_LINES = 10
    private const val MIN_CUSTOMER_LENGTH = 3

    fun extract(ocrText: String): InvoiceFields {
        val text = ocrText.replace("\r", "\n")

        return InvoiceFields(
            customer = guessCustomer(text),
            date = (DATE_NUMERIC.find(text) ?: DATE_WORDS.find(text))?.groupValues?.get(1),
            invoiceNumber = INVOICE_NUMBER.find(text)?.groupValues?.get(1),
            total = TOTAL.find(text)?.groupValues?.get(1),
            vat = VAT.find(text)?.groupValues?.get(1),
        )
    }

    /** Uses a "Bill to" / "Customer" label if there is one, otherwise the first line that looks like a name. */
    private fun guessCustomer(text: String): String? {
        CUSTOMER_LABEL.find(text)?.groupValues?.get(1)?.trim()?.let { if (it.isNotBlank()) return it }

        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(CUSTOMER_SEARCH_LINES)
            .firstOrNull { line ->
                val upper = line.uppercase(Locale.UK)
                line.length >= MIN_CUSTOMER_LENGTH &&
                    NOT_CUSTOMER_WORDS.none { upper.contains(it) } &&
                    !STARTS_WITH_DIGIT.containsMatchIn(line)
            }
    }
}
