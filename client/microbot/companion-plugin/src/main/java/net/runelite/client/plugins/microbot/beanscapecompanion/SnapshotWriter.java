package net.runelite.client.plugins.microbot.beanscapecompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

final class SnapshotWriter
{
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

	private SnapshotWriter()
	{
	}

	static File defaultDir()
	{
		return new File(System.getProperty("user.home"), ".runelite/beanscape/companion");
	}

	static String normalize(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-\\u00a0]+", "_");
	}

	static String toJson(Snapshot snapshot)
	{
		return GSON.toJson(snapshot);
	}

	/** Writes to a temp file then renames, so the launcher never reads half a file. */
	static void write(File dir, Snapshot snapshot) throws IOException
	{
		Files.createDirectories(dir.toPath());
		String base = normalize(snapshot.name);
		File tmp = new File(dir, base + ".json.tmp");
		File target = new File(dir, base + ".json");
		Files.write(tmp.toPath(), toJson(snapshot).getBytes(StandardCharsets.UTF_8));
		try
		{
			Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException atomicUnsupported)
		{
			Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
