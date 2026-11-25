package net.runelite.client.plugins.microbot.beanscapecompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SnapshotWriterTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static Snapshot sample()
	{
		Snapshot s = new Snapshot();
		s.name = "Iron Bramble";
		s.capturedAt = "2026-11-23T12:00:00Z";
		s.accountType = "IRONMAN";
		s.playtimeMinutes = null;
		Snapshot.SkillValue attack = new Snapshot.SkillValue();
		attack.level = 72;
		attack.xp = 900000;
		attack.boosted = 72;
		s.skills.put("attack", attack);
		return s;
	}

	@Test
	public void normalizesFileName()
	{
		assertEquals("iron_bramble", SnapshotWriter.normalize("Iron Bramble"));
		assertEquals("a_b_c", SnapshotWriter.normalize("A-b_C"));
	}

	@Test
	public void jsonHasSchemaAndNullPlaytime()
	{
		String json = SnapshotWriter.toJson(sample());
		assertTrue(json, json.contains("\"schema\": 1"));
		assertTrue(json, json.contains("\"playtimeMinutes\": null"));
		assertTrue(json, json.contains("\"xp\": 900000"));
	}

	@Test
	public void writeIsAtomic() throws Exception
	{
		File dir = tmp.newFolder("companion");
		SnapshotWriter.write(dir, sample());
		SnapshotWriter.write(dir, sample());
		String[] files = dir.list();
		assertEquals(1, files.length);
		assertEquals("iron_bramble.json", files[0]);
		assertTrue(new String(Files.readAllBytes(new File(dir, files[0]).toPath())).contains("Iron Bramble"));
	}
}
