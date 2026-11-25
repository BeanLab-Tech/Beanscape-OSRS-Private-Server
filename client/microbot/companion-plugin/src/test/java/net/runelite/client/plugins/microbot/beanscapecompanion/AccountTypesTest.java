package net.runelite.client.plugins.microbot.beanscapecompanion;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class AccountTypesTest
{
	@Test
	public void mapsVarbitValues()
	{
		assertEquals("NORMAL", AccountTypes.fromVarbit(0));
		assertEquals("IRONMAN", AccountTypes.fromVarbit(1));
		assertEquals("ULTIMATE_IRONMAN", AccountTypes.fromVarbit(2));
		assertEquals("HARDCORE_IRONMAN", AccountTypes.fromVarbit(3));
		assertEquals("GROUP_IRONMAN", AccountTypes.fromVarbit(4));
		assertEquals("UNKNOWN", AccountTypes.fromVarbit(99));
	}
}
