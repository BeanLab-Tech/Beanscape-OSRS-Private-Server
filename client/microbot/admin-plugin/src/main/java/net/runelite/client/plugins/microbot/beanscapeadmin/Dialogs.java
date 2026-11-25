package net.runelite.client.plugins.microbot.beanscapeadmin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Stone-themed replacements for JOptionPane, so confirmations and the one-time password look like
 * the rest of the console. Every dialog is modal, closes on Escape, and answers on Enter.
 */
final class Dialogs
{
	private static final int WIDTH = 420;

	private Dialogs()
	{
	}

	/** Yes or no. Returns true only when the admin picks the confirm button. */
	static boolean confirm(Window owner, String title, String message, String confirmLabel, boolean danger)
	{
		boolean[] result = {false};
		JDialog dialog = shell(owner, title);
		Ui.Button yes = new Ui.Button(confirmLabel, danger ? Ui.Kind.DANGER : Ui.Kind.PRIMARY, Ui.Glyph.NONE);
		Ui.Button no = new Ui.Button("Cancel", Ui.Kind.STONE, Ui.Glyph.NONE);
		yes.addActionListener(event ->
		{
			result[0] = true;
			dialog.dispose();
		});
		no.addActionListener(event -> dialog.dispose());
		show(dialog, title, danger ? Theme.RED : Theme.GOLD, body(message), buttons(no, yes), yes);
		return result[0];
	}

	/** Makes the admin type a name before a destructive action goes ahead. */
	static boolean typed(Window owner, String title, String message, String expected, String confirmLabel)
	{
		boolean[] result = {false};
		JDialog dialog = shell(owner, title);
		JTextField field = new JTextField();
		Ui.field(field);
		field.setPreferredSize(new Dimension(WIDTH - 56, 34));
		Ui.Button yes = new Ui.Button(confirmLabel, Ui.Kind.DANGER, Ui.Glyph.TRASH);
		Ui.Button no = new Ui.Button("Cancel", Ui.Kind.STONE, Ui.Glyph.NONE);
		yes.setEnabled(false);
		field.getDocument().addDocumentListener(new DocumentListener()
		{
			private void check()
			{
				yes.setEnabled(field.getText().trim().equalsIgnoreCase(expected));
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				check();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				check();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				check();
			}
		});
		yes.addActionListener(event ->
		{
			result[0] = true;
			dialog.dispose();
		});
		no.addActionListener(event -> dialog.dispose());
		JPanel content = Ui.clear(new BorderLayout(0, 12));
		content.add(body(message), BorderLayout.NORTH);
		content.add(field, BorderLayout.CENTER);
		show(dialog, title, Theme.RED, content, buttons(no, yes), no);
		return result[0];
	}

	/** Shows a generated password once, with a copy button. */
	static void secret(Window owner, String title, String message, String secret)
	{
		JDialog dialog = shell(owner, title);
		JLabel value = new JLabel(secret, JLabel.CENTER)
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				Theme.well(g, 0, 0, getWidth(), getHeight(), 8);
				g.dispose();
				super.paintComponent(graphics);
			}
		};
		value.setFont(Theme.uiBold(20f).deriveFont(java.awt.Font.BOLD));
		value.setForeground(Theme.YELLOW);
		value.setPreferredSize(new Dimension(WIDTH - 56, 54));
		JLabel status = Ui.label(" ", Theme.uiBold(12f), Theme.GREEN);
		Ui.Button copy = new Ui.Button("Copy password", Ui.Kind.PRIMARY, Ui.Glyph.SAVE);
		Ui.Button done = new Ui.Button("Done", Ui.Kind.STONE, Ui.Glyph.NONE);
		copy.addActionListener(event ->
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(secret), null);
			status.setText("Copied to the clipboard.");
		});
		done.addActionListener(event -> dialog.dispose());
		JPanel content = Ui.clear(new BorderLayout(0, 10));
		content.add(body(message), BorderLayout.NORTH);
		content.add(value, BorderLayout.CENTER);
		content.add(status, BorderLayout.SOUTH);
		show(dialog, title, Theme.GOLD, content, buttons(done, copy), copy);
	}

	// ------------------------------------------------------------------ plumbing

	private static JComponent body(String message)
	{
		return Ui.note(message, WIDTH - 60);
	}

	private static JPanel buttons(Ui.Button... buttons)
	{
		JPanel row = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		for (Ui.Button button : buttons)
		{
			row.add(button);
		}
		return row;
	}

	private static JDialog shell(Window owner, String title)
	{
		JDialog dialog = new JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setUndecorated(true);
		dialog.setResizable(false);
		return dialog;
	}

	private static void show(JDialog dialog, String title, Color accent, JComponent content, JPanel buttons, Ui.Button defaultButton)
	{
		JPanel card = new JPanel(new BorderLayout(0, 14))
		{
			@Override
			protected void paintComponent(Graphics graphics)
			{
				Graphics2D g = (Graphics2D) graphics.create();
				Theme.smooth(g);
				g.setColor(Theme.BG);
				g.fillRect(0, 0, getWidth(), getHeight());
				Theme.stone(g, 0, 0, getWidth(), getHeight(), 10, Theme.STONE_2, Theme.STONE_1);
				g.setPaint(new GradientPaint(0, 0, accent, getWidth() * 0.8f, 0, Theme.alpha(accent, 0)));
				g.fillRect(8, 1, getWidth() - 16, 2);
				g.dispose();
			}
		};
		card.setOpaque(false);
		card.setBorder(BorderFactory.createEmptyBorder(22, 28, 20, 28));
		card.add(Ui.label(title, Theme.display(18f), accent == Theme.RED ? new Color(0xFF8B7D) : Theme.GOLD_HI), BorderLayout.NORTH);
		JPanel middle = Ui.clear(new BorderLayout());
		middle.add(content, BorderLayout.CENTER);
		card.add(middle, BorderLayout.CENTER);
		card.add(buttons, BorderLayout.SOUTH);
		dialog.setContentPane(card);
		dialog.getRootPane().setBorder(BorderFactory.createLineBorder(Theme.EDGE_DARK));
		dialog.getRootPane().setDefaultButton(defaultButton);
		dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
		dialog.getRootPane().getActionMap().put("close", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				dialog.dispose();
			}
		});
		dialog.pack();
		dialog.setSize(new Dimension(Math.max(WIDTH, dialog.getWidth()), dialog.getHeight()));
		dialog.setLocationRelativeTo(dialog.getOwner());
		dialog.setVisible(true);
	}
}
