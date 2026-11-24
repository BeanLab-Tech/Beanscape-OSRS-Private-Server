package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Polygon;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;

/**
 * Swing controls painted in the shared stone-and-gold style. Every control here does real work;
 * nothing is decorative.
 */
final class Ui
{
	private Ui()
	{
	}

	enum Glyph
	{
		NONE, PLAY, PAUSE, STOP, RESTART, UNDO, RESET, SAVE, LOAD, TRASH, CHECK, LOCK, HUD, SHIELD, CLOCK, TARGET, GEAR
	}

	enum Kind
	{
		PRIMARY, STONE, DANGER, GHOST
	}

	// ------------------------------------------------------------------ labels

	static JLabel label(String text, Font font, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(color);
		return label;
	}

	/** Wrapped description text for page heads and option cards. */
	static JLabel note(String text, int width)
	{
		JLabel label = new JLabel("<html><div style='width:" + width + "px'>" + escape(text) + "</div></html>");
		label.setFont(Theme.ui(12.5f));
		label.setForeground(Theme.TEXT_3);
		return label;
	}

	static String escape(String text)
	{
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	static JPanel row(int gap, Component... children)
	{
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
		panel.setOpaque(false);
		for (Component child : children)
		{
			panel.add(child);
		}
		return panel;
	}

	private static final java.util.Map<String, Image> AVATARS = new java.util.HashMap<>();

	/** A gold initial on a transparent square, for lists of people who have no picture. */
	static synchronized Image avatar(String name)
	{
		String key = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
		return AVATARS.computeIfAbsent(key, letter ->
		{
			java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			Theme.smooth(g);
			g.setFont(Theme.display(24f));
			FontMetrics fm = g.getFontMetrics();
			Theme.shadowText(g, letter, (40 - fm.stringWidth(letter)) / 2, (40 + fm.getAscent() - fm.getDescent()) / 2, Theme.GOLD);
			g.dispose();
			return image;
		});
	}

	static JPanel clear(LayoutManager layout)
	{
		JPanel panel = new JPanel(layout);
		panel.setOpaque(false);
		return panel;
	}

	// ------------------------------------------------------------------ glyphs

	static void glyph(Graphics2D g, Glyph glyph, int x, int y, int size, Color color)
	{
		if (glyph == null || glyph == Glyph.NONE)
		{
			return;
		}
		Theme.smooth(g);
		g.setColor(color);
		float s = size;
		g.setStroke(new BasicStroke(Math.max(1.4f, s / 9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		switch (glyph)
		{
			case PLAY:
			{
				Polygon p = new Polygon();
				p.addPoint(x + Math.round(s * .22f), y + Math.round(s * .12f));
				p.addPoint(x + Math.round(s * .88f), y + Math.round(s * .5f));
				p.addPoint(x + Math.round(s * .22f), y + Math.round(s * .88f));
				g.fillPolygon(p);
				break;
			}
			case PAUSE:
				g.fillRoundRect(x + Math.round(s * .2f), y + Math.round(s * .14f), Math.round(s * .22f), Math.round(s * .72f), 2, 2);
				g.fillRoundRect(x + Math.round(s * .58f), y + Math.round(s * .14f), Math.round(s * .22f), Math.round(s * .72f), 2, 2);
				break;
			case STOP:
				g.fillRoundRect(x + Math.round(s * .18f), y + Math.round(s * .18f), Math.round(s * .64f), Math.round(s * .64f), 3, 3);
				break;
			case RESTART:
			case UNDO:
			case RESET:
			{
				int d = Math.round(s * .7f);
				int ox = x + Math.round(s * .15f);
				int oy = y + Math.round(s * .15f);
				g.drawArc(ox, oy, d, d, glyph == Glyph.UNDO ? 200 : 60, 270);
				Polygon head = new Polygon();
				if (glyph == Glyph.UNDO)
				{
					head.addPoint(ox - 1, oy + d / 2 - 3);
					head.addPoint(ox + 5, oy + d / 2 + 1);
					head.addPoint(ox - 3, oy + d / 2 + 4);
				}
				else
				{
					int hx = ox + d - 1;
					int hy = oy + Math.round(d * .18f);
					head.addPoint(hx + 3, hy - 4);
					head.addPoint(hx + 3, hy + 3);
					head.addPoint(hx - 4, hy + 1);
				}
				g.fillPolygon(head);
				if (glyph == Glyph.RESET)
				{
					g.fillOval(x + Math.round(s * .42f), y + Math.round(s * .42f), Math.max(2, Math.round(s * .16f)), Math.max(2, Math.round(s * .16f)));
				}
				break;
			}
			case SAVE:
				g.drawLine(x + Math.round(s * .5f), y + Math.round(s * .12f), x + Math.round(s * .5f), y + Math.round(s * .62f));
				g.drawLine(x + Math.round(s * .28f), y + Math.round(s * .42f), x + Math.round(s * .5f), y + Math.round(s * .64f));
				g.drawLine(x + Math.round(s * .72f), y + Math.round(s * .42f), x + Math.round(s * .5f), y + Math.round(s * .64f));
				g.drawLine(x + Math.round(s * .16f), y + Math.round(s * .86f), x + Math.round(s * .84f), y + Math.round(s * .86f));
				break;
			case LOAD:
				g.drawLine(x + Math.round(s * .5f), y + Math.round(s * .66f), x + Math.round(s * .5f), y + Math.round(s * .14f));
				g.drawLine(x + Math.round(s * .28f), y + Math.round(s * .36f), x + Math.round(s * .5f), y + Math.round(s * .14f));
				g.drawLine(x + Math.round(s * .72f), y + Math.round(s * .36f), x + Math.round(s * .5f), y + Math.round(s * .14f));
				g.drawLine(x + Math.round(s * .16f), y + Math.round(s * .86f), x + Math.round(s * .84f), y + Math.round(s * .86f));
				break;
			case TRASH:
				g.drawLine(x + Math.round(s * .14f), y + Math.round(s * .26f), x + Math.round(s * .86f), y + Math.round(s * .26f));
				g.drawLine(x + Math.round(s * .38f), y + Math.round(s * .14f), x + Math.round(s * .62f), y + Math.round(s * .14f));
				g.drawRoundRect(x + Math.round(s * .24f), y + Math.round(s * .3f), Math.round(s * .52f), Math.round(s * .58f), 3, 3);
				break;
			case CHECK:
				g.drawLine(x + Math.round(s * .18f), y + Math.round(s * .52f), x + Math.round(s * .42f), y + Math.round(s * .76f));
				g.drawLine(x + Math.round(s * .42f), y + Math.round(s * .76f), x + Math.round(s * .84f), y + Math.round(s * .26f));
				break;
			case LOCK:
				g.drawArc(x + Math.round(s * .3f), y + Math.round(s * .1f), Math.round(s * .4f), Math.round(s * .44f), 0, 180);
				g.fillRoundRect(x + Math.round(s * .2f), y + Math.round(s * .4f), Math.round(s * .6f), Math.round(s * .48f), 3, 3);
				break;
			case HUD:
				g.drawRoundRect(x + Math.round(s * .1f), y + Math.round(s * .16f), Math.round(s * .8f), Math.round(s * .68f), 4, 4);
				g.drawLine(x + Math.round(s * .26f), y + Math.round(s * .38f), x + Math.round(s * .74f), y + Math.round(s * .38f));
				g.drawLine(x + Math.round(s * .26f), y + Math.round(s * .54f), x + Math.round(s * .6f), y + Math.round(s * .54f));
				g.drawLine(x + Math.round(s * .26f), y + Math.round(s * .7f), x + Math.round(s * .5f), y + Math.round(s * .7f));
				break;
			case SHIELD:
			{
				Polygon p = new Polygon();
				p.addPoint(x + Math.round(s * .5f), y + Math.round(s * .1f));
				p.addPoint(x + Math.round(s * .84f), y + Math.round(s * .24f));
				p.addPoint(x + Math.round(s * .78f), y + Math.round(s * .62f));
				p.addPoint(x + Math.round(s * .5f), y + Math.round(s * .9f));
				p.addPoint(x + Math.round(s * .22f), y + Math.round(s * .62f));
				p.addPoint(x + Math.round(s * .16f), y + Math.round(s * .24f));
				g.drawPolygon(p);
				break;
			}
			case CLOCK:
				g.drawOval(x + Math.round(s * .12f), y + Math.round(s * .12f), Math.round(s * .76f), Math.round(s * .76f));
				g.drawLine(x + Math.round(s * .5f), y + Math.round(s * .5f), x + Math.round(s * .5f), y + Math.round(s * .28f));
				g.drawLine(x + Math.round(s * .5f), y + Math.round(s * .5f), x + Math.round(s * .68f), y + Math.round(s * .58f));
				break;
			case TARGET:
				g.drawOval(x + Math.round(s * .12f), y + Math.round(s * .12f), Math.round(s * .76f), Math.round(s * .76f));
				g.drawOval(x + Math.round(s * .34f), y + Math.round(s * .34f), Math.round(s * .32f), Math.round(s * .32f));
				break;
			case GEAR:
			{
				double cx = x + s / 2.0;
				double cy = y + s / 2.0;
				for (int i = 0; i < 8; i++)
				{
					double a = Math.PI * i / 4.0;
					g.drawLine((int) Math.round(cx + Math.cos(a) * s * .26), (int) Math.round(cy + Math.sin(a) * s * .26),
						(int) Math.round(cx + Math.cos(a) * s * .44), (int) Math.round(cy + Math.sin(a) * s * .44));
				}
				g.draw(new Ellipse2D.Double(cx - s * .27, cy - s * .27, s * .54, s * .54));
				g.fill(new Ellipse2D.Double(cx - s * .09, cy - s * .09, s * .18, s * .18));
				break;
			}
			default:
				break;
		}
	}

	// ------------------------------------------------------------------ section card

	/** Panel with an Ironman-style head: gold uppercase title, optional description, stone body. */
	static final class Section extends JPanel
	{
		private final JPanel body;

		Section(String title, String description, LayoutManager bodyLayout)
		{
			super(new BorderLayout());
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
			JPanel head = clear(new BorderLayout(10, 0));
			head.setBorder(BorderFactory.createEmptyBorder(10, 14, 9, 14));
			JPanel words = clear(new BorderLayout(0, 2));
			words.add(label(title.toUpperCase(), Theme.eyebrow(11f), Theme.GOLD), BorderLayout.NORTH);
			if (description != null && !description.isEmpty())
			{
				JLabel sub = label(description, Theme.ui(12f), Theme.TEXT_3);
				words.add(sub, BorderLayout.CENTER);
			}
			head.add(words, BorderLayout.CENTER);
			add(head, BorderLayout.NORTH);
			body = clear(bodyLayout);
			body.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));
			add(body, BorderLayout.CENTER);
		}

		JPanel body()
		{
			return body;
		}

		void addHeadRight(JComponent component)
		{
			JPanel head = (JPanel) getComponent(0);
			head.add(component, BorderLayout.EAST);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			Theme.stone(g, 0, 0, getWidth(), getHeight(), 8);
			int headHeight = getComponent(0).getHeight();
			g.setPaint(new GradientPaint(0, 0, new Color(255, 230, 180, 10), 0, headHeight, new Color(0, 0, 0, 0)));
			g.fillRoundRect(1, 1, getWidth() - 2, headHeight, 8, 8);
			g.setPaint(null);
			g.setColor(Theme.EDGE_DARK);
			g.drawLine(1, headHeight, getWidth() - 2, headHeight);
			g.setColor(Theme.EDGE_HI);
			g.drawLine(1, headHeight + 1, getWidth() - 2, headHeight + 1);
			g.dispose();
		}
	}

	// ------------------------------------------------------------------ selectable tile

	/** A pickable card. Vertical tiles suit item grids; horizontal tiles carry a description. */
	static final class Tile extends JComponent
	{
		private final boolean vertical;
		private final Supplier<Image> icon;
		private final Glyph glyph;
		private String title;
		private String detail;
		private String badge;
		private boolean selected;
		private boolean locked;
		private boolean hover;
		private Runnable onPick;

		Tile(boolean vertical, Supplier<Image> icon, Glyph glyph, String title, String detail)
		{
			this.vertical = vertical;
			this.icon = icon;
			this.glyph = glyph;
			this.title = title;
			this.detail = detail;
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setPreferredSize(vertical ? new Dimension(108, 86) : new Dimension(220, 62));
			addMouseListener(new MouseAdapter()
			{
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

				@Override
				public void mousePressed(MouseEvent e)
				{
					requestFocusInWindow();
					pick();
				}
			});
			addKeyListener(new KeyAdapter()
			{
				@Override
				public void keyPressed(KeyEvent e)
				{
					if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER)
					{
						pick();
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

		private void pick()
		{
			if (!locked && isEnabled() && onPick != null)
			{
				onPick.run();
			}
		}

		Tile onPick(Runnable action)
		{
			onPick = action;
			return this;
		}

		void setSelected(boolean value)
		{
			if (selected != value)
			{
				selected = value;
				repaint();
			}
		}

		boolean isSelected()
		{
			return selected;
		}

		void setLocked(boolean value, String reason)
		{
			locked = value;
			badge = value ? reason : badge;
			setCursor(Cursor.getPredefinedCursor(value ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
			repaint();
		}

		boolean isLocked()
		{
			return locked;
		}

		void setBadge(String value)
		{
			badge = value;
			repaint();
		}

		void setTitle(String value)
		{
			title = value;
			repaint();
		}

		void setDetail(String value)
		{
			detail = value;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = getWidth();
			int h = getHeight();
			Composite old = locked ? Theme.fade(g, 0.42f) : g.getComposite();
			if (selected)
			{
				Theme.stone(g, 0, 0, w, h, 8, new Color(0x4A3C2B), new Color(0x2E2519));
				g.setPaint(new GradientPaint(0, 0, new Color(226, 180, 86, 54), w, h, new Color(226, 180, 86, 8)));
				g.fillRoundRect(1, 1, w - 2, h - 2, 8, 8);
				g.setPaint(null);
				g.setColor(new Color(226, 180, 86, 170));
				g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
				g.setColor(new Color(255, 213, 110, 60));
				g.drawRoundRect(1, 1, w - 3, h - 3, 7, 7);
			}
			else
			{
				Theme.stone(g, 0, 0, w, h, 8, hover && !locked ? Theme.STONE_3 : Theme.STONE_2, Theme.STONE_1);
				if (hover && !locked)
				{
					g.setColor(Theme.GOLD_LO);
					g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
				}
			}
			if (isFocusOwner())
			{
				g.setColor(Theme.alpha(Theme.GOLD_HI, 150));
				g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 2f}, 0f));
				g.drawRoundRect(3, 3, w - 7, h - 7, 6, 6);
				g.setStroke(new BasicStroke(1f));
			}
			Image image = icon == null ? null : icon.get();
			if (vertical)
			{
				int slotW = 44;
				int slotH = 36;
				int sx = (w - slotW) / 2;
				int sy = 9;
				Theme.well(g, sx, sy, slotW, slotH, 6);
				if (image != null)
				{
					Theme.icon(g, image, sx + 4, sy + 2, slotW - 8, slotH - 4);
				}
				else
				{
					Ui.glyph(g, glyph, sx + (slotW - 20) / 2, sy + (slotH - 20) / 2, 20, selected ? Theme.GOLD_HI : Theme.TEXT_2);
				}
				g.setFont(Theme.uiBold(12.5f));
				FontMetrics fm = g.getFontMetrics();
				String t = Theme.fit(fm, title, w - 10);
				g.setColor(selected ? Theme.GOLD_HI : Theme.TEXT);
				g.drawString(t, (w - fm.stringWidth(t)) / 2, sy + slotH + 17);
				if (detail != null && !detail.isEmpty())
				{
					g.setFont(Theme.ui(11f));
					FontMetrics dm = g.getFontMetrics();
					String d = Theme.fit(dm, detail, w - 10);
					g.setColor(Theme.TEXT_3);
					g.drawString(d, (w - dm.stringWidth(d)) / 2, sy + slotH + 31);
				}
			}
			else
			{
				int slot = h - 18;
				Theme.well(g, 9, 9, slot + 6, slot, 6);
				if (image != null)
				{
					Theme.icon(g, image, 12, 11, slot, slot - 4);
				}
				else
				{
					Ui.glyph(g, glyph, 9 + (slot + 6 - 20) / 2, 9 + (slot - 20) / 2, 20, selected ? Theme.GOLD_HI : Theme.TEXT_2);
				}
				int tx = slot + 26;
				g.setFont(Theme.uiBold(13.5f));
				FontMetrics fm = g.getFontMetrics();
				g.setColor(selected ? Theme.GOLD_HI : Theme.TEXT);
				int textRight = w - 12 - (selected ? 22 : 0);
				g.drawString(Theme.fit(fm, title, textRight - tx), tx, detail == null ? h / 2 + 5 : 26);
				if (detail != null)
				{
					g.setFont(Theme.ui(11.5f));
					FontMetrics dm = g.getFontMetrics();
					g.setColor(Theme.TEXT_3);
					g.drawString(Theme.fit(dm, detail, textRight - tx), tx, 44);
				}
			}
			g.setComposite(old);
			if (selected)
			{
				int r = 16;
				int cx = w - r - 7;
				int cy = 7;
				g.setColor(Theme.GOLD);
				g.fillOval(cx, cy, r, r);
				g.setColor(new Color(0x5A3F12));
				g.drawOval(cx, cy, r, r);
				Ui.glyph(g, Glyph.CHECK, cx + 2, cy + 2, r - 4, new Color(0x1B1206));
			}
			if (badge != null && !badge.isEmpty())
			{
				g.setFont(Theme.eyebrow(9.5f));
				FontMetrics bm = g.getFontMetrics();
				int bw = bm.stringWidth(badge) + 10;
				int bx = w - bw - 6 - (selected ? 26 : 0);
				int by = 6;
				Color tone = locked ? Theme.RED : Theme.TEXT_3;
				g.setColor(Theme.alpha(tone, 36));
				g.fillRoundRect(bx, by, bw, 15, 15, 15);
				g.setColor(Theme.alpha(tone, 130));
				g.drawRoundRect(bx, by, bw - 1, 14, 14, 14);
				g.setColor(locked ? new Color(0xFFAB9F) : Theme.TEXT_2);
				g.drawString(badge, bx + 5, by + 11);
			}
			g.dispose();
		}
	}

	// ------------------------------------------------------------------ toggle switch

	static final class Toggle extends JComponent
	{
		private boolean on;
		private boolean hover;
		private Runnable onChange;

		Toggle()
		{
			setPreferredSize(new Dimension(42, 22));
			setMinimumSize(new Dimension(42, 22));
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
		}

		private void flip()
		{
			if (!isEnabled())
			{
				return;
			}
			on = !on;
			repaint();
			if (onChange != null)
			{
				onChange.run();
			}
		}

		Toggle onChange(Runnable action)
		{
			onChange = action;
			return this;
		}

		boolean isOn()
		{
			return on;
		}

		void setOn(boolean value)
		{
			on = value;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = 42;
			int h = 22;
			int y = (getHeight() - h) / 2;
			Composite old = isEnabled() ? g.getComposite() : Theme.fade(g, 0.4f);
			if (on)
			{
				g.setPaint(new GradientPaint(0, y, new Color(0x7FDC5E), 0, y + h, new Color(0x3C8F27)));
			}
			else
			{
				g.setPaint(new GradientPaint(0, y, Theme.STONE_0, 0, y + h, Theme.BG_2));
			}
			g.fillRoundRect(0, y, w, h, h, h);
			g.setPaint(null);
			g.setColor(on ? new Color(0x183312) : (hover ? Theme.GOLD_LO : Theme.EDGE_DARK));
			g.drawRoundRect(0, y, w - 1, h - 1, h, h);
			int knob = h - 6;
			int kx = on ? w - knob - 3 : 3;
			g.setPaint(new GradientPaint(0, y + 3, new Color(0xF6EBD2), 0, y + 3 + knob, new Color(0xBFAE8A)));
			g.fillOval(kx, y + 3, knob, knob);
			g.setPaint(null);
			g.setColor(new Color(0, 0, 0, 120));
			g.drawOval(kx, y + 3, knob, knob);
			if (isFocusOwner())
			{
				g.setColor(Theme.alpha(Theme.GOLD_HI, 160));
				g.drawRoundRect(-1, y - 2, w + 1, h + 3, h + 3, h + 3);
			}
			g.setComposite(old);
			g.dispose();
		}
	}

	/** One setting line: title and description on the left, a control on the right. */
	static JPanel settingRow(String title, String description, JComponent control)
	{
		JPanel row = clear(new BorderLayout(14, 0));
		row.setBorder(BorderFactory.createEmptyBorder(7, 0, 7, 0));
		JPanel words = clear(new BorderLayout(0, 1));
		words.add(label(title, Theme.uiBold(13f), Theme.TEXT), BorderLayout.NORTH);
		if (description != null)
		{
			words.add(label(description, Theme.ui(11.5f), Theme.TEXT_3), BorderLayout.CENTER);
		}
		row.add(words, BorderLayout.CENTER);
		JPanel holder = clear(new java.awt.GridBagLayout());
		holder.add(control);
		row.add(holder, BorderLayout.EAST);
		if (description != null)
		{
			row.setToolTipText(description);
			control.setToolTipText(description);
		}
		return row;
	}

	/** Thin carved rule between setting rows. */
	static JComponent rule()
	{
		JComponent line = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				graphics.setColor(Theme.EDGE_DARK);
				graphics.drawLine(0, 0, getWidth(), 0);
				graphics.setColor(Theme.EDGE_HI);
				graphics.drawLine(0, 1, getWidth(), 1);
			}
		};
		line.setPreferredSize(new Dimension(10, 2));
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
		return line;
	}

	// ------------------------------------------------------------------ buttons

	static final class Button extends JButton
	{
		private final Kind kind;
		private Glyph glyph;
		private boolean hover;

		Button(String text, Kind kind, Glyph glyph)
		{
			super(text);
			this.kind = kind;
			this.glyph = glyph;
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setFont(kind == Kind.PRIMARY ? Theme.uiBold(14f) : Theme.uiBold(12.5f));
			addMouseListener(new MouseAdapter()
			{
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
			resize();
		}

		void setGlyph(Glyph value)
		{
			glyph = value;
			resize();
			repaint();
		}

		@Override
		public void setText(String text)
		{
			super.setText(text);
			resize();
		}

		private void resize()
		{
			if (kind == null)
			{
				return;
			}
			FontMetrics fm = getFontMetrics(getFont());
			int glyphSpace = glyph == null || glyph == Glyph.NONE ? 0 : 20;
			int text = getText() == null || getText().isEmpty() ? 0 : fm.stringWidth(getText());
			int height = kind == Kind.PRIMARY ? 38 : 32;
			int width = Math.max(text == 0 ? height : 0, text + glyphSpace + (text == 0 ? 12 : 30));
			setPreferredSize(new Dimension(width, height));
			setMinimumSize(new Dimension(width, height));
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = getWidth();
			int h = getHeight();
			boolean enabled = isEnabled();
			boolean lit = enabled && hover;
			boolean down = enabled && getModel().isPressed();
			Composite old = enabled ? g.getComposite() : Theme.fade(g, 0.45f);
			int dy = down ? 1 : 0;
			Color fg;
			switch (kind)
			{
				case PRIMARY:
					Theme.gold(g, 0, dy, w, h - 1, 8, lit);
					fg = new Color(0x1B1206);
					break;
				case DANGER:
					Theme.stone(g, 0, dy, w, h - 1, 8, lit ? new Color(0x6A3226) : new Color(0x5A2A21), new Color(0x3D1B15));
					fg = lit ? new Color(0xFFD3CA) : new Color(0xFFB4A6);
					break;
				case GHOST:
					if (lit)
					{
						g.setColor(Theme.STONE_3);
						g.fillRoundRect(0, dy, w, h - 1, 8, 8);
					}
					fg = lit ? Theme.GOLD_HI : Theme.TEXT_2;
					break;
				case STONE:
				default:
					Theme.stone(g, 0, dy, w, h - 1, 8, lit ? new Color(0x433626) : Theme.STONE_3, Theme.STONE_2);
					if (lit)
					{
						g.setColor(Theme.GOLD_LO);
						g.drawRoundRect(0, dy, w - 1, h - 2, 8, 8);
					}
					fg = lit ? Theme.GOLD_HI : Theme.TEXT;
					break;
			}
			if (isFocusOwner())
			{
				g.setColor(Theme.alpha(Theme.GOLD_HI, 170));
				g.drawRoundRect(2, 2 + dy, w - 5, h - 6, 6, 6);
			}
			g.setFont(getFont());
			FontMetrics fm = g.getFontMetrics();
			String text = getText() == null ? "" : getText();
			boolean hasGlyph = glyph != null && glyph != Glyph.NONE;
			int glyphSize = 14;
			int content = fm.stringWidth(text) + (hasGlyph ? glyphSize + (text.isEmpty() ? 0 : 7) : 0);
			int x = (w - content) / 2;
			int baseline = (h - fm.getHeight()) / 2 + fm.getAscent() + dy - 1;
			if (hasGlyph)
			{
				Ui.glyph(g, glyph, x, (h - glyphSize) / 2 + dy - 1, glyphSize, fg);
				x += glyphSize + (text.isEmpty() ? 0 : 7);
			}
			if (kind == Kind.PRIMARY)
			{
				g.setColor(new Color(255, 240, 200, 100));
				g.drawString(text, x, baseline + 1);
			}
			g.setColor(fg);
			g.drawString(text, x, baseline);
			g.setComposite(old);
			g.dispose();
		}
	}

	// ------------------------------------------------------------------ segmented control

	static final class Segmented extends JComponent
	{
		private final String[] options;
		private final int[] edges;
		private int selected;
		private int hover = -1;
		private IntConsumer onChange;

		Segmented(String... options)
		{
			this.options = options;
			this.edges = new int[options.length + 1];
			setFont(Theme.uiBold(12.5f));
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			FontMetrics fm = getFontMetrics(getFont());
			int width = 6;
			for (String option : options)
			{
				width += fm.stringWidth(option) + 28;
			}
			setPreferredSize(new Dimension(width, 34));
			MouseAdapter mouse = new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					requestFocusInWindow();
					int index = indexAt(e.getX());
					if (index >= 0)
					{
						choose(index);
					}
				}

				@Override
				public void mouseMoved(MouseEvent e)
				{
					hover = indexAt(e.getX());
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = -1;
					repaint();
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
			addKeyListener(new KeyAdapter()
			{
				@Override
				public void keyPressed(KeyEvent e)
				{
					if (e.getKeyCode() == KeyEvent.VK_LEFT && selected > 0)
					{
						choose(selected - 1);
					}
					else if (e.getKeyCode() == KeyEvent.VK_RIGHT && selected < options.length - 1)
					{
						choose(selected + 1);
					}
				}
			});
		}

		Segmented onChange(IntConsumer action)
		{
			onChange = action;
			return this;
		}

		int getSelected()
		{
			return selected;
		}

		void setSelected(int index)
		{
			selected = Math.max(0, Math.min(options.length - 1, index));
			repaint();
		}

		private void choose(int index)
		{
			if (!isEnabled())
			{
				return;
			}
			if (index == selected)
			{
				return;
			}
			selected = index;
			repaint();
			if (onChange != null)
			{
				onChange.accept(index);
			}
		}

		private int indexAt(int x)
		{
			for (int i = 0; i < options.length; i++)
			{
				if (x >= edges[i] && x < edges[i + 1])
				{
					return i;
				}
			}
			return -1;
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = getWidth();
			int h = getHeight();
			Composite faded = isEnabled() ? g.getComposite() : Theme.fade(g, 0.45f);
			g.setColor(Theme.STONE_0);
			g.fillRoundRect(0, 0, w, h, 9, 9);
			Theme.well(g, 0, 0, w, h - 1, 9);
			g.setFont(getFont());
			FontMetrics fm = g.getFontMetrics();
			int each = (w - 6) / options.length;
			for (int i = 0; i < options.length; i++)
			{
				int x = 3 + i * each;
				int segW = i == options.length - 1 ? w - 3 - x : each;
				edges[i] = x;
				edges[i + 1] = x + segW;
				if (i == selected)
				{
					Theme.stone(g, x, 3, segW, h - 7, 7, new Color(0x4A3C2B), new Color(0x362B1F));
				}
				else if (i == hover)
				{
					g.setColor(Theme.STONE_2);
					g.fillRoundRect(x, 3, segW, h - 7, 7, 7);
				}
				g.setColor(i == selected ? Theme.GOLD_HI : (i == hover ? Theme.TEXT : Theme.TEXT_2));
				g.drawString(options[i], x + (segW - fm.stringWidth(options[i])) / 2, (h - fm.getHeight()) / 2 + fm.getAscent() - 1);
			}
			if (isFocusOwner())
			{
				g.setColor(Theme.alpha(Theme.GOLD_HI, 140));
				g.drawRoundRect(0, 0, w - 1, h - 2, 9, 9);
			}
			g.setComposite(faded);
			g.dispose();
		}
	}

	// ------------------------------------------------------------------ slider

	static final class Slider extends JComponent
	{
		private final int min;
		private final int max;
		private final String unit;
		private int value;
		private Runnable onChange;

		Slider(int min, int max, int value, String unit)
		{
			this.min = min;
			this.max = max;
			this.value = value;
			this.unit = unit;
			setFocusable(true);
			setPreferredSize(new Dimension(300, 36));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter mouse = new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					requestFocusInWindow();
					drag(e.getX());
				}

				@Override
				public void mouseDragged(MouseEvent e)
				{
					drag(e.getX());
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
			addKeyListener(new KeyAdapter()
			{
				@Override
				public void keyPressed(KeyEvent e)
				{
					if (e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_DOWN)
					{
						set(Slider.this.value - 1, true);
					}
					else if (e.getKeyCode() == KeyEvent.VK_RIGHT || e.getKeyCode() == KeyEvent.VK_UP)
					{
						set(Slider.this.value + 1, true);
					}
				}
			});
		}

		Slider onChange(Runnable action)
		{
			onChange = action;
			return this;
		}

		int getValue()
		{
			return value;
		}

		void setValue(int next)
		{
			set(next, false);
		}

		private int trackLeft()
		{
			return 10;
		}

		private int trackRight()
		{
			return getWidth() - 84;
		}

		private void drag(int x)
		{
			double t = (double) (x - trackLeft()) / Math.max(1, trackRight() - trackLeft());
			set((int) Math.round(min + t * (max - min)), true);
		}

		private void set(int next, boolean fire)
		{
			int clamped = Math.max(min, Math.min(max, next));
			if (clamped == value)
			{
				return;
			}
			value = clamped;
			repaint();
			if (fire && onChange != null)
			{
				onChange.run();
			}
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int left = trackLeft();
			int right = trackRight();
			int cy = getHeight() / 2;
			double t = (double) (value - min) / Math.max(1, max - min);
			Theme.bar(g, left, cy - 4, right - left, 8, t, Theme.GOLD_HI, new Color(0xB88426));
			int kx = left + (int) Math.round(t * (right - left));
			int knob = 18;
			g.setPaint(new GradientPaint(0, cy - knob / 2f, new Color(0xF6EBD2), 0, cy + knob / 2f, new Color(0xBFAE8A)));
			g.fillOval(kx - knob / 2, cy - knob / 2, knob, knob);
			g.setPaint(null);
			g.setColor(isFocusOwner() ? Theme.GOLD_HI : new Color(0x3A2A12));
			g.drawOval(kx - knob / 2, cy - knob / 2, knob, knob);
			int boxX = right + 14;
			int boxW = getWidth() - boxX;
			Theme.well(g, boxX, cy - 13, boxW, 26, 6);
			g.setFont(Theme.uiBold(14f));
			FontMetrics fm = g.getFontMetrics();
			String number = Integer.toString(value);
			g.setColor(Theme.YELLOW);
			int nx = boxX + 10;
			Theme.shadowText(g, number, nx, cy + 5, Theme.YELLOW);
			g.setFont(Theme.ui(11.5f));
			g.setColor(Theme.TEXT_3);
			g.drawString(unit, nx + fm.stringWidth(number) + 5, cy + 5);
			g.dispose();
		}
	}

	// ------------------------------------------------------------------ number stepper

	static final class Stepper extends JPanel
	{
		private final int min;
		private final int max;
		private final JTextField field = new JTextField();
		private int value;
		private Runnable onChange;

		Stepper(int min, int max, int value)
		{
			super(new BorderLayout(4, 0));
			this.min = min;
			this.max = max;
			this.value = value;
			setOpaque(false);
			Button minus = new Button("-", Kind.STONE, Glyph.NONE);
			Button plus = new Button("+", Kind.STONE, Glyph.NONE);
			minus.setFont(Theme.uiBold(15f));
			plus.setFont(Theme.uiBold(15f));
			minus.setPreferredSize(new Dimension(32, 32));
			plus.setPreferredSize(new Dimension(32, 32));
			minus.setToolTipText("Decrease");
			plus.setToolTipText("Increase");
			minus.addActionListener(e -> set(this.value - step(), true));
			plus.addActionListener(e -> set(this.value + step(), true));
			field(field);
			field.setHorizontalAlignment(SwingConstants.CENTER);
			field.setFont(Theme.uiBold(14f));
			field.setForeground(Theme.YELLOW);
			field.setColumns(5);
			field.setText(Integer.toString(value));
			field.addActionListener(e -> commit());
			field.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent e)
				{
					commit();
				}
			});
			add(minus, BorderLayout.WEST);
			add(field, BorderLayout.CENTER);
			add(plus, BorderLayout.EAST);
			setPreferredSize(new Dimension(150, 32));
		}

		private int step()
		{
			return max >= 1000 ? 10 : 1;
		}

		private void commit()
		{
			try
			{
				set(Integer.parseInt(field.getText().trim().replace(",", "")), true);
			}
			catch (NumberFormatException ex)
			{
				field.setText(Integer.toString(value));
			}
		}

		Stepper onChange(Runnable action)
		{
			onChange = action;
			return this;
		}

		int getValue()
		{
			return value;
		}

		void setValue(int next)
		{
			set(next, false);
		}

		private void set(int next, boolean fire)
		{
			int clamped = Math.max(min, Math.min(max, next));
			boolean changed = clamped != value;
			value = clamped;
			field.setText(Integer.toString(value));
			if (fire && changed && onChange != null)
			{
				onChange.run();
			}
		}

		@Override
		public void setEnabled(boolean enabled)
		{
			super.setEnabled(enabled);
			for (Component child : getComponents())
			{
				child.setEnabled(enabled);
			}
		}
	}

	// ------------------------------------------------------------------ left navigation

	static final class Nav extends JComponent
	{
		private final Supplier<Image> icon;
		private final Glyph glyph;
		private final String title;
		private String detail;
		private boolean active;
		private boolean hover;
		private Runnable onClick;

		Nav(Supplier<Image> icon, Glyph glyph, String title, String detail)
		{
			this.icon = icon;
			this.glyph = glyph;
			this.title = title;
			this.detail = detail;
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setPreferredSize(new Dimension(196, 48));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (onClick != null)
					{
						onClick.run();
					}
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
					if ((e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER) && onClick != null)
					{
						onClick.run();
					}
				}
			});
		}

