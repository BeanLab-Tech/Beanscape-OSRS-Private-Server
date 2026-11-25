package net.runelite.client.plugins.microbot.beanscapecompanion;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(CompanionConfig.GROUP)
public interface CompanionConfig extends Config
{
	String GROUP = "beanscapecompanion";

	@ConfigItem(
		keyName = "exportNow",
		name = "Export now",
		description = "Turn on to write this character's data for the Beanscape launcher right away. It turns itself off after the export."
	)
	default boolean exportNow()
	{
		return false;
	}
}
