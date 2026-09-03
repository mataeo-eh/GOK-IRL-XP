package com.gokirlbankedxp;

import com.gokirlbankedxp.service.XpMultiplierManager;
import com.gokirlbankedxp.ui.IrlXpUi;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Skill;
import net.runelite.client.util.QuantityFormatter;

/**
 * Focused banked-XP view embedded inside the unified IRL XP sidebar.
 *
 * <p>Two forms share this tab, switched by a segmented toggle: <b>Add XP</b>
 * deposits a chunk into any skill, and <b>Remove XP</b> takes a deposit back out
 * of a skill that has one. They are deliberately separate rather than one form
 * that accepts a sign, because "add" must never be able to reduce a balance:
 * typing {@code -500} into the deposit field is rejected, exactly as before.
 * Removal instead offers only the skills that actually hold XP and caps the
 * amount at what is there, so a balance can never go negative from either side.</p>
 */
@Singleton
class GokIrlBankedXpPanel extends JPanel
{
    private static final Insets FIELD_INSETS = new Insets(4, 0, 4, 8);

    private static final String ADD_CARD = "ADD";
    private static final String REMOVE_CARD = "REMOVE";

    private final GokIrlBankedXpPlugin plugin;
    private final XpMultiplierManager multiplierManager;

    private final JComboBox<Skill> skillSelector;
    private final JTextField xpField;
    /**
     * Says what the typed amount will actually become once the skill's level
     * multiplier is applied. Two rows because a multiplier of exactly zero needs
     * a sentence of explanation, not a number.
     */
    private final JTextArea depositPreview = IrlXpUi.wrappingNote(" ", 2);

    private final JComboBox<Skill> removeSkillSelector = new JComboBox<>();
    private final JTextField removeXpField = new JTextField();
    private final JLabel removeBalanceLabel = IrlXpUi.mutedLabel("Nothing banked yet.");
    private final JButton removeButton = new JButton("REMOVE XP");

    private final CardLayout formLayout = new CardLayout();
    private final JPanel formCards = new JPanel(formLayout);
    private final JButton addModeButton = new JButton("ADD XP");
    private final JButton removeModeButton = new JButton("REMOVE XP");

    private final DefaultListModel<String> skillListModel = new DefaultListModel<>();
    private final JLabel totalXpLabel = new JLabel("0 XP");

