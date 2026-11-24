package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Skill;

/** Online players on the left; the selected player's details and actions on the right. */
final class PlayersPage extends Page
{
	private final JTextField search = new JTextField();
	private final JPanel list = Ui.clear(null);
	private final JLabel count = Ui.label(" ", Theme.ui(12f), Theme.TEXT_3);
	private final JLabel name = Ui.label("Pick a player", Theme.display(18f), Theme.GOLD_HI);
	private final JLabel where = Ui.label(" ", Theme.ui(12.5f), Theme.TEXT_2);
	private final JPanel stats = Ui.clear(new GridLayout(0, 4, 8, 6));
	private final List<Ui.Button> moderatorButtons = new ArrayList<>();
	private final List<Ui.Button> adminButtons = new ArrayList<>();
	private String listSignature = "";
	private String statsFor = "";

	PlayersPage(Session session, AdminWindow window)
	{
		super("Players", "Who is online, with one-click moderation and quick links to items and stats.", "moderator", session, window);
	}

	@Override
	JComponent build()
	{
		JPanel page = shell();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		Ui.field(search);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuildList();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuildList();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuildList();
			}
		});

		Ui.Section online = new Ui.Section("Online", "Click a player to select them.", new BorderLayout(0, 8));
		JPanel top = Ui.clear(new BorderLayout(0, 4));
		top.add(search, BorderLayout.NORTH);
		top.add(count, BorderLayout.SOUTH);
		online.body().add(top, BorderLayout.NORTH);
		online.body().add(Ui.scroll(list), BorderLayout.CENTER);
		online.setPreferredSize(new Dimension(320, 100));

		Ui.Section selected = new Ui.Section("Selected player", "Actions are logged under your name.", new BorderLayout(0, 10));
		JPanel head = Ui.clear(new BorderLayout(0, 2));
		head.add(name, BorderLayout.NORTH);
		head.add(where, BorderLayout.CENTER);
		JPanel buttons = Ui.clear(new GridLayout(0, 2, 8, 8));
		buttons.add(moderator(new Ui.Button("Teleport to", Ui.Kind.STONE, Ui.Glyph.TARGET), "teleport-to", null));
		buttons.add(moderator(new Ui.Button("Summon here", Ui.Kind.STONE, Ui.Glyph.LOAD), "summon", null));
		buttons.add(moderator(new Ui.Button("Heal", Ui.Kind.STONE, Ui.Glyph.CHECK), "heal", null));
		buttons.add(moderator(new Ui.Button("Kick", Ui.Kind.DANGER, Ui.Glyph.STOP), "kick", "Kick %s from the game?"));
		Ui.Button give = new Ui.Button("Give item", Ui.Kind.STONE, Ui.Glyph.SAVE);
		give.addActionListener(event -> window.show(AdminWindow.ITEMS));
		adminButtons.add(give);
		buttons.add(give);
		Ui.Button setStats = new Ui.Button("Set stats", Ui.Kind.STONE, Ui.Glyph.GEAR);
		setStats.addActionListener(event -> window.show(AdminWindow.STATS));
		adminButtons.add(setStats);
		buttons.add(setStats);
		Ui.Button wipe = new Ui.Button("Wipe inventory", Ui.Kind.DANGER, Ui.Glyph.TRASH);
		wipe.addActionListener(event -> run("wipe-inventory", "Wipe %s's inventory? This can't be undone."));
		adminButtons.add(wipe);
		buttons.add(wipe);
		JPanel body = Ui.clear(new BorderLayout(0, 14));
		body.add(head, BorderLayout.NORTH);
		body.add(buttons, BorderLayout.CENTER);
		JPanel statsBox = Ui.clear(new BorderLayout(0, 6));
		statsBox.add(Ui.label("LEVELS", Theme.eyebrow(10f), Theme.TEXT_4), BorderLayout.NORTH);
		statsBox.add(stats, BorderLayout.CENTER);
		body.add(statsBox, BorderLayout.SOUTH);
		selected.body().add(body, BorderLayout.NORTH);

		JPanel columns = Ui.clear(new BorderLayout(14, 0));
		columns.add(online, BorderLayout.WEST);
		columns.add(selected, BorderLayout.CENTER);
		page.add(columns, BorderLayout.CENTER);
		rebuildList();
		return page;
	}

	private Ui.Button moderator(Ui.Button button, String action, String confirmText)
	{
		moderatorButtons.add(button);
		button.addActionListener(event -> run(action, confirmText));
		return button;
	}

	private void run(String action, String confirmText)
	{
		String target = session.selected();
		if (target.isEmpty())
		{
			window.flash("Select a player first.", Theme.ORANGE);
			return;
		}
		if (confirmText != null && !confirm(String.format(confirmText, target)))
		{
			return;
		}
		session.act("/v1/players/" + ApiClient.enc(target) + "/" + action, null,
			message -> window.flash(message, Theme.GREEN), error -> window.flash(error, Theme.RED));
	}

	@Override
	void onState()
	{
		boolean mod = session.has("moderator");
		boolean admin = session.has("admin");
		boolean picked = !session.selected().isEmpty() && onlineNow(session.selected());
		for (Ui.Button button : moderatorButtons)
		{
			button.setEnabled(mod && picked);
		}
		for (Ui.Button button : adminButtons)
		{
			button.setEnabled(admin && picked);
		}
		rebuildList();
		refreshDetail();
	}

	private boolean onlineNow(String player)
	{
		for (Session.Player p : session.players())
		{
			if (p.name.equalsIgnoreCase(player))
			{
				return true;
			}
		}
		return false;
	}

	private void rebuildList()
	{
		String query = search.getText().trim().toLowerCase();
		StringBuilder signature = new StringBuilder(query).append('|').append(session.selected());
		List<Session.Player> shown = new ArrayList<>();
		for (Session.Player p : session.players())
		{
			if (query.isEmpty() || p.name.toLowerCase().contains(query))
			{
				shown.add(p);
				signature.append(p.name).append(p.combat).append(p.area).append(p.modlevel).append(';');
			}
		}
		count.setText(session.players().size() + " online");
		if (signature.toString().equals(listSignature))
		{
			return;
		}
		listSignature = signature.toString();
		list.removeAll();
		if (shown.isEmpty())
		{
			list.add(hint(session.players().isEmpty() ? "Nobody is online right now." : "No one matches that search.", Theme.TEXT_3));
		}
		for (Session.Player p : shown)
		{
			String badge = Session.rank(p.modlevel) > 0 ? p.modlevel : null;
			Ui.Tile tile = new Ui.Tile(false, () -> Ui.avatar(p.name), Ui.Glyph.NONE, p.name, "Combat " + p.combat + "   " + p.place());
			tile.setBadge(badge);
			tile.setSelected(p.name.equalsIgnoreCase(session.selected()));
			tile.onPick(() -> session.select(p.name));
			tile.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
			tile.setAlignmentX(0f);
			list.add(tile);
			list.add(Box.createVerticalStrut(6));
		}
		list.revalidate();
		list.repaint();
	}

	private void refreshDetail()
	{
		String selected = session.selected();
		Session.Player found = null;
		for (Session.Player p : session.players())
		{
			if (p.name.equalsIgnoreCase(selected))
			{
				found = p;
			}
		}
		if (found == null)
		{
			name.setText(selected.isEmpty() ? "Pick a player" : selected);
			where.setText(selected.isEmpty() ? "Select someone from the list." : "Not online right now.");
			if (!statsFor.isEmpty())
			{
				statsFor = "";
				stats.removeAll();
				stats.repaint();
			}
			return;
		}
		name.setText(found.name);
		where.setText(found.place() + "   " + found.modlevel);
		if (!statsFor.equalsIgnoreCase(found.name))
		{
			statsFor = found.name;
			final String who = found.name;
			session.call(() -> session.api().get("/v1/players/" + ApiClient.enc(who)), json ->
			{
				if (who.equalsIgnoreCase(statsFor))
				{
					showStats(json);
				}
			}, error -> window.flash(error, Theme.RED));
		}
	}

	private void showStats(JsonObject json)
	{
		stats.removeAll();
		JsonObject levels = json.has("stats") && json.get("stats").isJsonObject() ? json.getAsJsonObject("stats") : new JsonObject();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			String level = levels.has(skill.getName()) ? levels.get(skill.getName()).getAsString() : "-";
			JLabel cell = new JLabel(level)
			{
				@Override
				protected void paintComponent(java.awt.Graphics graphics)
				{
					java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
					Theme.smooth(g);
					Theme.icon(g, session.skillIcon(skill), 0, 2, 18, 18);
					g.setFont(Theme.uiBold(14f));
					Theme.shadowText(g, getText(), 26, 16, Theme.YELLOW);
					g.dispose();
				}
			};
			cell.setPreferredSize(new Dimension(60, 22));
			cell.setToolTipText(skill.getName());
			stats.add(cell);
		}
		stats.revalidate();
		stats.repaint();
	}
}
