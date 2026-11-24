package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Recent admin actions from the server's audit log, newest first. Refreshes while open. */
final class LogPage extends Page
{
	private final JPanel rows = Ui.clear(null);
	private String signature = "";
	private boolean showing;

	LogPage(Session session, AdminWindow window)
	{
		super("Log", "Everything admins have done, so every change can be traced.", "admin", session, window);
	}

	@Override
	JComponent build()
	{
		JPanel page = shell();
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		Ui.Section section = new Ui.Section("Recent actions", "Updates every few seconds while this page is open.", new BorderLayout());
		section.body().add(Ui.scroll(rows), BorderLayout.CENTER);
		page.add(section, BorderLayout.CENTER);
		return page;
	}

	@Override
	void setShowing(boolean value)
	{
		showing = value;
		if (value)
		{
			refresh();
		}
	}

	@Override
	void onState()
	{
		if (showing)
		{
			refresh();
		}
	}

	private void refresh()
	{
		if (!session.has("admin"))
		{
			return;
		}
		session.call(() -> session.api().get("/v1/audit?limit=80"), json -> show(Session.array(json, "entries")),
			error -> window.flash(error, Theme.RED));
	}

	private void show(com.google.gson.JsonArray entries)
	{
		String next = String.valueOf(entries);
		if (next.equals(signature))
		{
			return;
		}
		signature = next;
		rows.removeAll();
		if (entries.size() == 0)
		{
			rows.add(hint("No admin actions yet.", Theme.TEXT_3));
		}
		for (JsonObject entry : Session.map(entries, e -> e))
		{
			JPanel row = Ui.clear(new BorderLayout(12, 0));
			row.setBorder(BorderFactory.createEmptyBorder(7, 0, 7, 0));
			row.add(Ui.label(Session.str(entry, "time"), Theme.ui(12f), Theme.TEXT_3), BorderLayout.WEST);
			JPanel words = Ui.clear(new BorderLayout(0, 1));
			JLabel head = Ui.label(Session.str(entry, "actor") + "  " + Session.str(entry, "action"), Theme.uiBold(13f), Theme.TEXT);
			words.add(head, BorderLayout.NORTH);
			words.add(Ui.label(Session.str(entry, "detail"), Theme.ui(12f), Theme.TEXT_2), BorderLayout.CENTER);
			row.add(words, BorderLayout.CENTER);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
			row.setAlignmentX(0f);
			rows.add(row);
			rows.add(Ui.rule());
			rows.add(Box.createVerticalStrut(2));
		}
		rows.revalidate();
		rows.repaint();
	}
}
