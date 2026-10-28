package org.rsmod.content.other.grandexchange

import jakarta.inject.Inject
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onCommand
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Process-wide Grand Exchange state, restored from the database on startup. */
public object GeState {
    public val book: GeBook = GeBook()
}

public class GeCommands
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val db: GameDbManager,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {

    override fun ScriptContext.startup() {
        onCommand("ge") {
            desc = "Grand Exchange: buy, sell, offers, cancel, collect, price"
            cheat { ge(this) }
        }
        restoreOffers()
    }

    private fun ge(cheat: Cheat) =
        with(cheat) {
            val parts = args.toList()
            val launched = protectedAccess.launch(player) { handleGe(parts) }
            if (!launched) {
                player.mes("You are busy - try again shortly.")
            }
        }

    private suspend fun ProtectedAccess.handleGe(parts: List<String>) {
        when (parts.firstOrNull()?.lowercase()) {
            "buy" -> buy(parts)
            "sell" -> sell(parts)
            "offers" -> listOffers()
            "cancel" -> cancel(parts)
            "collect" -> collect()
            "price" -> price(parts)
            else ->
                mes(
                    "Grand Exchange: ::ge buy <id> <qty> <price>, ::ge sell <id> <qty> <price>, " +
                        "::ge offers, ::ge cancel <slot>, ::ge collect, ::ge price <id>"
                )
        }
    }

    private suspend fun ProtectedAccess.buy(parts: List<String>) {
        if (parts.size < 4) {
            mes("Usage: ::ge buy <item id> <qty> <price each>")
            return
        }
        val itemId = parts[1].toIntOrNull()
        val qty = parts[2].toIntOrNull()
        val price = parts[3].toLongOrNull()
        if (itemId == null || qty == null || price == null || qty <= 0 || price <= 0) {
            mes("Invalid item id, quantity or price.")
            return
        }
        val type = objTypes[itemId]
        if (type == null) {
            mes("Unknown item id: $itemId")
            return
        }
        val accountId = player.accountId
        val active = GeState.book.activeOffersForAccount(accountId)
        if (active.size >= GeTypes.MAX_SLOTS_PER_ACCOUNT) {
            mes(
                "You already have ${active.size} active offers (max ${GeTypes.MAX_SLOTS_PER_ACCOUNT})."
            )
            return
        }
        val totalCost = qty.toLong() * price
        if (totalCost > Int.MAX_VALUE) {
            mes("Order value too large - split it into smaller orders.")
            return
        }
        if (invCoinTotal() < totalCost) {
            mes("You need ${totalCost} coins but only have ${invCoinTotal()}.")
            return
        }
        if (!invTakeFee(totalCost.toInt())) {
            mes("Not enough coins to escrow the buy order.")
            return
        }
        val slot = freeSlot(accountId)
        val placement =
            GeState.book.placeBuy(
                accountId = accountId,
                slot = slot,
                itemId = itemId,
                quantity = qty,
                pricePer = price,
                now = System.currentTimeMillis(),
            )
        persistOffer(placement.offer)
        persistTrades(placement.trades)
        updateResting(placement.trades)
        val name = itemName(type)
        if (placement.offer.state == GeOfferState.COMPLETED) {
            mes(
                "Buy order filled instantly: ${placement.offer.itemsFilled} x ${name}. ::ge collect to claim."
            )
        } else {
            mes("Buy offer placed: ${qty} x ${name} @ ${price} gp each.")
        }
    }

    private suspend fun ProtectedAccess.sell(parts: List<String>) {
        if (parts.size < 4) {
            mes("Usage: ::ge sell <item id> <qty> <price each>")
            return
        }
        val itemId = parts[1].toIntOrNull()
        val qty = parts[2].toIntOrNull()
        val price = parts[3].toLongOrNull()
        if (itemId == null || qty == null || price == null || qty <= 0 || price <= 0) {
            mes("Invalid item id, quantity or price.")
            return
        }
        val type = objTypes[itemId]
        if (type == null) {
            mes("Unknown item id: $itemId")
            return
        }
        val accountId = player.accountId
        val active = GeState.book.activeOffersForAccount(accountId)
        if (active.size >= GeTypes.MAX_SLOTS_PER_ACCOUNT) {
            mes(
                "You already have ${active.size} active offers (max ${GeTypes.MAX_SLOTS_PER_ACCOUNT})."
            )
            return
        }
        if (invTotal(inv, type) < qty) {
            mes("You only have ${invTotal(inv, type)} x ${itemName(type)}.")
            return
        }
        invDel(inv, type, qty)
        val slot = freeSlot(accountId)
        val placement =
            GeState.book.placeSell(
                accountId = accountId,
                slot = slot,
                itemId = itemId,
                quantity = qty,
                pricePer = price,
                now = System.currentTimeMillis(),
            )
        persistOffer(placement.offer)
        persistTrades(placement.trades)
        updateResting(placement.trades)
        val name = itemName(type)
        if (placement.offer.state == GeOfferState.COMPLETED) {
            val net = GeState.book.sellerProceeds(placement.offer)
            mes(
                "Sell order filled instantly: ${placement.offer.itemsFilled} x ${name}. Collect ${net} gp via ::ge collect."
            )
        } else {
            mes("Sell offer placed: ${qty} x ${name} @ ${price} gp each.")
        }
    }

    private suspend fun ProtectedAccess.listOffers() {
        val accountId = player.accountId
        val active = GeState.book.activeOffersForAccount(accountId)
        if (active.isEmpty()) {
            mes("You have no active Grand Exchange offers.")
            return
        }
        mes("Your offers (slot | type | item | progress @ price):")
        for (offer in active) {
            val name = itemName(objTypes[offer.itemId])
            mes(
                "[${offer.slot}] ${offer.type} ${name}: " +
                    "${offer.quantity - offer.remaining}/${offer.quantity} @ ${offer.pricePer} gp"
            )
        }
    }

    private suspend fun ProtectedAccess.cancel(parts: List<String>) {
        val slot = parts.getOrNull(1)?.toIntOrNull()
        if (slot == null) {
            mes("Usage: ::ge cancel <slot>")
            return
        }
        val accountId = player.accountId
        val offer = GeState.book.activeOffersForAccount(accountId).firstOrNull { it.slot == slot }
        if (offer == null) {
            mes("No active offer in slot $slot.")
            return
        }
        GeState.book.cancel(offer.id)
        persistState(offer)
        when (offer.type) {
            GeOfferType.BUY -> {
                val refund = GeState.book.buyerRefund(offer)
                mes("Offer cancelled. ::ge collect to reclaim ${refund} gp and any items.")
            }
            GeOfferType.SELL -> {
                mes("Offer cancelled. ::ge collect to reclaim your ${offer.remaining} items.")
            }
        }
    }

    private suspend fun ProtectedAccess.collect() {
        val accountId = player.accountId
        val pending = GeState.book.pendingOffersForAccount(accountId)
        if (pending.isEmpty()) {
            mes("Nothing to collect at the Grand Exchange.")
            return
        }
        val coins = objTypes[COINS] ?: return
        var goldOut = 0L
        var itemsOut = 0
        var itemTypesOut = mutableMapOf<Int, Int>()
        for (offer in pending) {
            if (offer.state == GeOfferState.ACTIVE) {
                // Active offers keep working; only completed/cancelled are collected.
                continue
            }
            when (offer.type) {
                GeOfferType.BUY -> {
                    if (offer.itemsFilled > 0) {
                        val type = objTypes[offer.itemId]
                        if (type != null && invAddOrDrop(objRepo, type, offer.itemsFilled)) {
                            itemsOut += offer.itemsFilled
                            itemTypesOut[offer.itemId] =
                                (itemTypesOut[offer.itemId] ?: 0) + offer.itemsFilled
                        }
                    }
                    goldOut += GeState.book.buyerRefund(offer)
                }
                GeOfferType.SELL -> {
                    goldOut += GeState.book.sellerProceeds(offer)
                    if (offer.remaining > 0) {
                        val type = objTypes[offer.itemId]
                        if (type != null && invAddOrDrop(objRepo, type, offer.remaining)) {
                            itemsOut += offer.remaining
                            itemTypesOut[offer.itemId] =
                                (itemTypesOut[offer.itemId] ?: 0) + offer.remaining
                        }
                    }
                }
            }
            GeState.book.markCollected(offer.id)
            persistState(offer)
        }
        if (goldOut > 0) {
            if (goldOut > Int.MAX_VALUE) {
                mes("Collection too large - contact an admin.")
                return
            }
            invAddOrDrop(objRepo, coins, goldOut.toInt())
        }
        mes("Collected: ${goldOut} gp, ${itemsOut} item(s).")
    }

    private suspend fun ProtectedAccess.price(parts: List<String>) {
        val itemId = parts.getOrNull(1)?.toIntOrNull()
        if (itemId == null) {
            mes("Usage: ::ge price <item id>")
            return
        }
        val type = objTypes[itemId]
        if (type == null) {
            mes("Unknown item id: $itemId")
            return
        }
        val name = itemName(type)
        db.request(
            request = { conn -> GameDbResult.Ok(recentPrices(conn, itemId)) },
            response = { result ->
                result.fold(
                    onOk = { rows ->
                        if (rows.isEmpty()) {
                            player.mes("No recent trades for ${name}.")
                        } else {
                            player.mes("Recent trades for ${name}:")
                            for (row in rows) {
                                player.mes("  ${row.second} x @ ${row.first} gp")
                            }
                        }
                    },
                    onErr = { player.mes("Database error reading prices.") },
                )
            },
        )
    }

    private fun freeSlot(accountId: Int): Int {
        val used = GeState.book.activeOffersForAccount(accountId).map { it.slot }.toSet()
        for (slot in 0 until GeTypes.MAX_SLOTS_PER_ACCOUNT) {
            if (slot !in used) {
                return slot
            }
        }
        return GeTypes.MAX_SLOTS_PER_ACCOUNT - 1
    }

    private fun itemName(type: org.rsmod.game.type.obj.ObjType?): String =
        type?.internalName ?: "item"

    private fun persistOffer(offer: GeOffer) {
        db.request(
            request = { conn -> GameDbResult.Ok(insertOffer(conn, offer)) },
            response = { _ -> },
        )
    }

    private fun persistTrades(trades: List<GeTrade>) {
        if (trades.isEmpty()) {
            return
        }
        db.request(
            request = { conn ->
                for (trade in trades) {
                    insertTrade(conn, trade)
                }
                GameDbResult.Ok(trades.size)
            },
            response = { _ -> },
        )
    }

    private fun updateResting(trades: List<GeTrade>) {
        if (trades.isEmpty()) {
            return
        }
        val touched = trades.map { listOf(it.buyerAccountId, it.sellerAccountId) }.flatten().toSet()
        for (offer in GeState.book.allOffers()) {
            if (offer.accountId in touched && offer.state != GeOfferState.ACTIVE) {
                persistState(offer)
            }
        }
    }

    private fun persistState(offer: GeOffer) {
        db.request(
            request = { conn -> GameDbResult.Ok(updateOfferState(conn, offer)) },
            response = { _ -> },
        )
    }

    private fun restoreOffers() {
        db.request(
            request = { conn -> GameDbResult.Ok(loadOffers(conn)) },
            response = { result ->
                result.fold(
                    onOk = { offers ->
                        for (offer in offers) {
                            GeState.book.restore(offer)
                        }
                    },
                    onErr = {},
                )
            },
        )
    }

    private fun insertOffer(conn: DatabaseConnection, offer: GeOffer): Int {
        val stmt =
            conn.prepareStatement(
                "INSERT OR REPLACE INTO ge_offers " +
                    "(id, account_id, slot, type, item_id, quantity, remaining, price_per, " +
                    "gold_escrow, gold_spent, proceeds_gold, items_filled, tax_paid, state) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
        stmt.setLong(1, offer.id)
        stmt.setInt(2, offer.accountId)
        stmt.setInt(3, offer.slot)
        stmt.setString(4, offer.type.name)
        stmt.setInt(5, offer.itemId)
        stmt.setInt(6, offer.quantity)
        stmt.setInt(7, offer.remaining)
        stmt.setLong(8, offer.pricePer)
        stmt.setLong(9, offer.goldEscrow)
        stmt.setLong(10, offer.goldSpent)
        stmt.setLong(11, offer.proceedsGold)
        stmt.setInt(12, offer.itemsFilled)
        stmt.setLong(13, offer.taxPaid)
        stmt.setString(14, offer.state.name)
        val rows = stmt.executeUpdate()
        stmt.close()
        return rows
    }

    private fun insertTrade(conn: DatabaseConnection, trade: GeTrade) {
        val stmt =
            conn.prepareStatement(
                "INSERT INTO ge_history " +
                    "(item_id, quantity, price_per, gross, tax, buyer_account_id, seller_account_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)"
            )
        stmt.setInt(1, trade.itemId)
        stmt.setInt(2, trade.qty)
        stmt.setLong(3, trade.pricePer)
        stmt.setLong(4, trade.gross)
        stmt.setLong(5, trade.tax)
        stmt.setInt(6, trade.buyerAccountId)
        stmt.setInt(7, trade.sellerAccountId)
        stmt.executeUpdate()
        stmt.close()
    }

    private fun updateOfferState(conn: DatabaseConnection, offer: GeOffer): Int {
        val stmt =
            conn.prepareStatement(
                "UPDATE ge_offers SET state = ?, remaining = ?, gold_spent = ?, proceeds_gold = ?, " +
                    "items_filled = ?, tax_paid = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
            )
        stmt.setString(1, offer.state.name)
        stmt.setInt(2, offer.remaining)
        stmt.setLong(3, offer.goldSpent)
        stmt.setLong(4, offer.proceedsGold)
        stmt.setInt(5, offer.itemsFilled)
        stmt.setLong(6, offer.taxPaid)
        stmt.setLong(7, offer.id)
        val rows = stmt.executeUpdate()
        stmt.close()
        return rows
    }

    private fun loadOffers(conn: DatabaseConnection): List<GeOffer> {
        val stmt =
            conn.prepareStatement(
                "SELECT id, account_id, slot, type, item_id, quantity, remaining, price_per, " +
                    "gold_escrow, gold_spent, proceeds_gold, items_filled, tax_paid, state, " +
                    "CAST(strftime('%s', created_at) AS INTEGER) * 1000 FROM ge_offers " +
                    "WHERE state IN ('ACTIVE', 'COMPLETED', 'CANCELLED')"
            )
        val rs = stmt.executeQuery()
        val offers = mutableListOf<GeOffer>()
        while (rs.next()) {
            val offer =
                GeOffer(
                    id = rs.getLong(1),
                    accountId = rs.getInt(2),
                    slot = rs.getInt(3),
                    type = GeOfferType.valueOf(rs.getString(4)),
                    itemId = rs.getInt(5),
                    quantity = rs.getInt(6),
                    remaining = rs.getInt(7),
                    pricePer = rs.getLong(8),
                    goldEscrow = rs.getLong(9),
                    goldSpent = rs.getLong(10),
                    proceedsGold = rs.getLong(11),
                    itemsFilled = rs.getInt(12),
                    taxPaid = rs.getLong(13),
                    state = GeOfferState.valueOf(rs.getString(14)),
                    createdAtMs = rs.getLong(15),
                )
            offers.add(offer)
        }
        rs.close()
        stmt.close()
        return offers
    }

    private fun recentPrices(conn: DatabaseConnection, itemId: Int): List<Pair<Long, Int>> {
        val stmt =
            conn.prepareStatement(
                "SELECT price_per, quantity FROM ge_history WHERE item_id = ? " +
                    "ORDER BY traded_at DESC LIMIT 5"
            )
        stmt.setInt(1, itemId)
        val rs = stmt.executeQuery()
        val rows = mutableListOf<Pair<Long, Int>>()
        while (rs.next()) {
            rows.add(Pair(rs.getLong(1), rs.getInt(2)))
        }
        rs.close()
        stmt.close()
        return rows
    }

    private companion object {
        const val COINS = 995
    }
}
