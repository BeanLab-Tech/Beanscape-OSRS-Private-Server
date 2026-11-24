package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** Find an item by name and give it to the selected player (or yourself). Icons come from the wiki. */
final class ItemsPage extends Page
{
	private static final class Item
	{
		final int id;
		final String name;

		Item(JsonObject json)
		{
			id = json.get("id").getAsInt();
			name = Session.str(json, "name");
		}
	}

	private final WikiIcons icons;
	private final JTextField search = new JTextField();
	private final JPanel results = Ui.clear(null);
	private final JLabel chosen = Ui.label("No item chosen", Theme.display(16f), Theme.GOLD_HI);
	private final JLabel target = Ui.label(" ", Theme.ui(12.5f), Theme.TEXT_2);
	private final Ui.Stepper quantity = new Ui.Stepper(1, 2_000_000_000, 1);
	private final Ui.Button give = new Ui.Button("Give item", Ui.Kind.PRIMARY, Ui.Glyph.SAVE);
	private final List<Item> found = new ArrayList<>();
	private Item picked;

	ItemsPage(Session session, AdminWindow window, WikiIcons icons)
	{
		super("Items", "Search by name, pick an item, and give it to the selected player.", "admin", session, window);
		this.icons = icons;
	}

	@Override
	JComponent build()
	{
		JPanel page = shell();
		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		Ui.field(search);
		search.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER)
				{
					runSearch();
				}
			}
		});
		Ui.Button go = new Ui.Button("Search", Ui.Kind.STONE, Ui.Glyph.NONE);
		go.addActionListener(event -> runSearch());

		Ui.Section find = new Ui.Section("Find an item", "Type part of a name, like whip or rune plate.", new BorderLayout(0, 8));
		JPanel bar = Ui.clear(new BorderLayout(8, 0));
		bar.add(search, BorderLayout.CENTER);
		bar.add(go, BorderLayout.EAST);
		find.body().add(bar, BorderLayout.NORTH);
		find.body().add(Ui.scroll(results), BorderLayout.CENTER);
		find.setPreferredSize(new Dimension(380, 100));
		showResults();

		Ui.Section giveBox = new Ui.Section("Give", "Items go to the target's inventory.", new BorderLayout(0, 12));
		JPanel form = Ui.clear(new BorderLayout(0, 10));
		JPanel head = Ui.clear(new BorderLayout(0, 2));
		head.add(chosen, BorderLayout.NORTH);
		head.add(target, BorderLayout.CENTER);
		form.add(head, BorderLayout.NORTH);
		form.add(Ui.settingRow("Quantity", "How many to give.", quantity), BorderLayout.CENTER);
		JPanel buttons = Ui.clear(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		buttons.add(give);
		form.add(buttons, BorderLayout.SOUTH);
		giveBox.body().add(form, BorderLayout.NORTH);
		give.addActionListener(event -> giveItem());
		updateTarget();

		JPanel columns = Ui.clear(new BorderLayout(14, 0));
		columns.add(find, BorderLayout.WEST);
		columns.add(giveBox, BorderLayout.CENTER);
		page.add(columns, BorderLayout.CENTER);
		return page;
	}

	@Override
	void onState()
	{
		updateTarget();
	}

	private void updateTarget()
	{
		String who = session.target();
		target.setText(who.isEmpty() ? "Connect to choose a target." : "To: " + who + (session.selected().isEmpty() ? " (you)" : ""));
		give.setEnabled(session.has("admin") && picked != null && !who.isEmpty());
	}

	private void runSearch()
	{
		String query = search.getText().trim();
		if (query.length() < 2)
		{
			window.flash("Type at least two letters.", Theme.ORANGE);
			return;
		}
		session.call(() -> session.api().get("/v1/items/search?q=" + ApiClient.enc(query)), json ->
		{
			found.clear();
			found.addAll(Session.map(Session.array(json, "items"), Item::new));
			showResults();
		}, error -> window.flash(error, Theme.RED));
	}

	private void showResults()
	{
		results.removeAll();
		if (found.isEmpty())
		{
			results.add(hint("Search results show up here.", Theme.TEXT_3));
		}
		for (Item item : found)
		{
			Ui.Tile real = new Ui.Tile(false, icons.supplier(item.id, item.name, results), Ui.Glyph.NONE, item.name, "Item " + item.id);
			real.setSelected(picked != null && picked.id == item.id);
			real.onPick(() ->
			{
				picked = item;
				chosen.setText(item.name);
				updateTarget();
				showResults();
			});
			real.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
			real.setAlignmentX(0f);
			results.add(real);
			results.add(Box.createVerticalStrut(6));
		}
		results.revalidate();
		results.repaint();
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
}