		Nav onClick(Runnable action)
		{
			onClick = action;
			return this;
		}

		void setActive(boolean value)
		{
			active = value;
			repaint();
		}

		void setDetail(String value)
		{
			if (value == null ? detail != null : !value.equals(detail))
			{
				detail = value;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = getWidth();
			int h = getHeight();
			if (active)
			{
				g.setPaint(new GradientPaint(0, 0, new Color(226, 180, 86, 46), w, 0, new Color(226, 180, 86, 5)));
				g.fillRoundRect(8, 3, w - 14, h - 6, 8, 8);
				g.setPaint(null);
				g.setColor(new Color(226, 180, 86, 90));
				g.drawRoundRect(8, 3, w - 15, h - 7, 8, 8);
				g.setColor(Theme.GOLD_HI);
				g.fillRoundRect(0, 11, 3, h - 22, 3, 3);
			}
			else if (hover)
			{
				g.setColor(Theme.STONE_2);
				g.fillRoundRect(8, 3, w - 14, h - 6, 8, 8);
			}
			int slot = 30;
			int sx = 16;
			int sy = (h - slot) / 2;
			g.setColor(active ? new Color(226, 180, 86, 30) : new Color(0, 0, 0, 50));
			g.fillRoundRect(sx, sy, slot, slot, 6, 6);
			g.setColor(active ? new Color(226, 180, 86, 90) : new Color(0, 0, 0, 90));
			g.drawRoundRect(sx, sy, slot - 1, slot - 1, 6, 6);
			Image image = icon == null ? null : icon.get();
			if (image != null)
			{
				Theme.icon(g, image, sx + 3, sy + 3, slot - 6, slot - 6);
			}
			else
			{
				Ui.glyph(g, glyph, sx + 7, sy + 7, 16, active ? Theme.GOLD_HI : Theme.TEXT_2);
			}
			int tx = sx + slot + 11;
			g.setFont(Theme.uiBold(13.5f));
			g.setColor(active ? Theme.GOLD_HI : (hover ? Theme.TEXT : Theme.TEXT_2));
			g.drawString(title, tx, detail == null ? h / 2 + 5 : h / 2 - 2);
			if (detail != null)
			{
				g.setFont(Theme.ui(11f));
				g.setColor(Theme.TEXT_4);
				g.drawString(Theme.fit(g.getFontMetrics(), detail, w - tx - 10), tx, h / 2 + 13);
			}
			if (isFocusOwner())
			{
				g.setColor(Theme.alpha(Theme.GOLD_HI, 130));
				g.drawRoundRect(9, 4, w - 17, h - 9, 7, 7);
			}
			g.dispose();
		}
	}

