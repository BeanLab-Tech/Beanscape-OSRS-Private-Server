package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin console for the BeanLab private server. The toolbar button opens a small stone-themed side
 * panel; the full console opens in its own window. Everything goes through the server's /v1 API,
 * which checks the caller's mod level on every request.
 */
@PluginDescriptor(
	name = "Beanscape Admin",
	description = "Admin console for the BeanLab private server: players, items, stats, accounts, world tools and an audit log.",
	tags = {"beanscape", "admin", "private server"},
	enabledByDefault = true
)
public class BeanscapeAdminPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(BeanscapeAdminPlugin.class);

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SkillIconManager skillIcons;

	@Inject
	private ItemManager itemManager;

	private Session session;
	private ItemIcons icons;
	private AdminWindow window;
	private NavigationButton button;

	@Override
	protected void startUp()
	{
		session = new Session(skillIcons);
		icons = new ItemIcons(itemManager);
		SwingUtilities.invokeLater(() ->
		{
			window = new AdminWindow(session, icons);
			button = NavigationButton.builder()
				.tooltip("Beanscape Admin")
				.icon(icon())
				.priority(9)
				.panel(new SidePanel(session, window))
				.build();
			clientToolbar.addNavigation(button);
		});
		session.start();
		log.info("Beanscape Admin started (configured: {})", session.settings().configured());
	}

	@Override
	protected void shutDown()
	{
		if (button != null)
		{
			clientToolbar.removeNavigation(button);
		}
		if (window != null)
		{
			window.close();
		}
		session.stop();
		icons.shutdown();
	}

	private static BufferedImage icon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		Theme.smooth(g);
		g.setPaint(new GradientPaint(0, 0, Theme.GOLD_HI, 0, 16, Theme.GOLD_LO));
		g.fillRoundRect(0, 0, 16, 16, 5, 5);
		g.setColor(new Color(0x2A1D08));
		g.setFont(Theme.display(11f));
		g.drawString("B", 4, 12);
		g.dispose();
		return image;
	}

	/** Compact side panel: connection state and a way into the full console. */
	private static final class SidePanel extends PluginPanel
	{
		SidePanel(Session session, AdminWindow window)
		{
			super(false);
			setLayout(new BorderLayout());
			JPanel body = new JPanel()
			{
				@Override
				protected void paintComponent(Graphics graphics)
				{
					Graphics2D g = (Graphics2D) graphics.create();
					g.setColor(Theme.BG);
					g.fillRect(0, 0, getWidth(), getHeight());
					g.setPaint(new GradientPaint(0, 0, new Color(226, 180, 86, 22), getWidth() * 0.6f, getHeight() * 0.3f, new Color(0, 0, 0, 0)));
					g.fillRect(0, 0, getWidth(), getHeight());
					g.dispose();
				}
			};
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setBorder(BorderFactory.createEmptyBorder(16, 14, 14, 14));

			JLabel title = Ui.label("Beanscape Admin", Theme.display(18f), Theme.GOLD_HI);
			JLabel sub = Ui.label("BeanLab private server", Theme.ui(12f), Theme.TEXT_3);
			Ui.Pill pill = new Ui.Pill();
			JLabel who = Ui.label(" ", Theme.ui(12.5f), Theme.TEXT_2);
			JLabel online = Ui.label(" ", Theme.ui(12.5f), Theme.TEXT_2);
			Ui.Button open = new Ui.Button("Open console", Ui.Kind.PRIMARY, Ui.Glyph.HUD);
			open.addActionListener(event -> window.open());

			JPanel pillRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
			pillRow.add(pill);
			JPanel openRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
			openRow.add(open);
			for (java.awt.Component c : new java.awt.Component[]{title, sub, pillRow, who, online, openRow})
			{
				((javax.swing.JComponent) c).setAlignmentX(0f);
			}
			body.add(title);
			body.add(sub);
			body.add(javax.swing.Box.createVerticalStrut(14));
			body.add(pillRow);
			body.add(javax.swing.Box.createVerticalStrut(10));
			body.add(who);
			body.add(online);
			body.add(javax.swing.Box.createVerticalStrut(16));
			body.add(openRow);
			add(body, BorderLayout.NORTH);
			setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 220));

			session.onChange(() ->
			{
				boolean up = session.connected();
				pill.set(up ? "Connected" : "Offline", up ? Theme.GREEN : Theme.RED);
				who.setText(up ? "Signed in as " + session.myName() + " (" + session.myLevel() + ")" : session.error());
				online.setText(up ? session.players().size() + " players online" : " ");
			});
		}
	}
}
