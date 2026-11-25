package net.runelite.client.plugins.microbot.beanscapecompanion;

/** One line in a character's account feed. Field names are the file format the launcher reads. */
final class GameEvent
{
	String t;
	String type;
	String title;
	String detail;
	String skill;
	Integer level;

	GameEvent(String type, String title, String detail)
	{
		this.type = type;
		this.title = title;
		this.detail = detail;
	}

	static GameEvent level(String skill, int level)
	{
		GameEvent e = new GameEvent("level", capitalize(skill) + " " + level, null);
		e.skill = skill;
		e.level = level;
		return e;
	}

	static String capitalize(String text)
	{
		if (text == null || text.isEmpty())
		{
			return text;
		}
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}
}
