package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.runelite.client.RuneLite;

/**
 * Connection settings written by the Beanscape launcher to {@code <home>/.runelite/beanscape-admin.json}:
 * {@code {"apiUrl": "https://.../adminapi", "token": "..."}}. The token is never logged.
 */
final class AdminSettings
{
	static final String FILE = "beanscape-admin.json";

	final String apiUrl;
	final String token;

	AdminSettings(String apiUrl, String token)
	{
		this.apiUrl = apiUrl == null ? "" : apiUrl.trim().replaceAll("/+$", "");
		this.token = token == null ? "" : token.trim();
	}

	boolean configured()
	{
		return !apiUrl.isEmpty() && !token.isEmpty();
	}

	/** Web admin lives next to the API on the same host. */
	String webAdminUrl()
	{
		int cut = apiUrl.indexOf("/adminapi");
		return (cut > 0 ? apiUrl.substring(0, cut) : apiUrl) + "/admin/";
	}

	static File file()
	{
		return new File(RuneLite.RUNELITE_DIR, FILE);
	}

	static AdminSettings load()
	{
		File file = file();
		if (!file.isFile())
		{
			return new AdminSettings("", "");
		}
		try
		{
			String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			JsonObject json = new Gson().fromJson(raw, JsonObject.class);
			return new AdminSettings(text(json, "apiUrl"), text(json, "token"));
		}
		catch (IOException | RuntimeException ex)
		{
			return new AdminSettings("", "");
		}
	}

	private static String text(JsonObject json, String key)
	{
		return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
	}
}
