package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * "Stone and parchment" tokens shared with the Ironman Command Center: carved stone panels,
 * bevelled slots, gold accents, and OSRS stat yellow. Used by the settings window and the HUD.
 */
final class Theme
{
	private Theme()
	{
	}

	// Surfaces, darkest to lightest
	static final Color BG = new Color(0x0F0C09);
	static final Color BG_2 = new Color(0x15110C);
	static final Color STONE_0 = new Color(0x1B1611);
	static final Color STONE_1 = new Color(0x231C15);
	static final Color STONE_2 = new Color(0x2C241A);
	static final Color STONE_3 = new Color(0x382E21);
	static final Color STONE_4 = new Color(0x46392A);
	static final Color SLOT = new Color(0x3B3226);
	static final Color SLOT_2 = new Color(0x4D4131);

	// Edges
	static final Color EDGE_DARK = new Color(0x070504);
	static final Color EDGE = new Color(0x4A3C2A);
	static final Color EDGE_SOFT = new Color(0x32291E);
	static final Color EDGE_HI = new Color(255, 228, 170, 20);

	// Text
	static final Color TEXT = new Color(0xF1E6CC);
	static final Color TEXT_2 = new Color(0xC9B994);
	static final Color TEXT_3 = new Color(0x9C8C6E);
	static final Color TEXT_4 = new Color(0x7A6D55);

	// Accents
	static final Color GOLD = new Color(0xE2B456);
	static final Color GOLD_HI = new Color(0xFFD56E);
	static final Color GOLD_LO = new Color(0x8F6D2C);
	static final Color YELLOW = new Color(0xFFE74A);
	static final Color ORANGE = new Color(0xFF9B33);
	static final Color GREEN = new Color(0x5EC64C);
	static final Color GREEN_LO = new Color(0x24401C);
	static final Color RED = new Color(0xE5584A);
	static final Color RED_LO = new Color(0x4A1D17);
	static final Color BLUE = new Color(0x6EA6E4);
	static final Color BLUE_LO = new Color(0x1D3048);
	static final Color LOG = new Color(0xB07A3E);

	private static final String UI_FAMILY = pick("Segoe UI", "Source Sans 3", "Source Sans Pro", "Dialog");
	private static final String DISPLAY_FAMILY = pick("Cinzel", "Trajan Pro", "Georgia", "Serif");
	private static final Map<String, Font> FONTS = new HashMap<>();

	static Font ui(float size)
	{
		return font(UI_FAMILY, Font.PLAIN, size, 0f);
	}

	static Font uiBold(float size)
	{
		return font(UI_FAMILY, Font.BOLD, size, 0f);
	}

	static Font display(float size)
	{
		return font(DISPLAY_FAMILY, Font.BOLD, size, 0.01f);
	}

	/** Small uppercase label with wide tracking, like the Ironman app's eyebrows and panel heads. */
	static Font eyebrow(float size)
	{
		return font(UI_FAMILY, Font.BOLD, size, 0.12f);
	}

	private static synchronized Font font(String family, int style, float size, float tracking)
	{
		String key = family + style + size + tracking;
		Font cached = FONTS.get(key);
		if (cached != null)
		{
			return cached;
		}
		Font base = new Font(family, style, Math.round(size));
		if (tracking != 0f)
		{
			Map<TextAttribute, Object> attributes = new HashMap<>();
			attributes.put(TextAttribute.TRACKING, tracking);
			base = base.deriveFont(attributes);
		}
		FONTS.put(key, base);
		return base;
	}

