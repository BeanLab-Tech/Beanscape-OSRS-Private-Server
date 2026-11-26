package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Hub map editor: the Market Hub layout on a 100x100 grid with tools to add and move NPCs and
 * objects, set the arrival and leave portal and edit room doors. Save sends the layout to the
 * server, which validates it and lists every problem. Floors are shown but cannot be edited here:
 * they live in the packed cache and need a repack.
 */
final class HubMapPage extends Page
{
	private static final String[] SHAPES = {
		"WallStraight", "WallDiagonalCorner", "WallL", "WallSquareCorner",
		"WallDecorStraightNoOffset", "WallDecorStraightOffset", "WallDecorDiagonalOffset",
		"WallDecorDiagonalNoOffset", "WallDecorDiagonalBoth", "WallDiagonal",
		"CentrepieceStraight", "CentrepieceDiagonal", "GroundDecor"
	};

	/** Result of a save: either saved, or the server's list of problems. */
	private static final class SaveResult
	{
		final String message;
		final List<String> problems;

		SaveResult(String message, List<String> problems)
		{
			this.message = message;
			this.problems = problems;
		}
	}

	private final HubMapView view = new HubMapView();
	private final Ui.Segmented tools = new Ui.Segmented("Select", "NPC", "Object", "Arrival", "Portal", "Doors");
	private final JLabel hint = Ui.label(" ", Theme.ui(12.5f), Theme.TEXT_3);
	private final JLabel source = Ui.label("Not loaded", Theme.uiBold(12f), Theme.TEXT_3);
	private final JLabel cursor = Ui.label(" ", Theme.ui(12f), Theme.TEXT_3);
	private final Ui.Button save = new Ui.Button("Save layout", Ui.Kind.PRIMARY, Ui.Glyph.SAVE);
	private final Ui.Button reload = new Ui.Button("Reload", Ui.Kind.STONE, Ui.Glyph.RESTART);
	private final JTextField name = new JTextField();
	private final JComboBox<String> shape = new JComboBox<>(SHAPES);
	private final JComboBox<String> angle = new JComboBox<>(HubLayout.ANGLES);
	private final JLabel position = Ui.label(" ", Theme.uiBold(13f), Theme.YELLOW);
	private final JLabel kindLabel = Ui.label("Nothing selected", Theme.uiBold(13f), Theme.TEXT_2);
	private final Ui.Button delete = new Ui.Button("Remove", Ui.Kind.DANGER, Ui.Glyph.TRASH);
	private final JLabel roomLabel = Ui.label("Pick the Doors tool, then click a room.", Theme.ui(12.5f), Theme.TEXT_3);
	private final JPanel doorRows = Ui.clear(null);
	private final JComboBox<String> newSide = new JComboBox<>(HubLayout.SIDES);
	private final Ui.Button addDoor = new Ui.Button("Add door", Ui.Kind.STONE, Ui.Glyph.PLAY);
	private final JPanel problemList = Ui.clear(null);
	private final JLabel counts = Ui.label(" ", Theme.ui(12.5f), Theme.TEXT_3);

	private HubLayout layout;
	private String baseline = "";
	private boolean dirty;
	private boolean loading;
	private boolean saving;
	private boolean syncing;

	HubMapPage(Session session, AdminWindow window)
	{
		super("Hub map", "Edit the Market Hub: NPCs, objects, doors, arrival and leave portal.", "owner", session, window);
	}

