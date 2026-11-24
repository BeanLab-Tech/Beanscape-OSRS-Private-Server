package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Item icons from the OSRS wiki, fetched on demand for items the panel actually shows and cached on
 * disk. Misses (wiki unreachable, unknown name) stay null and the tile draws without an icon.
 */
final class WikiIcons
{
	private static final String BASE = "https://oldschool.runescape.wiki/images/";

	private final Map<Integer, Image> cache = new ConcurrentHashMap<>();
	private final Set<Integer> pending = Collections.synchronizedSet(new HashSet<>());
	private final File dir = new File(RuneLite.RUNELITE_DIR, "beanscape-admin/icons");
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private final ExecutorService pool = Executors.newFixedThreadPool(2, runnable ->
	{
		Thread thread = new Thread(runnable, "beanscape-icons");
		thread.setDaemon(true);
		return thread;
	});

	/** Returns a supplier the tile calls while painting; the first call starts the fetch. */
	Supplier<Image> supplier(int id, String name, JComponent repaint)
	{
		return () ->
		{
			Image known = cache.get(id);
			if (known != null)
			{
				return known;
			}
			if (pending.add(id))
			{
				pool.execute(() -> load(id, name, repaint));
			}
			return null;
		};
	}

	void shutdown()
	{
		pool.shutdownNow();
	}

	private void load(int id, String name, JComponent repaint)
	{
		try
		{
			File file = new File(dir, id + ".png");
			BufferedImage image = file.isFile() ? ImageIO.read(file) : null;
			if (image == null)
			{
				image = download(name, file);
			}
			if (image != null)
			{
				cache.put(id, image);
				SwingUtilities.invokeLater(repaint::repaint);
			}
		}
		catch (IOException | RuntimeException ex)
		{
			// No icon: the tile draws without one.
		}
	}

	private BufferedImage download(String name, File target) throws IOException
	{
		String page = name.trim().replace(' ', '_');
		HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + ApiClient.enc(page) + ".png"))
			.timeout(Duration.ofSeconds(6))
			.header("User-Agent", "Beanscape-Admin/0.1 (private server admin tool)")
			.build();
		try
		{
			HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200)
			{
				return null;
			}
			BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
			if (image != null && (target.getParentFile().isDirectory() || target.getParentFile().mkdirs()))
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
}
