package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Create, search and manage accounts. Replaces the web admin's account screens. */
final class AccountsPage extends Page
{
	private static final class Account
	{
		final int id;
		final String username;
		final String modlevel;
		final boolean online;
		final String avatar;
		final String discord;

		Account(JsonObject json)
		{
			id = json.get("id").getAsInt();
			username = Session.str(json, "username");
			modlevel = Session.str(json, "modlevel");
			online = json.has("online") && json.get("online").getAsBoolean();
			avatar = Session.str(json, "avatar");
			discord = Session.str(json, "discord");
		}
	}

	private final JTextField search = new Widgets.SearchField("Search accounts");
	private final JPanel results = Ui.clear(null);
	private final JLabel summary = Ui.label(" ", Theme.ui(12f), Theme.TEXT_3);
	private final JTextField newName = new JTextField();
	private final Ui.Segmented newLevel = new Ui.Segmented("Player", "Moderator", "Admin");
	private final Ui.Segmented changeLevel = new Ui.Segmented("Player", "Moderator", "Admin", "Owner");
	private final JLabel chosen = Ui.label("No account selected", Theme.display(20f), Theme.GOLD_HI);
	private final JLabel chosenNote = Ui.label(" ", Theme.ui(13f), Theme.TEXT_2);
	private final JLabel chosenDiscord = Ui.label(" ", Theme.ui(12f), Theme.TEXT_3);
	private final Widgets.AvatarView chosenAvatar = new Widgets.AvatarView(64);
	private final Ui.Button applyLevel = new Ui.Button("Apply level", Ui.Kind.STONE, Ui.Glyph.CHECK);
	private final Ui.Button reset = new Ui.Button("Reset password", Ui.Kind.STONE, Ui.Glyph.RESET);
	private final Ui.Button delete = new Ui.Button("Delete account", Ui.Kind.DANGER, Ui.Glyph.TRASH);
	private final Ui.Button create = new Ui.Button("Create account", Ui.Kind.PRIMARY, Ui.Glyph.SAVE);
	private final List<Account> found = new ArrayList<>();
	private final Timer debounce;
	private final java.awt.CardLayout manageCards = new java.awt.CardLayout();
	private Account picked;
	private boolean showing;
	private boolean loading;
	private String lastQuery = "";
	private String signature = "";

	AccountsPage(Session session, AdminWindow window)
	{
		super("Accounts", "Find accounts, change mod levels and reset passwords.", "admin", session, window);
		debounce = new Timer(300, event -> runSearch());
		debounce.setRepeats(false);
	}

