package org.rsmod.content.other.beanlab.soulwars.map

import org.rsmod.api.type.builders.map.loc.MapLocSpawnBuilder
import org.rsmod.content.other.beanlab.soulwars.SoulWarsPortalScript

/**
 * Empty loc files for the arena's map squares. The client asks for an `l` file next to every `m`
 * file, so each square gets one that holds no locs. The portals are spawned by the script.
 */
object SoulWarsMapLocs : MapLocSpawnBuilder() {
    override fun onPackMapTask() {
        for (name in SoulWarsMapFiles.SQUARES) {
            resourceFile<SoulWarsPortalScript>("map/l$name")
        }
    }
}
