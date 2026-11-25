package org.rsmod.content.other.beanlab

import org.rsmod.game.inv.Inventory
import org.rsmod.game.type.inv.InvScope
import org.rsmod.game.type.inv.InvStackType
import org.rsmod.game.type.inv.InvStock
import org.rsmod.game.type.inv.InvTypeList
import org.rsmod.game.type.inv.UnpackedInvType
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType

/**
 * Opens each shopkeeper on that shop's own stock.
 *
 * Revision 233 keeps the shop inventory names (`axeshop`, `runeshop`, `generalshop3`) but the stock
 * lists are not in the cache. The lists here are the shop stocks from the Lost City content pack,
 * which uses those same inventory names. A keeper is matched by the name the older shop lists used,
 * or by cache name (`generalshopkeeper4` to `generalshop4`).
 */
class ShopCatalog(invTypes: InvTypeList, objTypes: ObjTypeList) {
    private val objsByName: Map<String, UnpackedObjType> =
        objTypes.values.mapNotNull { obj -> obj.internalName?.let { it to obj } }.toMap()

    private val typesByName: Map<String, UnpackedInvType> =
        invTypes.values
            .mapNotNull { type -> type.internalName?.lowercase()?.let { it to type } }
            .toMap()

    private val recipes: Map<String, ShopRecipe> = loadRecipes()
    private val inventories = HashMap<String, Inventory>()

    val recipeCount: Int
        get() = recipes.size

    fun inventoryFor(npc: UnpackedNpcType): Inventory? {
        val shopName = shopName(npc) ?: return null
        inventories[shopName]?.let {
            return it
        }
        val type = typesByName[shopName] ?: return null
        val recipe = recipes[shopName]
        val inv =
            when {
                recipe != null -> build(type, recipe)
                type.stock?.any { it != null } == true -> Inventory.create(type)
                else -> return null
            }
        inventories[shopName] = inv
        return inv
    }

    fun isGeneral(inv: Inventory): Boolean {
        if (inv.type.allStock) return true
        val name = inv.type.internalName?.lowercase() ?: return false
        return "general" in name
    }

    private fun shopName(npc: UnpackedNpcType): String? {
        val name = npc.internalName?.lowercase() ?: return null
        val options = ArrayList<String>()
        ALIASES[name]?.let { options += it }
        options += candidates(name)
        return options.firstOrNull { it in recipes || typesByName[it].hasStock() }
    }

    private fun UnpackedInvType?.hasStock(): Boolean = this?.stock?.any { it != null } == true

    private fun build(type: UnpackedInvType, recipe: ShopRecipe): Inventory {
        val size = maxOf(type.size, recipe.size, recipe.rows.maxOf { it.slot + 1 })
        val stock = arrayOfNulls<InvStock>(size)
        for (row in recipe.rows) {
            val obj = objsByName[row.obj] ?: continue
            if (row.slot !in stock.indices) continue
            stock[row.slot] = InvStock(obj, row.count, row.cycles)
        }
        var flags = type.flags or UnpackedInvType.RESTOCK_FLAG
        if (recipe.allStock) flags = flags or UnpackedInvType.ALL_STOCK_FLAG
        val typed =
            type.copy(
                scope = InvScope.Shared,
                stack = InvStackType.Always,
                size = size,
                flags = flags,
                stock = stock,
            )
        return Inventory.create(typed)
    }

