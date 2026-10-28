package org.rsmod.content.other.grandexchange

/**
 * Pure Grand Exchange matching engine: price-time priority, resting-offer pricing, 2% seller tax
 * (exempt below 100 gp per item, capped at 5m per trade), escrowed buy offers with refunds, partial
 * fills.
 */
public object GeTypes {
    public const val TAX_RATE_NUMERATOR: Long = 2
    public const val TAX_RATE_DENOMINATOR: Long = 100
    public const val TAX_CAP: Long = 5_000_000L
    public const val TAX_EXEMPT_BELOW_PRICE: Long = 100L
    public const val MAX_SLOTS_PER_ACCOUNT: Int = 8
}

public enum class GeOfferType {
    BUY,
    SELL,
}

public enum class GeOfferState {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    COLLECTED,
}

public data class GeOffer(
    public val id: Long,
    public val accountId: Int,
    public val slot: Int,
    public val type: GeOfferType,
    public val itemId: Int,
    public val quantity: Int,
    public var remaining: Int,
    public val pricePer: Long,
    public var goldEscrow: Long,
    public var goldSpent: Long = 0L,
    public var proceedsGold: Long = 0L,
    public var itemsFilled: Int = 0,
    public var taxPaid: Long = 0L,
    public var state: GeOfferState = GeOfferState.ACTIVE,
    public val createdAtMs: Long,
)

public data class GeTrade(
    public val itemId: Int,
    public val qty: Int,
    public val pricePer: Long,
    public val gross: Long,
    public val tax: Long,
    public val netToSeller: Long,
    public val buyerAccountId: Int,
    public val sellerAccountId: Int,
)

public data class GePlacement(public val offer: GeOffer, public val trades: List<GeTrade>) {
    public val filledImmediately: Int
        get() = offer.itemsFilled

    public val proceedsGold: Long
        get() = offer.proceedsGold
}

public class GeBook {
    private val offers = LinkedHashMap<Long, GeOffer>()
    private var nextId = 1L

    public fun activeOffersForAccount(accountId: Int): List<GeOffer> =
        offers.values.filter { it.accountId == accountId && it.state == GeOfferState.ACTIVE }

    /** All offers for an account that still hold collectables (not yet collected). */
    public fun pendingOffersForAccount(accountId: Int): List<GeOffer> =
        offers.values.filter { it.accountId == accountId && it.state != GeOfferState.COLLECTED }

    public fun offer(id: Long): GeOffer? = offers[id]

    public fun activeOffersForItem(itemId: Int): List<GeOffer> =
        offers.values.filter { it.itemId == itemId && it.state == GeOfferState.ACTIVE }

    /** Snapshot of every offer currently tracked by the book. */
    public fun allOffers(): List<GeOffer> = offers.values.toList()

    /** Restores an offer persisted in a previous server run. */
    public fun restore(offer: GeOffer) {
        offers[offer.id] = offer
        ensureNextIdAbove(offer.id)
    }

    public fun ensureNextIdAbove(id: Long) {
        if (id >= nextId) {
            nextId = id + 1
        }
    }

    public fun placeBuy(
        accountId: Int,
        slot: Int,
        itemId: Int,
        quantity: Int,
        pricePer: Long,
        now: Long,
    ): GePlacement {
        val offer = createOffer(GeOfferType.BUY, accountId, slot, itemId, quantity, pricePer, now)
        offers[offer.id] = offer
        val trades = match(offer)
        finishIfDone(offer)
        return GePlacement(offer, trades)
    }

    public fun placeSell(
        accountId: Int,
        slot: Int,
        itemId: Int,
        quantity: Int,
        pricePer: Long,
        now: Long,
    ): GePlacement {
        val offer = createOffer(GeOfferType.SELL, accountId, slot, itemId, quantity, pricePer, now)
        offers[offer.id] = offer
        val trades = match(offer)
        finishIfDone(offer)
        return GePlacement(offer, trades)
    }

