package org.rsmod.content.other.beanlab.soulwars

import java.nio.file.Files
import java.nio.file.Path
import org.rsmod.content.other.beanlab.soulwars.map.SoulWarsMapFiles

/**
 * Writes the hub's `m` files from a layout file. Run through the Gradle task `generateHubTerrain`
 * (arguments: layout file, output directory), then `packCache`.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <layout.json> <output dir>" }
    val map = HubMap.parse(Files.readString(Path.of(args[0])))
    val out = Path.of(args[1])
    Files.createDirectories(out)
    for (square in SoulWarsMapFiles.SQUARES) {
        val parts = square.split("_")
        val bytes = map.terrain(parts[0].toInt(), parts[1].toInt())
        Files.write(out.resolve("m$square"), bytes)
    }
    println("Wrote ${SoulWarsMapFiles.SQUARES.size} terrain files to $out")
}
