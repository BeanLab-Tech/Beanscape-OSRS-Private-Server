package net.runelite.client.plugins.microbot.beanscapeadmin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

class HubLayoutTest
{
	/** A small valid layout: one room with a west door, one NPC, one object. */
	private static final String SMALL = "{\"version\":1,\"size\":50,"
		+ "\"palette\":{\"g\":1,\"path\":2,\"in\":3,\"rug\":4},\"ground\":\"g\","
		+ "\"floors\":[{\"rect\":[0,0,9,9],\"floor\":\"path\"}],"
		+ "\"rooms\":[{\"name\":\"A\",\"rect\":[2,2,6,6],\"floor\":\"in\",\"wall\":\"w\","
		+ "\"doors\":[{\"side\":\"west\",\"from\":4,\"to\":4}]}],"
		+ "\"detail\":[{\"rect\":[3,3,4,4],\"floor\":\"rug\"}],"
		+ "\"walls\":[{\"loc\":\"w\",\"line\":[10,10,12,10],\"angle\":\"north\"}],"
		+ "\"locs\":[{\"loc\":\"board\",\"x\":9,\"z\":9,\"shape\":\"WallDecorStraightNoOffset\",\"angle\":\"north\"}],"
		+ "\"npcs\":[{\"npc\":\"zeke\",\"x\":4,\"z\":4}],"
		+ "\"arrival\":{\"x\":8,\"z\":1},\"leavePortal\":{\"x\":20,\"z\":20}}";

	@Test
	void roundTripKeepsEveryField()
	{
		HubLayout layout = HubLayout.fromJson(SMALL);
		assertEquals(new JsonParser().parse(SMALL), layout.toJson());
		assertEquals(layout.toJson(), HubLayout.fromJson(layout.toText()).toJson());
	}

	@Test
	void bundledServerLayoutRoundTrips() throws Exception
	{
		Path file = Paths.get("..", "..", "..", "content", "other", "beanlab-soulwars", "src", "main", "resources",
			"org", "rsmod", "content", "other", "beanlab", "soulwars", "hub-layout.json");
		if (!Files.isRegularFile(file))
		{
			return; // plugin built outside the repo
		}
		String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		HubLayout layout = HubLayout.fromJson(text);
		assertEquals(100, layout.size);
		assertFalse(layout.rooms.isEmpty());
		assertFalse(layout.npcs.isEmpty());
		assertEquals(new JsonParser().parse(text), layout.toJson());
	}

	@Test
	void floorsPaintInOrderAndLaterWins()
	{
		HubLayout layout = HubLayout.fromJson(SMALL);
		assertEquals("g", layout.floorAt(30, 30));
		assertEquals("path", layout.floorAt(8, 8));
		assertEquals("in", layout.floorAt(5, 5));
		assertEquals("rug", layout.floorAt(3, 3));
	}

	@Test
	void roomRingLeavesDoorwaysOpen()
	{
		HubLayout layout = HubLayout.fromJson(SMALL);
		List<HubLayout.Piece> pieces = layout.wallPieces();
		// 5x5 room: 16 edge tiles, one doorway, plus the 3-tile run.
		assertEquals(15 + 3, pieces.size());
		assertTrue(pieces.stream().noneMatch(p -> p.x == 2 && p.z == 4 && !p.corner));
		assertEquals(4, pieces.stream().filter(p -> p.corner).count());
		HubLayout.Piece northWest = pieces.stream().filter(p -> p.x == 2 && p.z == 6).findFirst().get();
		assertTrue(northWest.corner);
		assertEquals("west", northWest.angle);
	}

	@Test
	void editingDoorsChangesTheWallsAndTheJson()
	{
		HubLayout layout = HubLayout.fromJson(SMALL);
		HubLayout.Room room = layout.rooms.get(0);
		room.doors.add(layout.newDoor(room, "north"));
		HubLayout.Door added = room.doors.get(1);
		assertEquals("north", added.side);
		int[] range = layout.doorRange(room, "north");
		assertEquals(3, range[0]);
		assertEquals(5, range[1]);
		assertTrue(added.from >= range[0] && added.to <= range[1] && added.from <= added.to);
		assertEquals(15 - (added.to - added.from + 1) + 3, layout.wallPieces().size());
		assertEquals(2, layout.toJson().getAsJsonArray("rooms").get(0).getAsJsonObject().getAsJsonArray("doors").size());
	}

	@Test
	void movingThingsIsReflectedInTheJson()
	{
		HubLayout layout = HubLayout.fromJson(SMALL);
		layout.npcs.get(0).x = 7;
		layout.npcs.get(0).z = 8;
		layout.arrivalX = 11;
		layout.leaveX = 30;
		HubLayout.Npc extra = new HubLayout.Npc();
		extra.npc = "bob";
		extra.x = 1;
		extra.z = 2;
		layout.npcs.add(extra);
		HubLayout back = HubLayout.fromJson(layout.toText());
		assertEquals(7, back.npcs.get(0).x);
		assertEquals(8, back.npcs.get(0).z);
		assertEquals(2, back.npcs.size());
		assertEquals("bob", back.npcs.get(1).npc);
		assertEquals(11, back.arrivalX);
		assertEquals(30, back.leaveX);
	}

	@Test
	void leavePortalCoversThreeByThreeAndRoomsAreFoundByTile()
	{
		HubLayout layout = HubLayout.fromJson(SMALL);
		assertTrue(layout.onLeavePortal(20, 20));
		assertTrue(layout.onLeavePortal(22, 22));
		assertFalse(layout.onLeavePortal(23, 20));
		assertNotNull(layout.roomAt(4, 4));
		assertNull(layout.roomAt(20, 4));
	}

	@Test
	void missingSectionsReadAsEmpty()
	{
		HubLayout layout = HubLayout.fromJson("{\"version\":1,\"size\":20}");
		assertTrue(layout.rooms.isEmpty());
		assertTrue(layout.npcs.isEmpty());
		assertEquals(0, layout.arrivalX);
	}
}
