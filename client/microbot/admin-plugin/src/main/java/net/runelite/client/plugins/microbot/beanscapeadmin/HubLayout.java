package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Market Hub layout as the plugin edits it: the same JSON the server reads (see
 * docs/HUB_LAYOUT.md), in arena-local tiles with z growing north. Floors, rooms, palette and plain
 * walls are carried through unchanged; the editor changes objects, NPCs, doors, the arrival tile
 * and the leave portal. Validation stays on the server, the one place that knows every rule; this
 * class only reads, writes and derives what the map view draws.
 */
final class HubLayout
{
	static final String[] SIDES = {"west", "east", "north", "south"};
	static final String[] ANGLES = {"west", "north", "east", "south"};

	static final class Rect
	{
		int x0;
		int z0;
		int x1;
		int z1;

		Rect(int x0, int z0, int x1, int z1)
		{
			this.x0 = x0;
			this.z0 = z0;
			this.x1 = x1;
			this.z1 = z1;
		}

		boolean contains(int x, int z)
		{
			return x >= x0 && x <= x1 && z >= z0 && z <= z1;
		}
	}

	static final class Door
	{
		String side;
		int from;
		int to;

		Door(String side, int from, int to)
		{
			this.side = side;
			this.from = from;
			this.to = to;
		}
	}

	static final class Room
	{
		String name;
		Rect rect;
		String floor;
		String wall;
		final List<Door> doors = new ArrayList<>();
	}

	static final class Paint
	{
		Rect rect;
		String floor;
	}

	static final class WallRun
	{
		String loc;
		Rect line;
		String angle;
	}

	static final class Loc
	{
		String loc;
		int x;
		int z;
		String shape;
		String angle;
	}

	static final class Npc
	{
		String npc;
		int x;
		int z;
	}

	/** One wall piece on one tile, as the server would spawn it. */
	static final class Piece
	{
		final int x;
		final int z;
		final String angle;
		final boolean corner;

		Piece(int x, int z, String angle, boolean corner)
		{
			this.x = x;
			this.z = z;
			this.angle = angle;
			this.corner = corner;
		}
	}

	int version = 1;
	int size = 100;
	final Map<String, Integer> palette = new LinkedHashMap<>();
	String ground = "";
	final List<Paint> floors = new ArrayList<>();
	final List<Room> rooms = new ArrayList<>();
	final List<Paint> detail = new ArrayList<>();
	final List<WallRun> walls = new ArrayList<>();
	final List<Loc> locs = new ArrayList<>();
	final List<Npc> npcs = new ArrayList<>();
	int arrivalX;
	int arrivalZ;
	int leaveX;
	int leaveZ;

	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

	// =================================================================== reading

	static HubLayout fromJson(JsonObject json)
	{
		HubLayout layout = new HubLayout();
		layout.version = integer(json, "version", 1);
		layout.size = integer(json, "size", 100);
		for (Map.Entry<String, JsonElement> entry : obj(json, "palette").entrySet())
		{
			layout.palette.put(entry.getKey(), entry.getValue().getAsInt());
		}
		layout.ground = text(json, "ground");
		for (JsonElement element : array(json, "floors"))
		{
			layout.floors.add(paint(element.getAsJsonObject()));
		}
		for (JsonElement element : array(json, "detail"))
		{
			layout.detail.add(paint(element.getAsJsonObject()));
		}
		for (JsonElement element : array(json, "rooms"))
		{
			JsonObject o = element.getAsJsonObject();
			Room room = new Room();
			room.name = text(o, "name");
			room.rect = rect(o.get("rect"));
			room.floor = text(o, "floor");
			room.wall = text(o, "wall");
			for (JsonElement door : array(o, "doors"))
			{
				JsonObject d = door.getAsJsonObject();
				room.doors.add(new Door(text(d, "side"), integer(d, "from", 0), integer(d, "to", 0)));
			}
			layout.rooms.add(room);
		}
		for (JsonElement element : array(json, "walls"))
		{
			JsonObject o = element.getAsJsonObject();
			WallRun run = new WallRun();
			run.loc = text(o, "loc");
			run.line = rect(o.get("line"));
			run.angle = text(o, "angle");
			layout.walls.add(run);
		}
		for (JsonElement element : array(json, "locs"))
		{
			JsonObject o = element.getAsJsonObject();
			Loc loc = new Loc();
			loc.loc = text(o, "loc");
			loc.x = integer(o, "x", 0);
			loc.z = integer(o, "z", 0);
			loc.shape = text(o, "shape");
			loc.angle = text(o, "angle");
			layout.locs.add(loc);
		}
		for (JsonElement element : array(json, "npcs"))
		{
			JsonObject o = element.getAsJsonObject();
			Npc npc = new Npc();
			npc.npc = text(o, "npc");
			npc.x = integer(o, "x", 0);
			npc.z = integer(o, "z", 0);
			layout.npcs.add(npc);
		}
		JsonObject arrival = obj(json, "arrival");
		layout.arrivalX = integer(arrival, "x", 0);
		layout.arrivalZ = integer(arrival, "z", 0);
		JsonObject leave = obj(json, "leavePortal");
		layout.leaveX = integer(leave, "x", 0);
		layout.leaveZ = integer(leave, "z", 0);
		return layout;
	}

