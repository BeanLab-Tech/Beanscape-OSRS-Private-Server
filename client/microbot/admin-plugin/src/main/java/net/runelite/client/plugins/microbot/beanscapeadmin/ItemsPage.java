package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Find an item by name and give it to the selected player (or yourself). The server says what each
 * item is for, so gear, food and potions come first and items that do nothing are tucked away.
 */
final class ItemsPage extends Page
{
	/** What an item is good for, as the server classifies it. Order is display priority. */
	private enum Kind
	{
		GEAR("Gear", Theme.BLUE),
		CONSUMABLE("Consumable", Theme.GREEN),
		USABLE("Usable", Theme.GOLD),
		MISC("Misc", Theme.TEXT_3),
		JUNK("Unusable", Theme.RED);

		final String label;
		final Color tone;

		Kind(String label, Color tone)
		{
			this.label = label;
			this.tone = tone;
		}

		static Kind of(String raw)
		{
			switch (raw == null ? "" : raw)
			{
				case "equipment":
					return GEAR;
				case "consumable":
					return CONSUMABLE;
				case "usable":
					return USABLE;
				case "junk":
					return JUNK;
				default:
					return MISC;
			}
		}
	}

	private static final class Item
	{
		final int id;
		final String name;
		final Kind kind;
		final boolean tradeable;
		final boolean members;

		Item(JsonObject json)
		{
			id = json.get("id").getAsInt();
			name = Session.str(json, "name");
			kind = Kind.of(Session.str(json, "kind"));
			tradeable = !json.has("tradeable") || json.get("tradeable").getAsBoolean();
			members = json.has("members") && json.get("members").getAsBoolean();
		}
	}

	private static final String[] FILTERS = {"All", "Gear", "Consumables", "Other"};
	private static final String[] QUICK = {"1", "10", "100", "1K", "10K", "100K", "1M", "10M", "100M", "1B"};
	private static final int[] QUICK_VALUE = {1, 10, 100, 1000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, 1_000_000_000};

	private final ItemIcons icons;
	private final JTextField search = new Widgets.SearchField("Search items by name");
	private final JPanel results = Ui.clear(null);
	private final JLabel summary = Ui.label(" ", Theme.ui(12f), Theme.TEXT_3);
	private final Widgets.FilterChip[] filters = new Widgets.FilterChip[FILTERS.length];
	private final Widgets.FilterChip showJunk = new Widgets.FilterChip("Show unusable", false);
	private final PickedCard card = new PickedCard();
	private final Widgets.AvatarView targetAvatar = new Widgets.AvatarView(36);
	private final JLabel targetName = Ui.label(" ", Theme.uiBold(14f), Theme.TEXT);
	private final JLabel targetNote = Ui.label(" ", Theme.ui(12f), Theme.TEXT_3);
	private final Ui.Stepper quantity = new Ui.Stepper(1, 2_000_000_000, 1);
	private final Ui.Button give = new Ui.Button("Give item", Ui.Kind.PRIMARY, Ui.Glyph.SAVE);
	private final List<Item> found = new ArrayList<>();
	private final Timer debounce;
	private Item picked;
	private int filter;
	private int sequence;
	private String lastQuery = "";
	private String targetKey = "";

