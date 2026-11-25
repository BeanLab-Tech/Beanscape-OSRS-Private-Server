package org.rsmod.content.other.beanlab.soulwars.map

import org.rsmod.api.type.builders.map.tile.MapTileBuilder
import org.rsmod.content.other.beanlab.soulwars.SoulWarsPortalScript

/**
 * Flat terrain for the four map squares the Soul Wars arena covers (see [SoulWarsPortalScript]).
 * Packed by the Gradle `packCache` task, not at server startup.
 */
object SoulWarsMapTiles : MapTileBuilder() {
    override fun onPackMapTask() {
        for (name in SoulWarsMapFiles.SQUARES) {
            resourceFile<SoulWarsPortalScript>("map/m$name")
        }
    }
}