	static HubLayout fromJson(String text)
	{
		return fromJson(new JsonParser().parse(text).getAsJsonObject());
	}

	// =================================================================== writing

	JsonObject toJson()
	{
		JsonObject root = new JsonObject();
		root.addProperty("version", version);
		root.addProperty("size", size);
		JsonObject pal = new JsonObject();
		for (Map.Entry<String, Integer> entry : palette.entrySet())
		{
			pal.addProperty(entry.getKey(), entry.getValue());
		}
		root.add("palette", pal);
		root.addProperty("ground", ground);
		root.add("floors", paints(floors));
		JsonArray rms = new JsonArray();
		for (Room room : rooms)
		{
			JsonObject o = new JsonObject();
			o.addProperty("name", room.name);
			o.add("rect", rect(room.rect));
			o.addProperty("floor", room.floor);
			o.addProperty("wall", room.wall);
			JsonArray ds = new JsonArray();
			for (Door door : room.doors)
			{
				JsonObject d = new JsonObject();
				d.addProperty("side", door.side);
				d.addProperty("from", door.from);
				d.addProperty("to", door.to);
				ds.add(d);
			}
			o.add("doors", ds);
			rms.add(o);
		}
		root.add("rooms", rms);
		root.add("detail", paints(detail));
		JsonArray ws = new JsonArray();
		for (WallRun run : walls)
		{
			JsonObject o = new JsonObject();
			o.addProperty("loc", run.loc);
			o.add("line", rect(run.line));
			o.addProperty("angle", run.angle);
			ws.add(o);
		}
		root.add("walls", ws);
		JsonArray ls = new JsonArray();
		for (Loc loc : locs)
		{
			JsonObject o = new JsonObject();
			o.addProperty("loc", loc.loc);
			o.addProperty("x", loc.x);
			o.addProperty("z", loc.z);
			o.addProperty("shape", loc.shape);
			o.addProperty("angle", loc.angle);
			ls.add(o);
		}
		root.add("locs", ls);
		JsonArray ns = new JsonArray();
		for (Npc npc : npcs)
		{
			JsonObject o = new JsonObject();
			o.addProperty("npc", npc.npc);
			o.addProperty("x", npc.x);
			o.addProperty("z", npc.z);
			ns.add(o);
		}
		root.add("npcs", ns);
		JsonObject arrival = new JsonObject();
		arrival.addProperty("x", arrivalX);
		arrival.addProperty("z", arrivalZ);
		root.add("arrival", arrival);
		JsonObject leave = new JsonObject();
		leave.addProperty("x", leaveX);
		leave.addProperty("z", leaveZ);
		root.add("leavePortal", leave);
		return root;
	}

	String toText()
	{
		return PRETTY.toJson(toJson());
	}

	// =================================================================== derived

	/** Floor name painted at a tile: ground, then floors, room floors, detail; later wins. */
	String floorAt(int x, int z)
	{
		String floor = ground;
		for (Paint paint : floors)
		{
			if (paint.rect.contains(x, z))
			{
				floor = paint.floor;
			}
		}
		for (Room room : rooms)
		{
			if (room.rect.contains(x, z))
			{
				floor = room.floor;
			}
		}
		for (Paint paint : detail)
		{
			if (paint.rect.contains(x, z))
			{
				floor = paint.floor;
			}
		}
		return floor;
	}

