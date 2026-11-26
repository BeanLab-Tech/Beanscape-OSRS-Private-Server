package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JComponent;

/**
 * The 100x100 hub grid, north at the top. Draws the floors, rooms, walls, objects and NPCs from a
 * [HubLayout] and turns clicks and drags into edits through [Listener]. Floors are read-only here:
 * they live in the packed cache, so the view never changes them.
 */
final class HubMapView extends JComponent
{
	enum Tool
	{
		SELECT, NPC, OBJECT, ARRIVAL, PORTAL, DOORS
	}

	enum Kind
	{
		NONE, NPC, LOC, ARRIVAL, PORTAL
	}

	interface Listener
	{
		/** The user placed or moved something. */
		void edited();

		/** The selection or the chosen room changed. */
		void selectionChanged();

		/** A tile was clicked with an NPC or object tool: add one there. */
		void place(int x, int z);

		void hover(int x, int z);
	}

	private static final int MARGIN = 26;
	private static final Color WALL = new Color(0xE0D2B0);
	private static final Map<Integer, Color> KNOWN = new HashMap<>();

	static
	{
		KNOWN.put(71, new Color(0x0C0B0E));
		KNOWN.put(45, new Color(0x241A2C));
		KNOWN.put(56, new Color(0x333338));
		KNOWN.put(55, new Color(0x6A6A70));
		KNOWN.put(43, new Color(0x5B3A7A));
		KNOWN.put(27, new Color(0x7B4FA3));
	}

	private HubLayout layout;
	private BufferedImage floorImage;
	private Tool tool = Tool.SELECT;
	private Kind selKind = Kind.NONE;
	private int selIndex = -1;
	private HubLayout.Room selRoom;
	private int hoverX = -1;
	private int hoverZ = -1;
	private boolean dragging;
	private Listener listener;

