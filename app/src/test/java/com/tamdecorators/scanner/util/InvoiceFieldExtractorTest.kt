package com.tamdecorators.scanner.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvoiceFieldExtractorTest {

    private val sampleInvoice = """
        TAM Decorators
        Invoice No: INV-2041
        Date: 14/03/2026
        Bill to: John Murphy
        Subtotal: €1,000.00
        VAT @ 23%: €230.00
        Total Due: €1,230.00
    """.trimIndent()

    @Test
    fun `reads all fields from a typical invoice`() {
        val fields = InvoiceFieldExtractor.extract(sampleInvoice)

        assertEquals("INV-2041", fields.invoiceNumber)
        assertEquals("14/03/2026", fields.date)
        assertEquals("John Murphy", fields.customer)
        assertEquals("230.00", fields.vat)
        assertEquals("1,230.00", fields.total)
    }

    @Test
    fun `total ignores the subtotal line`() {
        val fields = InvoiceFieldExtractor.extract("Subtotal: 50.00\nTotal: 61.50")
        assertEquals("61.50", fields.total)
    }

    @Test
    fun `reads euro and pound amounts`() {
        assertEquals("85.00", InvoiceFieldExtractor.extract("TOTAL €85.00").total)
        assertEquals("85.00", InvoiceFieldExtractor.extract("TOTAL £ 85.00").total)
    }

    @Test
    fun `keeps thousands separators in large totals`() {
        assertEquals("12,450.75", InvoiceFieldExtractor.extract("Amount due: €12,450.75").total)
    }

    @Test
    fun `reads dates written with month names`() {
        assertEquals("3 March 2026", InvoiceFieldExtractor.extract("Issued 3 March 2026").date)
    }

    @Test
    fun `falls back to first name-like line for customer`() {
        val text = "RECEIPT\n12 Main Street\nMcCarthy Paints Ltd\nTotal: 40.00"
        assertEquals("McCarthy Paints Ltd", InvoiceFieldExtractor.extract(text).customer)
    }

    @Test
    fun `returns nulls for empty text`() {
        val fields = InvoiceFieldExtractor.extract("")
        assertNull(fields.customer)
        assertNull(fields.date)
        assertNull(fields.invoiceNumber)
        assertNull(fields.total)
        assertNull(fields.vat)
    }
}
