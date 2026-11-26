package net.runelite.client.plugins.microbot.beanscapecompanion;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/** Reads the logged-in character. Must run on the client thread. Read-only. */
final class SnapshotReader
{
	private final Client client;
	private final ItemManager itemManager;
	private final BankMemory bank = new BankMemory();
	// getItemPrice(int) returns int on RuneLite 1.11 (live) and long on 1.13 (staging). Calling it
	// directly links against one return type and throws NoSuchMethodError on the other client.
	private final Method itemPrice;

	SnapshotReader(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.itemPrice = findItemPrice();
	}

	private static Method findItemPrice()
	{
		try
		{
			return ItemManager.class.getMethod("getItemPrice", int.class);
		}
		catch (NoSuchMethodException e)
		{
			return null;
		}
	}

	private long price(int id)
	{
		if (itemPrice == null)
		{
			return 0;
		}
		try
		{
			return ((Number) itemPrice.invoke(itemManager, id)).longValue();
		}
		catch (ReflectiveOperationException | ClassCastException e)
		{
			return 0;
		}
	}

	void rememberBank()
	{
		ItemContainer container = client.getItemContainer(InventoryID.BANK);
		Player player = client.getLocalPlayer();
		if (container != null && player != null && player.getName() != null)
		{
			bank.remember(player.getName(), value(container), Instant.now().toString());
		}
	}

	/** Called on logout so the next account starts with no remembered bank. */
	void forgetBank()
	{
		bank.forget();
	}

	Snapshot read()
	{
		Player player = client.getLocalPlayer();
		if (player == null || player.getName() == null || player.getName().isEmpty())
		{
			return null;
		}
		Snapshot s = new Snapshot();
		s.name = player.getName();
		s.capturedAt = Instant.now().toString();
		s.world = client.getWorld();
		s.accountType = AccountTypes.fromVarbit(client.getVarbitValue(VarbitID.IRONMAN));
		int credit = client.getVarpValue(VarPlayerID.ACCOUNT_CREDIT);
		s.membershipDays = credit >= 0 ? credit : null;
		s.members = credit > 0;
		s.combat = player.getCombatLevel();
		s.total = client.getTotalLevel();
		s.totalXp = client.getOverallExperience();
		s.questPoints = client.getVarpValue(VarPlayerID.QP);
		int playtime = client.getVarcIntValue(VarClientID.ACCOUNT_SUMMARY_PLAYTIME);
		s.playtimeMinutes = playtime > 0 ? playtime : null;

		for (Skill skill : Skill.values())
		{
			Snapshot.SkillValue v = new Snapshot.SkillValue();
			v.level = client.getRealSkillLevel(skill);
			v.xp = client.getSkillExperience(skill);
			v.boosted = client.getBoostedSkillLevel(skill);
			s.skills.put(skill.getName().toLowerCase(Locale.ROOT), v);
		}

		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		s.wealth.gp = coins(inv);
		s.wealth.inventory = value(inv);
		s.wealth.equipment = value(worn);
		if (client.getItemContainer(InventoryID.BANK) != null)
		{
			rememberBank();
		}
		s.wealth.bank = bank.valueFor(s.name);
		s.wealth.bankSeenAt = bank.seenAtFor(s.name);
		s.wealth.total = s.wealth.inventory + s.wealth.equipment + s.wealth.bank;

		WorldPoint wp = player.getWorldLocation();
		if (wp != null)
		{
			s.position = new Snapshot.Position();
			s.position.x = wp.getX();
			s.position.y = wp.getY();
			s.position.plane = wp.getPlane();
		}
		return s;
	}

	private static long coins(ItemContainer container)
	{
		if (container == null)
		{
			return 0;
		}
		long gp = 0;
		for (Item item : container.getItems())
		{
			if (item.getId() == ItemID.COINS)
			{
				gp += item.getQuantity();
			}
		}
		return gp;
	}

	private long value(ItemContainer container)
	{
		if (container == null)
		{
			return 0;
		}
		long total = 0;
		for (Item item : container.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			if (item.getId() == ItemID.COINS)
			{
				total += item.getQuantity();
				continue;
			}
			int id = itemManager.canonicalize(item.getId());
			total += price(id) * item.getQuantity();
		}
		return total;
	}
}