	HubMapView()
	{
		setOpaque(false);
		setPreferredSize(new Dimension(620, 620));
		setMinimumSize(new Dimension(320, 320));
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				requestFocusInWindow();
				press(e);
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				drag(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				dragging = false;
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				moved(e);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hoverX = -1;
				hoverZ = -1;
				if (listener != null)
				{
					listener.hover(-1, -1);
				}
				repaint();
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	void setListener(Listener listener)
	{
		this.listener = listener;
	}

	void setHub(HubLayout next)
	{
		layout = next;
		selKind = Kind.NONE;
		selIndex = -1;
		selRoom = null;
		rebuildFloors();
		repaint();
	}

	void setTool(Tool next)
	{
		tool = next;
		repaint();
	}

	Tool tool()
	{
		return tool;
	}

	Kind selectedKind()
	{
		return selKind;
	}

	int selectedIndex()
	{
		return selIndex;
	}

	HubLayout.Room selectedRoom()
	{
		return selRoom;
	}

	void select(Kind kind, int index)
	{
		selKind = kind;
		selIndex = index;
		repaint();
		if (listener != null)
		{
			listener.selectionChanged();
		}
	}

	void selectRoom(HubLayout.Room room)
	{
		selRoom = room;
		repaint();
		if (listener != null)
		{
			listener.selectionChanged();
		}
	}

	// =================================================================== geometry

	private int tile()
	{
		int size = layout == null ? 100 : layout.size;
		return Math.max(2, Math.min((getWidth() - 2 * MARGIN) / size, (getHeight() - 2 * MARGIN) / size));
	}

	private int originX()
	{
		int size = layout == null ? 100 : layout.size;
		return (getWidth() - tile() * size) / 2;
	}

	private int originY()
	{
		int size = layout == null ? 100 : layout.size;
		return (getHeight() - tile() * size) / 2;
	}

	private int sx(int x)
	{
		return originX() + x * tile();
	}

	/** Top edge of the row for tile z: z grows north, so row 0 of the screen is the largest z. */
	private int sy(int z)
	{
		return originY() + (layout.size - 1 - z) * tile();
	}

	private int tileX(MouseEvent e)
	{
		int x = Math.floorDiv(e.getX() - originX(), tile());
		return Math.max(0, Math.min(layout.size - 1, x));
	}

	private int tileZ(MouseEvent e)
	{
		int row = Math.floorDiv(e.getY() - originY(), tile());
		return Math.max(0, Math.min(layout.size - 1, layout.size - 1 - row));
	}

	// =================================================================== input

	private void moved(MouseEvent e)
	{
		if (layout == null)
		{
			return;
		}
		hoverX = tileX(e);
		hoverZ = tileZ(e);
		if (listener != null)
		{
			listener.hover(hoverX, hoverZ);
		}
		repaint();
	}

	private void press(MouseEvent e)
	{
		if (layout == null || e.getButton() != MouseEvent.BUTTON1)
		{
			return;
		}
		int x = tileX(e);
		int z = tileZ(e);
		switch (tool)
		{
			case SELECT:
				pick(x, z);
				dragging = selKind != Kind.NONE;
				break;
			case NPC:
			case OBJECT:
				if (listener != null)
				{
					listener.place(x, z);
				}
				break;
			case ARRIVAL:
				layout.arrivalX = x;
				layout.arrivalZ = z;
				select(Kind.ARRIVAL, 0);
				edited();
				break;
			case PORTAL:
				layout.leaveX = Math.min(x, layout.size - 3);
				layout.leaveZ = Math.min(z, layout.size - 3);
				select(Kind.PORTAL, 0);
				edited();
				break;
			case DOORS:
				selectRoom(layout.roomAt(x, z));
				break;
			default:
				break;
		}
	}

	private void drag(MouseEvent e)
	{
		if (layout == null || !dragging || tool != Tool.SELECT)
		{
			return;
		}
		int x = tileX(e);
		int z = tileZ(e);
		hoverX = x;
		hoverZ = z;
		boolean moved = false;
		switch (selKind)
		{
			case NPC:
				HubLayout.Npc npc = layout.npcs.get(selIndex);
				moved = npc.x != x || npc.z != z;
				npc.x = x;
				npc.z = z;
				break;
			case LOC:
				HubLayout.Loc loc = layout.locs.get(selIndex);
				moved = loc.x != x || loc.z != z;
				loc.x = x;
				loc.z = z;
				break;
			case ARRIVAL:
				moved = layout.arrivalX != x || layout.arrivalZ != z;
				layout.arrivalX = x;
				layout.arrivalZ = z;
				break;
			case PORTAL:
				int px = Math.min(x, layout.size - 3);
				int pz = Math.min(z, layout.size - 3);
				moved = layout.leaveX != px || layout.leaveZ != pz;
				layout.leaveX = px;
				layout.leaveZ = pz;
				break;
			default:
				break;
		}
		if (listener != null)
		{
			listener.hover(x, z);
		}
		if (moved)
		{
			edited();
		}
	}

	private void edited()
	{
		repaint();
		if (listener != null)
		{
			listener.edited();
		}
	}

	/** Objects and NPCs sit on top of the arrival marker and portal, so they win clicks. */
	private void pick(int x, int z)
	{
		for (int i = layout.npcs.size() - 1; i >= 0; i--)
		{
			if (layout.npcs.get(i).x == x && layout.npcs.get(i).z == z)
			{
				select(Kind.NPC, i);
				return;
			}
		}
		for (int i = layout.locs.size() - 1; i >= 0; i--)
		{
			if (layout.locs.get(i).x == x && layout.locs.get(i).z == z)
			{
				select(Kind.LOC, i);
				return;
			}
		}
		if (layout.arrivalX == x && layout.arrivalZ == z)
		{
			select(Kind.ARRIVAL, 0);
		}
		else if (layout.onLeavePortal(x, z))
		{
			select(Kind.PORTAL, 0);
		}
		else
		{
			select(Kind.NONE, -1);
		}
	}

	/** What a tile holds, for the tooltip. */
	String describe(int x, int z)
	{
		if (layout == null || x < 0)
		{
			return null;
		}
		StringBuilder out = new StringBuilder();
		for (HubLayout.Npc npc : layout.npcs)
		{
			if (npc.x == x && npc.z == z)
			{
				out.append("NPC ").append(npc.npc).append("  ");
			}
		}
		for (HubLayout.Loc loc : layout.locs)
		{
			if (loc.x == x && loc.z == z)
			{
				out.append(loc.loc).append(" (").append(loc.shape).append(", ").append(loc.angle).append(")  ");
			}
		}
		if (layout.arrivalX == x && layout.arrivalZ == z)
		{
			out.append("Arrival  ");
		}
		if (layout.onLeavePortal(x, z))
		{
			out.append("Leave portal  ");
		}
		HubLayout.Room room = layout.roomAt(x, z);
		if (room != null)
		{
			out.append("Room ").append(room.name).append("  ");
		}
		out.append("floor ").append(layout.floorAt(x, z));
		return out.toString();
	}

	// =================================================================== painting

	static Color floorColor(int underlayId)
	{
		Color known = KNOWN.get(underlayId);
		if (known != null)
		{
			return known;
		}
		return Color.getHSBColor((underlayId * 0.137f) % 1f, 0.45f, 0.5f);
	}

	private void rebuildFloors()
	{
		if (layout == null)
		{
			floorImage = null;
			return;
		}
		int size = layout.size;
		floorImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < size; x++)
		{
			for (int z = 0; z < size; z++)
			{
				Integer id = layout.palette.get(layout.floorAt(x, z));
				floorImage.setRGB(x, size - 1 - z, floorColor(id == null ? 0 : id).getRGB());
			}
		}
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		Theme.well(g, 0, 0, getWidth(), getHeight(), 10);
		if (layout == null || floorImage == null)
		{
			g.dispose();
			return;
		}
		int t = tile();
		int size = layout.size;
		int ox = originX();
		int oy = originY();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(floorImage, ox, oy, size * t, size * t, null);

		paintGrid(g, ox, oy, t, size);
		paintRooms(g, t);
		paintWalls(g, t);
		paintDoors(g, t);
		paintLocs(g, t);
		paintPortal(g, t);
		paintNpcs(g, t);
		paintArrival(g, t);
		paintSelection(g, t);
		if (hoverX >= 0)
		{
			g.setColor(new Color(255, 255, 255, 140));
			g.setStroke(new BasicStroke(1f));
			g.drawRect(sx(hoverX), sy(hoverZ), t, t);
		}
		g.dispose();
	}

	private void paintGrid(Graphics2D g, int ox, int oy, int t, int size)
	{
		g.setFont(Theme.ui(10f));
		FontMetrics fm = g.getFontMetrics();
		for (int i = 0; i <= size; i += 10)
		{
			int px = ox + i * t;
			int py = oy + (size - i) * t;
			g.setColor(new Color(255, 255, 255, i % 50 == 0 ? 60 : 28));
			g.drawLine(px, oy, px, oy + size * t);
			g.drawLine(ox, py, ox + size * t, py);
			g.setColor(Theme.TEXT_4);
			if (i < size)
			{
				String label = String.valueOf(i);
				g.drawString(label, px - fm.stringWidth(label) / 2, oy + size * t + 14);
			}
			if (i > 0 || size > 0)
			{
				String label = String.valueOf(i);
				g.drawString(label, ox - 6 - fm.stringWidth(label), py + 4);
			}
		}
		g.setColor(Theme.TEXT_2);
		g.drawString("N", ox + size * t / 2 - 3, oy - 8);
	}

	private void paintRooms(Graphics2D g, int t)
	{
		g.setFont(Theme.uiBold(Math.max(9f, Math.min(13f, t * 1.4f))));
		FontMetrics fm = g.getFontMetrics();
		for (HubLayout.Room room : layout.rooms)
		{
			HubLayout.Rect r = room.rect;
			int x = sx(r.x0);
			int y = sy(r.z1);
			int w = (r.x1 - r.x0 + 1) * t;
			int h = (r.z1 - r.z0 + 1) * t;
			if (room == selRoom)
			{
				g.setColor(Theme.alpha(Theme.GOLD, 40));
				g.fillRect(x, y, w, h);
			}
			String name = Theme.fit(fm, room.name, w - 6);
			g.setColor(Theme.alpha(Theme.TEXT, 190));
			g.drawString(name, x + (w - fm.stringWidth(name)) / 2, y + h / 2 + fm.getAscent() / 2 - 2);
		}
	}

	private void paintWalls(Graphics2D g, int t)
	{
		float thick = Math.max(2f, t / 4f);
		g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
		g.setColor(WALL);
		for (HubLayout.Piece piece : layout.wallPieces())
		{
			int x = sx(piece.x);
			int y = sy(piece.z);
			int half = (int) (thick / 2);
			if (piece.corner)
			{
				int q = Math.max(3, t / 2);
				int cx = piece.angle.equals("west") || piece.angle.equals("south") ? x : x + t - q;
				int cy = piece.angle.equals("west") || piece.angle.equals("north") ? y : y + t - q;
				g.fillRect(cx, cy, q, q);
				continue;
			}
			switch (piece.angle)
			{
				case "west":
					g.drawLine(x + half, y, x + half, y + t);
					break;
				case "east":
					g.drawLine(x + t - half, y, x + t - half, y + t);
					break;
				case "north":
					g.drawLine(x, y + half, x + t, y + half);
					break;
				default:
					g.drawLine(x, y + t - half, x + t, y + t - half);
					break;
			}
		}
	}

	private void paintDoors(Graphics2D g, int t)
	{
		for (HubLayout.Room room : layout.rooms)
		{
			boolean chosen = room == selRoom;
			for (HubLayout.Door door : room.doors)
			{
				HubLayout.Rect r = room.rect;
				int x;
				int z1;
				int w;
				int h;
				switch (door.side)
				{
					case "west":
					case "east":
						x = sx(door.side.equals("west") ? r.x0 : r.x1);
						z1 = sy(door.to);
						w = t;
						h = (door.to - door.from + 1) * t;
						g.setColor(Theme.alpha(Theme.GREEN, chosen ? 170 : 80));
						g.fillRect(x, z1, w, h);
						break;
					default:
						int y = sy(door.side.equals("north") ? r.z1 : r.z0);
						x = sx(door.from);
						w = (door.to - door.from + 1) * t;
						g.setColor(Theme.alpha(Theme.GREEN, chosen ? 170 : 80));
						g.fillRect(x, y, w, t);
						break;
				}
			}
		}
	}

	private void paintLocs(Graphics2D g, int t)
	{
		for (HubLayout.Loc loc : layout.locs)
		{
			int x = sx(loc.x);
			int y = sy(loc.z);
			boolean decor = loc.shape.startsWith("WallDecor");
			g.setColor(decor ? Theme.GOLD : Theme.ORANGE);
			if (decor)
			{
				int s = Math.max(2, t / 3);
				switch (loc.angle)
				{
					case "west":
						g.fillRect(x, y + t / 4, s, t / 2);
						break;
					case "east":
						g.fillRect(x + t - s, y + t / 4, s, t / 2);
						break;
					case "north":
						g.fillRect(x + t / 4, y, t / 2, s);
						break;
					default:
						g.fillRect(x + t / 4, y + t - s, t / 2, s);
						break;
				}
			}
			else
			{
				int m = Math.max(1, t / 5);
				g.fillRect(x + m, y + m, t - 2 * m, t - 2 * m);
				g.setColor(Color.BLACK);
				g.drawRect(x + m, y + m, t - 2 * m - 1, t - 2 * m - 1);
			}
		}
	}

	private void paintNpcs(Graphics2D g, int t)
	{
		for (HubLayout.Npc npc : layout.npcs)
		{
			int x = sx(npc.x);
			int y = sy(npc.z);
			int m = Math.max(1, t / 6);
			g.setColor(Theme.BLUE);
			g.fillOval(x + m, y + m, t - 2 * m, t - 2 * m);
			g.setColor(Color.BLACK);
			g.drawOval(x + m, y + m, t - 2 * m - 1, t - 2 * m - 1);
		}
	}

	private void paintArrival(Graphics2D g, int t)
	{
		int x = sx(layout.arrivalX);
		int y = sy(layout.arrivalZ);
		g.setColor(Theme.GREEN);
		g.setStroke(new BasicStroke(2f));
		g.drawRect(x, y, t - 1, t - 1);
		g.drawLine(x, y, x + t, y + t);
		g.drawLine(x + t, y, x, y + t);
	}

	private void paintPortal(Graphics2D g, int t)
	{
		int x = sx(layout.leaveX);
		int y = sy(layout.leaveZ + 2);
		g.setColor(new Color(170, 90, 230, 130));
		g.fillRect(x, y, 3 * t, 3 * t);
		g.setColor(new Color(210, 150, 255));
		g.setStroke(new BasicStroke(2f));
		g.drawRect(x, y, 3 * t - 1, 3 * t - 1);
	}

	private void paintSelection(Graphics2D g, int t)
	{
		int x;
		int y;
		int w = t;
		int h = t;
		switch (selKind)
		{
			case NPC:
				x = sx(layout.npcs.get(selIndex).x);
				y = sy(layout.npcs.get(selIndex).z);
				break;
			case LOC:
				x = sx(layout.locs.get(selIndex).x);
				y = sy(layout.locs.get(selIndex).z);
				break;
			case ARRIVAL:
				x = sx(layout.arrivalX);
				y = sy(layout.arrivalZ);
				break;
			case PORTAL:
				x = sx(layout.leaveX);
				y = sy(layout.leaveZ + 2);
				w = 3 * t;
				h = 3 * t;
				break;
			default:
				return;
		}
		g.setColor(Theme.GOLD_HI);
		g.setStroke(new BasicStroke(2f));
		g.drawRect(x - 2, y - 2, w + 3, h + 3);
	}
}
