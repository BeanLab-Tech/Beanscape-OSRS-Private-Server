package org.rsmod.content.other.beanlab.soulwars

import org.rsmod.content.other.beanlab.soulwars.map.SoulWarsMapFiles
import org.rsmod.game.loc.LocAngle

/**
 * The Market Hub plan, in arena-local tiles (0..99 in x and z, z grows north). One source for the
 * packed terrain (see [floorAt]) and for the walls and NPCs the script spawns.
 *
 * Theme: black and grey stone, purple accents. Floors use cache underlay ids: 56 dark grey ground,
 * 55 lighter grey paths, 45 and 71 near-black interiors, 43 and 27 purples.
 */
internal object HubLayout {
    const val SIZE = 100

    const val GROUND = 56
    const val PATH = 55
    const val INTERIOR = 45
    const val HALL_FLOOR = 71
    const val RUG = 27
    const val PLAZA = 43

    enum class Side {
        West,
        East,
        North,
        South,
    }

    /** A doorway: tiles [from]..[to] of an edge (z for west/east edges, x for north/south). */
    data class Door(val side: Side, val from: Int, val to: Int)

    data class Room(
        val name: String,
        val x0: Int,
        val z0: Int,
        val x1: Int,
        val z1: Int,
        val doors: List<Door>,
    )

    data class Spawn(val npc: String, val x: Int, val z: Int)

    val SHOPS =
        listOf(
            Room("Weapons", 58, 82, 75, 95, listOf(Door(Side.West, 87, 89))),
            Room("Armour", 79, 82, 96, 95, listOf(Door(Side.West, 87, 89))),
            Room("Skilling", 58, 64, 75, 77, listOf(Door(Side.West, 69, 71))),
            Room("Runes", 79, 64, 96, 77, listOf(Door(Side.West, 69, 71))),
            Room("Food", 4, 21, 21, 34, listOf(Door(Side.East, 26, 28))),
            Room("General", 25, 21, 42, 34, listOf(Door(Side.East, 26, 28))),
            Room("Crafting", 4, 3, 21, 16, listOf(Door(Side.East, 8, 10))),
            Room("Fletching", 25, 3, 42, 16, listOf(Door(Side.East, 8, 10))),
        )

    val EXCHANGE =
        Room(
            "Grand Exchange",
            33,
            39,
            66,
            60,
            listOf(
                Door(Side.North, 48, 51),
                Door(Side.South, 48, 51),
                Door(Side.West, 48, 51),
                Door(Side.East, 48, 51),
            ),
        )

    /** Open plaza for the message boards; only its north edge is walled. */
    val BOARD_PLAZA = Room("Message boards", 4, 64, 41, 95, emptyList())
    val BOARD_XS = listOf(9, 22, 35)
    const val BOARD_Z = 95

    val ARRIVAL_PLAZA = Room("Arrival", 75, 3, 96, 16, emptyList())
    const val ARRIVAL_X = 80
    const val ARRIVAL_Z = 12
    const val PORTAL_X = 84
    const val PORTAL_Z = 8

    val SPAWNS =
        listOf(
            Spawn("zeke", 66, 88),
            Spawn("horvik_the_armourer", 87, 88),
            Spawn("bob", 66, 70),
            Spawn("aubury", 87, 70),
            Spawn("wydin", 12, 27),
            Spawn("generalshopkeeper1", 33, 27),
            Spawn("dommik", 12, 9),
            Spawn("lowe", 33, 9),
            Spawn("banker1", 39, 44),
            Spawn("banker1", 60, 44),
            Spawn("ge_clerk_1", 40, 55),
            Spawn("ge_clerk_2", 49, 55),
            Spawn("ge_clerk_3", 59, 55),
        )

    private fun Room.contains(x: Int, z: Int) = x in x0..x1 && z in z0..z1

    private val RUG_ROOM = Room("rug", 38, 43, 61, 56, emptyList())
    private val ARRIVAL_CORE = Room("core", 78, 5, 93, 14, emptyList())

    /** Cache underlay id for the ground at arena-local ([x], [z]). */
    fun floorAt(x: Int, z: Int): Int =
        when {
            x !in 0 until SIZE || z !in 0 until SIZE -> GROUND
            RUG_ROOM.contains(x, z) -> RUG
            EXCHANGE.contains(x, z) -> HALL_FLOOR
            SHOPS.any { it.contains(x, z) } -> INTERIOR
            BOARD_PLAZA.contains(x, z) -> PLAZA
            ARRIVAL_CORE.contains(x, z) -> RUG
            ARRIVAL_PLAZA.contains(x, z) -> INTERIOR
            x in 46..53 || z in 46..53 -> PATH
            else -> GROUND
        }

    data class Wall(val x: Int, val z: Int, val angle: LocAngle, val corner: Boolean)

    /** Wall pieces around [room]'s edge, leaving its doorways open. */
    fun walls(room: Room): List<Wall> {
        val out = ArrayList<Wall>()
        fun open(side: Side, coord: Int) =
            room.doors.any { it.side == side && coord in it.from..it.to }
        for (x in room.x0..room.x1) {
            for (z in room.z0..room.z1) {
                val west = x == room.x0
                val east = x == room.x1
                val south = z == room.z0
                val north = z == room.z1
                if (!(west || east || south || north)) continue
                when {
                    west && north -> out += Wall(x, z, LocAngle.West, corner = true)
                    north && east -> out += Wall(x, z, LocAngle.North, corner = true)
                    east && south -> out += Wall(x, z, LocAngle.East, corner = true)
                    south && west -> out += Wall(x, z, LocAngle.South, corner = true)
                    west -> if (!open(Side.West, z)) out += Wall(x, z, LocAngle.West, false)
                    east -> if (!open(Side.East, z)) out += Wall(x, z, LocAngle.East, false)
                    south -> if (!open(Side.South, x)) out += Wall(x, z, LocAngle.South, false)
                    north -> if (!open(Side.North, x)) out += Wall(x, z, LocAngle.North, false)
                }
            }
        }
        return out
    }

    /** North wall of the message-board plaza. */
    fun boardWall(): List<Wall> =
        (BOARD_PLAZA.x0 + 1 until BOARD_PLAZA.x1).map { Wall(it, BOARD_Z, LocAngle.North, false) }

    /** One `m` file for map square ([squareX], [squareZ]): flat ground, level 0 coloured. */
    fun terrain(squareX: Int, squareZ: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val baseX = (squareX - SoulWarsMapFiles.FIRST_X) * 64
        val baseZ = (squareZ - SoulWarsMapFiles.FIRST_Z) * 64
        fun short(v: Int) {
            out.write(v shr 8)
            out.write(v and 0xFF)
        }
        for (level in 0 until 4) {
            for (x in 0 until 64) {
                for (z in 0 until 64) {
                    if (level == 0) {
                        short(82 + floorAt(baseX + x, baseZ + z))
                        short(1)
                        out.write(0)
                    } else {
                        short(0)
                    }
                }
            }
        }
        return out.toByteArray()
    }
}