    @Inject
    GokIrlBankedXpPanel(GokIrlBankedXpPlugin plugin, XpMultiplierManager multiplierManager)
    {
        this.plugin = plugin;
        this.multiplierManager = multiplierManager;

        setLayout(new BorderLayout(0, 10));
        setBackground(IrlXpUi.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        JPanel inputPanel = IrlXpUi.card(new BorderLayout(0, 10));
        inputPanel.add(buildModeToggle(), BorderLayout.NORTH);

        JPanel addForm = new JPanel(new GridBagLayout());
        addForm.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = FIELD_INSETS;
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridwidth = GridBagConstraints.REMAINDER;

        JLabel formTitle = IrlXpUi.sectionTitle("Bank a chunk");
        addForm.add(formTitle, gc);

        gc.gridy = 1;
        JLabel formHint = IrlXpUi.mutedLabel("Add XP earned offline.");
        addForm.add(formHint, gc);

        gc.gridy = 2;
        JLabel skillLabel = new JLabel("Skill");
        skillLabel.setForeground(IrlXpUi.MUTED_TEXT);
        addForm.add(skillLabel, gc);

        gc.gridy = 3;
        skillSelector = new JComboBox<>(plugin.getTrackableSkills());
        skillSelector.setPreferredSize(new Dimension(1, 32));
        skillSelector.setRenderer(IrlXpUi.skillNameRenderer());
        // A different skill can be on a different multiplier tier, so the
        // preview has to follow the selection, not just the typed amount.
        skillSelector.addActionListener(e -> refreshDepositPreview());
        IrlXpUi.styleField(skillSelector);
        addForm.add(skillSelector, gc);

        gc.gridy = 4;
        JLabel xpLabel = new JLabel("XP amount");
        xpLabel.setForeground(IrlXpUi.MUTED_TEXT);
        addForm.add(xpLabel, gc);

        gc.gridy = 5;
        xpField = new JTextField();
        xpField.setToolTipText("Enter a positive whole-number XP amount");
        xpField.setEditable(true);
        xpField.setFocusable(true);
        xpField.setCaretColor(IrlXpUi.TEXT);
        xpField.setPreferredSize(new Dimension(1, 36));
        IrlXpUi.styleField(xpField);
        xpField.addActionListener(e -> onAddXp());
        xpField.addMouseListener(new FocusOnClick(xpField));
        // Live rather than on submit: a multiplier that changes what you banked
        // is only fair if you can see it before you press the button.
        xpField.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent event)
            {
                refreshDepositPreview();
            }

            @Override
            public void removeUpdate(DocumentEvent event)
            {
                refreshDepositPreview();
            }

            @Override
            public void changedUpdate(DocumentEvent event)
            {
                refreshDepositPreview();
            }
        });
        addForm.add(xpField, gc);

        gc.gridy = 6;
        addForm.add(depositPreview, gc);

        gc.gridy = 7;
        gc.insets = new Insets(8, 0, 0, 0);
        JButton addButton = new JButton("BANK XP");
        IrlXpUi.stylePrimaryButton(addButton);
        addButton.addActionListener(e -> onAddXp());
        addForm.add(addButton, gc);

        formCards.setOpaque(false);
        formCards.add(addForm, ADD_CARD);
        formCards.add(buildRemoveForm(), REMOVE_CARD);
        inputPanel.add(formCards, BorderLayout.CENTER);

        add(inputPanel, BorderLayout.NORTH);

        JPanel balanceCard = IrlXpUi.card(new BorderLayout(0, 8));
        JPanel balanceHeader = new JPanel(new BorderLayout());
        balanceHeader.setOpaque(false);
        balanceHeader.add(IrlXpUi.sectionTitle("Current balance"), BorderLayout.WEST);
        totalXpLabel.setForeground(IrlXpUi.ACCENT);
        totalXpLabel.setFont(totalXpLabel.getFont().deriveFont(Font.BOLD, 15f));
        balanceHeader.add(totalXpLabel, BorderLayout.EAST);
        balanceCard.add(balanceHeader, BorderLayout.NORTH);

        JList<String> skillList = new JList<>(skillListModel);
        skillList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skillList.setVisibleRowCount(10);
        skillList.setCellRenderer(new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
            {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
                label.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
                return label;
            }
        });
        JScrollPane scrollPane = new JScrollPane(skillList);
        IrlXpUi.styleList(skillList, scrollPane);
        balanceCard.add(scrollPane, BorderLayout.CENTER);
        add(balanceCard, BorderLayout.CENTER);

        add(buildSettingsPointer(), BorderLayout.SOUTH);

        showMode(ADD_CARD);
        refreshDepositPreview();
    }

    /**
     * A permanent signpost to the settings that are not in this sidebar.
     *
     * <p>RuneLite owns the settings panel, and a plugin cannot open it or move
     * its items into a sidebar tab. The warnings — the LOW marker, the chat
     * message, and the red screen flash as a skill runs dry — therefore live
     * somewhere the sidebar cannot show, and testers reliably failed to find
     * them. Naming the exact route, and the exact section headings to look for,
     * is the whole of what this note is for.</p>
     */
    private JPanel buildSettingsPointer()
    {
        JPanel card = IrlXpUi.card(new BorderLayout(0, 4));
        card.add(IrlXpUi.mutedLabel("Warnings & screen flash"), BorderLayout.NORTH);
        card.add(IrlXpUi.wrappingNote(
            "Set how and when you are warned that a skill is running out in RuneLite's own settings: "
                + "Configuration (the wrench) > IRL XP. Look for \"Low banked XP warning (chat)\" and "
                + "\"Almost-out warning (red screen flash)\".", 4), BorderLayout.CENTER);
        return card;
    }

    /**
     * Restates the typed deposit as the amount that will actually be banked.
     *
     * <p>Silent about the multiplier when there is none in force, so a user who
     * never touched the feature never sees a line about it. When one is in force
     * this is the only warning that "500" is about to become something else, and
     * it deliberately shows the same figure the plugin will bank, computed by the
     * same service, rather than a separately derived estimate.</p>
     */
    private void refreshDepositPreview()
    {
        Skill selected = (Skill) skillSelector.getSelectedItem();
        long typed = parsePositiveXp(xpField.getText());

        if (selected == null)
        {
            depositPreview.setText(" ");
            return;
        }

        double multiplier = multiplierManager.currentMultiplier(selected);
        if (multiplier == XpMultiplierManager.NEUTRAL_MULTIPLIER)
        {
            depositPreview.setText(" ");
            return;
        }

        if (multiplier == 0.0)
        {
            depositPreview.setText(String.format(
                Locale.US,
                "%s is set to 0.00x at your level, so nothing would be banked. Change it on the "
                    + "LEVEL MULTIPLIERS tab.",
                selected.getName()));
            return;
        }

        if (typed <= 0)
        {
            depositPreview.setText(String.format(
                Locale.US,
                "%s banks at %s right now.",
                selected.getName(),
                IrlXpUi.formatMultiplier(multiplier)));
            return;
        }

        depositPreview.setText(String.format(
            Locale.US,
            "Banks as %s XP (%s at %s).",
            QuantityFormatter.formatNumber(XpMultiplierManager.scale(typed, multiplier)),
            IrlXpUi.formatMultiplier(multiplier),
            selected.getName()));
    }

    /** The Add/Remove switch, styled like the sidebar's own tab strip. */
    private JPanel buildModeToggle()
    {
        JPanel toggle = new JPanel(new GridLayout(1, 2, 5, 0));
        toggle.setOpaque(false);

        configureModeButton(addModeButton, ADD_CARD);
        configureModeButton(removeModeButton, REMOVE_CARD);

        toggle.add(addModeButton);
        toggle.add(removeModeButton);
        return toggle;
    }

    private void configureModeButton(JButton button, String card)
    {
        button.setFont(button.getFont().deriveFont(Font.BOLD, 10f));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(6, 5, 6, 5));
        button.addActionListener(event -> showMode(card));
    }

    private void showMode(String card)
    {
        boolean adding = ADD_CARD.equals(card);
        formLayout.show(formCards, card);
        styleModeButton(addModeButton, adding);
        styleModeButton(removeModeButton, !adding);
    }

    private void styleModeButton(JButton button, boolean selected)
    {
        button.setOpaque(true);
        button.setBackground(selected ? IrlXpUi.ACCENT : IrlXpUi.SURFACE_RAISED);
        button.setForeground(selected ? new java.awt.Color(0x18130B) : IrlXpUi.MUTED_TEXT);
    }

    /**
     * Builds the correction form.
     *
     * <p>Its skill drop-down is populated from the banked-XP snapshot rather than
     * from every skill in the game, so a skill with nothing banked simply is not
     * offered — there is no way to reach "subtract from an empty balance" through
     * this UI at all. The amount is checked against the live balance on submit as
     * well, because the snapshot can be a moment stale if XP drained in-game
     * while the form sat open.</p>
     */
    private JPanel buildRemoveForm()
    {
        JPanel removeForm = new JPanel(new GridBagLayout());
        removeForm.setOpaque(false);

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = FIELD_INSETS;
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridwidth = GridBagConstraints.REMAINDER;

        removeForm.add(IrlXpUi.sectionTitle("Correct a mistake"), gc);

        gc.gridy = 1;
        removeForm.add(IrlXpUi.wrappingNote(
            "Banked to the wrong skill, or typed too much? Take it back out here.", 2), gc);

        gc.gridy = 2;
        JLabel skillLabel = new JLabel("Skill");
        skillLabel.setForeground(IrlXpUi.MUTED_TEXT);
        removeForm.add(skillLabel, gc);

        gc.gridy = 3;
        removeSkillSelector.setPreferredSize(new Dimension(1, 32));
        removeSkillSelector.setRenderer(IrlXpUi.skillNameRenderer());
        removeSkillSelector.addActionListener(e -> refreshRemoveBalanceLabel());
        IrlXpUi.styleField(removeSkillSelector);
        removeForm.add(removeSkillSelector, gc);

        gc.gridy = 4;
        removeForm.add(removeBalanceLabel, gc);

        gc.gridy = 5;
        JLabel amountLabel = new JLabel("XP to remove");
        amountLabel.setForeground(IrlXpUi.MUTED_TEXT);
        removeForm.add(amountLabel, gc);

        gc.gridy = 6;
        removeXpField.setToolTipText("Enter a positive whole number, no larger than the banked amount");
        removeXpField.setCaretColor(IrlXpUi.TEXT);
        removeXpField.setPreferredSize(new Dimension(1, 36));
        IrlXpUi.styleField(removeXpField);
        removeXpField.addActionListener(e -> onRemoveXp());
        removeXpField.addMouseListener(new FocusOnClick(removeXpField));
        removeForm.add(removeXpField, gc);

        gc.gridy = 7;
        gc.insets = new Insets(8, 0, 0, 0);
        IrlXpUi.styleDangerButton(removeButton);
        removeButton.setFont(removeButton.getFont().deriveFont(Font.BOLD));
        removeButton.addActionListener(e -> onRemoveXp());
        removeForm.add(removeButton, gc);

        return removeForm;
    }

    void updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot snapshot)
    {
        SwingUtilities.invokeLater(() -> {
            skillListModel.clear();
            refreshRemovableSkills(snapshot);
            // A snapshot follows every XP change, and an XP change can be the
            // level-up that moves this skill onto a different multiplier tier.
            refreshDepositPreview();

            if (snapshot == null || !snapshot.hasData())
            {
                totalXpLabel.setText("0 XP");
                skillListModel.addElement("No banked XP yet — add your first chunk above.");
                return;
            }

            totalXpLabel.setText(QuantityFormatter.formatNumber(snapshot.getTotalXp()) + " XP");

            for (GokIrlBankedXpPlugin.BankedSkill entry : snapshot.getSkills())
            {
                // A debt prints with its minus sign (e.g. "-5,000 XP") and is
                // marked OWED; LOW is reserved for a positive balance nearly spent.
                String marker = "";
                if (entry.isInDebt())
                {
                    marker = "   OWED";
                }
                else if (entry.isBelowThreshold())
                {
                    marker = "   LOW";
                }
                String line = String.format(
                    Locale.US,
                    "%s   •   %s XP%s",
                    entry.getDisplayName(),
                    QuantityFormatter.formatNumber(entry.getRemainingXp()),
                    marker
                );
                skillListModel.addElement(line);
            }
        });
    }

    /**
     * Rebuilds the removal drop-down to hold exactly the skills with a positive
     * balance. A skill in debt is left out: there is nothing banked to take back,
     * and removal must never deepen a debt.
     *
     * <p>Already on the EDT — the only caller is inside {@link #updateSnapshot}'s
     * {@code invokeLater} — so it must not queue another round trip, or the
     * selection restored here would be undone by the listener firing afterwards.</p>
     */
    private void refreshRemovableSkills(GokIrlBankedXpPlugin.BankedXpSnapshot snapshot)
    {
        Skill previous = (Skill) removeSkillSelector.getSelectedItem();

        List<Skill> banked = new ArrayList<>();
        if (snapshot != null)
        {
            for (GokIrlBankedXpPlugin.BankedSkill entry : snapshot.getSkills())
            {
                if (entry.getRemainingXp() > 0)
                {
                    banked.add(entry.getSkill());
                }
            }
        }

        DefaultComboBoxModel<Skill> model = new DefaultComboBoxModel<>();
        for (Skill skill : banked)
        {
            model.addElement(skill);
        }
        removeSkillSelector.setModel(model);

        if (previous != null && banked.contains(previous))
        {
            removeSkillSelector.setSelectedItem(previous);
        }
        else if (!banked.isEmpty())
        {
            removeSkillSelector.setSelectedIndex(0);
        }

        // With nothing banked there is nothing to correct, so the whole form goes
        // inert rather than accepting input it would only reject.
        boolean anyBanked = !banked.isEmpty();
        removeSkillSelector.setEnabled(anyBanked);
        removeXpField.setEnabled(anyBanked);
        removeButton.setEnabled(anyBanked);
        if (!anyBanked)
        {
            removeXpField.setText("");
        }

        refreshRemoveBalanceLabel();
    }

    /** Shows the ceiling for the current removal, so the cap is never a surprise. */
    private void refreshRemoveBalanceLabel()
    {
        Skill selected = (Skill) removeSkillSelector.getSelectedItem();
        if (selected == null)
        {
            removeBalanceLabel.setText("Nothing banked yet.");
            return;
        }

        removeBalanceLabel.setText(String.format(
            Locale.US,
            "Banked: %s XP — remove up to that.",
            QuantityFormatter.formatNumber(plugin.getBankedXp(selected))));
    }

    private void onAddXp()
    {
        Skill selectedSkill = (Skill) skillSelector.getSelectedItem();
        if (selectedSkill == null)
        {
            return;
        }

        long xp = parsePositiveXp(xpField.getText());
        if (xp <= 0)
        {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a positive whole number.",
                "Invalid XP amount",
                JOptionPane.WARNING_MESSAGE
            );
            xpField.requestFocusInWindow();
            xpField.selectAll();
            return;
        }

        plugin.addManualXp(selectedSkill, xp);
        xpField.setText("");
        xpField.requestFocusInWindow();
    }

    private void onRemoveXp()
    {
        Skill selectedSkill = (Skill) removeSkillSelector.getSelectedItem();
        if (selectedSkill == null)
        {
            JOptionPane.showMessageDialog(
                this,
                "No skill has banked XP to remove.",
                "Nothing to remove",
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        long requested = parsePositiveXp(removeXpField.getText());
        if (requested <= 0)
        {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a positive whole number.",
                "Invalid XP amount",
                JOptionPane.WARNING_MESSAGE
            );
            removeXpField.requestFocusInWindow();
            removeXpField.selectAll();
            return;
        }

        long banked = plugin.getBankedXp(selectedSkill);
        if (banked <= 0)
        {
            // The balance drained in-game while this form sat open.
            JOptionPane.showMessageDialog(
                this,
                String.format(Locale.US, "%s has no banked XP left to remove.", selectedSkill.getName()),
                "Nothing to remove",
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        if (requested > banked)
        {
            JOptionPane.showMessageDialog(
                this,
                String.format(
                    Locale.US,
                    "%s only has %s XP banked. Enter %s or less.",
                    selectedSkill.getName(),
                    QuantityFormatter.formatNumber(banked),
                    QuantityFormatter.formatNumber(banked)),
                "Amount too large",
                JOptionPane.WARNING_MESSAGE
            );
            removeXpField.requestFocusInWindow();
            removeXpField.selectAll();
            return;
        }

        long removed = plugin.removeManualXp(selectedSkill, requested);
        if (removed <= 0)
        {
            JOptionPane.showMessageDialog(
                this,
                String.format(Locale.US, "%s has no banked XP left to remove.", selectedSkill.getName()),
                "Nothing to remove",
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        removeXpField.setText("");
        removeXpField.requestFocusInWindow();
    }

    /** Parses user-entered XP without relying on formatted-field edit state. */
    static long parsePositiveXp(String text)
    {
        if (text == null || text.trim().isEmpty())
        {
            return 0L;
        }

        try
        {
            long value = Long.parseLong(text.trim());
            return value > 0 ? value : 0L;
        }
        catch (NumberFormatException ignored)
        {
            return 0L;
        }
    }

    /**
     * Gives a text field keyboard focus when it is clicked.
     *
     * <p>RuneLite normally transfers focus automatically, but making it explicit
     * prevents the game canvas from retaining keyboard focus when a sidebar
     * control is clicked.</p>
     */
    private static class FocusOnClick extends MouseAdapter
    {
        private final JTextField field;

        FocusOnClick(JTextField field)
        {
            this.field = field;
        }

        @Override
        public void mousePressed(MouseEvent event)
        {
            field.requestFocusInWindow();
        }
    }

}
