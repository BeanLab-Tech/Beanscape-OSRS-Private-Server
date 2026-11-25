package net.runelite.client.plugins.microbot.beanscapecompanion;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** Appends account-feed events to ~/.runelite/beanscape/companion/events/<name>.jsonl. */
final class EventLog
{
	private static final Gson GSON = new Gson();

	private EventLog()
	{
	}

	static File defaultDir()
	{
		return new File(SnapshotWriter.defaultDir(), "events");
	}

	static void append(File dir, String name, GameEvent event, String at) throws IOException
	{
		Files.createDirectories(dir.toPath());
		event.t = at;
		String line = GSON.toJson(event) + System.lineSeparator();
		Files.write(
			new File(dir, SnapshotWriter.normalize(name) + ".jsonl").toPath(),
			line.getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.CREATE,
			StandardOpenOption.APPEND);
	}
}
