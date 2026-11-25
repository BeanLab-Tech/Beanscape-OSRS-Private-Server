package net.runelite.client.plugins.microbot.beanscapecompanion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns game chat lines into account-feed events. Read-only: it only looks at messages. */
final class ChatEvents
{
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");
	private static final Pattern QUEST = Pattern.compile("^Congratulations, you've completed a quest: (.+?)\\.?$");
	private static final Pattern DIARY_TASK = Pattern.compile("^Well done! You have completed an? (\\w+) task in the (.+?) area\\.");
	private static final Pattern DIARY_TIER = Pattern.compile("^Congratulations! You have completed all of the (\\w+) tasks in the (.+?) area\\.");
	private static final Pattern COMBAT = Pattern.compile("^Congratulations, you've completed an? (\\w+) combat task: (.+?)(?: \\(\\d+ points?\\))?\\.?$");
	private static final Pattern CLOG = Pattern.compile("^New item added to your collection log: (.+)$");
	private static final Pattern CLUE = Pattern.compile("^You have completed ([\\d,]+) (\\w+) Treasure Trails?\\.$");
	private static final Pattern DROP = Pattern.compile("^Valuable drop: (.+?) \\(([\\d,]+ coins)\\)$");

	private ChatEvents()
	{
	}

	static GameEvent parse(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String text = TAGS.matcher(raw).replaceAll("").trim();
		Matcher m;
		if ((m = QUEST.matcher(text)).find())
		{
			return new GameEvent("quest", m.group(1), "Quest complete");
		}
		if ((m = DIARY_TASK.matcher(text)).find())
		{
			return new GameEvent("diary", m.group(2) + " " + m.group(1).toLowerCase() + " task", "Achievement diary task");
		}
		if ((m = DIARY_TIER.matcher(text)).find())
		{
			return new GameEvent("diary", m.group(2) + " " + m.group(1).toLowerCase() + " diary", "Achievement diary complete");
		}
		if ((m = COMBAT.matcher(text)).find())
		{
			return new GameEvent("combat", m.group(2), GameEvent.capitalize(m.group(1).toLowerCase()) + " combat task");
		}
		if ((m = CLOG.matcher(text)).find())
		{
			return new GameEvent("clog", m.group(1), "New collection log item");
		}
		if ((m = CLUE.matcher(text)).find())
		{
			return new GameEvent("clue", GameEvent.capitalize(m.group(2).toLowerCase()) + " clue #" + m.group(1), "Treasure trail complete");
		}
		if ((m = DROP.matcher(text)).find())
		{
			return new GameEvent("drop", m.group(1), m.group(2));
		}
		if (text.startsWith("You have a funny feeling like you")
			|| text.startsWith("You feel something weird sneaking into your backpack"))
		{
			return new GameEvent("pet", "Pet drop!", text);
		}
		return null;
	}
}
