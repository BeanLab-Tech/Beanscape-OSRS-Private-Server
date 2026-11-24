package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** Create, search and manage accounts. Replaces the web admin's account screens. */
final class AccountsPage extends Page
{
	private static final class Account
	{
		final int id;
		final String username;
		final String modlevel;
		final boolean online;

		Account(JsonObject json)
		{
			id = json.get("id").getAsInt();
			username = Session.str(json, "username");
			modlevel = Session.str(json, "modlevel");
			online = json.has("online") && json.get("online").getAsBoolean();
		}
	}

	private final JTextField search = new JTextField();
	private final JPanel results = Ui.clear(null);
	private final JTextField newName = new JTextField();
	private final Ui.Segmented newLevel = new Ui.Segmented("Player", "Moderator", "Admin");
	private final Ui.Segmented changeLevel = new Ui.Segmented("Player", "Moderator", "Admin", "Owner");
	private final JLabel chosen = Ui.label("No account selected", Theme.display(16f), Theme.GOLD_HI);
	private final JLabel chosenNote = Ui.label(" ", Theme.ui(12.5f), Theme.TEXT_2);
	private final Ui.Button applyLevel = new Ui.Button("Apply", Ui.Kind.STONE, Ui.Glyph.CHECK);
	private final Ui.Button reset = new Ui.Button("Reset password", Ui.Kind.STONE, Ui.Glyph.RESET);
	private final Ui.Button delete = new Ui.Button("Delete", Ui.Kind.DANGER, Ui.Glyph.TRASH);
	private final Ui.Button create = new Ui.Button("Create account", Ui.Kind.PRIMARY, Ui.Glyph.SAVE);
	private final List<Account> found = new ArrayList<>();
	private Account picked;
	private boolean showing;
	private boolean loading;
	private String lastQuery = "";
	private String signature = "";

	AccountsPage(Session session, AdminWindow window)
	{
		super("Accounts", "Create accounts, change mod levels, reset passwords. Passwords are shown once.", "admin", session, window);
	}

