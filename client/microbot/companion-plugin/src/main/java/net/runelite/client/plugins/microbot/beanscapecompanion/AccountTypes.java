package net.runelite.client.plugins.microbot.beanscapecompanion;

/** Maps the account-type varbit (VarbitID.IRONMAN, 1777) to a stable name. */
final class AccountTypes
{
	private AccountTypes()
	{
	}

	static String fromVarbit(int value)
	{
		switch (value)
		{
			case 0:
				return "NORMAL";
			case 1:
				return "IRONMAN";
			case 2:
				return "ULTIMATE_IRONMAN";
			case 3:
				return "HARDCORE_IRONMAN";
			case 4:
				return "GROUP_IRONMAN";
			case 5:
				return "HARDCORE_GROUP_IRONMAN";
			case 6:
				return "UNRANKED_GROUP_IRONMAN";
			default:
				return "UNKNOWN";
		}
	}
}
