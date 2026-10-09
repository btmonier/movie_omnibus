package org.btmonier

import kotlin.test.Test
import kotlin.test.assertEquals

class OrderMathTest {

    private fun cents(value: Double): Long = kotlin.math.round(value * 100).toLong()

    private fun assertSharesAddUp(subtotals: List<Double>, tax: Double, shipping: Double) {
        val shares = allocateOrderCosts(subtotals, tax, shipping)
        assertEquals(subtotals.size, shares.size)
        assertEquals(cents(tax), shares.sumOf { cents(it.first) }, "tax shares of $subtotals")
        assertEquals(cents(shipping), shares.sumOf { cents(it.second) }, "shipping shares of $subtotals")
        shares.forEach { (t, s) ->
            assertEquals(cents(t) / 100.0, t, 1e-9)
            assertEquals(cents(s) / 100.0, s, 1e-9)
        }
    }

    @Test
    fun `shares are proportional to subtotal`() {
        val shares = allocateOrderCosts(listOf(30.0, 10.0), tax = 2.40, shipping = 8.0)
        assertEquals(listOf(1.80 to 6.0, 0.60 to 2.0), shares)
    }

    @Test
    fun `shares always add up exactly to the order's tax and shipping`() {
        assertSharesAddUp(listOf(10.0, 10.0, 10.0), tax = 1.80, shipping = 5.0)
        assertSharesAddUp(listOf(19.99, 24.99, 7.49), tax = 3.15, shipping = 6.99)
        assertSharesAddUp(listOf(0.01, 99.99), tax = 6.0, shipping = 0.01)
        assertSharesAddUp((1..37).map { it * 1.37 }, tax = 61.07, shipping = 12.34)
    }

    @Test
    fun `leftover cents go to the lines whose share was cut the most`() {
        // $5 over three equal lines is 1.666... each: two lines get the extra cent
        val shipping = allocateOrderCosts(listOf(10.0, 10.0, 10.0), tax = 0.0, shipping = 5.0).map { it.second }
        assertEquals(listOf(1.67, 1.67, 1.66), shipping)
    }

    @Test
    fun `zero subtotals split costs evenly`() {
        val shares = allocateOrderCosts(listOf(0.0, 0.0), tax = 0.0, shipping = 5.0)
        assertEquals(listOf(0.0 to 2.50, 0.0 to 2.50), shares)
    }

    @Test
    fun `a free item on a paid order carries no share`() {
        val shares = allocateOrderCosts(listOf(20.0, 0.0), tax = 1.20, shipping = 4.0)
        assertEquals(listOf(1.20 to 4.0, 0.0 to 0.0), shares)
    }

    @Test
    fun `an order of one keeps the whole tax and shipping`() {
        assertEquals(listOf(1.88 to 4.99), allocateOrderCosts(listOf(31.32), tax = 1.88, shipping = 4.99))
    }

    @Test
    fun `no lines means no shares`() {
        assertEquals(emptyList(), allocateOrderCosts(emptyList(), tax = 1.0, shipping = 1.0))
    }

    @Test
    fun `order tax is computed once on the summed subtotal`() {
        assertEquals(1.80, orderTax(listOf(9.99, 9.99, 9.99), 0.06))
        // 3 x 0.25 at 6%: per line 0.02 each (0.06 total) but once on 0.75 is 0.05
        assertEquals(0.05, orderTax(listOf(0.25, 0.25, 0.25), 0.06))
    }

    @Test
    fun `an override replaces the computed tax`() {
        assertEquals(0.0, orderTax(listOf(50.0, 25.0), 0.06, override = 0.0))
        assertEquals(4.51, orderTax(listOf(50.0, 25.0), 0.06, override = 4.51))
        assertEquals(4.50, orderTax(listOf(50.0, 25.0), 0.06))
    }

    @Test
    fun `order total equals the sum of its items' totals`() {
        val subtotals = listOf(19.99, 24.99, 7.49, 34.98)
        val order = Order(
            taxRate = 0.06,
            shipping = 9.97,
            lines = subtotals.map { OrderLine(subtotal = it) }
        )
        val shares = allocateOrderCosts(subtotals, order.effectiveTax, order.shipping)
        val itemTotals = subtotals.zip(shares).map { (subtotal, share) ->
            Purchase(subtotal = subtotal, taxAmount = share.first, shipping = share.second).total
        }
        assertEquals(cents(order.total), itemTotals.sumOf { cents(it) })
    }

    @Test
    fun `order total uses the override when set`() {
        val order = Order(taxRate = 0.06, taxAmount = 0.0, shipping = 5.0, lines = listOf(OrderLine(10.0), OrderLine(20.0)))
        assertEquals(30.0, order.subtotal)
        assertEquals(0.0, order.effectiveTax)
        assertEquals(35.0, order.total)
    }
}
