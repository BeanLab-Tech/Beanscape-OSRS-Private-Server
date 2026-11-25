package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Image;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;

/** State shared by every page: who we are, who is online, and the poll loop that keeps it fresh. */
final class Session
{
	static final String[] LEVELS = {"player", "moderator", "admin", "owner"};

	/** One row of the online list. */
	static final class Player
	{
		final String name;
		final int combat;
		final String modlevel;
		final String area;
		final String coords;
		final String avatar;
		final String discord;

		Player(JsonObject json)
		{
			name = str(json, "name");
			combat = json.has("combat") ? json.get("combat").getAsInt() : 0;
			modlevel = str(json, "modlevel");
			area = str(json, "area");
			coords = str(json, "coords");
			avatar = str(json, "avatar");
			discord = str(json, "discord");
		}

		/** Where they are, in words when the server knows the area and as coordinates otherwise. */
		String place()
		{
			return area.isEmpty() ? coords : area;
		}
	}

	private final AdminSettings[] settings = {AdminSettings.load()};
	private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, runnable ->
	{
		Thread thread = new Thread(runnable, "beanscape-admin");
		thread.setDaemon(true);
		return thread;
	});
	private final ApiClient api = new ApiClient(() -> settings[0]);
	private final SkillIconManager skillIcons;
	private final List<Runnable> listeners = new ArrayList<>();
	private final Avatars avatars = new Avatars();
	private static final java.awt.image.BufferedImage EMPTY = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
	private final Image[] bundledSkillIcons = new Image[Skill.values().length + 1];

	private volatile boolean connected;
	private volatile String error = "Connecting";
	private volatile String myName = "";
	private volatile String myLevel = "player";
	private volatile String myAvatar = "";
	private volatile List<Player> players = new ArrayList<>();
	private volatile JsonObject world = new JsonObject();
	private volatile String selected = "";

	Session(SkillIconManager skillIcons)
	{
		this.skillIcons = skillIcons;
	}

	void start()
	{
		pool.scheduleWithFixedDelay(this::poll, 0, 3, TimeUnit.SECONDS);
	}

	void stop()
	{
		pool.shutdownNow();
		avatars.shutdown();
	}

	void reload()
	{
		settings[0] = AdminSettings.load();
		pool.execute(this::poll);
	}

	AdminSettings settings()
	{
		return settings[0];
	}

	ApiClient api()
	{
		return api;
	}

	boolean connected()
	{
		return connected;
	}

	String error()
	{
		return error;
	}

	String myName()
	{
		return myName;
	}

	String myLevel()
	{
		return myLevel;
	}

	String myAvatar()
	{
		return myAvatar;
	}

	Avatars avatars()
	{
		return avatars;
	}

	List<Player> players()
	{
		return players;
	}

	JsonObject world()
	{
		return world;
	}

	String selected()
	{
		return selected;
	}

	void select(String name)
	{
		selected = name == null ? "" : name;
		fire();
	}

	/** Who an action applies to: the selected player, or you when nobody is selected. */
	String target()
	{
		return selected.isEmpty() ? myName : selected;
	}

	static int rank(String level)
	{
		for (int i = 0; i < LEVELS.length; i++)
		{
			if (LEVELS[i].equalsIgnoreCase(level))
			{
				return i;
			}
		}
		return 0;
	}

	boolean has(String level)
	{
		return connected && rank(myLevel) >= rank(level);
	}

	void onChange(Runnable listener)
	{
		listeners.add(listener);
	}

	/** The skill's sprite; null asks for the total-level icon (the client's OVERALL constant is unusable). */
	/* The skill's sprite: the client's icon manager first, the sprite bundled in the client jar otherwise. */
	Image skillIcon(Skill skill)
	{
		try
		{
			Image image = skillIcons == null || skill == null ? null : skillIcons.getSkillImage(skill, true);
			if (image != null)
			{
				return image;
			}
		}
		catch (RuntimeException ex)
		{
			// Fall through to the bundled sprite.
		}
		return bundled(skill);
	}

	private synchronized Image bundled(Skill skill)
	{
		int slot = skill == null ? bundledSkillIcons.length - 1 : skill.ordinal();
		Image known = bundledSkillIcons[slot];
		if (known == null)
		{
			known = EMPTY;
			String path = "/skill_icons_small/" + (skill == null ? "overall" : skill.getName().toLowerCase()) + ".png";
			try (java.io.InputStream in = SkillIconManager.class.getResourceAsStream(path))
			{
				Image read = in == null ? null : javax.imageio.ImageIO.read(in);
				if (read != null)
				{
					known = read;
				}
			}
			catch (java.io.IOException | RuntimeException ex)
			{
				// No bundled sprite: callers draw the skill's short name.
			}
			bundledSkillIcons[slot] = known;
		}
		return known == EMPTY ? null : known;
	}

	/** Discord picture link for a player or account name; empty when we do not know one. */
	String avatarOf(String name)
	{
		if (name.equalsIgnoreCase(myName))
		{
			return myAvatar;
		}
		for (Player p : players)
		{
			if (p.name.equalsIgnoreCase(name))
			{
				return p.avatar;
			}
		}
		return "";
	}

	/** Runs [work] off the UI thread, then hands the result (or a readable error) back on it. */
	<T> void call(Callable<T> work, Consumer<T> ok, Consumer<String> fail)
	{
		pool.execute(() ->
		{
			try
			{
				T result = work.call();
				SwingUtilities.invokeLater(() -> ok.accept(result));
			}
			catch (ApiClient.ApiException ex)
			{
				SwingUtilities.invokeLater(() -> fail.accept(ex.getMessage()));
			}
			catch (Exception ex)
			{
				SwingUtilities.invokeLater(() -> fail.accept("Something went wrong: " + ex.getMessage()));
			}
		});
	}

	/** Convenience for the common "POST an action, show its message" case. */
	void act(String path, JsonObject body, Consumer<String> done, Consumer<String> fail)
	{
		call(() -> api.post(path, body), json -> done.accept(json.has("message") ? json.get("message").getAsString() : "Done"), fail);
	}

	private void poll()
	{
		try
		{
			JsonObject me = api.get("/v1/me");
			myName = str(me, "name");
			myLevel = str(me, "modlevel");
			myAvatar = str(me, "avatar");
			List<Player> next = new ArrayList<>();
			JsonObject list = api.get("/v1/players");
			if (list.has("players"))
			{
				for (JsonElement element : list.getAsJsonArray("players"))
				{
					next.add(new Player(element.getAsJsonObject()));
				}
			}
			players = next;
			world = rank(myLevel) >= rank("moderator") ? api.get("/v1/world") : new JsonObject();
			connected = true;
			error = "";
		}
		catch (ApiClient.ApiException ex)
		{
			connected = false;
			error = ex.getMessage();
		}
		catch (RuntimeException ex)
		{
			connected = false;
			error = "Unexpected reply from the server";
		}
		fire();
	}

	private void fire()
	{
		SwingUtilities.invokeLater(() ->
		{
			for (Runnable listener : listeners)
			{
				listener.run();
			}
		});
	}

	static String str(JsonObject json, String key)
	{
		return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
	}

	static JsonArray array(JsonObject json, String key)
	{
		return json != null && json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
	}

	static <T> List<T> map(JsonArray array, Function<JsonObject, T> mapper)
	{
		List<T> out = new ArrayList<>();
		for (JsonElement element : array)
		{
			out.add(mapper.apply(element.getAsJsonObject()));
		}
		return out;
	}
}