	ItemsPage(Session session, AdminWindow window, ItemIcons icons)
	{
		super("Items", "Find an item and give it to the selected player.", "admin", session, window);
		this.icons = icons;
		debounce = new Timer(320, event -> runSearch(false));
		debounce.setRepeats(false);
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
		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		search.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER)
				{
					debounce.stop();
					runSearch(true);
				}
			}
		});
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			private void changed()
			{
				debounce.restart();
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				changed();
			}
		});

		JPanel chips = Ui.clear(new FlowLayout(FlowLayout.LEFT, 6, 0));
		for (int i = 0; i < FILTERS.length; i++)
		{
			final int index = i;
			filters[i] = new Widgets.FilterChip(FILTERS[i], i == 0).onChange(() -> chooseFilter(index));
			chips.add(filters[i]);
		}
		showJunk.onChange(this::showResults);

		Ui.Section find = new Ui.Section("Find an item", null, new BorderLayout(0, 10));
		JPanel countRow = Ui.clear(new BorderLayout(8, 0));
		countRow.add(summary, BorderLayout.CENTER);
		countRow.add(showJunk, BorderLayout.EAST);
		JPanel head = Widgets.column(8, search, chips, countRow);
		find.body().add(head, BorderLayout.NORTH);
		find.body().add(Ui.scroll(results), BorderLayout.CENTER);
		showResults();

		Ui.Section giveBox = new Ui.Section("Give", "Items go straight into the target's inventory.", new BorderLayout());
		give.addActionListener(event -> giveItem());
		giveBox.body().add(Ui.scroll(Widgets.top(giveBody())), BorderLayout.CENTER);

		page.add(new Widgets.Split(find, giveBox, 0.52, 340, 560, 600), BorderLayout.CENTER);
		updateTarget();
		return page;
	}

	private JComponent giveBody()
	{
		JPanel targetRow = Ui.clear(new BorderLayout(10, 0));
		targetRow.add(targetAvatar, BorderLayout.WEST);
		JPanel words = Ui.clear(new BorderLayout(0, 1));
		words.add(Ui.label("GIVE TO", Theme.eyebrow(10f), Theme.TEXT_4), BorderLayout.NORTH);
		JPanel names = Ui.clear(new BorderLayout(0, 0));
		names.add(targetName, BorderLayout.NORTH);
		names.add(targetNote, BorderLayout.CENTER);
		words.add(names, BorderLayout.CENTER);
		targetRow.add(words, BorderLayout.CENTER);

		JPanel quick = Ui.clear(new Widgets.WrapGrid(46, 30, 6));
		for (int i = 0; i < QUICK.length; i++)
		{
			final int value = QUICK_VALUE[i];
			Ui.Button button = new Ui.Button(QUICK[i], Ui.Kind.STONE, Ui.Glyph.NONE);
			button.addActionListener(event -> quantity.setValue(value));
			quick.add(button);
		}
		JPanel amount = Ui.clear(new BorderLayout(0, 8));
		amount.add(Ui.label("QUANTITY", Theme.eyebrow(10f), Theme.TEXT_4), BorderLayout.NORTH);
		JPanel entry = Ui.clear(new BorderLayout(0, 8));
		quantity.setPreferredSize(new Dimension(220, 32));
		JPanel stepperRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		stepperRow.add(quantity);
		entry.add(stepperRow, BorderLayout.NORTH);
		entry.add(quick, BorderLayout.CENTER);
		amount.add(entry, BorderLayout.CENTER);

		JPanel action = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		action.add(give);
		return Widgets.column(16, card, targetRow, amount, action);
	}

	@Override
	void onState()
	{
		updateTarget();
	}

	private void updateTarget()
	{
		String who = session.target();
		String key = who + "|" + session.avatarOf(who);
		if (!key.equals(targetKey))
		{
			targetKey = key;
			targetAvatar.set(session.avatars().supplier(session.avatarOf(who), who, targetAvatar), who.isEmpty() ? null : Boolean.TRUE);
		}
		targetName.setText(who.isEmpty() ? "No target" : who);
		targetNote.setText(who.isEmpty() ? "Connect to choose a target." : session.selected().isEmpty() ? "That is you. Pick a player to send it to someone else." : "Selected on the Players page.");
		give.setEnabled(session.has("admin") && picked != null && !who.isEmpty());
	}

	private void chooseFilter(int index)
	{
		filter = index;
		for (int i = 0; i < filters.length; i++)
		{
			filters[i].setSelected(i == index);
		}
		showResults();
	}

	private void runSearch(boolean loud)
	{
		String query = search.getText().trim();
		if (query.length() < 2)
		{
			found.clear();
			lastQuery = "";
			showResults();
			if (loud)
			{
				window.flash("Type at least two letters.", Theme.ORANGE);
			}
			return;
		}
		lastQuery = query;
		final int mine = ++sequence;
		summary.setText("Searching…");
		session.call(() -> session.api().get("/v1/items/search?q=" + ApiClient.enc(query)), json ->
		{
			if (mine != sequence)
			{
				return;
			}
			found.clear();
			found.addAll(Session.map(Session.array(json, "items"), Item::new));
			found.sort(relevance(query.toLowerCase()));
			showResults();
		}, error ->
		{
			if (mine == sequence)
			{
				summary.setText(" ");
				window.flash(error, Theme.RED);
			}
		});
	}

	/** Working items first, then the best name match, then the shortest name. */
	private static Comparator<Item> relevance(String needle)
	{
		return Comparator
			.comparingInt((Item i) -> i.kind == Kind.JUNK ? 1 : 0)
			.thenComparingInt(i -> match(i.name.toLowerCase(), needle))
			.thenComparingInt(i -> i.kind.ordinal())
			.thenComparingInt(i -> i.name.length())
			.thenComparing(i -> i.name);
	}

	private static int match(String name, String needle)
	{
		if (name.equals(needle))
		{
			return 0;
		}
		if (name.startsWith(needle))
		{
			return 1;
		}
		return name.contains(" " + needle) ? 2 : 3;
	}

	private boolean passes(Item item)
	{
		if (item.kind == Kind.JUNK)
		{
			return showJunk.isSelected();
		}
		switch (filter)
		{
			case 1:
				return item.kind == Kind.GEAR;
			case 2:
				return item.kind == Kind.CONSUMABLE;
			case 3:
				return item.kind == Kind.USABLE || item.kind == Kind.MISC;
			default:
				return true;
		}
	}

	private void showResults()
	{
		results.removeAll();
		int shown = 0;
		int hiddenJunk = 0;
		for (Item item : found)
		{
			if (item.kind == Kind.JUNK && !showJunk.isSelected())
			{
				hiddenJunk++;
			}
			if (!passes(item))
			{
				continue;
			}
			shown++;
			ItemRow row = new ItemRow(item);
			row.setAlignmentX(0f);
			results.add(row);
			results.add(Box.createVerticalStrut(5));
		}
		if (found.isEmpty())
		{
			results.add(lastQuery.isEmpty()
				? Widgets.empty("Search the item list", "Type part of a name, like whip, rune plate or shark. Working items are listed first.")
				: Widgets.empty("Nothing found", "No items match “" + lastQuery + "”."));
			summary.setText(" ");
		}
		else
		{
			if (shown == 0)
			{
				results.add(Widgets.empty("Nothing in this view", hiddenJunk > 0 ? "The matches are items that do nothing. Switch on Show unusable to see them." : "Try another filter."));
			}
			String text = shown + (shown == 1 ? " item" : " items");
			if (hiddenJunk > 0)
			{
				text += "  ·  " + hiddenJunk + " unusable hidden";
			}
			summary.setText(text);
		}
		results.revalidate();
		results.repaint();
	}

	private void pick(Item item)
	{
		picked = item;
		card.repaint();
		updateTarget();
		showResults();
	}

	private void giveItem()
	{
		if (picked == null)
		{
			return;
		}
		String who = session.target();
		JsonObject body = new JsonObject();
		body.addProperty("id", picked.id);
		body.addProperty("qty", quantity.getValue());
		session.act("/v1/players/" + ApiClient.enc(who) + "/give", body,
			message -> window.flash(message, Theme.GREEN), error -> window.flash(error, Theme.RED));
	}

	// ------------------------------------------------------------------ painting

	/** The chosen item, large, so there is no doubt what is about to be given. */
	private final class PickedCard extends JComponent
	{
		PickedCard()
		{
			setPreferredSize(new Dimension(300, 92));
			setMinimumSize(new Dimension(160, 92));
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = getWidth();
			int h = getHeight();
			Theme.well(g, 0, 0, w, h, 8);
			if (picked == null)
			{
				g.setFont(Theme.uiBold(14f));
				g.setColor(Theme.TEXT_3);
				g.drawString("No item chosen", 18, h / 2 - 2);
				g.setFont(Theme.ui(12f));
				g.setColor(Theme.TEXT_4);
				g.drawString("Pick one from the list.", 18, h / 2 + 16);
				g.dispose();
				return;
			}
			Theme.stone(g, 14, 14, 64, 64, 8, Theme.SLOT_2, new Color(0x2A2218));
			Image image = icons.supplier(picked.id, picked.name, this).get();
			if (image != null)
			{
				Theme.icon(g, image, 18, 18, 56, 56);
			}
			int tx = 94;
			g.setFont(Theme.display(17f));
			Theme.shadowText(g, Theme.fit(g.getFontMetrics(), picked.name, w - tx - 12), tx, 38, Theme.GOLD_HI);
			g.setFont(Theme.ui(12f));
			g.setColor(Theme.TEXT_3);
			g.drawString("Item " + picked.id + (picked.members ? "  ·  Members" : ""), tx, 56);
			int x = tx;
			x += Widgets.chip(g, picked.kind.label, x, 64, picked.kind.tone, true) + 6;
			Widgets.chip(g, picked.tradeable ? "Tradeable" : "Untradeable", x, 64, Theme.TEXT_3, false);
			g.dispose();
		}
	}

	/** One search result. Unusable items are dimmed so the working ones stand out. */
	private final class ItemRow extends JComponent
	{
		private final Item item;
		private boolean hover;

		ItemRow(Item item)
		{
			this.item = item;
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setPreferredSize(new Dimension(260, 54));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					requestFocusInWindow();
					pick(item);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}
			});
			addKeyListener(new KeyAdapter()
			{
				@Override
				public void keyPressed(KeyEvent e)
				{
					if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER)
					{
						pick(item);
					}
				}
			});
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = getWidth();
			int h = getHeight();
			boolean selected = picked != null && picked.id == item.id;
			boolean junk = item.kind == Kind.JUNK;
			Composite old = junk ? Theme.fade(g, 0.5f) : g.getComposite();
			if (selected)
			{
				Theme.stone(g, 0, 0, w, h, 8, new Color(0x4A3C2B), new Color(0x2E2519));
				g.setPaint(new GradientPaint(0, 0, new Color(226, 180, 86, 54), w, h, new Color(226, 180, 86, 8)));
				g.fillRoundRect(1, 1, w - 2, h - 2, 8, 8);
				g.setPaint(null);
				g.setColor(new Color(226, 180, 86, 170));
				g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
			}
			else
			{
				Theme.stone(g, 0, 0, w, h, 8, hover ? Theme.STONE_3 : Theme.STONE_2, Theme.STONE_1);
				if (hover)
				{
					g.setColor(Theme.GOLD_LO);
					g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
				}
			}
			if (isFocusOwner())
			{
				g.setColor(Theme.alpha(Theme.GOLD_HI, 150));
				g.drawRoundRect(3, 3, w - 7, h - 7, 6, 6);
			}
			Theme.well(g, 8, 7, 40, 40, 6);
			Image image = icons.supplier(item.id, item.name, this).get();
			if (image != null)
			{
				Theme.icon(g, image, 10, 9, 36, 36);
			}
			else if (icons.isMissing(item.id))
			{
				g.setFont(Theme.uiBold(18f));
				g.setColor(Theme.TEXT_4);
				g.drawString("?", 24, 33);
			}
			int tx = 58;
			int chipWidth = chipWidth(g, item.kind.label);
			int textRight = w - chipWidth - 20;
			g.setFont(Theme.uiBold(13.5f));
			g.setColor(selected ? Theme.GOLD_HI : Theme.TEXT);
			g.drawString(Theme.fit(g.getFontMetrics(), item.name, textRight - tx), tx, 24);
			g.setFont(Theme.ui(11.5f));
			g.setColor(Theme.TEXT_3);
			String detail = "ID " + item.id + (item.tradeable ? "" : "  ·  Untradeable") + (item.members ? "  ·  Members" : "");
			g.drawString(Theme.fit(g.getFontMetrics(), detail, textRight - tx), tx, 41);
			Widgets.chip(g, item.kind.label, w - chipWidth - 10, (h - 16) / 2, item.kind.tone, item.kind != Kind.MISC && !junk);
			g.setComposite(old);
			g.dispose();
		}

		private int chipWidth(Graphics2D g, String text)
		{
			g.setFont(Theme.eyebrow(9.5f));
			FontMetrics fm = g.getFontMetrics();
			return fm.stringWidth(text.toUpperCase()) + 14;
		}
	}
}
