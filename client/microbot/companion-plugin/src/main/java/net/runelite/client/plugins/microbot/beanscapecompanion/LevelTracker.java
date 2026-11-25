package net.runelite.client.plugins.microbot.beanscapecompanion;

import java.util.HashMap;
import java.util.Map;

/** Spots real level-ups: the first value seen for a skill after login is only remembered. */
final class LevelTracker
{
	private final Map<String, Integer> levels = new HashMap<>();

	boolean levelledUp(String skill, int level)
	{
		Integer before = levels.put(skill, level);
		return before != null && before > 0 && level > before;
	}

	void reset()
	{
		levels.clear();
	}
}
