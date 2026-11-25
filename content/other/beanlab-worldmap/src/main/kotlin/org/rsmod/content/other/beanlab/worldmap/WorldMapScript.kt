package org.rsmod.content.other.beanlab.worldmap

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

internal object WorldMapInterfaces : InterfaceReferences() {
    val worldmap = find("worldmap")
}

internal object WorldMapComponents : ComponentReferences() {
    /** The globe next to the minimap. */
    val orb = find("orbs:worldmap")
    val close = find("worldmap:close")
    val esckey = find("worldmap:esckey")
    val toggles = find("worldmap:toggles")

    /** Gameframe slot the world map opens in; translated for fixed and resizable layouts. */
    val target = find("toplevel_osrs_stretch:map_container")
}

/**
 * The world map. RsMod has no handler for the minimap globe, so it did nothing.
 *
 * Clicking the globe (any option) opens the cache's `worldmap` interface in the gameframe's map
 * slot; clicking it again, the map's close button or Escape closes it. While it is open the server
 * sends the player's position (clientscript `worldmap_transmitdata`) whenever they move, so the
 * "you are here" marker and the map follow the player. The fullscreen option opens the same
 * floating map.
 */
class WorldMapScript @Inject constructor(private val players: PlayerList) : PluginScript() {
    /** Players with the map open, and the last position sent to each. */
    private val open = WeakHashMap<Player, Int>()

    override fun ScriptContext.startup() {
        val bus = eventBus
        onIfOverlayButton(WorldMapComponents.orb) { toggle(player, bus) }
        onIfOverlayButton(WorldMapComponents.close) { close(player, bus) }
        onIfOverlayButton(WorldMapComponents.esckey) { close(player, bus) }
        onEvent<GameLifecycle.StartCycle> { transmitPositions() }
        onPlayerLogout { open.remove(player) }
    }

    private fun toggle(player: Player, bus: EventBus) {
        if (player.ui.containsOverlay(WorldMapInterfaces.worldmap)) {
            close(player, bus)
            return
        }
        player.ifOpenOverlay(WorldMapInterfaces.worldmap, WorldMapComponents.target, bus)
        player.ifSetEvents(WorldMapComponents.toggles, 0..4, IfEvent.Op1)
        transmit(player)
    }

    private fun close(player: Player, bus: EventBus) {
        open.remove(player)
        if (player.ui.containsOverlay(WorldMapInterfaces.worldmap)) {
            player.ifCloseOverlay(WorldMapInterfaces.worldmap, bus)
        }
    }

    private fun transmit(player: Player) {
        val coords = player.coords.packed
        open[player] = coords
        player.runClientScript(TRANSMIT_DATA, coords)
    }

    private fun transmitPositions() {
        if (open.isEmpty()) return
        for (player in players) {
            val last = open[player] ?: continue
            if (!player.ui.containsOverlay(WorldMapInterfaces.worldmap)) {
                open.remove(player)
                continue
            }
            if (player.coords.packed != last) transmit(player)
        }
    }

    private companion object {
        /** `[clientscript,worldmap_transmitdata]`: centres the map on a packed coordinate. */
        const val TRANSMIT_DATA = 1749
    }
}
