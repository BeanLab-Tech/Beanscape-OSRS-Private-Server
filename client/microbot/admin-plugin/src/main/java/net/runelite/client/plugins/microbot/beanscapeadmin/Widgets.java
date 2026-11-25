package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Layout and small controls added in the console overhaul: a split that stacks when the window is
 * narrow, a grid that wraps by width, filter chips, a search field and round avatars. Everything is
 * painted in the same stone-and-gold style as {@link Ui}.
 */
final class Widgets
{
	private Widgets()
	{
	}

	/**
	 * The skills tab as the game draws it: three columns read left to right, top to bottom. Using the
	 * same order means an admin finds a skill where their hands already expect it.
	 */
	static final net.runelite.api.Skill[] GAME_ORDER = {
		net.runelite.api.Skill.ATTACK, net.runelite.api.Skill.HITPOINTS, net.runelite.api.Skill.MINING,
		net.runelite.api.Skill.STRENGTH, net.runelite.api.Skill.AGILITY, net.runelite.api.Skill.SMITHING,
		net.runelite.api.Skill.DEFENCE, net.runelite.api.Skill.HERBLORE, net.runelite.api.Skill.FISHING,
		net.runelite.api.Skill.RANGED, net.runelite.api.Skill.THIEVING, net.runelite.api.Skill.COOKING,
		net.runelite.api.Skill.PRAYER, net.runelite.api.Skill.CRAFTING, net.runelite.api.Skill.FIREMAKING,
		net.runelite.api.Skill.MAGIC, net.runelite.api.Skill.FLETCHING, net.runelite.api.Skill.WOODCUTTING,
		net.runelite.api.Skill.RUNECRAFT, net.runelite.api.Skill.SLAYER, net.runelite.api.Skill.FARMING,
		net.runelite.api.Skill.CONSTRUCTION, net.runelite.api.Skill.HUNTER,
	};

	// ------------------------------------------------------------------ responsive layouts

	/**
	 * Two panes side by side. The first takes a share of the width (clamped), the second the rest.
	 * Under {@code stackBelow} pixels the panes stack, first on top, so nothing is ever squeezed.
	 */
	static final class Split extends JPanel
	{
		Split(JComponent first, JComponent second, double share, int minFirst, int maxFirst, int stackBelow)
		{
			super(null);
			setOpaque(false);
			add(first);
			add(second);
			setLayout(new LayoutManager()
			{
				private static final int GAP = 14;

				@Override
				public void addLayoutComponent(String name, Component comp)
				{
				}

				@Override
				public void removeLayoutComponent(Component comp)
				{
				}

				@Override
				public Dimension preferredLayoutSize(Container parent)
				{
					return new Dimension(900, 520);
				}

				@Override
				public Dimension minimumLayoutSize(Container parent)
				{
					return new Dimension(320, 240);
				}

				@Override
				public void layoutContainer(Container parent)
				{
					Insets in = parent.getInsets();
					int w = parent.getWidth() - in.left - in.right;
					int h = parent.getHeight() - in.top - in.bottom;
					if (w <= 0 || h <= 0)
					{
						return;
					}
					if (w < stackBelow)
					{
						int top = Math.max(200, (int) (h * 0.46));
						top = Math.min(top, h - 160);
						first.setBounds(in.left, in.top, w, top);
						second.setBounds(in.left, in.top + top + GAP, w, h - top - GAP);
					}
					else
					{
						int fw = Math.max(minFirst, Math.min(maxFirst, (int) (w * share)));
						first.setBounds(in.left, in.top, fw, h);
						second.setBounds(in.left + fw + GAP, in.top, w - fw - GAP, h);
					}
				}
			});
		}
	}

	/** Fixed-size cells that wrap into as many columns as fit. Height follows the column count. */
	static final class WrapGrid implements LayoutManager
	{
		private final int cellW;
		private final int cellH;
		private final int gap;
		private int lastCols = -1;

