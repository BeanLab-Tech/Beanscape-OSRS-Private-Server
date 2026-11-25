package net.runelite.client.plugins.microbot.beanscapecompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class BankMemoryTest
{
	@Test
	public void bankValueBelongsToItsCharacter()
	{
		BankMemory bank = new BankMemory();
		bank.remember("Oakheart", 5_000_000L, "2026-11-23T10:00:00Z");
		assertEquals(5_000_000L, bank.valueFor("Oakheart"));
		assertEquals(0L, bank.valueFor("Iron Bramble"));
		assertNull(bank.seenAtFor("Iron Bramble"));
	}

	@Test
	public void forgetClearsTheValue()
	{
		BankMemory bank = new BankMemory();
		bank.remember("Oakheart", 10L, "t");
		bank.forget();
		assertEquals(0L, bank.valueFor("Oakheart"));
	}
}