	private static String pick(String... families)
	{
		Set<String> installed;
		try
		{
			installed = new HashSet<>(Arrays.asList(
				GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
		}
		catch (Throwable ex)
		{
			return families[families.length - 1];
		}
		for (String family : families)
		{
			if (installed.contains(family))
			{
				return family;
			}
		}
		return families[families.length - 1];
	}

	static Color alpha(Color color, int alpha)
	{
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
	}

	static Color mix(Color a, Color b, double t)
	{
		double k = Math.max(0, Math.min(1, t));
		return new Color(
			(int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * k),
			(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k),
			(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k),
			(int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * k));
	}

	static void smooth(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
	}

	/** Carved stone panel: warm vertical gradient, near-black edge, one-pixel highlight inside the top. */
	static void stone(Graphics2D g, int x, int y, int w, int h, int arc)
	{
		stone(g, x, y, w, h, arc, STONE_2, STONE_1);
	}

	static void stone(Graphics2D g, int x, int y, int w, int h, int arc, Color top, Color bottom)
	{
		if (w <= 0 || h <= 0)
		{
			return;
		}
		g.setPaint(new GradientPaint(0, y, top, 0, y + h, bottom));
		g.fillRoundRect(x, y, w, h, arc, arc);
		g.setPaint(null);
		g.setColor(EDGE_HI);
		g.drawLine(x + arc / 2, y + 1, x + w - 1 - arc / 2, y + 1);
		g.setColor(EDGE_DARK);
		g.setStroke(new BasicStroke(1f));
		g.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
	}

	/** Recessed slot: the dark inset well used by inputs, bars and KPI boxes. */
	static void well(Graphics2D g, int x, int y, int w, int h, int arc)
	{
		if (w <= 0 || h <= 0)
		{
			return;
		}
		g.setColor(new Color(0, 0, 0, 70));
		g.fillRoundRect(x, y, w, h, arc, arc);
		g.setColor(new Color(0, 0, 0, 90));
		g.drawLine(x + 2, y + 1, x + w - 3, y + 1);
		g.setColor(new Color(0, 0, 0, 110));
		g.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
		g.setColor(EDGE_HI);
		g.drawLine(x + arc / 2, y + h, x + w - 1 - arc / 2, y + h);
	}

	/** Gold metal fill used for primary buttons and the selected step. */
	static void gold(Graphics2D g, int x, int y, int w, int h, int arc, boolean hover)
	{
		Color top = hover ? new Color(0xF0C66A) : new Color(0xE4B75A);
		Color bottom = hover ? new Color(0xB98A35) : new Color(0xA87C2D);
		g.setPaint(new GradientPaint(0, y, top, 0, y + h, bottom));
		g.fillRoundRect(x, y, w, h, arc, arc);
		g.setPaint(null);
		g.setColor(new Color(255, 240, 200, 90));
		g.drawLine(x + arc / 2, y + 1, x + w - 1 - arc / 2, y + 1);
		g.setColor(new Color(0x5A3F12));
		g.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
	}

	/** Progress bar in the Ironman style: black trough, glossy two-tone fill. */
	static void bar(Graphics2D g, int x, int y, int w, int h, double fraction, Color hi, Color lo)
	{
		g.setColor(new Color(0x0B0906));
		g.fillRoundRect(x, y, w, h, 4, 4);
		int fill = (int) Math.round((w - 2) * Math.max(0, Math.min(1, fraction)));
		if (fill > 0)
		{
			g.setPaint(new GradientPaint(0, y + 1, hi, 0, y + h - 1, lo));
			g.fillRoundRect(x + 1, y + 1, fill, h - 2, 3, 3);
			g.setPaint(null);
			g.setColor(new Color(255, 255, 255, 40));
			g.drawLine(x + 2, y + 1, x + fill - 1, y + 1);
		}
		g.setColor(Color.BLACK);
		g.drawRoundRect(x, y, w - 1, h - 1, 4, 4);
	}

	/** OSRS stat text: colored glyphs with a one-pixel black drop shadow. */
	static void shadowText(Graphics2D g, String text, int x, int y, Color color)
	{
		if (text == null)
		{
			return;
		}
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(color);
		g.drawString(text, x, y);
	}

	static int textWidth(Graphics2D g, Font font, String text)
	{
		return text == null ? 0 : g.getFontMetrics(font).stringWidth(text);
	}

	/** Cuts text with an ellipsis so it fits in the given width. */
	static String fit(FontMetrics metrics, String text, int width)
	{
		if (text == null)
		{
			return "";
		}
		if (metrics.stringWidth(text) <= width)
		{
			return text;
		}
		String dots = "...";
		int end = text.length();
		while (end > 0 && metrics.stringWidth(text.substring(0, end) + dots) > width)
		{
			end--;
		}
		return end <= 0 ? dots : text.substring(0, end).trim() + dots;
	}

	/** Draws an item or skill sprite centered in a box, keeping its aspect ratio. */
	static void icon(Graphics2D g, Image image, int x, int y, int w, int h)
	{
		if (image == null)
		{
			return;
		}
		int iw = image.getWidth(null);
		int ih = image.getHeight(null);
		if (iw <= 0 || ih <= 0)
		{
			return;
		}
		double scale = Math.min((double) w / iw, (double) h / ih);
		int dw = (int) Math.round(iw * scale);
		int dh = (int) Math.round(ih * scale);
		Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale >= 1
			? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
			: RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(image, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
		if (old != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
		}
	}

	static Composite fade(Graphics2D g, float opacity)
	{
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
		return old;
	}
}
