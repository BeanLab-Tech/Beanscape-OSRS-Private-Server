package net.runelite.client.plugins.microbot.beanscapecompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ChatEventsTest
{
	@Test
	public void quest()
	{
		GameEvent e = ChatEvents.parse("Congratulations, you've completed a quest: <col=0e7912>Cook's Assistant</col>");
		assertEquals("quest", e.type);
		assertEquals("Cook's Assistant", e.title);
	}

	@Test
	public void diaryTaskAndTier()
	{
		GameEvent task = ChatEvents.parse("Well done! You have completed an easy task in the Varrock area. Your Achievement Diary has been updated.");
		assertEquals("diary", task.type);
		assertEquals("Varrock easy task", task.title);
		GameEvent tier = ChatEvents.parse("Congratulations! You have completed all of the hard tasks in the Lumbridge & Draynor area. Speak to Hatius Cosaintus to claim your reward.");
		assertEquals("diary", tier.type);
		assertEquals("Lumbridge & Draynor hard diary", tier.title);
	}

	@Test
	public void combatTask()
	{
		GameEvent e = ChatEvents.parse("Congratulations, you've completed an easy combat task: <col=06600c>Noxious Foe</col> (1 point).");
		assertEquals("combat", e.type);
		assertEquals("Noxious Foe", e.title);
		assertEquals("Easy combat task", e.detail);
	}

	@Test
	public void collectionLog()
	{
		GameEvent e = ChatEvents.parse("New item added to your collection log: <col=ef1020>Bronze pickaxe</col>");
		assertEquals("clog", e.type);
		assertEquals("Bronze pickaxe", e.title);
	}

	@Test
	public void pet()
	{
		assertEquals("pet", ChatEvents.parse("You have a funny feeling like you're being followed.").type);
		assertEquals("pet", ChatEvents.parse("You feel something weird sneaking into your backpack.").type);
	}

	@Test
	public void clueAndDrop()
	{
		GameEvent clue = ChatEvents.parse("You have completed <col=ef1020>12</col> medium Treasure Trails.");
		assertEquals("clue", clue.type);
		assertEquals("Medium clue #12", clue.title);
		GameEvent drop = ChatEvents.parse("<col=ef1020>Valuable drop: Dragon med helm (59,234 coins)</col>");
		assertEquals("drop", drop.type);
		assertEquals("Dragon med helm", drop.title);
		assertEquals("59,234 coins", drop.detail);
	}

	@Test
	public void ignoresOtherMessages()
	{
		assertNull(ChatEvents.parse("You swing your axe at the tree."));
		assertNull(ChatEvents.parse(null));
	}

	@Test
	public void levelTrackerOnlyFiresOnRealLevelUps()
	{
		LevelTracker t = new LevelTracker();
		assertFalse(t.levelledUp("woodcutting", 40));
		assertFalse(t.levelledUp("woodcutting", 40));
		assertTrue(t.levelledUp("woodcutting", 41));
		t.reset();
		assertFalse(t.levelledUp("woodcutting", 70));
	}
}
