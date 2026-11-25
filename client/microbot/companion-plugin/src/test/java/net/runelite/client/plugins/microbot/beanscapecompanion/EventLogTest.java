package net.runelite.client.plugins.microbot.beanscapecompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EventLogTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void appendsOneJsonLinePerEvent() throws Exception
	{
		File dir = tmp.newFolder("events");
		EventLog.append(dir, "Iron Bramble", GameEvent.level("woodcutting", 41), "2026-11-23T10:00:00Z");
		EventLog.append(dir, "Iron Bramble", new GameEvent("quest", "Cook's Assistant", "Quest complete"), "2026-11-23T10:05:00Z");
		List<String> lines = Files.readAllLines(new File(dir, "iron_bramble.jsonl").toPath());
		assertEquals(2, lines.size());
		assertTrue(lines.get(0), lines.get(0).contains("\"type\":\"level\""));
		assertTrue(lines.get(0), lines.get(0).contains("\"level\":41"));
		assertTrue(lines.get(1), lines.get(1).contains("\"t\":\"2026-11-23T10:05:00Z\""));
	}
}
