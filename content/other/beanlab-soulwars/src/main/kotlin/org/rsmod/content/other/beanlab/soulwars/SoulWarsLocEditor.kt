package org.rsmod.content.other.beanlab.soulwars

import org.rsmod.api.type.editors.loc.LocEditor
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.game.type.util.CompactableIntArray

internal typealias soulwars_locs = SoulWarsLocs

internal object SoulWarsLocs : LocReferences() {
    val enter_portal = find("soul_wars_edgeville_portal")
    val leave_portal = find("soul_wars_leave_soulwars_portal")
}

/**
 * Recolors both Soul Wars portals purple. The cache colors the portal model with five source colors
 * (HSL16: 6 bits hue, 3 bits saturation, 7 bits lightness). Each is kept as is, with only its hue
 * swapped, so the shading of the swirl stays intact. Applied by `packCache`, which is what the
 * client sees; the server types get the same edit at startup.
 */
internal object SoulWarsLocEditor : LocEditor() {
    private const val PURPLE_HUE = 49
    private const val LIGHT_SAT_MASK = 0x3FF

    private val sources = intArrayOf(17073, 17069, 17063, 17059, 17057)
    private val originals = intArrayOf(9905, 9901, 10935, 11061, 11076)
    private val purples =
        IntArray(originals.size) { ((PURPLE_HUE shl 10) or (originals[it] and LIGHT_SAT_MASK)) }
            .map { it.toShort().toInt() }
            .toIntArray()

    init {
        for (type in listOf(soulwars_locs.enter_portal, soulwars_locs.leave_portal)) {
            edit(type) {
                recolS = CompactableIntArray(sources)
                recolD = CompactableIntArray(purples)
            }
        }
    }
}
