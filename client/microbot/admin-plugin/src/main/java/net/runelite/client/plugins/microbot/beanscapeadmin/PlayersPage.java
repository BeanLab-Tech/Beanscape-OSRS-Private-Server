package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
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
	private final JTextField search = new Widgets.SearchField("Search players");
	private final JPanel list = Ui.clear(null);
	private final JLabel count = Ui.label(" ", Theme.ui(12f), Theme.TEXT_3);
	private final CardLayout detailCards = new CardLayout();
	private final JPanel detail = Ui.clear(detailCards);
	private final Widgets.AvatarView avatar = new Widgets.AvatarView(56);
	private final JLabel name = Ui.label(" ", Theme.display(20f), Theme.GOLD_HI);
	private final JLabel rank = Ui.label(" ", Theme.uiBold(11f), Theme.TEXT_3);
	private final JLabel where = Ui.label(" ", Theme.ui(13f), Theme.TEXT_2);
	private final JLabel discord = Ui.label(" ", Theme.ui(12f), Theme.TEXT_3);
	private final JPanel stats = Ui.clear(new java.awt.GridLayout(0, 3, 6, 3));
	private final List<Ui.Button> moderatorButtons = new ArrayList<>();
	private final List<Ui.Button> adminButtons = new ArrayList<>();
	private String listSignature = "";
	private String statsFor = "";
	private String avatarFor = "";

	PlayersPage(Session session, AdminWindow window)
	{
		super("Players", "Who is online, with one-click moderation.", "moderator", session, window);
	}

	@Override
	boolean targeted()
	{
		return true;
	}

	@Override
	JComponent build()
	{
		JPanel page = shell();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
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

		Ui.Section online = new Ui.Section("Online now", null, new BorderLayout(0, 8));
		JPanel top = Ui.clear(new BorderLayout(0, 6));
		top.add(search, BorderLayout.NORTH);
		top.add(count, BorderLayout.SOUTH);
		online.body().add(top, BorderLayout.NORTH);
		online.body().add(Ui.scroll(list), BorderLayout.CENTER);

		Ui.Section selected = new Ui.Section("Selected player", null, new BorderLayout());
		detail.add(Widgets.empty("Pick a player", "Choose someone from the list to see their levels and moderation tools."), "empty");
		detail.add(Ui.scroll(Widgets.top(detailBody())), "detail");
		selected.body().add(detail, BorderLayout.CENTER);

		page.add(new Widgets.Split(online, selected, 0.38, 300, 420, 600), BorderLayout.CENTER);
		rebuildList();
		return page;
	}

	private JComponent detailBody()
	{
		JPanel head = Ui.clear(new BorderLayout(14, 0));
		head.add(avatar, BorderLayout.WEST);
		JPanel words = Ui.clear(null);
		words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
		JPanel nameRow = Ui.clear(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 10, 0));
		nameRow.add(name);
		nameRow.add(rank);
		for (JComponent c : new JComponent[]{nameRow, where, discord})
		{
			c.setAlignmentX(0f);
			words.add(c);
		}
		JPanel center = Ui.clear(new java.awt.GridBagLayout());
		center.add(words);
		JPanel wordsHolder = Ui.clear(new BorderLayout());
		wordsHolder.add(words, BorderLayout.CENTER);
		head.add(wordsHolder, BorderLayout.CENTER);

		Ui.Button give = new Ui.Button("Give item", Ui.Kind.STONE, Ui.Glyph.SAVE);
		give.addActionListener(event -> window.show(AdminWindow.ITEMS));
		adminButtons.add(give);
		Ui.Button setStats = new Ui.Button("Set stats", Ui.Kind.STONE, Ui.Glyph.GEAR);
		setStats.addActionListener(event -> window.show(AdminWindow.STATS));
		adminButtons.add(setStats);
		Ui.Button wipe = new Ui.Button("Wipe inventory", Ui.Kind.DANGER, Ui.Glyph.TRASH);
		wipe.addActionListener(event -> run("wipe-inventory", "Wipe inventory", "Wipe %s's inventory? This can't be undone.", "Wipe"));
		adminButtons.add(wipe);

		JPanel levels = Ui.clear(new BorderLayout(0, 8));
		levels.add(Ui.label("LEVELS", Theme.eyebrow(10f), Theme.TEXT_4), BorderLayout.NORTH);
		levels.add(stats, BorderLayout.CENTER);

		return Widgets.column(10,
			head,
			group("Actions",
				moderator(new Ui.Button("Teleport to", Ui.Kind.STONE, Ui.Glyph.TARGET), "teleport-to", null, null, null),
				moderator(new Ui.Button("Summon here", Ui.Kind.STONE, Ui.Glyph.LOAD), "summon", null, null, null),
				moderator(new Ui.Button("Heal", Ui.Kind.STONE, Ui.Glyph.CHECK), "heal", null, null, null),
				give,
				setStats),
			levels,
			group("Danger zone",
				moderator(new Ui.Button("Kick", Ui.Kind.DANGER, Ui.Glyph.STOP), "kick", "Kick player", "Kick %s from the game?", "Kick"),
				wipe));
	}

	private static JComponent group(String title, JComponent... buttons)
	{
		JPanel grid = Ui.clear(new Widgets.WrapGrid(138, 34, 8));
		for (JComponent button : buttons)
		{
			grid.add(button);
		}
		JPanel box = Ui.clear(new BorderLayout(0, 8));
		box.add(Ui.label(title.toUpperCase(), Theme.eyebrow(10f), Theme.TEXT_4), BorderLayout.NORTH);
		box.add(grid, BorderLayout.CENTER);
		return box;
	}

	private Ui.Button moderator(Ui.Button button, String action, String heading, String confirmText, String confirmLabel)
	{
		moderatorButtons.add(button);
		button.addActionListener(event -> run(action, heading, confirmText, confirmLabel));
		return button;
	}

	private void run(String action, String heading, String confirmText, String confirmLabel)
	{
		String target = session.selected();
		if (target.isEmpty())
		{
			window.flash("Select a player first.", Theme.ORANGE);
			return;
		}
		if (confirmText != null && !confirm(heading, String.format(confirmText, target), confirmLabel, true))
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
				signature.append(p.name).append(p.combat).append(p.area).append(p.modlevel).append(p.avatar).append(';');
			}
		}
		count.setText(query.isEmpty() ? session.players().size() + " online" : shown.size() + " of " + session.players().size() + " online");
		if (signature.toString().equals(listSignature))
		{
			return;
		}
		listSignature = signature.toString();
		list.removeAll();
		if (shown.isEmpty())
		{
			list.add(Widgets.empty(session.players().isEmpty() ? "Nobody is online" : "No match",
				session.players().isEmpty() ? "Players appear here as they log in." : "Nobody online has that name."));
		}
		for (Session.Player p : shown)
		{
			Ui.Tile tile = new Ui.Tile(false, session.avatars().supplier(p.avatar, p.name, list), Ui.Glyph.NONE, p.name,
				"Combat " + p.combat + "  ·  " + p.place());
			tile.round(Boolean.TRUE);
			tile.setBadge(Session.rank(p.modlevel) > 0 ? p.modlevel : null);
			tile.setSelected(p.name.equalsIgnoreCase(session.selected()));
			tile.onPick(() -> session.select(p.name));
			tile.setPreferredSize(new Dimension(220, 58));
			tile.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
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
			detailCards.show(detail, "empty");
			statsFor = "";
			avatarFor = "";
			return;
		}
		detailCards.show(detail, "detail");
		name.setText(found.name);
		rank.setText(" ");
		rank.setIcon(new RankIcon(found.modlevel));
		where.setText(found.place() + "  ·  Combat " + found.combat);
		discord.setText(found.discord.isEmpty() ? "No Discord account linked" : "Discord: " + found.discord);
		String avatarKey = found.name + "|" + found.avatar;
		if (!avatarKey.equals(avatarFor))
		{
			avatarFor = avatarKey;
			avatar.set(session.avatars().supplier(found.avatar, found.name, avatar), Boolean.TRUE);
		}
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

	/** Paints the rank as a chip in place of an icon, so the name row stays one line. */
	private static final class RankIcon implements javax.swing.Icon
	{
		private final String level;

		RankIcon(String level)
		{
			this.level = level;
		}

		@Override
		public void paintIcon(java.awt.Component c, Graphics graphics, int x, int y)
		{
			if (Session.rank(level) == 0)
			{
				return;
			}
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			Widgets.chip(g, level, x, y, Widgets.rankTone(level), true);
			g.dispose();
		}

		@Override
		public int getIconWidth()
		{
			if (Session.rank(level) == 0)
			{
				return 0;
			}
			return new java.awt.Canvas().getFontMetrics(Theme.eyebrow(9.5f)).stringWidth(level.toUpperCase()) + 14;
		}

		@Override
		public int getIconHeight()
		{
			return 16;
		}
	}

	private void showStats(JsonObject json)
	{
		stats.removeAll();
		JsonObject levels = json.has("stats") && json.get("stats").isJsonObject() ? json.getAsJsonObject("stats") : new JsonObject();
		int total = 0;
		for (Skill skill : Widgets.GAME_ORDER)
		{
			String level = levels.has(skill.getName()) ? levels.get(skill.getName()).getAsString() : "-";
			total += levels.has(skill.getName()) ? levels.get(skill.getName()).getAsInt() : 0;
			stats.add(new LevelCell(skill, level));
		}
		stats.add(new LevelCell(null, Integer.toString(total)));
		stats.revalidate();
		stats.repaint();
	}

	/** Skill sprite (or its short name when the client has none) beside the level in stat yellow. */
	private final class LevelCell extends JComponent
	{
		private final Skill skill;
		private final String level;

		LevelCell(Skill skill, String level)
		{
			this.skill = skill;
			this.level = level;
			setToolTipText(skill == null ? "Total level" : skill.getName());
			setPreferredSize(new Dimension(70, 24));
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			Theme.well(g, 0, 0, getWidth(), getHeight(), 6);
			java.awt.Image image = session.skillIcon(skill);
			if (image != null)
			{
				Theme.icon(g, image, 6, 3, 18, 18);
			}
			else
			{
				g.setFont(Theme.eyebrow(9.5f));
				g.setColor(Theme.TEXT_3);
				g.drawString(skill.getName().substring(0, 3).toUpperCase(), 6, 16);
			}
			g.setFont(Theme.uiBold(13.5f));
			String text = level;
			Theme.shadowText(g, text, getWidth() - 8 - g.getFontMetrics().stringWidth(text), 17, Theme.YELLOW);
			g.dispose();
		}
	}
}