		WrapGrid(int cellW, int cellH, int gap)
		{
			this.cellW = cellW;
			this.cellH = cellH;
			this.gap = gap;
		}

		private int columns(int width, int count)
		{
			int fit = Math.max(1, (width + gap) / (cellW + gap));
			return Math.max(1, Math.min(fit, Math.max(1, count)));
		}

		@Override
		public void addLayoutComponent(String name, Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(Component comp)
		{
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			int count = parent.getComponentCount();
			int width = parent.getWidth() > 0 ? parent.getWidth() : (cellW + gap) * 4;
			int cols = columns(width, count);
			int rows = (count + cols - 1) / Math.max(1, cols);
			return new Dimension(cols * (cellW + gap) - gap, Math.max(0, rows * (cellH + gap) - gap));
		}

		@Override
		public Dimension minimumLayoutSize(Container parent)
		{
			return new Dimension(cellW, cellH);
		}

		@Override
		public void layoutContainer(Container parent)
		{
			int count = parent.getComponentCount();
			int cols = columns(parent.getWidth(), count);
			int cw = Math.max(cellW, (parent.getWidth() - (cols - 1) * gap) / cols);
			for (int i = 0; i < count; i++)
			{
				int col = i % cols;
				int row = i / cols;
				parent.getComponent(i).setBounds(col * (cw + gap), row * (cellH + gap), cw, cellH);
			}
			if (cols != lastCols)
			{
				lastCols = cols;
				SwingUtilities.invokeLater(parent::revalidate);
			}
		}
	}

	/** A transparent column that stacks its children top to bottom with a fixed gap. */
	static JPanel column(int gap, JComponent... children)
	{
		JPanel panel = Ui.clear(null);
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		for (int i = 0; i < children.length; i++)
		{
			children[i].setAlignmentX(0f);
			panel.add(children[i]);
			if (i < children.length - 1)
			{
				panel.add(javax.swing.Box.createVerticalStrut(gap));
			}
		}
		return panel;
	}

	/** Pins a component to the top of its cell so scroll content never stretches. */
	static JPanel top(JComponent content)
	{
		JPanel holder = Ui.clear(new java.awt.BorderLayout());
		holder.add(content, java.awt.BorderLayout.NORTH);
		return holder;
	}

	/** Centered title and note for a list with nothing in it yet. */
	static JPanel empty(String title, String note)
	{
		JPanel center = Ui.clear(new GridBagLayout());
		JPanel words = Ui.clear(null);
		words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
		JLabel head = Ui.label(title, Theme.uiBold(14f), Theme.TEXT_2);
		JLabel sub = Ui.note(note, 240);
		sub.setHorizontalAlignment(JLabel.CENTER);
		head.setAlignmentX(0.5f);
		sub.setAlignmentX(0.5f);
		words.add(head);
		words.add(javax.swing.Box.createVerticalStrut(4));
		words.add(sub);
		center.add(words);
		center.setBorder(BorderFactory.createEmptyBorder(28, 8, 28, 8));
		return center;
	}

	// ------------------------------------------------------------------ chips

	/** Draws a small rounded label and returns its width, so rows can line chips up in a run. */
	static int chip(Graphics2D g, String text, int x, int y, Color tone, boolean filled)
	{
		g.setFont(Theme.eyebrow(9.5f));
		FontMetrics fm = g.getFontMetrics();
		String upper = text.toUpperCase();
		int w = fm.stringWidth(upper) + 14;
		int h = 16;
		g.setColor(Theme.alpha(tone, filled ? 46 : 22));
		g.fillRoundRect(x, y, w, h, h, h);
		g.setColor(Theme.alpha(tone, filled ? 150 : 90));
		g.drawRoundRect(x, y, w - 1, h - 1, h, h);
		g.setColor(Theme.mix(tone, Color.WHITE, filled ? 0.4 : 0.25));
		g.drawString(upper, x + 7, y + (h - fm.getHeight()) / 2 + fm.getAscent());
		return w;
	}

