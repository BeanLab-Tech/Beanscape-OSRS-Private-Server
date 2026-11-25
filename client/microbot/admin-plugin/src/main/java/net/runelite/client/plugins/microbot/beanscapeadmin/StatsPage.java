package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Skill;

/**
 * Edit a player's levels. One compact cell per skill, the skill's own icon beside its stepper, laid
 * out so all 23 fit on screen without scrolling. Loads current levels, then applies the grid at once.
 */
final class StatsPage extends Page
{
	private final Map<Skill, Ui.Stepper> steppers = new EnumMap<>(Skill.class);
	private final Ui.Button apply = new Ui.Button("Apply levels", Ui.Kind.PRIMARY, Ui.Glyph.CHECK);
	private final TotalCell total = new TotalCell();
	private String loadedFor = "";

	StatsPage(Session session, AdminWindow window)
	{
		super("Stats", "Set levels for the selected player, or yourself when nobody is selected.", "admin", session, window);
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
		Ui.Section grid = new Ui.Section("Levels", "Each skill from 1 to 99. Nothing changes until you apply.", new BorderLayout());
		JPanel cells = Ui.clear(new java.awt.GridLayout(0, 3, 14, 6));
		for (Skill skill : Widgets.GAME_ORDER)
		{
			Ui.Stepper stepper = new Ui.Stepper(1, 99, 1).compact().onChange(this::updateTotal);
			steppers.put(skill, stepper);
			JPanel cell = Ui.clear(new BorderLayout(8, 0));
			cell.add(new SkillBadge(skill), BorderLayout.WEST);
			cell.add(stepper, BorderLayout.CENTER);
			cells.add(cell);
		}
		cells.add(total);
		grid.body().add(Widgets.top(cells), BorderLayout.NORTH);

		JPanel bar = Ui.clear(new BorderLayout(10, 0));
		bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
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

		page.add(Widgets.top(Widgets.column(12, grid, bar)), BorderLayout.CENTER);
		apply.addActionListener(event -> applyLevels());
		return page;
	}

	private void updateTotal()
	{
		int sum = 0;
		for (Ui.Stepper stepper : steppers.values())
		{
			sum += stepper.getValue();
		}
		total.set(sum);
	}

	/** The in-game "Total level" cell, kept in step with the grid as levels change. */
	private final class TotalCell extends JComponent
	{
		private int sum;

		TotalCell()
		{
			setPreferredSize(new Dimension(100, 30));
			setToolTipText("Total level");
		}

		void set(int next)
		{
			if (next != sum)
			{
				sum = next;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			Theme.well(g, 0, 1, getWidth(), 30, 6);
			Image image = session.skillIcon(null);
			if (image != null)
			{
				Theme.icon(g, image, 5, 5, 22, 22);
			}
			g.setFont(Theme.eyebrow(9.5f));
			g.setColor(Theme.TEXT_3);
			g.drawString("TOTAL", 36, 20);
			g.setFont(Theme.uiBold(14f));
			String text = String.format("%,d", sum);
			Theme.shadowText(g, text, getWidth() - 10 - g.getFontMetrics().stringWidth(text), 21, Theme.YELLOW);
			g.dispose();
		}
	}

	/** The skill's icon in a small well. Shows the skill's name on hover. */
	private final class SkillBadge extends JComponent
	{
		private final Skill skill;

		SkillBadge(Skill skill)
		{
			this.skill = skill;
			setPreferredSize(new Dimension(32, 30));
			setToolTipText(skill.getName());
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			Theme.well(g, 0, 1, 32, 30, 6);
			Image image = session.skillIcon(skill);
			if (image != null)
			{
				Theme.icon(g, image, 5, 5, 22, 22);
			}
			else
			{
				g.setFont(Theme.eyebrow(9.5f));
				g.setColor(Theme.TEXT_3);
				g.drawString(skill.getName().substring(0, 3).toUpperCase(), 5, 21);
			}
			g.dispose();
		}
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
		apply.setEnabled(session.has("admin") && !who.isEmpty());
		if (!who.equalsIgnoreCase(loadedFor) && isLive())
		{
			load(false);
		}
	}

	private boolean isLive()
	{
		return session.connected() && !session.target().isEmpty();
	}

	private void fill(int level, int hitpoints)
	{
		for (Map.Entry<Skill, Ui.Stepper> entry : steppers.entrySet())
		{
			entry.getValue().setValue(entry.getKey() == Skill.HITPOINTS ? hitpoints : level);
		}
		updateTotal();
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
			updateTotal();
		}, error ->
		{
			loadedFor = "";
			window.flash(error, Theme.RED);
		});
	}

	private void applyLevels()
	{
		String who = session.target();
		if (!confirm("Set levels", "Set " + who + "'s levels to the values shown?", "Apply levels", false))
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
