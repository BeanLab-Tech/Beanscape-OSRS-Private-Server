package net.runelite.client.plugins.microbot.beanscapecompanion;

import com.google.inject.Provides;
import java.io.File;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Beanscape Companion",
	description = "Exports this character's skills, XP, wealth, account type, membership, and playtime for the Beanscape launcher. Read-only.",
	tags = {"beanscape", "launcher", "export", "stats"},
	authors = {"Beanscape"},
	version = CompanionPlugin.VERSION,
	minClientVersion = "2.0.20",
	enabledByDefault = true,
	isExternal = true
)
public class CompanionPlugin extends Plugin
{
	static final String VERSION = "1.1.0";
	private static final Logger log = LoggerFactory.getLogger(CompanionPlugin.class);
	private static final long MIN_GAP_MS = 60_000;
	private static final long PERIOD_MS = 5 * 60_000;
	private static final int LOGIN_SETTLE_TICKS = 3;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private CompanionConfig config;

	private final File dir = SnapshotWriter.defaultDir();
	private final File eventDir = EventLog.defaultDir();
	private final LevelTracker levels = new LevelTracker();
	private SnapshotReader reader;
	private long lastWrite;
	private boolean pending;
	private int loginTicks = -1;

	@Provides
	CompanionConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CompanionConfig.class);
	}

	@Override
	protected void startUp()
	{
		reader = new SnapshotReader(client, itemManager);
		lastWrite = 0;
		loginTicks = client.getGameState() == GameState.LOGGED_IN ? LOGIN_SETTLE_TICKS : -1;
	}

	@Override
	protected void shutDown()
	{
		reader = null;
		loginTicks = -1;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loginTicks = LOGIN_SETTLE_TICKS;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			levels.reset();
			if (reader != null)
			{
				reader.forgetBank();
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		pending = true;
		String skill = event.getSkill().getName().toLowerCase(java.util.Locale.ROOT);
		int level = client.getRealSkillLevel(event.getSkill());
		if (levels.levelledUp(skill, level))
		{
			logEvent(GameEvent.level(skill, level));
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		GameEvent parsed = ChatEvents.parse(event.getMessage());
		if (parsed != null)
		{
			logEvent(parsed);
		}
	}

	/** Remember every level once after login so the next StatChanged is a real level-up. */
	private void seedLevels()
	{
		for (Skill skill : Skill.values())
		{
			levels.levelledUp(skill.getName().toLowerCase(java.util.Locale.ROOT), client.getRealSkillLevel(skill));
		}
	}

	private void logEvent(GameEvent event)
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return;
		}
		try
		{
			EventLog.append(eventDir, client.getLocalPlayer().getName(), event, java.time.Instant.now().toString());
		}
		catch (Exception e)
		{
			log.warn("Beanscape Companion event log failed", e);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK && reader != null)
		{
			reader.rememberBank();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			export();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (CompanionConfig.GROUP.equals(event.getGroup()) && "exportNow".equals(event.getKey()) && config.exportNow())
		{
			clientThread.invokeLater(this::export);
			configManager.setConfiguration(CompanionConfig.GROUP, "exportNow", false);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (loginTicks > 0)
		{
			// Varps and containers arrive a few ticks after LOGGED_IN.
			loginTicks--;
			if (loginTicks == 0)
			{
				loginTicks = -1;
				pending = false;
				seedLevels();
				export();
			}
			return;
		}
		long now = System.currentTimeMillis();
		if ((pending && now - lastWrite >= MIN_GAP_MS) || now - lastWrite >= PERIOD_MS)
		{
			pending = false;
			export();
		}
	}

	private void export()
	{
		if (reader == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		try
		{
			Snapshot snapshot = reader.read();
			if (snapshot == null)
			{
				return;
			}
			SnapshotWriter.write(dir, snapshot);
			lastWrite = System.currentTimeMillis();
		}
		catch (Exception e)
		{
			log.warn("Beanscape Companion export failed", e);
		}
	}
}
