package org.rsmod.content.other.beanlab.soulwars.map

internal object SoulWarsMapFiles {
    /** South-west map square of the arena (x, z). */
    const val FIRST_X: Int = 80
    const val FIRST_Z: Int = 48

    /** `[x]_[z]` map square keys: the arena (5120..5219, 3072..3171) spans 80..81 x 48..49. */
    val SQUARES = listOf("80_48", "80_49", "81_48", "81_49")
}