	@Override
	JComponent build()
	{
		JPanel page = shell();
		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		Ui.field(search);
		Ui.field(newName);
		search.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER)
				{
					runSearch();
				}
			}
		});
		Ui.Button go = new Ui.Button("Search", Ui.Kind.STONE, Ui.Glyph.NONE);
		go.addActionListener(event -> runSearch());

		Ui.Section find = new Ui.Section("Find an account", "Search by login name. Leave empty to list the newest.", new BorderLayout(0, 8));
		JPanel bar = Ui.clear(new BorderLayout(8, 0));
		bar.add(search, BorderLayout.CENTER);
		bar.add(go, BorderLayout.EAST);
		find.body().add(bar, BorderLayout.NORTH);
		find.body().add(Ui.scroll(results), BorderLayout.CENTER);
		find.setPreferredSize(new Dimension(360, 100));

		Ui.Section createBox = new Ui.Section("New account", "A 16-character password is generated for you.", new BorderLayout(0, 8));
		JPanel createForm = Ui.clear(new BorderLayout(0, 10));
		createForm.add(caption("LOGIN NAME  (2 to 12 letters, numbers, underscores)", newName), BorderLayout.NORTH);
		createForm.add(caption("STARTING LEVEL", newLevel), BorderLayout.CENTER);
		JPanel createButtons = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		createButtons.add(create);
		createForm.add(createButtons, BorderLayout.SOUTH);
		createBox.body().add(createForm, BorderLayout.NORTH);
		create.addActionListener(event -> createAccount());

		Ui.Section manage = new Ui.Section("Selected account", "Changes apply right away, even if they are online.", new BorderLayout(0, 8));
		JPanel manageForm = Ui.clear(new BorderLayout(0, 10));
		JPanel head = Ui.clear(new BorderLayout(0, 2));
		head.add(chosen, BorderLayout.NORTH);
		head.add(chosenNote, BorderLayout.CENTER);
		JPanel headRow = Ui.clear(new BorderLayout(10, 0));
		headRow.add(head, BorderLayout.CENTER);
		JPanel deleteHolder = Ui.clear(new java.awt.GridBagLayout());
		deleteHolder.add(delete);
		headRow.add(deleteHolder, BorderLayout.EAST);
		manageForm.add(headRow, BorderLayout.NORTH);
		manageForm.add(caption("MOD LEVEL", changeLevel), BorderLayout.CENTER);
		JPanel manageButtons = Ui.clear(new FlowLayout(FlowLayout.LEFT, 8, 0));
		manageButtons.add(applyLevel);
		manageButtons.add(reset);
		manageForm.add(manageButtons, BorderLayout.SOUTH);
		manage.body().add(manageForm, BorderLayout.NORTH);
		applyLevel.addActionListener(event -> changeModLevel());
		reset.addActionListener(event -> resetPassword());
		delete.addActionListener(event -> deleteAccount());

		JPanel right = Page.stack(createBox, manage);
		JPanel columns = Ui.clear(new BorderLayout(14, 0));
		columns.add(find, BorderLayout.WEST);
		columns.add(right, BorderLayout.CENTER);
		page.add(columns, BorderLayout.CENTER);
		showResults();
		syncButtons();
		return page;
	}

	/** Small gold caption over a control, so forms read top to bottom without squeezing. */
	private static JPanel caption(String text, JComponent control)
	{
		JPanel box = Ui.clear(new BorderLayout(0, 5));
		box.add(Ui.label(text, Theme.eyebrow(10f), Theme.TEXT_3), BorderLayout.NORTH);
		JPanel holder = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
		if (control instanceof JTextField)
		{
			control.setPreferredSize(new Dimension(300, 32));
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
				nextSignature.append(account.id).append(account.username).append(account.modlevel).append(account.online).append(';');
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
		if (same == null)
		{
			chosen.setText("No account selected");
			chosenNote.setText(" ");
		}
		else
		{
			chosenNote.setText(same.online ? "Online now. Changes reach them live." : "Offline. Changes apply on next login.");
		}
		syncButtons();
	}

	private void showResults()
	{
		results.removeAll();
		if (found.isEmpty())
		{
			results.add(hint("Accounts show up here.", Theme.TEXT_3));
		}
		for (Account account : found)
		{
			Ui.Tile tile = new Ui.Tile(false, () -> Ui.avatar(account.username), Ui.Glyph.NONE, account.username, account.online ? "Online now" : "Offline");
			tile.setBadge(Session.rank(account.modlevel) > 0 ? account.modlevel : null);
			tile.setSelected(picked != null && picked.id == account.id);
			tile.onPick(() ->
			{
				picked = account;
				chosen.setText(account.username);
				chosenNote.setText(account.online ? "Online now. Changes reach them live." : "Offline. Changes apply on next login.");
				changeLevel.setSelected(Math.min(3, Session.rank(account.modlevel)));
				syncButtons();
				showResults();
			});
			tile.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
			tile.setAlignmentX(0f);
			results.add(tile);
			results.add(Box.createVerticalStrut(6));
		}
		results.revalidate();
		results.repaint();
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
			runSearch();
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
			runSearch();
		}, error -> window.flash(error, Theme.RED));
	}

	private void resetPassword()
	{
		if (picked == null || !confirm("Reset the password for " + picked.username + "? Their old password stops working."))
		{
			return;
		}
		final String name = picked.username;
		session.call(() -> session.api().post("/v1/accounts/" + picked.id + "/password", null), json ->
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
		String typed = JOptionPane.showInputDialog(window.owner(),
			"This permanently deletes the account and its character.\nType " + picked.username + " to confirm.",
			"Delete account", JOptionPane.WARNING_MESSAGE);
		if (typed == null || !typed.trim().equalsIgnoreCase(picked.username))
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
			chosen.setText("No account selected");
			chosenNote.setText(" ");
			syncButtons();
			runSearch();
		}, error -> window.flash(error, Theme.RED));
	}

	/** The generated password is only ever shown here, once. */
	private void showPassword(String name, String password)
	{
		if (password.isEmpty())
		{
			return;
		}
		Object[] options = {"Copy and close", "Close"};
		int choice = JOptionPane.showOptionDialog(window.owner(),
			"Password for " + name + ":\n\n    " + password + "\n\nThis is the only time it is shown.",
			"New password", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
		if (choice == 0)
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(password), null);
		}
	}
}