	/** Every wall piece: room rings with doorways left open, then the plain runs. */
	List<Piece> wallPieces()
	{
		List<Piece> out = new ArrayList<>();
		for (Room room : rooms)
		{
			Rect r = room.rect;
			for (int x = r.x0; x <= r.x1; x++)
			{
				for (int z = r.z0; z <= r.z1; z++)
				{
					boolean west = x == r.x0;
					boolean east = x == r.x1;
					boolean south = z == r.z0;
					boolean north = z == r.z1;
					if (!(west || east || south || north))
					{
						continue;
					}
					if (west && north)
					{
						out.add(new Piece(x, z, "west", true));
					}
					else if (north && east)
					{
						out.add(new Piece(x, z, "north", true));
					}
					else if (east && south)
					{
						out.add(new Piece(x, z, "east", true));
					}
					else if (south && west)
					{
						out.add(new Piece(x, z, "south", true));
					}
					else if (west)
					{
						if (!open(room, "west", z))
						{
							out.add(new Piece(x, z, "west", false));
						}
					}
					else if (east)
					{
						if (!open(room, "east", z))
						{
							out.add(new Piece(x, z, "east", false));
						}
					}
					else if (south)
					{
						if (!open(room, "south", x))
						{
							out.add(new Piece(x, z, "south", false));
						}
					}
					else if (!open(room, "north", x))
					{
						out.add(new Piece(x, z, "north", false));
					}
				}
			}
		}
		for (WallRun run : walls)
		{
			for (int x = run.line.x0; x <= run.line.x1; x++)
			{
				for (int z = run.line.z0; z <= run.line.z1; z++)
				{
					out.add(new Piece(x, z, run.angle, false));
				}
			}
		}
		return out;
	}

	private static boolean open(Room room, String side, int coord)
	{
		for (Door door : room.doors)
		{
			if (door.side.equals(side) && coord >= door.from && coord <= door.to)
			{
				return true;
			}
		}
		return false;
	}

	/** The room containing a tile (rooms never overlap), or null. */
	Room roomAt(int x, int z)
	{
		for (Room room : rooms)
		{
			if (room.rect.contains(x, z))
			{
				return room;
			}
		}
		return null;
	}

	/** The leave portal is 3x3 from its south-west tile. */
	boolean onLeavePortal(int x, int z)
	{
		return x >= leaveX && x < leaveX + 3 && z >= leaveZ && z < leaveZ + 3;
	}

	/** Inclusive range of a door coordinate along its wall, as the server allows it. */
	int[] doorRange(Room room, String side)
	{
		boolean vertical = side.equals("west") || side.equals("east");
		return vertical
			? new int[]{room.rect.z0 + 1, room.rect.z1 - 1}
			: new int[]{room.rect.x0 + 1, room.rect.x1 - 1};
	}

	/** A two-tile door near the middle of a side: a sensible starting point for the editor. */
	Door newDoor(Room room, String side)
	{
		int[] range = doorRange(room, side);
		int mid = (range[0] + range[1]) / 2;
		return new Door(side, mid, Math.min(range[1], mid + 1));
	}

	// =================================================================== json helpers

	private static Paint paint(JsonObject o)
	{
		Paint paint = new Paint();
		paint.rect = rect(o.get("rect"));
		paint.floor = text(o, "floor");
		return paint;
	}

	private static JsonArray paints(List<Paint> list)
	{
		JsonArray out = new JsonArray();
		for (Paint paint : list)
		{
			JsonObject o = new JsonObject();
			o.add("rect", rect(paint.rect));
			o.addProperty("floor", paint.floor);
			out.add(o);
		}
		return out;
	}

	private static Rect rect(JsonElement element)
	{
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 4)
		{
			return new Rect(0, 0, 0, 0);
		}
		JsonArray a = element.getAsJsonArray();
		return new Rect(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt());
	}

	private static JsonArray rect(Rect r)
	{
		JsonArray a = new JsonArray();
		a.add(r.x0);
		a.add(r.z0);
		a.add(r.x1);
		a.add(r.z1);
		return a;
	}

	private static JsonObject obj(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
	}

	private static JsonArray array(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	private static String text(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static int integer(JsonObject o, String key, int fallback)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
	}
}
