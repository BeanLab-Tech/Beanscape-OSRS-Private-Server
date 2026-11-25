package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** One screen of the console. Pages share the stone-and-gold look of the Woodcutting window. */
abstract class Page
{
	final String title;
	final String description;
	/** Lowest mod level that may use this page. */
	final String requires;
	final Session session;
	final AdminWindow window;

	Page(String title, String description, String requires, Session session, AdminWindow window)
	{
		this.title = title;
		this.description = description;
		this.requires = requires;
		this.session = session;
		this.window = window;
	}

	abstract JComponent build();

	/** Called after every poll, on the UI thread. */
	void onState()
	{
	}

	/** Called when the page is shown or hidden, on the UI thread. */
	void setShowing(boolean showing)
	{
	}

	final boolean allowed()
	{
		return session.has(requires);
	}

	JPanel shell()
	{
		return shell(null);
	}

	/** Page frame: title and one-line purpose on the left, an optional control on the right. */
	/** Pages whose actions apply to a chosen player show the target chip in their header. */
	boolean targeted()
	{
		return false;
	}

	JPanel shell(JComponent right)
	{
		if (right == null && targeted())
		{
			right = new Widgets.TargetChip(session);
		}
		JPanel page = Ui.clear(new BorderLayout(0, 16));
		JPanel head = Ui.clear(new BorderLayout(16, 0));
		JPanel words = Ui.clear(new BorderLayout(0, 3));
		words.add(Ui.label(title, Theme.display(21f), Theme.GOLD_HI), BorderLayout.NORTH);
		words.add(Ui.label(description, Theme.ui(13f), Theme.TEXT_3), BorderLayout.CENTER);
		head.add(words, BorderLayout.CENTER);
		if (right != null)
		{
			head.add(right, BorderLayout.EAST);
		}
		page.add(head, BorderLayout.NORTH);
		return page;
	}

	static JPanel stack(JComponent... sections)
	{
		JPanel body = Ui.clear(null);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		for (int i = 0; i < sections.length; i++)
		{
			sections[i].setAlignmentX(0f);
			body.add(sections[i]);
			if (i < sections.length - 1)
			{
				body.add(Box.createVerticalStrut(12));
			}
		}
		return body;
	}

	boolean confirm(String message)
	{
		return confirm("Are you sure?", message, "Continue", true);
	}

	boolean confirm(String heading, String message, String confirmLabel, boolean danger)
	{
		return Dialogs.confirm(window.owner(), heading, message, confirmLabel, danger);
	}

	/** A recessed number box: small gold label over a big stat-yellow value. */
	static final class Kpi extends JComponent
	{
		private final String label;
		private String value = "-";

		Kpi(String label)
		{
			this.label = label;
			setPreferredSize(new Dimension(150, 64));
		}

		void set(String next)
		{
			if (!next.equals(value))
			{
				value = next;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			Theme.well(g, 0, 0, getWidth(), getHeight(), 8);
			g.setFont(Theme.eyebrow(10f));
			g.setColor(Theme.TEXT_3);
			g.drawString(label.toUpperCase(), 14, 22);
			g.setFont(Theme.uiBold(22f));
			Theme.shadowText(g, value, 14, 49, Theme.YELLOW);
			g.dispose();
		}
	}

	static JLabel hint(String text, Color color)
	{
		JLabel label = Ui.label(text, Theme.ui(12f), color);
		label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		return label;
	}
}
