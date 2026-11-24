package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.api.Skill;

/** Edit a player's levels. Loads their current levels, then applies the whole grid at once. */
final class StatsPage extends Page
{
	private final Map<Skill, Ui.Stepper> steppers = new EnumMap<>(Skill.class);
	private final JLabel target = Ui.label(" ", Theme.uiBold(13f), Theme.GOLD_HI);
	private final Ui.Button apply = new Ui.Button("Apply levels", Ui.Kind.PRIMARY, Ui.Glyph.CHECK);
	private String loadedFor = "";

	StatsPage(Session session, AdminWindow window)
	{
		super("Stats", "Set levels for the selected player, or yourself when nobody is selected.", "admin", session, window);
	}

	@Override
	JComponent build()
	{
		JPanel page = shell();
		Ui.Section grid = new Ui.Section("Levels", "Each skill from 1 to 99. Nothing changes until you apply.", new BorderLayout(0, 10));
		JPanel cells = Ui.clear(new GridLayout(0, 3, 22, 4));
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			Ui.Stepper stepper = new Ui.Stepper(1, 99, 1);
			steppers.put(skill, stepper);
			JPanel cell = Ui.clear(new BorderLayout(8, 0));
			JLabel name = new JLabel(skill.getName())
			{
				@Override
				protected void paintComponent(java.awt.Graphics graphics)
				{
					java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
					Theme.smooth(g);
					Theme.icon(g, session.skillIcon(skill), 0, 3, 18, 18);
					g.setFont(Theme.ui(12.5f));
					g.setColor(Theme.TEXT);
					g.drawString(getText(), 24, 17);
					g.dispose();
				}
			};
			name.setPreferredSize(new java.awt.Dimension(104, 24));
			cell.add(name, BorderLayout.WEST);
			cell.add(stepper, BorderLayout.CENTER);
			cells.add(cell);
		}
		grid.body().add(cells, BorderLayout.NORTH);

		JPanel bar = Ui.clear(new BorderLayout(10, 0));
		bar.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		bar.add(target, BorderLayout.CENTER);
		JPanel buttons = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		Ui.Button all99 = new Ui.Button("All 99", Ui.Kind.STONE, Ui.Glyph.NONE);
		all99.addActionListener(event -> fill(99, 99));
		Ui.Button fresh = new Ui.Button("Fresh account", Ui.Kind.STONE, Ui.Glyph.RESET);
		fresh.addActionListener(event -> fill(1, 10));
		Ui.Button reload = new Ui.Button("Load current", Ui.Kind.STONE, Ui.Glyph.UNDO);
		reload.addActionListener(event -> load(true));
		buttons.add(all99);
		buttons.add(fresh);
		buttons.add(reload);
		buttons.add(apply);
		bar.add(buttons, BorderLayout.EAST);
		page.add(Page.stack(grid, bar), BorderLayout.CENTER);
		apply.addActionListener(event -> applyLevels());
		return page;
	}

	@Override
	void setShowing(boolean showing)
	{
		if (showing)
		{
			load(false);
		}
	}

	@Override
	void onState()
	{
		String who = session.target();
		target.setText(who.isEmpty() ? " " : "Editing " + who + (session.selected().isEmpty() ? " (you)" : ""));
		apply.setEnabled(session.has("admin") && !who.isEmpty());
	}

	private void fill(int level, int hitpoints)
	{
		for (Map.Entry<Skill, Ui.Stepper> entry : steppers.entrySet())
		{
			entry.getValue().setValue(entry.getKey() == Skill.HITPOINTS ? hitpoints : level);
		}
	}

	private void load(boolean force)
	{
		String who = session.target();
		if (who.isEmpty() || (!force && who.equalsIgnoreCase(loadedFor)))
		{
			return;
		}
		loadedFor = who;
		session.call(() -> session.api().get("/v1/players/" + ApiClient.enc(who)), json ->
		{
			JsonObject levels = json.has("stats") && json.get("stats").isJsonObject() ? json.getAsJsonObject("stats") : new JsonObject();
			for (Map.Entry<Skill, Ui.Stepper> entry : steppers.entrySet())
			{
				String key = entry.getKey().getName();
				if (levels.has(key))
				{
					entry.getValue().setValue(levels.get(key).getAsInt());
				}
			}
		}, error ->
		{
			loadedFor = "";
			window.flash(error, Theme.RED);
		});
	}

	private void applyLevels()
	{
		String who = session.target();
		if (!confirm("Set " + who + "'s levels to the values shown?"))
		{
			return;
		}
		JsonObject levels = new JsonObject();
		for (Map.Entry<Skill, Ui.Stepper> entry : steppers.entrySet())
		{
			levels.addProperty(entry.getKey().getName(), entry.getValue().getValue());
		}
		JsonObject body = new JsonObject();
		body.add("levels", levels);
		session.act("/v1/players/" + ApiClient.enc(who) + "/stats", body,
			message -> window.flash(message, Theme.GREEN), error -> window.flash(error, Theme.RED));
	}
}