	@Override
	JComponent build()
	{
		Ui.field(name);
		Ui.combo(shape);
		Ui.combo(angle);
		Ui.combo(newSide);
		doorRows.setLayout(new BoxLayout(doorRows, BoxLayout.Y_AXIS));
		problemList.setLayout(new BoxLayout(problemList, BoxLayout.Y_AXIS));

		JPanel buttons = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(source);
		buttons.add(reload);
		buttons.add(save);
		JPanel page = shell(buttons);

		JLabel notice = Ui.label("Floors are display-only here: changing them needs a cache repack (docs/HUB_LAYOUT.md). "
			+ "Walls, objects and NPCs apply after a server restart.", Theme.ui(12.5f), Theme.ORANGE);

		JPanel toolbar = Ui.clear(new BorderLayout(14, 0));
		toolbar.add(tools, BorderLayout.WEST);
		toolbar.add(hint, BorderLayout.CENTER);
		toolbar.add(cursor, BorderLayout.EAST);

		JPanel left = Ui.clear(new BorderLayout(0, 10));
		JPanel top = Ui.clear(new BorderLayout(0, 8));
		top.add(notice, BorderLayout.NORTH);
		top.add(toolbar, BorderLayout.SOUTH);
		left.add(top, BorderLayout.NORTH);
		left.add(view, BorderLayout.CENTER);

		JPanel side = Page.stack(selectedSection(), doorsSection(), problemsSection());
		javax.swing.JScrollPane scroll = Ui.scroll(Widgets.top(side));
		scroll.setPreferredSize(new Dimension(330, 100));

		JPanel body = Ui.clear(new BorderLayout(14, 0));
		body.add(left, BorderLayout.CENTER);
		body.add(scroll, BorderLayout.EAST);
		page.add(body, BorderLayout.CENTER);

		tools.onChange(index ->
		{
			view.setTool(HubMapView.Tool.values()[index]);
			syncPanels();
		});
		view.setListener(new HubMapView.Listener()
		{
			@Override
			public void edited()
			{
				markEdited();
			}

			@Override
			public void selectionChanged()
			{
				syncPanels();
			}

			@Override
			public void place(int x, int z)
			{
				placeAt(x, z);
			}

			@Override
			public void hover(int x, int z)
			{
				cursor.setText(x < 0 ? " " : x + ", " + z);
				view.setToolTipText(view.describe(x, z));
			}
		});
		name.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				nameEdited();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				nameEdited();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				nameEdited();
			}
		});
		shape.addActionListener(event -> shapeEdited());
		angle.addActionListener(event -> shapeEdited());
		delete.addActionListener(event -> deleteSelected());
		addDoor.addActionListener(event -> addDoor());
		save.addActionListener(event -> save());
		reload.addActionListener(event -> reload());
		view.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove");
		view.getActionMap().put("remove", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				deleteSelected();
			}
		});
		syncPanels();
		return page;
	}

	private JComponent selectedSection()
	{
		Ui.Section section = new Ui.Section("Selected", "Click a thing on the map. Drag to move it.", new BorderLayout(0, 8));
		JPanel rows = Ui.clear(new GridLayout(0, 1, 0, 6));
		rows.add(kindLabel);
		rows.add(position);
		rows.add(labelled("Name", name));
		rows.add(labelled("Shape", shape));
		rows.add(labelled("Facing", angle));
		JPanel foot = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		foot.add(delete);
		section.body().add(rows, BorderLayout.CENTER);
		section.body().add(foot, BorderLayout.SOUTH);
		return section;
	}

	private JComponent doorsSection()
	{
		Ui.Section section = new Ui.Section("Doors", "Gaps in a room's wall. Rooms and their walls are fixed here.", new BorderLayout(0, 8));
		JPanel head = Ui.clear(new BorderLayout(0, 6));
		head.add(roomLabel, BorderLayout.NORTH);
		head.add(doorRows, BorderLayout.CENTER);
		JPanel add = Ui.clear(new BorderLayout(8, 0));
		add.add(newSide, BorderLayout.CENTER);
		add.add(addDoor, BorderLayout.EAST);
		section.body().add(head, BorderLayout.CENTER);
		section.body().add(add, BorderLayout.SOUTH);
		return section;
	}

	private JComponent problemsSection()
	{
		Ui.Section section = new Ui.Section("Problems", "What the server said about the last save.", new BorderLayout(0, 6));
		section.body().add(problemList, BorderLayout.CENTER);
		section.body().add(counts, BorderLayout.SOUTH);
		return section;
	}

	private static JPanel labelled(String label, JComponent control)
	{
		JPanel row = Ui.clear(new BorderLayout(10, 0));
		JLabel text = Ui.label(label, Theme.ui(12.5f), Theme.TEXT_3);
		text.setPreferredSize(new Dimension(52, 28));
		row.add(text, BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	// =================================================================== loading

	@Override
	void setShowing(boolean showing)
	{
		if (showing && layout == null && !loading && session.connected() && allowed())
		{
			load();
		}
	}

	private void reload()
	{
		if (dirty && !confirm("Discard changes", "Reload from the server and lose your unsaved edits?", "Reload", true))
		{
			return;
		}
		load();
	}

	private void load()
	{
		loading = true;
		session.call(() ->
		{
			try
			{
				return session.api().get("/v1/hub/layout");
			}
			catch (ApiClient.ApiException ex)
			{
				// Servers without the Market Hub (staging, until the hub is ported) have no route.
				if (ex.status == 404)
				{
					return null;
				}
				throw ex;
			}
		}, json ->
		{
			loading = false;
			if (json == null)
			{
				source.setText("This server has no Market Hub.");
				source.setForeground(Theme.TEXT_3);
				syncPanels();
				return;
			}
			layout = HubLayout.fromJson(json.getAsJsonObject("layout"));
			baseline = layout.toText();
			dirty = false;
			view.setHub(layout);
			String from = Session.str(json, "source");
			source.setText("Loaded: " + (from.isEmpty() ? "server" : from));
			source.setForeground(Theme.TEXT_2);
			showProblems(Collections.emptyList());
			syncPanels();
			window.flash("Hub layout loaded.", Theme.GREEN);
		}, error ->
		{
			loading = false;
			window.flash(error, Theme.RED);
		});
	}

	// =================================================================== saving

	private void save()
	{
		if (layout == null || saving)
		{
			return;
		}
		JsonObject body = layout.toJson();
		saving = true;
		syncPanels();
		session.call(() ->
		{
			try
			{
				JsonObject reply = session.api().put("/v1/hub/layout", body);
				return new SaveResult(Session.str(reply, "message"), Collections.<String>emptyList());
			}
			catch (ApiClient.ApiException ex)
			{
				if (ex.status == 422)
				{
					return new SaveResult(ex.getMessage(), ex.problems);
				}
				throw ex;
			}
		}, result ->
		{
			saving = false;
			if (result.problems.isEmpty())
			{
				baseline = layout.toText();
				dirty = false;
				source.setText("Loaded: override (saved)");
				showProblems(Collections.emptyList());
				window.flash(result.message.isEmpty() ? "Saved." : result.message, Theme.GREEN);
			}
			else
			{
				showProblems(result.problems);
				window.flash(result.message, Theme.RED);
			}
			syncPanels();
		}, error ->
		{
			saving = false;
			window.flash(error, Theme.RED);
			syncPanels();
		});
	}

	private void showProblems(List<String> problems)
	{
		problemList.removeAll();
		for (String problem : problems)
		{
			JLabel line = Ui.note(problem, 270);
			line.setForeground(Theme.RED);
			problemList.add(line);
			problemList.add(Box.createVerticalStrut(4));
		}
		counts.setText(problems.isEmpty() ? "No problems." : problems.size() + " problem(s); nothing was saved.");
		problemList.revalidate();
		problemList.repaint();
	}

	// =================================================================== editing

	private void markEdited()
	{
		dirty = layout != null && !layout.toText().equals(baseline);
		syncPanels(false);
	}

	private void placeAt(int x, int z)
	{
		if (layout == null)
		{
			return;
		}
		String entered = name.getText().trim();
		if (entered.isEmpty())
		{
			window.flash("Type the " + (view.tool() == HubMapView.Tool.NPC ? "NPC" : "object") + " name first.", Theme.ORANGE);
			return;
		}
		if (view.tool() == HubMapView.Tool.NPC)
		{
			HubLayout.Npc npc = new HubLayout.Npc();
			npc.npc = entered;
			npc.x = x;
			npc.z = z;
			layout.npcs.add(npc);
			view.select(HubMapView.Kind.NPC, layout.npcs.size() - 1);
		}
		else
		{
			HubLayout.Loc loc = new HubLayout.Loc();
			loc.loc = entered;
			loc.x = x;
			loc.z = z;
			loc.shape = (String) shape.getSelectedItem();
			loc.angle = (String) angle.getSelectedItem();
			layout.locs.add(loc);
			view.select(HubMapView.Kind.LOC, layout.locs.size() - 1);
		}
		view.repaint();
		markEdited();
	}

	private boolean editingSelection()
	{
		return !syncing && view.tool() == HubMapView.Tool.SELECT;
	}

	private void nameEdited()
	{
		if (!editingSelection() || layout == null)
		{
			return;
		}
		String text = name.getText().trim();
		if (view.selectedKind() == HubMapView.Kind.NPC)
		{
			layout.npcs.get(view.selectedIndex()).npc = text;
		}
		else if (view.selectedKind() == HubMapView.Kind.LOC)
		{
			layout.locs.get(view.selectedIndex()).loc = text;
		}
		else
		{
			return;
		}
		markEdited();
	}

	private void shapeEdited()
	{
		if (!editingSelection() || layout == null || view.selectedKind() != HubMapView.Kind.LOC)
		{
			return;
		}
		HubLayout.Loc loc = layout.locs.get(view.selectedIndex());
		loc.shape = (String) shape.getSelectedItem();
		loc.angle = (String) angle.getSelectedItem();
		view.repaint();
		markEdited();
	}

	private void deleteSelected()
	{
		if (layout == null)
		{
			return;
		}
		if (view.selectedKind() == HubMapView.Kind.NPC)
		{
			layout.npcs.remove(view.selectedIndex());
		}
		else if (view.selectedKind() == HubMapView.Kind.LOC)
		{
			layout.locs.remove(view.selectedIndex());
		}
		else
		{
			return;
		}
		view.select(HubMapView.Kind.NONE, -1);
		markEdited();
	}

	private void addDoor()
	{
		HubLayout.Room room = view.selectedRoom();
		if (layout == null || room == null)
		{
			return;
		}
		room.doors.add(layout.newDoor(room, (String) newSide.getSelectedItem()));
		view.repaint();
		markEdited();
		rebuildDoors();
	}

	// =================================================================== panels

	/** Brings the side panels and buttons in line with the tool, selection and dirty state. */
	private void syncPanels()
	{
		syncPanels(true);
	}

	private void syncPanels(boolean doors)
	{
		syncing = true;
		try
		{
			HubMapView.Tool tool = view.tool();
			HubMapView.Kind kind = view.selectedKind();
			boolean placing = tool == HubMapView.Tool.NPC || tool == HubMapView.Tool.OBJECT;
			boolean selecting = tool == HubMapView.Tool.SELECT;
			boolean hasThing = layout != null && selecting && (kind == HubMapView.Kind.NPC || kind == HubMapView.Kind.LOC);

			String text = "";
			String kindText = "Nothing selected";
			String where = " ";
			boolean showShape = false;
			if (placing)
			{
				kindText = tool == HubMapView.Tool.NPC ? "New NPC" : "New object";
				showShape = tool == HubMapView.Tool.OBJECT;
				text = name.getText();
			}
			else if (layout != null && selecting)
			{
				switch (kind)
				{
					case NPC:
						HubLayout.Npc npc = layout.npcs.get(view.selectedIndex());
						kindText = "NPC";
						text = npc.npc;
						where = npc.x + ", " + npc.z;
						break;
					case LOC:
						HubLayout.Loc loc = layout.locs.get(view.selectedIndex());
						kindText = "Object";
						text = loc.loc;
						where = loc.x + ", " + loc.z;
						shape.setSelectedItem(loc.shape);
						angle.setSelectedItem(loc.angle);
						showShape = true;
						break;
					case ARRIVAL:
						kindText = "Arrival tile";
						where = layout.arrivalX + ", " + layout.arrivalZ;
						break;
					case PORTAL:
						kindText = "Leave portal (3x3, south-west tile)";
						where = layout.leaveX + ", " + layout.leaveZ;
						break;
					default:
						break;
				}
			}
			if (!text.equals(name.getText()))
			{
				name.setText(text);
			}
			kindLabel.setText(kindText);
			position.setText(where);
			name.setEnabled(placing || hasThing);
			shape.setEnabled(showShape);
			angle.setEnabled(showShape);
			delete.setEnabled(hasThing);
			hint.setText(hintFor(tool));
			if (doors)
			{
				rebuildDoors();
			}
			save.setEnabled(layout != null && dirty && !saving);
			reload.setEnabled(!loading && !saving);
			save.setText(saving ? "Saving..." : dirty ? "Save layout *" : "Save layout");
		}
		finally
		{
			syncing = false;
		}
	}

	private static String hintFor(HubMapView.Tool tool)
	{
		switch (tool)
		{
			case NPC:
				return "Type a name, click a tile to add an NPC.";
			case OBJECT:
				return "Type a name, pick shape and facing, click a tile.";
			case ARRIVAL:
				return "Click the tile where the portal puts players.";
			case PORTAL:
				return "Click the south-west tile of the 3x3 leave portal.";
			case DOORS:
				return "Click a room, then edit its doors on the right.";
			default:
				return "Click to select, drag to move, Delete removes.";
		}
	}

	private void rebuildDoors()
	{
		HubLayout.Room room = view.selectedRoom();
		doorRows.removeAll();
		boolean doors = layout != null && view.tool() == HubMapView.Tool.DOORS && room != null;
		roomLabel.setText(doors ? "Room: " + room.name : "Pick the Doors tool, then click a room.");
		newSide.setEnabled(doors);
		addDoor.setEnabled(doors);
		if (doors)
		{
			for (HubLayout.Door door : new ArrayList<>(room.doors))
			{
				doorRows.add(doorRow(room, door));
				doorRows.add(Box.createVerticalStrut(6));
			}
		}
		doorRows.revalidate();
		doorRows.repaint();
	}

	private JComponent doorRow(HubLayout.Room room, HubLayout.Door door)
	{
		int[] range = layout.doorRange(room, door.side);
		JPanel row = Ui.clear(new BorderLayout(6, 0));
		JComboBox<String> side = new JComboBox<>(HubLayout.SIDES);
		Ui.combo(side);
		side.setSelectedItem(door.side);
		side.setPreferredSize(new Dimension(84, 32));
		int hi = Math.max(range[1], layout.size - 1);
		Ui.Stepper from = new Ui.Stepper(0, hi, door.from);
		Ui.Stepper to = new Ui.Stepper(0, hi, door.to);
		from.setPreferredSize(new Dimension(118, 32));
		to.setPreferredSize(new Dimension(118, 32));
		Ui.Button remove = new Ui.Button("", Ui.Kind.DANGER, Ui.Glyph.TRASH);
		remove.setToolTipText("Remove this door");
		side.addActionListener(event ->
		{
			door.side = (String) side.getSelectedItem();
			view.repaint();
			markEdited();
		});
		from.onChange(() ->
		{
			door.from = from.getValue();
			view.repaint();
			markEdited();
		});
		to.onChange(() ->
		{
			door.to = to.getValue();
			view.repaint();
			markEdited();
		});
		remove.addActionListener(event ->
		{
			room.doors.remove(door);
			view.repaint();
			markEdited();
			rebuildDoors();
		});
		JPanel numbers = Ui.clear(new GridLayout(1, 2, 6, 0));
		numbers.add(from);
		numbers.add(to);
		JLabel range1 = Ui.label("allowed " + range[0] + " to " + range[1] + " on this wall", Theme.ui(11.5f), Theme.TEXT_4);
		JPanel top = Ui.clear(new BorderLayout(6, 0));
		top.add(side, BorderLayout.WEST);
		top.add(numbers, BorderLayout.CENTER);
		top.add(remove, BorderLayout.EAST);
		row.add(top, BorderLayout.CENTER);
		row.add(range1, BorderLayout.SOUTH);
		return row;
	}
}