    private fun loadRecipes(): Map<String, ShopRecipe> {
        val text =
            ShopCatalog::class.java.getResourceAsStream("shops.txt")?.bufferedReader()?.use {
                it.readText()
            } ?: return emptyMap()
        val recipes = HashMap<String, ShopRecipe>()
        var current: ShopRecipe? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                current = null
                continue
            }
            val parts = line.split(' ')
            if (
                parts.size == 3 && parts[1].toIntOrNull() != null && parts[2].toIntOrNull() != null
            ) {
                val recipe = ShopRecipe(parts[1].toInt(), parts[2] == "1")
                recipes[parts[0]] = recipe
                current = recipe
                continue
            }
            val recipe = current ?: continue
            if (parts.size != 4) continue
            val slot = parts[0].toIntOrNull() ?: continue
            val count = parts[2].toIntOrNull() ?: continue
            val cycles = parts[3].toIntOrNull() ?: continue
            recipe.rows += ShopRow(slot, parts[1], count, cycles)
        }
        return recipes
    }

    private class ShopRecipe(val size: Int, val allStock: Boolean) {
        val rows = ArrayList<ShopRow>()
    }

    private data class ShopRow(val slot: Int, val obj: String, val count: Int, val cycles: Int)

    private fun candidates(name: String): List<String> {
        val keys = LinkedHashSet<String>()
        GENERAL.matchEntire(name)?.let { keys += "generalshop${it.groupValues[1]}" }
        keys += name
        keys += name.replace("shopkeeper", "shop")
        keys += name.replace("assistant", "shop")
        keys += name.replace("_owner", "")
        keys += name.replace("merchant", "shop")
        keys += name.replace("seller", "shop")
        keys += name.replace("trader", "shop")
        val expanded = keys.toList()
        for (key in expanded) {
            keys += key.replace("_", "")
            NUMBERED.matchEntire(key)?.let { keys += it.groupValues[1].trimEnd('_') }
        }
        return keys.toList()
    }

    private companion object {
        val GENERAL = Regex("""^general(?:shopkeeper|assistant)(\d+)$""")
        val NUMBERED = Regex("""^(.+?)(\d+)$""")

        val ALIASES =
            mapOf(
                "bob" to "axeshop",
                "horvik_the_armourer" to "armourshop",
                "aubury" to "runeshop",
                "zaff" to "staffshop",
                "lowe" to "archeryshop",
                "thessalia" to "clotheshop",
                "thessalia_normal" to "clotheshop",
                "tailorp" to "clotheshop",
                "peksa" to "helmetshop",
                "zeke" to "scimitarshop",
                "louie_legs" to "legsshop",
                "cassie" to "shieldshop",
                "ranael" to "skirtshop",
                "valaine" to "championshop",
                "flynn" to "maceshop",
                "wydin" to "foodshop",
                "brian" to "battleaxeshop",
                "wayne" to "chainmailshop",
                "drogo" to "miningstore",
                "betty" to "magicshop",
                "herquin" to "gemshop",
                "gem_trader" to "gemshop2",
                "rommik" to "craftingshop",
                "dommik" to "craftingshop2",
                "grum" to "goldshop",
                "gerrant" to "fishingshop",
                "slayer_master_1_tureal" to "slayershop",
                "slayer_master_2_mazchna" to "slayershop",
                "slayer_master_3" to "slayershop",
                "slayer_master_4" to "slayershop",
                "slayer_master_5_duradel" to "slayershop",
                "slayer_master_5_kuradal" to "slayershop",
                "slayer_master_nieve" to "slayershop",
                "slayer_master_steve" to "slayershop",
                "slayer_master_7" to "slayershop",
                "slayer_master_8" to "slayershop",
                "slayer_master_9_active" to "slayershop",
                "harry" to "fishingshop2",
                "scavvo" to "runiteshop",
                "oziach" to "runeplateshop",
                "jakut" to "cheapringshop",
                "gaius" to "2handedshop",
                "jatix" to "herbloreshop",
                "frincos" to "herbloreshop2",
                "hickton" to "archeryshop2",
                "davon" to "amuletshop",
                "zenesha" to "topshop",
                "fancy_dan" to "fancyclothesstore",
                "jiminua" to "junglestore",
                "khazard_shopkeeper" to "khazardshop",
                "lathas_shopkeeper" to "lathastrainingstore",
                "nurmof" to "pickaxeshop",
                "tea_seller" to "teashop",
                "fat_tony" to "pizzabaseshop",
                "shantay" to "shantayshop",
                "bedabin" to "bedabincampshop",
                "nulodion" to "mcannonshop",
                "gnome_hudo" to "gnomeshop_hudo",
                "gnome_rometti" to "gnomeshop_rometti",
                "gnome_gulluck" to "gnomeshop_gulluck",
                "gnome_heckelfunch" to "gnomeshop_heck",
                "dwarf_city_shop_warhammer" to "keldagrim_warhammer_shop",
                "dwarf_city_shop_weapons" to "keldagrim_quality_weapons_shop",
                "dwarf_city_shop_armour" to "keldagrim_quality_armour_shop",
                "dwarf_city_shop_general_store" to "keldagrim_general_shop",
                "dwarf_city_shop_pickaxe" to "keldagrim_pickaxe_shop",
                "dwarf_city_shop_cloth" to "keldagrim_clothes_shop",
                "dwarf_city_shop_bakery" to "keldagrim_general_shop",
            )
    }
}
