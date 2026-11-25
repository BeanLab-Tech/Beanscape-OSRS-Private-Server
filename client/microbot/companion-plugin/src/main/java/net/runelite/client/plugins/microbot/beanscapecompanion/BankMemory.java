package net.runelite.client.plugins.microbot.beanscapecompanion;

/**
 * The bank is only readable while it is open, so its value is remembered between exports.
 * The memory belongs to one character: switching accounts in the same client never carries
 * one character's bank into another's file.
 */
final class BankMemory
{
	private String owner;
	private long value;
	private String seenAt;

	void remember(String name, long bankValue, String at)
	{
		owner = name;
		value = bankValue;
		seenAt = at;
	}

	long valueFor(String name)
	{
		return name != null && name.equals(owner) ? value : 0L;
	}

	String seenAtFor(String name)
	{
		return name != null && name.equals(owner) ? seenAt : null;
	}

	void forget()
	{
		owner = null;
		value = 0L;
		seenAt = null;
	}
}