	// ------------------------------------------------------------------ status pill

	static final class Pill extends JComponent
	{
		private String text = "";
		private Color tone = Theme.TEXT_3;

		Pill()
		{
			setFont(Theme.eyebrow(10.5f));
			setPreferredSize(new Dimension(96, 24));
		}

		void set(String value, Color color)
		{
			if (!value.equals(text) || !color.equals(tone))
			{
				text = value;
				tone = color;
				FontMetrics fm = getFontMetrics(getFont());
				setPreferredSize(new Dimension(fm.stringWidth(text) + 34, 24));
				revalidate();
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Theme.smooth(g);
			int w = getWidth();
			int h = getHeight();
			g.setColor(Theme.alpha(tone, 34));
			g.fillRoundRect(0, 0, w, h, h, h);
			g.setColor(Theme.alpha(tone, 120));
			g.drawRoundRect(0, 0, w - 1, h - 1, h, h);
			g.setColor(Theme.alpha(tone, 60));
			g.fillOval(7, h / 2 - 6, 12, 12);
			g.setColor(tone);
			g.fillOval(10, h / 2 - 3, 6, 6);
			g.setFont(getFont());
			FontMetrics fm = g.getFontMetrics();
			g.setColor(Theme.mix(tone, Color.WHITE, 0.35));
			g.drawString(text, 24, (h - fm.getHeight()) / 2 + fm.getAscent());
			g.dispose();
		}
	}

