package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import net.runelite.client.plugins.microbot.Microbot;

/**
 * The admin console window. Same carved-stone frame as the Woodcutting settings window: header with
 * connection state, a nav rail of pages, and a footer that reports what just happened.
 */
final class AdminWindow
{
	static final int PLAYERS = 0;
	static final int ITEMS = 1;
	static final int STATS = 2;
	static final int ACCOUNTS = 3;
	static final int WORLD = 4;
	static final int LOG = 5;

	private final Session session;
	private final JDialog frame;
	private final Page[] pages;
	private final Ui.Nav[] navs;
	private final CardLayout pageCards = new CardLayout();
	private final JPanel pageHost = Ui.clear(pageCards);
	private final CardLayout modeCards = new CardLayout();
	private final JPanel modeHost = Ui.clear(modeCards);
	private final Ui.Pill pill = new Ui.Pill();
	private final JLabel who = Ui.label(" ", Theme.ui(13f), Theme.TEXT_2);
	private final JLabel offlineTitle = Ui.label("Not connected", Theme.display(19f), Theme.GOLD_HI);
	private final JLabel offlineNote = Ui.label(" ", Theme.ui(13f), Theme.TEXT_2);
	private final JLabel flashText = Ui.label(" ", Theme.uiBold(12.5f), Theme.TEXT_3);
	private final JLabel flashDot = new JLabel();
	private final Timer flashTimer;
	private Color flashColor = Theme.TEXT_3;
	private int current = -1;

	AdminWindow(Session session, WikiIcons icons)
	{
		this.session = session;
		pages = new Page[]{
			new PlayersPage(session, this),
			new ItemsPage(session, this, icons),
			new StatsPage(session, this),
			new AccountsPage(session, this),
			new WorldPage(session, this),
			new LogPage(session, this),
		};
		navs = new Ui.Nav[pages.length];
		frame = new JDialog(gameWindow(), "Beanscape Admin", java.awt.Dialog.ModalityType.MODELESS);
		frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		frame.setContentPane(build());
		frame.pack();
		frame.setMinimumSize(new Dimension(960, frame.getHeight()));
		frame.setLocationRelativeTo(null);
		flashTimer = new Timer(6000, event -> clearFlash());
		flashTimer.setRepeats(false);
		session.onChange(this::refresh);
		show(PLAYERS);
		refresh();
	}

	Window owner()
	{
		return frame;
	}

	private static Window gameWindow()
	{
		try
		{
			if (Microbot.getClient() != null && Microbot.getClient().getCanvas() != null)
			{
				return SwingUtilities.getWindowAncestor(Microbot.getClient().getCanvas());
			}
		}
		catch (Throwable ignored)
		{
			// Client not up yet: the window opens unowned.
		}
		return null;
	}

	void open()
	{
		SwingUtilities.invokeLater(() ->
		{
			frame.setVisible(true);
			frame.toFront();
		});
	}

	void close()
	{
		SwingUtilities.invokeLater(() ->
		{
			frame.setVisible(false);
			frame.dispose();
		});
	}

	void flash(String text, Color color)
	{
		flashText.setText(text);
		flashText.setForeground(color);
		flashColor = color;
		flashDot.repaint();
		flashTimer.restart();
	}

	private void clearFlash()
	{
		flashText.setText(" ");
		flashColor = Theme.TEXT_3;
		flashDot.repaint();
	}

	void show(int index)
	{
		if (index < 0 || index >= pages.length)
		{
			return;
		}
		if (current >= 0)
		{
			pages[current].setShowing(false);
		}
		current = index;
		pageCards.show(pageHost, pages[index].title);
		for (int i = 0; i < navs.length; i++)
		{
			navs[i].setActive(i == index);
		}
		pages[index].setShowing(true);
		pages[index].onState();
	}

	// =================================================================== layout

