package net.runelite.client.plugins.microbot.beanscapecompanion;

import java.util.LinkedHashMap;
import java.util.Map;

/** One character's exported state. Field names are the file format the launcher reads. */
final class Snapshot
{
	int schema = 1;
	String name;
	String capturedAt;
	String pluginVersion = CompanionPlugin.VERSION;
	Integer world;
	String accountType;
	Boolean members;
	Integer membershipDays;
	Integer combat;
	Integer total;
	Long totalXp;
	Integer questPoints;
	Integer playtimeMinutes;
	Map<String, SkillValue> skills = new LinkedHashMap<>();
	Wealth wealth = new Wealth();
	Position position;

	static final class SkillValue
	{
		int level;
		long xp;
		int boosted;
	}

	static final class Wealth
	{
		long gp;
		long inventory;
		long equipment;
		long bank;
		long total;
		String bankSeenAt;
	}

	static final class Position
	{
		int x;
		int y;
		int plane;
	}
}