	// ------------------------------------------------------------------ inputs

	static void field(JTextField field)
	{
		field.setBackground(Theme.STONE_0);
		field.setForeground(Theme.TEXT);
		field.setCaretColor(Theme.GOLD_HI);
		field.setSelectionColor(Theme.GOLD_LO);
		field.setSelectedTextColor(Color.WHITE);
		field.setFont(Theme.ui(13f));
		field.setOpaque(true);
		field.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Theme.EDGE_DARK),
			BorderFactory.createEmptyBorder(5, 9, 5, 9)));
	}

	/** Restyles a combo box as a stone well with a gold chevron and a stone popup list. */
	static <T> void combo(JComboBox<T> box)
	{
		box.setUI(new BasicComboBoxUI()
		{
			@Override
			protected JButton createArrowButton()
			{
				JButton arrow = new JButton()
				{
					@Override
					protected void paintComponent(Graphics graphics)
					{
						Graphics2D g = (Graphics2D) graphics.create();
						Theme.smooth(g);
						int cx = getWidth() / 2;
						int cy = getHeight() / 2;
						g.setColor(comboBox.isEnabled() ? Theme.GOLD : Theme.TEXT_4);
						g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
						g.drawLine(cx - 4, cy - 2, cx, cy + 2);
						g.drawLine(cx, cy + 2, cx + 4, cy - 2);
						g.dispose();
					}
				};
				arrow.setBorder(BorderFactory.createEmptyBorder());
				arrow.setContentAreaFilled(false);
				arrow.setFocusPainted(false);
				arrow.setOpaque(false);
				arrow.setPreferredSize(new Dimension(28, 28));
				return arrow;
			}

			@Override
			public void paintCurrentValueBackground(Graphics graphics, java.awt.Rectangle bounds, boolean hasFocus)
			{
				graphics.setColor(Theme.STONE_0);
				graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
			}

			@Override
			protected ComboPopup createPopup()
			{
				BasicComboPopup popup = new BasicComboPopup(comboBox);
				popup.setBorder(BorderFactory.createLineBorder(Theme.EDGE));
				popup.getList().setBackground(Theme.STONE_1);
				popup.getList().setForeground(Theme.TEXT);
				popup.getList().setSelectionBackground(Theme.SLOT_2);
				popup.getList().setSelectionForeground(Theme.GOLD_HI);
				return popup;
			}
		});
		box.setBackground(Theme.STONE_0);
		box.setForeground(Theme.TEXT);
		box.setFont(Theme.ui(13f));
		box.setOpaque(true);
		box.setFocusable(true);
		box.setMaximumRowCount(12);
		box.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Theme.EDGE_DARK),
			BorderFactory.createEmptyBorder(1, 4, 1, 0)));
		box.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
			{
				JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				label.setFont(Theme.ui(13f));
				label.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
				boolean current = index < 0;
				label.setBackground(current ? Theme.STONE_0 : (isSelected ? Theme.SLOT_2 : Theme.STONE_1));
				label.setForeground(current ? Theme.TEXT : (isSelected ? Theme.GOLD_HI : Theme.TEXT));
				label.setOpaque(true);
				return label;
			}
		});
		box.setPreferredSize(new Dimension(Math.max(200, box.getPreferredSize().width), 32));
		Object child = box.getAccessibleContext().getAccessibleChild(0);
		if (child instanceof JPopupMenu)
		{
			((JPopupMenu) child).setBorder(BorderFactory.createLineBorder(Theme.EDGE));
		}
	}

	/** Vertical-only scroller for a page body, with a thin gold-on-stone bar and a clear viewport. */
	static javax.swing.JScrollPane scroll(JComponent view)
	{
		JPanel track = new ScrollBody();
		track.add(view, BorderLayout.CENTER);
		javax.swing.JScrollPane pane = new javax.swing.JScrollPane(track,
			javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		pane.setOpaque(false);
		pane.getViewport().setOpaque(false);
		pane.setBorder(BorderFactory.createEmptyBorder());
		pane.setViewportBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
		javax.swing.JScrollBar bar = pane.getVerticalScrollBar();
		bar.setUnitIncrement(22);
		bar.setOpaque(false);
		bar.setPreferredSize(new Dimension(8, 10));
		bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI()
		{
			@Override
			protected void paintTrack(Graphics graphics, JComponent c, java.awt.Rectangle bounds)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				g.setColor(new Color(0, 0, 0, 70));
				g.fillRoundRect(bounds.x + 1, bounds.y, bounds.width - 2, bounds.height, 6, 6);
				g.dispose();
			}

			@Override
			protected void paintThumb(Graphics graphics, JComponent c, java.awt.Rectangle bounds)
			{
				if (bounds.isEmpty())
				{
					return;
				}
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				g.setColor(isDragging || isThumbRollover() ? Theme.GOLD : Theme.GOLD_LO);
				g.fillRoundRect(bounds.x + 1, bounds.y + 1, bounds.width - 2, bounds.height - 2, 6, 6);
				g.dispose();
			}

			@Override
			protected JButton createDecreaseButton(int orientation)
			{
				return zeroButton();
			}

			@Override
			protected JButton createIncreaseButton(int orientation)
			{
				return zeroButton();
			}

			private JButton zeroButton()
			{
				JButton button = new JButton();
				button.setPreferredSize(new Dimension(0, 0));
				button.setMinimumSize(new Dimension(0, 0));
				button.setMaximumSize(new Dimension(0, 0));
				return button;
			}
		});
		return pane;
	}

	/** Panel that follows the viewport width so only vertical scrolling happens. */
	private static final class ScrollBody extends JPanel implements javax.swing.Scrollable
	{
		ScrollBody()
		{
			super(new BorderLayout());
			setOpaque(false);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return 22;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return Math.max(22, visible.height - 40);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/** Small text button with a chevron, for menus in slim bars. */
	static JButton menuButton(String text)
	{
		JButton button = new JButton(text)
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				if (getModel().isRollover() || getModel().isPressed())
				{
					g.setColor(Theme.SLOT_2);
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
				}
				super.paintComponent(g);
				int cx = getWidth() - 13;
				int cy = getHeight() / 2;
				g.setColor(Theme.GOLD);
				g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawLine(cx - 3, cy - 1, cx, cy + 2);
				g.drawLine(cx, cy + 2, cx + 3, cy - 1);
				g.dispose();
			}
		};
		button.setHorizontalAlignment(SwingConstants.LEFT);
		button.setFont(Theme.uiBold(12f));
		button.setForeground(Theme.GOLD_HI);
		button.setContentAreaFilled(false);
		button.setFocusPainted(false);
		button.setOpaque(false);
		button.setRolloverEnabled(true);
		button.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 24));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}

	/** Stone popup menu item. */
	static javax.swing.JMenuItem menuItem(String text, Runnable action)
	{
		javax.swing.JMenuItem item = new javax.swing.JMenuItem(text);
		item.setFont(Theme.ui(13f));
		item.setOpaque(true);
		item.setBackground(Theme.STONE_1);
		item.setForeground(Theme.TEXT);
		item.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 14));
		item.addActionListener(event -> action.run());
		return item;
	}

	static JPopupMenu popup()
	{
		JPopupMenu menu = new JPopupMenu();
		menu.setBackground(Theme.STONE_1);
		menu.setBorder(BorderFactory.createLineBorder(Theme.EDGE));
		return menu;
	}

	static Insets insets(int all)
	{
		return new Insets(all, all, all, all);
	}
}