    private fun createOffer(
        type: GeOfferType,
        accountId: Int,
        slot: Int,
        itemId: Int,
        quantity: Int,
        pricePer: Long,
        now: Long,
    ): GeOffer =
        GeOffer(
            id = nextId++,
            accountId = accountId,
            slot = slot,
            type = type,
            itemId = itemId,
            quantity = quantity,
            remaining = quantity,
            pricePer = pricePer,
            goldEscrow = if (type == GeOfferType.BUY) quantity.toLong() * pricePer else 0L,
            createdAtMs = now,
        )

    public fun cancel(offerId: Long): GeOffer? {
        val offer = offers[offerId] ?: return null
        if (offer.state != GeOfferState.ACTIVE) {
            return null
        }
        offer.state = GeOfferState.CANCELLED
        return offer
    }

    public fun markCollected(offerId: Long) {
        offers[offerId]?.state = GeOfferState.COLLECTED
    }

    /** Buyer refund: escrow not spent on fills. */
    public fun buyerRefund(offer: GeOffer): Long = offer.goldEscrow - offer.goldSpent

    /** Seller collect: net proceeds after tax. */
    public fun sellerProceeds(offer: GeOffer): Long = offer.proceedsGold

    private fun match(incoming: GeOffer): List<GeTrade> {
        val trades = mutableListOf<GeTrade>()
        val resting =
            offers.values
                .filter {
                    it.state == GeOfferState.ACTIVE &&
                        it.id != incoming.id &&
                        it.itemId == incoming.itemId &&
                        it.type != incoming.type
                }
                .sortedWith(
                    compareBy(
                        { if (incoming.type == GeOfferType.BUY) it.pricePer else -it.pricePer },
                        { it.createdAtMs },
                        { it.id },
                    )
                )
        for (other in resting) {
            if (incoming.remaining == 0) {
                break
            }
            val crosses =
                if (incoming.type == GeOfferType.BUY) {
                    other.pricePer <= incoming.pricePer
                } else {
                    other.pricePer >= incoming.pricePer
                }
            if (!crosses) {
                break
            }
            val qty = minOf(incoming.remaining, other.remaining)
            val pricePer = other.pricePer
            val gross = qty.toLong() * pricePer
            val tax = taxOn(gross, pricePer)
            val net = gross - tax
            when (incoming.type) {
                GeOfferType.BUY -> {
                    incoming.goldSpent += gross
                    incoming.remaining -= qty
                    incoming.itemsFilled += qty
                }
                GeOfferType.SELL -> {
                    incoming.proceedsGold += net
                    incoming.remaining -= qty
                    incoming.itemsFilled += qty
                    incoming.taxPaid += tax
                }
            }
            when (other.type) {
                GeOfferType.BUY -> {
                    other.goldSpent += gross
                    other.remaining -= qty
                    other.itemsFilled += qty
                }
                GeOfferType.SELL -> {
                    other.proceedsGold += net
                    other.remaining -= qty
                    other.itemsFilled += qty
                    other.taxPaid += tax
                }
            }
            val buyer = if (incoming.type == GeOfferType.BUY) incoming else other
            val seller = if (incoming.type == GeOfferType.BUY) other else incoming
            trades.add(
                GeTrade(
                    itemId = incoming.itemId,
                    qty = qty,
                    pricePer = pricePer,
                    gross = gross,
                    tax = tax,
                    netToSeller = net,
                    buyerAccountId = buyer.accountId,
                    sellerAccountId = seller.accountId,
                )
            )
            finishIfDone(other)
        }
        return trades
    }

    private fun finishIfDone(offer: GeOffer) {
        if (offer.remaining == 0 && offer.state == GeOfferState.ACTIVE) {
            offer.state = GeOfferState.COMPLETED
        }
    }

    private fun taxOn(gross: Long, pricePer: Long): Long {
        if (pricePer < GeTypes.TAX_EXEMPT_BELOW_PRICE) {
            return 0L
        }
        val raw = gross * GeTypes.TAX_RATE_NUMERATOR / GeTypes.TAX_RATE_DENOMINATOR
        return minOf(raw, GeTypes.TAX_CAP)
    }
}
