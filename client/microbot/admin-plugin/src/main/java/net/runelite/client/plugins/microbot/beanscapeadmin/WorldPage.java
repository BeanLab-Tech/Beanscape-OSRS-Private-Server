package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** World numbers, broadcast, reboot countdown, a teleport picker, and a link to the web admin. */
final class WorldPage extends Page
{
	private static final class Destination
	{
		final String name;
		final int x;
		final int z;
		final int level;

		Destination(JsonObject json)
		{
			name = Session.str(json, "name");
			x = json.get("x").getAsInt();
			z = json.get("z").getAsInt();
			level = json.has("level") ? json.get("level").getAsInt() : 0;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	private final Kpi online = new Kpi("Online");
	private final Kpi uptime = new Kpi("Uptime");
	private final Kpi rate = new Kpi("XP rate");
	private final JTextField message = new JTextField();
	private final Ui.Segmented countdown = new Ui.Segmented("1 minute", "5 minutes", "15 minutes");
	private final Ui.Button send = new Ui.Button("Send broadcast", Ui.Kind.STONE, Ui.Glyph.HUD);
	private final Ui.Button reboot = new Ui.Button("Reboot world", Ui.Kind.DANGER, Ui.Glyph.RESTART);
	private final JComboBox<String> categories = new JComboBox<>();
	private final JComboBox<Destination> destinations = new JComboBox<>();
	private final Ui.Button go = new Ui.Button("Teleport", Ui.Kind.PRIMARY, Ui.Glyph.TARGET);
	private final List<List<Destination>> catalog = new ArrayList<>();
	private boolean catalogLoaded;

	WorldPage(Session session, AdminWindow window)
	{
		super("World", "Server health, announcements, restarts and teleports.", "moderator", session, window);
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
		Ui.field(message);
		Ui.combo(categories);
		Ui.combo(destinations);

		JPanel kpis = Ui.clear(new GridLayout(1, 3, 12, 0));
		kpis.add(online);
		kpis.add(uptime);
		kpis.add(rate);

		Ui.Section announce = new Ui.Section("Broadcast", "Sends a message to everyone online.", new BorderLayout(8, 0));
		announce.body().add(message, BorderLayout.CENTER);
		announce.body().add(send, BorderLayout.EAST);
		send.addActionListener(event -> broadcast());

		Ui.Section restart = new Ui.Section("Reboot", "Players get a countdown and are saved before the world restarts.", new BorderLayout(10, 0));
		restart.body().add(countdown, BorderLayout.WEST);
		JPanel restartButton = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		restartButton.add(reboot);
		restart.body().add(restartButton, BorderLayout.CENTER);
		reboot.addActionListener(event -> reboot());

		Ui.Section tele = new Ui.Section("Teleport", "Sends the selected player, or you when nobody is selected.", new BorderLayout(10, 0));
		JPanel pickers = Ui.clear(new GridLayout(1, 2, 8, 0));
		pickers.add(categories);
		pickers.add(destinations);
		tele.body().add(pickers, BorderLayout.CENTER);
		tele.body().add(go, BorderLayout.EAST);
		categories.addActionListener(event -> fillDestinations());
		go.addActionListener(event -> teleport());

		Ui.Section web = new Ui.Section("Web admin", "Server status, logs and anything not in this console.", new BorderLayout());
		Ui.Button open = new Ui.Button("Open web admin", Ui.Kind.STONE, Ui.Glyph.HUD);
		open.addActionListener(event -> openWeb());
		JPanel openRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		openRow.add(open);
		web.body().add(openRow, BorderLayout.CENTER);

		page.add(Ui.scroll(Widgets.top(Page.stack(kpis, announce, restart, tele, web))), BorderLayout.CENTER);
		syncButtons();
		return page;
	}

	@Override
	void setShowing(boolean showing)
	{
		if (showing && !catalogLoaded && session.connected())
		{
			loadCatalog();
		}
	}

	@Override
	void onState()
	{
		JsonObject world = session.world();
		online.set(world.has("online") ? world.get("online").getAsString() : String.valueOf(session.players().size()));
		uptime.set(world.has("uptimeSeconds") ? duration(world.get("uptimeSeconds").getAsLong()) : "-");
		rate.set(world.has("xpRate") ? Math.round(world.get("xpRate").getAsDouble()) + "x" : "-");
		syncButtons();
	}

	private void syncButtons()
	{
		send.setEnabled(session.has("admin"));
		reboot.setEnabled(session.has("owner"));
		go.setEnabled(session.has("moderator") && destinations.getItemCount() > 0);
	}

	private void loadCatalog()
	{
		catalogLoaded = true;
		session.call(() -> session.api().get("/v1/teleports"), json ->
		{
			catalog.clear();
			categories.removeAllItems();
			for (JsonObject category : Session.map(Session.array(json, "categories"), c -> c))
			{
				categories.addItem(Session.str(category, "name"));
				catalog.add(Session.map(Session.array(category, "destinations"), Destination::new));
			}
			fillDestinations();
		}, error ->
		{
			catalogLoaded = false;
			window.flash(error, Theme.RED);
		});
	}

	private void fillDestinations()
	{
		destinations.removeAllItems();
		int index = categories.getSelectedIndex();
		if (index >= 0 && index < catalog.size())
		{
			for (Destination destination : catalog.get(index))
			{
				destinations.addItem(destination);
			}
		}
		syncButtons();
	}

	private void teleport()
	{
		Destination destination = (Destination) destinations.getSelectedItem();
		if (destination == null)
		{
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("target", session.target());
		body.addProperty("x", destination.x);
		body.addProperty("z", destination.z);
		body.addProperty("level", destination.level);
		session.act("/v1/teleport", body, text -> window.flash(text, Theme.GREEN), error -> window.flash(error, Theme.RED));
	}

	private void broadcast()
	{
		String text = message.getText().trim();
		if (text.isEmpty())
		{
			window.flash("Write a message first.", Theme.ORANGE);
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("message", text);
		session.act("/v1/world/broadcast", body, done ->
		{
			message.setText("");
			window.flash(done, Theme.GREEN);
		}, error -> window.flash(error, Theme.RED));
	}

	private void reboot()
	{
		int[] seconds = {60, 300, 900};
		int chosen = seconds[Math.max(0, Math.min(2, countdown.getSelected()))];
		if (!confirm("Reboot world", "Reboot the world in " + (chosen / 60) + " minute(s)? Everyone online is saved and logged out.", "Reboot", true))
		{
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("seconds", chosen);
		session.act("/v1/world/reboot", body, text -> window.flash(text, Theme.ORANGE), error -> window.flash(error, Theme.RED));
	}

	private void openWeb()
	{
		try
		{
			Desktop.getDesktop().browse(URI.create(session.settings().webAdminUrl()));
		}
		catch (Exception ex)
		{
			window.flash("Couldn't open the browser. Go to " + session.settings().webAdminUrl(), Theme.ORANGE);
		}
	}

	private static String duration(long seconds)
	{
		long days = seconds / 86400;
		long hours = seconds % 86400 / 3600;
		long minutes = seconds % 3600 / 60;
		return days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
	}
}
