package org.rsmod.content.other.grandexchange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeBookTest {
    @Test
    fun crossingBuyFillsRestingSellAtRestingPrice() {
        val book = GeBook()
        val sell =
            book.placeSell(
                accountId = 2,
                slot = 0,
                itemId = 4151,
                quantity = 2,
                pricePer = 1_500_000,
                now = 1,
            )
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 4151,
                quantity = 1,
                pricePer = 2_000_000,
                now = 2,
            )
        assertEquals(1, buy.trades.size)
        assertEquals(1_500_000, buy.trades[0].pricePer)
        assertEquals(2, buy.trades[0].sellerAccountId)
        assertEquals(500_000L, book.buyerRefund(buy.offer))
        val tax = 1_500_000L * 2 / 100
        assertEquals(1_500_000L - tax, book.sellerProceeds(sell.offer))
    }

    @Test
    fun partialFillLeavesRemainderActive() {
        val book = GeBook()
        book.placeSell(accountId = 2, slot = 0, itemId = 995, quantity = 10, pricePer = 1, now = 1)
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 995,
                quantity = 4,
                pricePer = 1,
                now = 2,
            )
        assertEquals(4, buy.offer.itemsFilled)
        assertEquals(GeOfferState.COMPLETED, buy.offer.state)
        val sell = book.activeOffersForItem(995).single()
        assertEquals(6, sell.remaining)
        assertEquals(GeOfferState.ACTIVE, sell.state)
    }

    @Test
    fun nonCrossingOffersDoNotTrade() {
        val book = GeBook()
        book.placeSell(
            accountId = 2,
            slot = 0,
            itemId = 4151,
            quantity = 1,
            pricePer = 3_000_000,
            now = 1,
        )
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 4151,
                quantity = 1,
                pricePer = 2_000_000,
                now = 2,
            )
        assertTrue(buy.trades.isEmpty())
        assertEquals(1, buy.offer.remaining)
        assertEquals(0L, buy.offer.goldSpent)
    }

    @Test
    fun cheapItemsAreTaxExemptAndCapAppliesToHugeTrades() {
        val book = GeBook()
        val sellCheap =
            book.placeSell(
                accountId = 2,
                slot = 0,
                itemId = 319,
                quantity = 100,
                pricePer = 10,
                now = 1,
            )
        book.placeBuy(accountId = 1, slot = 0, itemId = 319, quantity = 100, pricePer = 10, now = 2)
        assertEquals(0L, sellCheap.offer.taxPaid)

        val book2 = GeBook()
        val sellBig =
            book2.placeSell(
                accountId = 2,
                slot = 0,
                itemId = 1,
                quantity = 10_000,
                pricePer = 1_000_000,
                now = 1,
            )
        book2.placeBuy(
            accountId = 1,
            slot = 0,
            itemId = 1,
            quantity = 10_000,
            pricePer = 1_000_000,
            now = 2,
        )
        assertEquals(GeTypes.TAX_CAP, sellBig.offer.taxPaid)
    }

    @Test
    fun priceTimePriority() {
        val book = GeBook()
        book.placeSell(accountId = 3, slot = 0, itemId = 2, quantity = 1, pricePer = 120, now = 1)
        book.placeSell(accountId = 4, slot = 0, itemId = 2, quantity = 1, pricePer = 100, now = 2)
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 2,
                quantity = 1,
                pricePer = 200,
                now = 3,
            )
        assertEquals(4, buy.trades[0].sellerAccountId)
        assertEquals(100, buy.trades[0].pricePer)
    }
}
