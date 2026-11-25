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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.client.RuneLite;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Item pictures by id. The game client already has the real sprites, so those come first; the OSRS
 * wiki fills in anything the client cannot draw. Wiki downloads are cached on disk. An item with no
 * picture anywhere stays null and the row draws a neutral placeholder instead.
 */
final class ItemIcons
{
	private static final String WIKI = "https://oldschool.runescape.wiki/images/";

	private final ItemManager itemManager;
	private final Map<Integer, Image> cache = new ConcurrentHashMap<>();
	private final Set<Integer> missing = Collections.synchronizedSet(new HashSet<>());
	private final Set<Integer> pending = Collections.synchronizedSet(new HashSet<>());
	private final File dir = new File(RuneLite.RUNELITE_DIR, "beanscape-admin/icons");
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private final ExecutorService pool = Executors.newFixedThreadPool(3, runnable ->
	{
		Thread thread = new Thread(runnable, "beanscape-icons");
		thread.setDaemon(true);
		return thread;
	});

	ItemIcons(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	/** Returns a supplier the row calls while painting; the first call starts the fetch. */
	Supplier<Image> supplier(int id, String name, JComponent repaint)
	{
		return () ->
		{
			Image known = cache.get(id);
			if (known != null)
			{
				return known;
			}
			if (!missing.contains(id) && pending.add(id))
			{
				pool.execute(() -> load(id, name, repaint));
			}
			return null;
		};
	}

	/** True once every source has been tried and none had a picture. */
	boolean isMissing(int id)
	{
		return missing.contains(id);
	}

	void shutdown()
	{
		pool.shutdownNow();
	}

	private void load(int id, String name, JComponent repaint)
	{
		try
		{
			Image image = fromClient(id);
			if (image == null)
			{
				image = fromWiki(id, name);
			}
			if (image != null)
			{
				cache.put(id, image);
			}
			else
			{
				missing.add(id);
			}
			SwingUtilities.invokeLater(repaint::repaint);
		}
		finally
		{
			pending.remove(id);
		}
	}

	/** The client's own sprite. Ids the client's cache does not know come back empty. */
	private Image fromClient(int id)
	{
		if (itemManager == null)
		{
			return null;
		}
		try
		{
			AsyncBufferedImage async = itemManager.getImage(id);
			CountDownLatch loaded = new CountDownLatch(1);
			async.onLoaded(loaded::countDown);
			loaded.await(4, TimeUnit.SECONDS);
			return hasPixels(async) ? async : null;
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			return null;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** An empty sprite is fully transparent; a real one has at least a few visible pixels. */
	private static boolean hasPixels(BufferedImage image)
	{
		int seen = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) > 40 && ++seen >= 6)
				{
					return true;
				}
			}
		}
		return false;
	}

	private Image fromWiki(int id, String name)
	{
		try
		{
			File file = new File(dir, id + ".png");
			BufferedImage image = file.isFile() ? ImageIO.read(file) : null;
			if (image != null)
			{
				return image;
			}
			String page = name.trim().replace(' ', '_');
			for (String candidate : new String[]{page, page + "_detail"})
			{
				image = download(candidate);
				if (image != null)
				{
					if (file.getParentFile().isDirectory() || file.getParentFile().mkdirs())
					{
						ImageIO.write(image, "png", file);
					}
					return image;
				}
			}
		}
		catch (IOException | RuntimeException ex)
		{
			// No wiki picture; the row draws a placeholder.
		}
		return null;
	}

	private BufferedImage download(String page) throws IOException
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(WIKI + ApiClient.enc(page) + ".png"))
			.timeout(Duration.ofSeconds(6))
			.header("User-Agent", "Beanscape-Admin/0.2 (private server admin tool)")
			.build();
		try
		{
			HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			return response.statusCode() == 200 ? ImageIO.read(new java.io.ByteArrayInputStream(response.body())) : null;
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
