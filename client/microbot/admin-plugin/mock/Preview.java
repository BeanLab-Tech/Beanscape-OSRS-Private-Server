package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;

/**
 * Dev tool: renders console pages to PNGs so the design can be checked without a game client.
 * Usage: Preview outDir [width height]. Renders each page at the given size (default 1080x720) and
 * again at a narrow 860x580 for the pages that reflow.
 */
public final class Preview
{
	public static void main(String[] args) throws Exception
	{
		File out = new File(args[0]);
		out.mkdirs();
		Session session = new Session(null);
		ItemIcons icons = new ItemIcons(null);
		AdminWindow[] holder = new AdminWindow[1];
		SwingUtilities.invokeAndWait(() -> holder[0] = new AdminWindow(session, icons));
		session.start();
		Thread.sleep(2500);
		AdminWindow window = holder[0];
		session.select("Umpix");
		Thread.sleep(800);
		String[] names = {"players", "items", "stats", "accounts", "world", "log"};
		for (int i = 0; i < names.length; i++)
		{
			final int page = i;
			SwingUtilities.invokeAndWait(() -> window.show(page));
			if (page == AdminWindow.ITEMS)
			{
				searchItems(window, "a");
				Thread.sleep(2500);
			}
			// The window is never shown, so the first paint is what starts image loads: paint, wait, paint again.
			SwingUtilities.invokeAndWait(() -> save(window, new File(out, "warm.png"), 1080, 720));
			Thread.sleep(3500);
			SwingUtilities.invokeAndWait(() -> save(window, new File(out, names[page] + ".png"), 1080, 720));
		}
		for (int page : new int[]{AdminWindow.PLAYERS, AdminWindow.ITEMS, AdminWindow.ACCOUNTS})
		{
			SwingUtilities.invokeAndWait(() -> window.show(page));
			SwingUtilities.invokeAndWait(() -> save(window, new File(out, "warm.png"), 860, 580));
			Thread.sleep(1500);
			SwingUtilities.invokeAndWait(() -> save(window, new File(out, "narrow_" + names[page] + ".png"), 860, 580));
		}
		System.exit(0);
	}

	private static void searchItems(AdminWindow window, String text) throws Exception
	{
		java.lang.reflect.Field pagesField = AdminWindow.class.getDeclaredField("pages");
		pagesField.setAccessible(true);
		Page[] pages = (Page[]) pagesField.get(window);
		ItemsPage items = (ItemsPage) pages[AdminWindow.ITEMS];
		java.lang.reflect.Field search = ItemsPage.class.getDeclaredField("search");
		search.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				((JTextField) search.get(items)).setText(text + "b");
			}
			catch (Exception ex)
			{
				throw new RuntimeException(ex);
			}
		});
	}

	private static void save(AdminWindow window, File file, int width, int height)
	{
		try
		{
			JDialog dialog = (JDialog) window.owner();
			dialog.setSize(width, height);
			dialog.validate();
			Container content = dialog.getContentPane();
			content.doLayout();
			BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = image.createGraphics();
			content.printAll(g);
			g.dispose();
			ImageIO.write(image, "png", file);
			System.out.println("wrote " + file);
		}
		catch (Exception ex)
		{
			ex.printStackTrace();
		}
	}
}
