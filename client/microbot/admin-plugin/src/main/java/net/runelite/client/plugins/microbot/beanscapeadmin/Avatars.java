package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.client.RuneLite;

/**
 * Discord profile pictures for accounts. The server sends a CDN link for accounts created through
 * Discord; this class downloads each one once, keeps it on disk, and hands out a square image the
 * caller crops round. Until a picture arrives (or when an account has none) a gold initial on stone
 * stands in, so lists never jump when images load.
 */
final class Avatars
{
	private static final int PX = 128;

	private final Map<String, BufferedImage> pictures = new ConcurrentHashMap<>();
	private final Map<String, BufferedImage> initials = new ConcurrentHashMap<>();
	private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
	private final Map<String, Long> failed = new ConcurrentHashMap<>();
	private final File dir = new File(RuneLite.RUNELITE_DIR, "beanscape-admin/avatars");
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private final ExecutorService pool = Executors.newFixedThreadPool(2, runnable ->
	{
		Thread thread = new Thread(runnable, "beanscape-avatars");
		thread.setDaemon(true);
		return thread;
	});

	/** Only Discord's own image hosts are fetched; the link itself comes from the server. */
	static boolean trusted(String url)
	{
		try
		{
			URI uri = URI.create(url);
			String host = uri.getHost();
			return "https".equals(uri.getScheme()) && host != null
				&& (host.equals("cdn.discordapp.com") || host.equals("media.discordapp.net"));
		}
		catch (IllegalArgumentException ex)
		{
			return false;
		}
	}

	/**
	 * A supplier for painting: returns the picture when loaded, the initial otherwise. The first call
	 * for a link starts the download and repaints {@code repaint} when it lands.
	 */
	Supplier<Image> supplier(String url, String name, JComponent repaint)
	{
		return () ->
		{
			if (url == null || url.isEmpty() || !trusted(url))
			{
				return initial(name);
			}
			BufferedImage known = pictures.get(url);
			if (known != null)
			{
				return known;
			}
			Long lastFail = failed.get(url);
			boolean cooling = lastFail != null && System.currentTimeMillis() - lastFail < 60_000;
			if (!cooling && pending.add(url))
			{
				pool.execute(() -> load(url, repaint));
			}
			return initial(name);
		};
	}

	void shutdown()
	{
		pool.shutdownNow();
	}

	/** A gold letter on a stone disc. */
	BufferedImage initial(String name)
	{
		String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
		return initials.computeIfAbsent(letter, key ->
		{
			BufferedImage image = new BufferedImage(PX, PX, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			Theme.smooth(g);
			g.setPaint(new GradientPaint(0, 0, Theme.SLOT_2, 0, PX, new Color(0x1C1610)));
			g.fillRect(0, 0, PX, PX);
			g.setPaint(null);
			g.setFont(Theme.display(64f));
			FontMetrics fm = g.getFontMetrics();
			Theme.shadowText(g, key, (PX - fm.stringWidth(key)) / 2, (PX + fm.getAscent() - fm.getDescent()) / 2, Theme.GOLD_HI);
			g.dispose();
			return image;
		});
	}

	private void load(String url, JComponent repaint)
	{
		try
		{
			File file = new File(dir, hash(url) + ".png");
			BufferedImage image = file.isFile() ? ImageIO.read(file) : null;
			if (image == null)
			{
				image = download(url, file);
			}
			if (image != null)
			{
				pictures.put(url, image);
				SwingUtilities.invokeLater(repaint::repaint);
			}
		}
		catch (IOException | RuntimeException ex)
		{
			// Keep the initial. The next session tries again.
		}
		finally
		{
			// A failure may be a blip; a later paint retries after a minute instead of hammering.
			pending.remove(url);
			if (!pictures.containsKey(url))
			{
				failed.put(url, System.currentTimeMillis());
			}
		}
	}

	private BufferedImage download(String url, File target) throws IOException
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(8))
			.header("User-Agent", "Beanscape-Admin/0.2 (private server admin tool)")
			.build();
		try
		{
			HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200 || response.body().length > 2_000_000)
			{
				return null;
			}
			BufferedImage raw = ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
			if (raw == null)
			{
				return null;
			}
			BufferedImage image = square(raw);
			if (target.getParentFile().isDirectory() || target.getParentFile().mkdirs())
			{
				ImageIO.write(image, "png", target);
			}
			return image;
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/** Center-crops to a square and scales to the working size. */
	private static BufferedImage square(BufferedImage raw)
	{
		int side = Math.min(raw.getWidth(), raw.getHeight());
		BufferedImage out = new BufferedImage(PX, PX, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(raw, 0, 0, PX, PX, (raw.getWidth() - side) / 2, (raw.getHeight() - side) / 2,
			(raw.getWidth() + side) / 2, (raw.getHeight() + side) / 2, null);
		g.dispose();
		return out;
	}

	private static String hash(String url)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for (byte b : digest)
			{
				out.append(String.format("%02x", b));
			}
			return out.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			return Integer.toHexString(url.hashCode());
		}
	}
}
