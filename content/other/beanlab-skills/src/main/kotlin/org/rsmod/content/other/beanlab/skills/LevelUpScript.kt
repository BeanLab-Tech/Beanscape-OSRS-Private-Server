package org.rsmod.content.other.beanlab.skills

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.script.advanced.onAdvanceStat
import org.rsmod.api.script.onEvent
import org.rsmod.game.type.spot.SpotanimType
import org.rsmod.game.type.spot.SpotanimTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.game.type.synth.SynthType
import org.rsmod.game.type.synth.SynthTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Shows a level-up. Experience already raises the skill and queues [EngineQueueType.AdvanceStat];
 * nothing upstream listens, so the chat line, the sound and the fireworks never played.
 */
class LevelUpScript
@Inject
constructor(
    private val statTypes: StatTypeList,
    private val spotTypes: SpotanimTypeList,
    private val synthTypes: SynthTypeList,
) : PluginScript() {
    private var fireworks: SpotanimType? = null
    private var jingle: SynthType? = null

    override fun ScriptContext.startup() {
        onAdvanceStat { announce(it.args) }
        onEvent<GameLifecycle.Startup> {
            fireworks = spotTypes[FIREWORKS]
            jingle = synthTypes.values.firstOrNull { it.internalName == "levelup" }
        }
    }

    private fun ProtectedAccess.announce(stat: StatType) {
        val unpacked = statTypes[stat]
        val level = player.statBase(unpacked)
        val name = unpacked.displayName
        val article = if (name.first().lowercaseChar() in "aeiou") "an" else "a"
        fireworks?.let { spotanim(it) }
        jingle?.let { soundSynth(it) }
        mes("Congratulations, you've just advanced $article $name level.")
        mes("Your $name level is now $level.")
    }

    private companion object {
        const val FIREWORKS = 199
    }
}