	static Color rankTone(String level)
	{
		switch (Session.rank(level))
		{
			case 3:
				return Theme.GOLD_HI;
			case 2:
				return Theme.ORANGE;
			case 1:
				return Theme.BLUE;
			default:
				return Theme.TEXT_3;
		}
	}

	/** A pill that toggles a filter on or off. */
	static final class FilterChip extends JComponent
	{
		private final String text;
		private boolean selected;
		private boolean hover;
		private Runnable onChange;

		FilterChip(String text, boolean selected)
		{
			this.text = text;
			this.selected = selected;
			setFont(Theme.uiBold(12f));
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			FontMetrics fm = getFontMetrics(getFont());
			setPreferredSize(new Dimension(fm.stringWidth(text) + 26, 26));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					requestFocusInWindow();
					flip();
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
						flip();
					}
				}
			});
			addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusGained(FocusEvent e)
				{
					repaint();
				}

				@Override
				public void focusLost(FocusEvent e)
				{
					repaint();
				}
			});
		}

		FilterChip onChange(Runnable action)
		{
			onChange = action;
			return this;
		}

		boolean isSelected()
		{
			return selected;
		}

		void setSelected(boolean value)
		{
			if (selected != value)
			{
				selected = value;
				repaint();
			}
		}

		private void flip()
		{
			selected = !selected;
			repaint();
			if (onChange != null)
			{
				onChange.run();
			}
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = getWidth();
			int h = getHeight();
			if (selected)
			{
				g.setColor(Theme.alpha(Theme.GOLD, 46));
				g.fillRoundRect(0, 0, w, h, h, h);
				g.setColor(Theme.alpha(Theme.GOLD, 150));
			}
			else
			{
				g.setColor(hover ? Theme.STONE_3 : Theme.STONE_1);
				g.fillRoundRect(0, 0, w, h, h, h);
				g.setColor(hover ? Theme.GOLD_LO : Theme.EDGE_DARK);
			}
			g.drawRoundRect(0, 0, w - 1, h - 1, h, h);
			if (isFocusOwner())
			{
				g.setColor(Theme.alpha(Theme.GOLD_HI, 150));
				g.drawRoundRect(2, 2, w - 5, h - 5, h, h);
			}
			g.setFont(getFont());
			FontMetrics fm = g.getFontMetrics();
			g.setColor(selected ? Theme.GOLD_HI : Theme.TEXT_2);
			g.drawString(text, (w - fm.stringWidth(text)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
			g.dispose();
		}
	}

	// ------------------------------------------------------------------ search field

	/** A text field with a magnifier and a hint that shows while it is empty. */
	static final class SearchField extends JTextField
	{
		private final String hint;

		SearchField(String hint)
		{
			this.hint = hint;
			Ui.field(this);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.EDGE_DARK),
				BorderFactory.createEmptyBorder(6, 30, 6, 9)));
			setPreferredSize(new Dimension(200, 34));
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int cy = getHeight() / 2;
			g.setColor(isFocusOwner() ? Theme.GOLD : Theme.TEXT_4);
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawOval(10, cy - 6, 9, 9);
			g.drawLine(18, cy + 2, 22, cy + 6);
			if (getText().isEmpty())
			{
				g.setFont(getFont());
				g.setColor(Theme.TEXT_4);
				g.drawString(hint, 30, cy + g.getFontMetrics().getAscent() / 2 - 1);
			}
			g.dispose();
		}
	}

	// ------------------------------------------------------------------ avatars

	/** A round picture with a thin ring, optionally with a presence dot. */
	static final class AvatarView extends JComponent
	{
		private final int size;
		private Supplier<Image> image;
		private Boolean online;

		AvatarView(int size)
		{
			this.size = size;
			setPreferredSize(new Dimension(size, size));
			setMinimumSize(new Dimension(size, size));
			setMaximumSize(new Dimension(size, size));
		}

		void set(Supplier<Image> next, Boolean presence)
		{
			image = next;
			online = presence;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			paintAvatar(g, image == null ? null : image.get(), 0, 0, size, online);
			g.dispose();
		}
	}

	/** Draws a picture clipped to a circle, then the ring and the optional presence dot. */
	static void paintAvatar(Graphics2D g, Image picture, int x, int y, int size, Boolean online)
	{
		g.setColor(new Color(0, 0, 0, 90));
		g.fillOval(x, y + 1, size, size);
		if (picture != null)
		{
			java.awt.Shape old = g.getClip();
			g.clip(new Ellipse2D.Float(x + 1, y + 1, size - 2, size - 2));
			Theme.icon(g, picture, x + 1, y + 1, size - 2, size - 2);
			g.setClip(old);
		}
		g.setColor(Theme.GOLD_LO);
		g.setStroke(new BasicStroke(1.4f));
		g.drawOval(x + 1, y + 1, size - 3, size - 3);
		if (online != null)
		{
			int d = Math.max(9, size / 4);
			int dx = x + size - d;
			int dy = y + size - d;
			g.setColor(Theme.BG);
			g.fillOval(dx - 2, dy - 2, d + 4, d + 4);
			g.setColor(online ? Theme.GREEN : Theme.TEXT_4);
			g.fillOval(dx, dy, d, d);
		}
	}

	// ------------------------------------------------------------------ target indicator

	/**
	 * Shows who an action on this page applies to: the selected player, or you when nobody is
	 * selected. A cross clears the selection. Sits in the page header so it is always in view.
	 */
	static final class TargetChip extends JComponent
	{
		private final Session session;
		private boolean overClear;

		TargetChip(Session session)
		{
			this.session = session;
			setPreferredSize(new Dimension(230, 44));
			session.onChange(this::repaint);
			addMouseMotionListener(new java.awt.event.MouseMotionAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent e)
				{
					boolean over = clearVisible() && e.getX() > getWidth() - 34;
					if (over != overClear)
					{
						overClear = over;
						setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
						repaint();
					}
				}
			});
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (clearVisible() && e.getX() > getWidth() - 34)
					{
						session.select("");
					}
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					overClear = false;
					repaint();
				}
			});
		}

		private boolean clearVisible()
		{
			return !session.selected().isEmpty();
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			return clearVisible() && e.getX() > getWidth() - 34 ? "Clear selection (act on yourself)" : null;
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = getWidth();
			int h = getHeight();
			boolean picked = clearVisible();
			String who = session.target();
			Theme.stone(g, 0, 0, w, h, 10, picked ? new Color(0x4A3C2B) : Theme.STONE_2, picked ? new Color(0x2E2519) : Theme.STONE_1);
			if (picked)
			{
				g.setColor(new Color(226, 180, 86, 150));
				g.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);
			}
			setToolTipText(picked ? "Selected player" : null);
			paintAvatar(g, session.avatars().supplier(session.avatarOf(who), who, this).get(), 8, (h - 28) / 2, 28, who.isEmpty() ? null : Boolean.TRUE);
			g.setFont(Theme.eyebrow(9.5f));
			g.setColor(picked ? Theme.GOLD : Theme.TEXT_4);
			g.drawString(picked ? "TARGET" : "TARGET  \u00b7  YOU", 44, 17);
			g.setFont(Theme.uiBold(14f));
			g.setColor(Theme.TEXT);
			int right = picked ? w - 40 : w - 12;
			g.drawString(Theme.fit(g.getFontMetrics(), who.isEmpty() ? "Not connected" : who, right - 44), 44, 34);
			if (picked)
			{
				int cx = w - 22;
				int cy = h / 2;
				g.setColor(overClear ? Theme.RED : Theme.TEXT_3);
				g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
				g.drawLine(cx - 4, cy + 4, cx + 4, cy - 4);
			}
			g.dispose();
		}
	}
}