	private JPanel build()
	{
		JPanel root = new JPanel(new BorderLayout())
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				g.setColor(Theme.BG);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setPaint(new GradientPaint(0, 0, new Color(226, 180, 86, 16), getWidth() * 0.5f, getHeight() * 0.5f, new Color(0, 0, 0, 0)));
				g.fillRect(0, 0, getWidth(), getHeight());
				g.dispose();
			}
		};
		root.add(header(), BorderLayout.NORTH);
		root.add(navRail(), BorderLayout.WEST);

		for (Page page : pages)
		{
			CardLayout lock = new CardLayout();
			JPanel holder = Ui.clear(lock);
			holder.add(page.build(), "page");
			holder.add(lockedCard(page), "locked");
			pageHost.add(holder, page.title);
		}
		pageHost.setBorder(BorderFactory.createEmptyBorder(18, 22, 16, 14));
		pageHost.setPreferredSize(new Dimension(760, 610));
		modeHost.add(pageHost, "pages");
		modeHost.add(offlineCard(), "offline");
		root.add(modeHost, BorderLayout.CENTER);
		root.add(footer(), BorderLayout.SOUTH);
		bindKeys(root);
		return root;
	}

	private void bindKeys(JPanel root)
	{
		for (int i = 0; i < pages.length; i++)
		{
			final int index = i;
			String key = "page" + i;
			root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, KeyEvent.ALT_DOWN_MASK), key);
			root.getActionMap().put(key, new AbstractAction()
			{
				@Override
				public void actionPerformed(java.awt.event.ActionEvent e)
				{
					show(index);
				}
			});
		}
	}

	private JComponent header()
	{
		JPanel head = new JPanel(new BorderLayout(16, 0))
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				int w = getWidth();
				int h = getHeight();
				g.setPaint(new GradientPaint(0, 0, Theme.STONE_2, 0, h, Theme.STONE_1));
				g.fillRect(0, 0, w, h);
				g.setPaint(new GradientPaint(0, 0, new Color(226, 180, 86, 40), w * 0.55f, 0, new Color(226, 180, 86, 0)));
				g.fillRect(0, 0, w, h);
				g.setPaint(new GradientPaint(0, 0, Theme.GOLD, w * 0.7f, 0, new Color(226, 180, 86, 0)));
				g.fillRect(0, 0, w, 2);
				g.setPaint(null);
				g.setColor(Theme.EDGE_DARK);
				g.drawLine(0, h - 1, w, h - 1);
				g.dispose();
			}
		};
		head.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));
		head.add(crest(), BorderLayout.WEST);

		JPanel words = Ui.clear(new BorderLayout(0, 3));
		final String text = "Beanscape Admin";
		JLabel title = new JLabel(text)
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				g.setFont(getFont());
				int baseline = g.getFontMetrics().getAscent();
				g.setColor(Color.BLACK);
				g.drawString(getText(), 0, baseline + 2);
				g.setColor(Theme.GOLD_HI);
				g.drawString(getText(), 0, baseline);
				g.dispose();
			}
		};
		Font font = Theme.display(25f);
		title.setFont(font);
		title.setPreferredSize(new Dimension(title.getFontMetrics(font).stringWidth(text) + 12, 32));
		JPanel titleRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		titleRow.add(title);
		JLabel version = Ui.label("v0.1.0", Theme.eyebrow(10f), Theme.TEXT_4);
		version.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		titleRow.add(version);
		words.add(titleRow, BorderLayout.NORTH);
		words.add(who, BorderLayout.CENTER);
		head.add(words, BorderLayout.CENTER);

		JPanel right = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 8));
		right.add(pill);
		head.add(right, BorderLayout.EAST);
		return head;
	}

	private JComponent crest()
	{
		JComponent crest = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				Theme.stone(g, 0, 0, 56, 56, 12, Theme.SLOT_2, new Color(0x1C1610));
				g.setFont(Theme.display(30f));
				int width = g.getFontMetrics().stringWidth("B");
				Theme.shadowText(g, "B", (56 - width) / 2, 39, Theme.GOLD_HI);
				g.dispose();
			}
		};
		crest.setPreferredSize(new Dimension(56, 56));
		return crest;
	}

	private JComponent navRail()
	{
		JPanel rail = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				g.setPaint(new GradientPaint(0, 0, new Color(0x1E1812), 0, getHeight(), new Color(0x16110D)));
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setPaint(null);
				g.setColor(Theme.EDGE_DARK);
				g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
				g.setColor(Theme.EDGE_SOFT);
				g.drawLine(getWidth() - 2, 0, getWidth() - 2, getHeight());
				g.dispose();
			}
		};
		rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
		rail.setBorder(BorderFactory.createEmptyBorder(14, 0, 14, 8));
		rail.setPreferredSize(new Dimension(212, 10));
		JLabel label = Ui.label("CONSOLE", Theme.eyebrow(10f), Theme.TEXT_4);
		label.setBorder(BorderFactory.createEmptyBorder(0, 18, 8, 0));
		label.setAlignmentX(0f);
		rail.add(label);
		Ui.Glyph[] glyphs = {Ui.Glyph.SHIELD, Ui.Glyph.LOAD, Ui.Glyph.GEAR, Ui.Glyph.LOCK, Ui.Glyph.CLOCK, Ui.Glyph.HUD};
		for (int i = 0; i < pages.length; i++)
		{
			final int index = i;
			navs[i] = new Ui.Nav(null, glyphs[i], pages[i].title, null);
			navs[i].onClick(() -> show(index));
			navs[i].setAlignmentX(0f);
			navs[i].setToolTipText(pages[i].title + "  (Alt+" + (i + 1) + ")");
			rail.add(navs[i]);
			rail.add(Box.createVerticalStrut(2));
		}
		rail.add(Box.createVerticalGlue());
		JLabel hint = Ui.note("Every change you make here is written to the audit log.", 128);
		hint.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 0));
		hint.setAlignmentX(0f);
		rail.add(hint);
		return rail;
	}

	private JComponent footer()
	{
		JPanel foot = new JPanel(new BorderLayout(12, 0))
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				g.setPaint(new GradientPaint(0, 0, new Color(0x1A1510), 0, getHeight(), new Color(0x120E0A)));
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setPaint(null);
				g.setColor(Theme.EDGE_DARK);
				g.drawLine(0, 0, getWidth(), 0);
				g.setColor(Theme.EDGE_HI);
				g.drawLine(0, 1, getWidth(), 1);
				g.dispose();
			}
		};
		foot.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
		JComponent dot = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				g.setColor(Theme.alpha(flashColor, 60));
				g.fillOval(0, 1, 12, 12);
				g.setColor(flashColor);
				g.fillOval(3, 4, 6, 6);
				g.dispose();
			}
		};
		dot.setPreferredSize(new Dimension(12, 14));
		JPanel left = Ui.clear(new FlowLayout(FlowLayout.LEFT, 8, 4));
		left.add(dot);
		left.add(flashText);
		foot.add(left, BorderLayout.CENTER);
		foot.add(Ui.label("Polling every 3 seconds", Theme.ui(12f), Theme.TEXT_4), BorderLayout.EAST);
		return foot;
	}

	private JComponent lockedCard(Page page)
	{
		JPanel card = Ui.clear(new BorderLayout());
		JPanel center = Ui.clear(new java.awt.GridBagLayout());
		JPanel words = Ui.clear(null);
		words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
		JLabel head = Ui.label(page.title + " needs " + page.requires + " level", Theme.display(19f), Theme.GOLD_HI);
		JLabel note = Ui.label("Your account doesn't have access to this page.", Theme.ui(13f), Theme.TEXT_2);
		head.setAlignmentX(0.5f);
		note.setAlignmentX(0.5f);
		words.add(head);
		words.add(Box.createVerticalStrut(6));
		words.add(note);
		center.add(words);
		card.add(center, BorderLayout.CENTER);
		return card;
	}

	private JComponent offlineCard()
	{
		JPanel center = Ui.clear(new java.awt.GridBagLayout());
		JPanel words = Ui.clear(null);
		words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
		offlineTitle.setAlignmentX(0.5f);
		offlineNote.setAlignmentX(0.5f);
		Ui.Button retry = new Ui.Button("Try again", Ui.Kind.PRIMARY, Ui.Glyph.RESTART);
		retry.setAlignmentX(0.5f);
		retry.addActionListener(event ->
		{
			session.reload();
			flash("Checking the connection.", Theme.TEXT_2);
		});
		words.add(offlineTitle);
		words.add(Box.createVerticalStrut(8));
		words.add(offlineNote);
		words.add(Box.createVerticalStrut(18));
		words.add(retry);
		center.add(words);
		return center;
	}

	// =================================================================== state

	private void refresh()
	{
		boolean up = session.connected();
		pill.set(up ? "Connected" : "Offline", up ? Theme.GREEN : Theme.RED);
		who.setText(up ? "Signed in as " + session.myName() + "  (" + session.myLevel() + ")" : "Not signed in");
		offlineNote.setText(session.error());
		modeCards.show(modeHost, up ? "pages" : "offline");
		for (int i = 0; i < pages.length; i++)
		{
			CardLayout lock = (CardLayout) ((JPanel) pageHost.getComponent(i)).getLayout();
			lock.show((JPanel) pageHost.getComponent(i), pages[i].allowed() ? "page" : "locked");
			if (pages[i].allowed())
			{
				pages[i].onState();
			}
		}
		navs[PLAYERS].setDetail(session.players().size() + " online");
	}
}
