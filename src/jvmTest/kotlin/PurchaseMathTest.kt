package org.btmonier

import kotlin.test.Test
import kotlin.test.assertEquals

class PurchaseMathTest {

    @Test
    fun `roundToCents rounds halves up like a receipt`() {
        assertEquals(1.01, roundToCents(1.005))
        assertEquals(1.88, roundToCents(1.8792))
        assertEquals(2.50, roundToCents(2.5))
        assertEquals(0.0, roundToCents(0.0))
        assertEquals(19.99, roundToCents(19.99))
    }

    @Test
    fun `computeTax applies Iowa six percent by default`() {
        assertEquals(1.88, computeTax(31.32, DEFAULT_TAX_RATE))
        assertEquals(3.00, computeTax(49.95, DEFAULT_TAX_RATE))
        assertEquals(0.90, computeTax(14.99, DEFAULT_TAX_RATE))
        assertEquals(0.0, computeTax(0.0, DEFAULT_TAX_RATE))
    }

    @Test
    fun `total adds computed tax and shipping`() {
        val purchase = Purchase(subtotal = 31.32, shipping = 4.99)
        assertEquals(31.32 + 1.88 + 4.99, purchase.total, 0.0001)
    }

    @Test
    fun `total respects an explicit tax amount`() {
        val taxExempt = Purchase(subtotal = 31.32, taxAmount = 0.0)
        assertEquals(31.32, taxExempt.total, 0.0001)

        val receipt = Purchase(subtotal = 31.32, taxRate = 0.06, taxAmount = 2.19, shipping = 0.0)
        assertEquals(33.51, receipt.total, 0.0001)
    }

    @Test
    fun `total uses a custom rate when given`() {
        val purchase = Purchase(subtotal = 100.0, taxRate = 0.07)
        assertEquals(107.0, purchase.total, 0.0001)
    }
}
