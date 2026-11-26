package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
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
	static final int HUB_MAP = 6;

	private final Session session;
	private final JDialog frame;
	private final Page[] pages;
	private final Ui.Nav[] navs;
	private final CardLayout pageCards = new CardLayout();
	private final JPanel pageHost = Ui.clear(pageCards);
	private final CardLayout modeCards = new CardLayout();
	private final JPanel modeHost = Ui.clear(modeCards);
	private final Ui.Pill pill = new Ui.Pill();
	private final JLabel online = Ui.label(" ", Theme.ui(12.5f), Theme.TEXT_3);
	private final JLabel clock = Ui.label(" ", Theme.uiBold(12.5f), Theme.TEXT_2);
	private JComponent meCard;
	private JComponent rail;
	private boolean compact;
	private final JLabel offlineTitle = Ui.label("Not connected", Theme.display(19f), Theme.GOLD_HI);
	private final JLabel offlineNote = Ui.label(" ", Theme.ui(13f), Theme.TEXT_2);
	private final JLabel flashText = Ui.label(" ", Theme.uiBold(12.5f), Theme.TEXT_3);
	private final JLabel flashDot = new JLabel();
	private final Timer flashTimer;
	private Color flashColor = Theme.TEXT_3;
	private int current = -1;

	AdminWindow(Session session, ItemIcons icons)
	{
		this.session = session;
		pages = new Page[]{
			new PlayersPage(session, this),
			new ItemsPage(session, this, icons),
			new StatsPage(session, this),
			new AccountsPage(session, this),
			new WorldPage(session, this),
			new LogPage(session, this),
			new HubMapPage(session, this),
		};
		navs = new Ui.Nav[pages.length];
		frame = new JDialog(gameWindow(), "Beanscape Admin", java.awt.Dialog.ModalityType.MODELESS);
		frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		frame.setContentPane(build());
		frame.pack();
		frame.setMinimumSize(new Dimension(820, 560));
		java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		frame.setSize(Math.min(frame.getWidth(), screen.width - 40), Math.min(frame.getHeight(), screen.height - 40));
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
				g.setPaint(new GradientPaint(0, 0, new Color(226, 180, 86, 20), getWidth() * 0.55f, getHeight() * 0.45f, new Color(0, 0, 0, 0)));
				g.fillRect(0, 0, getWidth(), getHeight());
				g.dispose();
			}
		};
		root.add(sidebar(), BorderLayout.WEST);

		for (Page page : pages)
		{
			CardLayout lock = new CardLayout();
			JPanel holder = Ui.clear(lock);
			holder.add(page.build(), "page");
			holder.add(lockedCard(page), "locked");
			pageHost.add(holder, page.title);
		}
		modeHost.setBorder(BorderFactory.createEmptyBorder(22, 26, 14, 22));
		modeHost.add(pageHost, "pages");
		modeHost.add(offlineCard(), "offline");
		root.add(modeHost, BorderLayout.CENTER);
		root.add(statusBar(), BorderLayout.SOUTH);
		root.setPreferredSize(new Dimension(1080, 720));
		root.addComponentListener(new java.awt.event.ComponentAdapter()
		{
			@Override
			public void componentResized(java.awt.event.ComponentEvent e)
			{
				applyCompact(root.getWidth() < 940);
			}
		});
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

	/** Left rail: the mark, the pages, and who you are signed in as. */
	private JComponent sidebar()
	{
		JPanel rail = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				g.setPaint(new GradientPaint(0, 0, new Color(0x1E1812), 0, getHeight(), new Color(0x15110C)));
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setPaint(null);
				g.setColor(Theme.EDGE_DARK);
				g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
				g.setColor(Theme.EDGE_HI);
				g.drawLine(getWidth() - 2, 0, getWidth() - 2, getHeight());
				g.dispose();
			}
		};
		rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
		rail.setBorder(BorderFactory.createEmptyBorder(16, 0, 14, 8));
		rail.setPreferredSize(new Dimension(212, 10));
		this.rail = rail;

		JComponent brand = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				int bx = compact ? (getWidth() - 8 - 40) / 2 : 16;
				Theme.gold(g, bx, 2, 40, 40, 10, false);
				g.setFont(Theme.display(25f));
				FontMetrics fm = g.getFontMetrics();
				g.setColor(new Color(0x2A1D08));
				g.drawString("B", bx + (40 - fm.stringWidth("B")) / 2, 2 + (40 + fm.getAscent() - fm.getDescent()) / 2);
				if (compact)
				{
					g.dispose();
					return;
				}
				g.setFont(Theme.display(17f));
				Theme.shadowText(g, "BEANSCAPE", 66, 21, Theme.GOLD_HI);
				g.setFont(Theme.eyebrow(10f));
				g.setColor(Theme.TEXT_3);
				g.drawString("ADMIN CONSOLE", 66, 37);
				g.dispose();
			}
		};
		brand.setPreferredSize(new Dimension(204, 46));
		brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
		brand.setAlignmentX(0f);
		rail.add(brand);
		rail.add(Box.createVerticalStrut(22));

		Ui.Glyph[] glyphs = {Ui.Glyph.SHIELD, Ui.Glyph.LOAD, Ui.Glyph.GEAR, Ui.Glyph.LOCK, Ui.Glyph.CLOCK, Ui.Glyph.HUD, Ui.Glyph.TARGET};
		for (int i = 0; i < pages.length; i++)
		{
			final int index = i;
			navs[i] = new Ui.Nav(null, glyphs[i], pages[i].title, null);
			navs[i].onClick(() -> show(index));
			navs[i].setAlignmentX(0f);
			navs[i].setPreferredSize(new Dimension(196, 44));
			navs[i].setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
			navs[i].setToolTipText(pages[i].title + "  (Alt+" + (i + 1) + ")");
			rail.add(navs[i]);
			rail.add(Box.createVerticalStrut(2));
		}
		rail.add(Box.createVerticalGlue());

		JComponent me = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				int w = getWidth();
				int h = getHeight();
				boolean up = session.connected();
				String name = up ? session.myName() : "Not signed in";
				if (compact)
				{
					Widgets.paintAvatar(g, session.avatars().supplier(session.myAvatar(), name, this).get(), (w - 8 - 36) / 2, (h - 36) / 2, 36, up ? Boolean.TRUE : null);
					g.dispose();
					return;
				}
				g.setColor(new Color(0, 0, 0, 60));
				g.fillRoundRect(8, 0, w - 8, h, 10, 10);
				g.setColor(Theme.EDGE_SOFT);
				g.drawRoundRect(8, 0, w - 9, h - 1, 10, 10);
				Widgets.paintAvatar(g, session.avatars().supplier(session.myAvatar(), name, this).get(), 18, (h - 36) / 2, 36, up ? Boolean.TRUE : null);
				g.setFont(Theme.uiBold(13.5f));
				g.setColor(Theme.TEXT);
				g.drawString(Theme.fit(g.getFontMetrics(), name, w - 80), 64, h / 2 - 3);
				if (up)
				{
					Widgets.chip(g, session.myLevel(), 64, h / 2 + 4, Widgets.rankTone(session.myLevel()), true);
				}
				g.dispose();
			}
		};
		me.setPreferredSize(new Dimension(204, 58));
		me.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
		me.setAlignmentX(0f);
		meCard = me;
		rail.add(me);
		return rail;
	}

	/** Bottom bar: what just happened on the left, connection facts on the right. */
	private JComponent statusBar()
	{
		JPanel foot = new JPanel(new BorderLayout(12, 0))
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				g.setPaint(new GradientPaint(0, 0, new Color(0x1A1510), 0, getHeight(), Theme.BG_2));
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setPaint(null);
				g.setColor(Theme.EDGE_DARK);
				g.drawLine(0, 0, getWidth(), 0);
				g.setColor(Theme.EDGE_HI);
				g.drawLine(0, 1, getWidth(), 1);
				g.dispose();
			}
		};
		foot.setBorder(BorderFactory.createEmptyBorder(7, 18, 7, 18));
		JComponent dot = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				if (flashText.getText().trim().isEmpty())
				{
					g.dispose();
					return;
				}
				g.setColor(Theme.alpha(flashColor, 60));
				g.fillOval(0, 1, 12, 12);
				g.setColor(flashColor);
				g.fillOval(3, 4, 6, 6);
				g.dispose();
			}
		};
		dot.setPreferredSize(new Dimension(12, 14));
		JPanel left = Ui.clear(new FlowLayout(FlowLayout.LEFT, 8, 3));
		left.add(dot);
		left.add(flashText);
		foot.add(left, BorderLayout.CENTER);
		JPanel right = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 14, 0));
		right.add(online);
		right.add(pill);
		right.add(clock);
		foot.add(right, BorderLayout.EAST);
		new Timer(20_000, event -> tick()).start();
		tick();
		return foot;
	}

	/** Narrow windows get an icon-only rail so the pages keep their room. */
	private void applyCompact(boolean value)
	{
		if (value == compact || rail == null)
		{
			return;
		}
		compact = value;
		rail.setPreferredSize(new Dimension(value ? 68 : 212, 10));
		for (Ui.Nav nav : navs)
		{
			nav.setCompact(value);
		}
		rail.revalidate();
		rail.repaint();
		if (meCard != null)
		{
			meCard.repaint();
		}
	}

	private void tick()
	{
		clock.setText(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
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
		online.setText(up ? session.players().size() + " online" : " ");
		if (meCard != null)
		{
			meCard.repaint();
		}
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