	@Override
	JComponent build()
	{
		JPanel page = shell();
		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		Ui.field(newName);
		search.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER)
				{
					debounce.stop();
					runSearch();
				}
			}
		});
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				debounce.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				debounce.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				debounce.restart();
			}
		});

		Ui.Section find = new Ui.Section("Accounts", null, new BorderLayout(0, 8));
		JPanel top = Ui.clear(new BorderLayout(0, 6));
		top.add(search, BorderLayout.NORTH);
		top.add(summary, BorderLayout.SOUTH);
		find.body().add(top, BorderLayout.NORTH);
		find.body().add(Ui.scroll(results), BorderLayout.CENTER);

		Ui.Section manage = new Ui.Section("Selected account", "Changes apply right away, even while they are online.", new BorderLayout());
		manage.body().setLayout(manageCards);
		manageHost = manage.body();
		JPanel head = Ui.clear(new BorderLayout(14, 0));
		head.add(chosenAvatar, BorderLayout.WEST);
		JPanel words = Ui.clear(new BorderLayout(0, 2));
		words.add(chosen, BorderLayout.NORTH);
		JPanel sub = Ui.clear(new BorderLayout(0, 1));
		sub.add(chosenNote, BorderLayout.NORTH);
		sub.add(chosenDiscord, BorderLayout.CENTER);
		words.add(sub, BorderLayout.CENTER);
		head.add(words, BorderLayout.CENTER);

		JPanel levelButtons = Ui.clear(new FlowLayout(FlowLayout.LEFT, 8, 0));
		levelButtons.add(applyLevel);
		levelButtons.add(reset);
		applyLevel.addActionListener(event -> changeModLevel());
		reset.addActionListener(event -> resetPassword());
		delete.addActionListener(event -> deleteAccount());

		JPanel dangerRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		dangerRow.add(delete);
		JPanel manageBody = Widgets.column(16,
			head,
			caption("MOD LEVEL", changeLevel),
			levelButtons,
			caption("DANGER ZONE", dangerRow));
		manage.body().add(Widgets.empty("Pick an account", "Choose one from the list to change its level or reset its password."), "empty");
		manage.body().add(manageBody, "detail");

		Ui.Section createBox = new Ui.Section("New account", "A 16-character password is generated and shown once.", new BorderLayout());
		JPanel createButtons = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		createButtons.add(create);
		createBox.body().add(Widgets.column(12,
			caption("LOGIN NAME  ·  2 to 12 letters, numbers or underscores", newName),
			caption("STARTING LEVEL", newLevel),
			createButtons), BorderLayout.NORTH);
		create.addActionListener(event -> createAccount());

		JComponent right = Ui.scroll(Widgets.top(Widgets.column(14, manage, createBox)));
		page.add(new Widgets.Split(find, right, 0.4, 300, 440, 600), BorderLayout.CENTER);
		showResults();
		syncButtons();
		return page;
	}

	/** Small caption over a control, so forms read top to bottom without squeezing. */
	private static JPanel caption(String text, JComponent control)
	{
		JPanel box = Ui.clear(new BorderLayout(0, 6));
		box.add(Ui.label(text, Theme.eyebrow(10f), Theme.TEXT_4), BorderLayout.NORTH);
		JPanel holder = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		if (control instanceof JTextField)
		{
			control.setPreferredSize(new Dimension(300, 34));
		}
		holder.add(control);
		box.add(holder, BorderLayout.CENTER);
		return box;
	}

	@Override
	void setShowing(boolean value)
	{
		showing = value;
		if (value)
		{
			load(found.isEmpty());
		}
	}

	/** Runs after every poll: keeps the list and its online flags current while the page is open. */
	@Override
	void onState()
	{
		syncButtons();
		if (showing && session.has("admin"))
		{
			load(false);
		}
	}

	private void syncButtons()
	{
		boolean admin = session.has("admin");
		boolean owner = session.has("owner");
		create.setEnabled(admin);
		applyLevel.setEnabled(admin && picked != null);
		reset.setEnabled(admin && picked != null);
		delete.setEnabled(owner && picked != null);
	}

	private String levelName(Ui.Segmented segmented)
	{
		return Session.LEVELS[Math.max(0, Math.min(Session.LEVELS.length - 1, segmented.getSelected()))];
	}

	private void runSearch()
	{
		lastQuery = search.getText().trim();
		load(true);
	}

	/**
	 * Fetches the list for the last search. A background refresh stays quiet on failure and only
	 * redraws when something changed, so the list doesn't flicker or lose the selection.
	 */
	private void load(boolean loud)
	{
		if (loading)
		{
			return;
		}
		loading = true;
		final String query = lastQuery;
		session.call(() -> session.api().get("/v1/accounts?q=" + ApiClient.enc(query)), json ->
		{
			loading = false;
			List<Account> next = Session.map(Session.array(json, "accounts"), Account::new);
			StringBuilder nextSignature = new StringBuilder();
			for (Account account : next)
			{
				nextSignature.append(account.id).append(account.username).append(account.modlevel).append(account.online).append(account.avatar).append(';');
			}
			if (nextSignature.toString().equals(signature) && !loud)
			{
				return;
			}
			signature = nextSignature.toString();
			found.clear();
			found.addAll(next);
			refreshPicked();
			showResults();
		}, error ->
		{
			loading = false;
			if (loud)
			{
				window.flash(error, Theme.RED);
			}
		});
	}

	/** Points the selection at the refreshed copy of the same account, or clears it if it is gone. */
	private void refreshPicked()
	{
		if (picked == null)
		{
			return;
		}
		Account same = null;
		for (Account account : found)
		{
			if (account.id == picked.id)
			{
				same = account;
			}
		}
		picked = same;
		describePicked();
		syncButtons();
	}

	private void describePicked()
	{
		if (picked == null)
		{
			chosen.setText(" ");
			chosenNote.setText(" ");
			chosenDiscord.setText(" ");
			chosenAvatar.set(null, null);
			manageCards.show(manageHost(), "empty");
			return;
		}
		manageCards.show(manageHost(), "detail");
		chosen.setText(picked.username);
		chosenNote.setText(picked.online ? "Online now. Changes reach them live." : "Offline. Changes apply on next login.");
		chosenDiscord.setText(picked.discord.isEmpty() ? "No Discord account linked" : "Discord: " + picked.discord);
		chosenAvatar.set(session.avatars().supplier(picked.avatar, picked.username, chosenAvatar), picked.online);
	}

	private void showResults()
	{
		results.removeAll();
		if (found.isEmpty())
		{
			results.add(Widgets.empty(lastQuery.isEmpty() ? "No accounts yet" : "No match",
				lastQuery.isEmpty() ? "Accounts appear here once someone registers." : "No account name contains “" + lastQuery + "”."));
		}
		int online = 0;
		for (Account account : found)
		{
			online += account.online ? 1 : 0;
			Ui.Tile tile = new Ui.Tile(false, session.avatars().supplier(account.avatar, account.username, results), Ui.Glyph.NONE,
				account.username, account.discord.isEmpty() ? (account.online ? "Online now" : "Offline") : account.discord + "  ·  " + (account.online ? "online" : "offline"));
			tile.round(account.online);
			tile.setBadge(Session.rank(account.modlevel) > 0 ? account.modlevel : null);
			tile.setSelected(picked != null && picked.id == account.id);
			tile.onPick(() ->
			{
				picked = account;
				describePicked();
				changeLevel.setSelected(Math.min(3, Session.rank(account.modlevel)));
				syncButtons();
				showResults();
			});
			tile.setPreferredSize(new Dimension(220, 58));
			tile.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
			tile.setAlignmentX(0f);
			results.add(tile);
			results.add(Box.createVerticalStrut(6));
		}
		summary.setText(found.isEmpty() ? " " : found.size() + (found.size() == 1 ? " account" : " accounts") + "  ·  " + online + " online");
		describePicked();
		results.revalidate();
		results.repaint();
	}

	private JPanel manageHost;

	private JPanel manageHost()
	{
		return manageHost;
	}

	private void createAccount()
	{
		String name = newName.getText().trim().toLowerCase();
		if (!name.matches("[a-z0-9_]{2,12}"))
		{
			window.flash("Names are 2 to 12 letters, numbers or underscores.", Theme.ORANGE);
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("username", name);
		body.addProperty("modlevel", levelName(newLevel));
		session.call(() -> session.api().post("/v1/accounts", body), json ->
		{
			newName.setText("");
			window.flash("Created " + name + ".", Theme.GREEN);
			showPassword(name, Session.str(json, "password"));
			load(true);
		}, error -> window.flash(error, Theme.RED));
	}

	private void changeModLevel()
	{
		if (picked == null)
		{
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("modlevel", levelName(changeLevel));
		session.act("/v1/accounts/" + picked.id + "/modlevel", body, message ->
		{
			window.flash(message, Theme.GREEN);
			load(true);
		}, error -> window.flash(error, Theme.RED));
	}

	private void resetPassword()
	{
		if (picked == null || !confirm("Reset password", "Reset the password for " + picked.username + "? Their old password stops working straight away.", "Reset password", true))
		{
			return;
		}
		final String name = picked.username;
		final int id = picked.id;
		session.call(() -> session.api().post("/v1/accounts/" + id + "/password", null), json ->
		{
			window.flash("Password reset for " + name + ".", Theme.GREEN);
			showPassword(name, Session.str(json, "password"));
		}, error -> window.flash(error, Theme.RED));
	}

	private void deleteAccount()
	{
		if (picked == null)
		{
			return;
		}
		if (!Dialogs.typed(window.owner(), "Delete account",
			"This permanently deletes the account and its character. Type " + picked.username + " to confirm.",
			picked.username, "Delete forever"))
		{
			window.flash("Delete cancelled.", Theme.TEXT_2);
			return;
		}
		final int id = picked.id;
		final String name = picked.username;
		session.call(() -> session.api().delete("/v1/accounts/" + id), json ->
		{
			window.flash("Deleted " + name + ".", Theme.GREEN);
			picked = null;
			describePicked();
			syncButtons();
			load(true);
		}, error -> window.flash(error, Theme.RED));
	}

	/** The generated password is only ever shown here, once. */
	private void showPassword(String name, String password)
	{
		if (password.isEmpty())
		{
			return;
		}
		Dialogs.secret(window.owner(), "Password for " + name,
			"Give this to the player now. It is the only time it is shown.", password);
	}
}
