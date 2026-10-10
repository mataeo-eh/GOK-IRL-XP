package com.gokirlbankedxp;

import com.gokirlbankedxp.service.XpMultiplierManager;
import com.gokirlbankedxp.ui.CollapsibleSection;
import com.gokirlbankedxp.ui.IrlXpUi;
import com.gokirlbankedxp.ui.SkillTrackingGrid;
import com.gokirlbankedxp.ui.StateMarkIcon;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
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
import net.runelite.client.game.SkillIconManager;
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
    /** The space left under each section of the tab. */
    private static final Insets SECTION_GAP = new Insets(0, 0, 10, 0);
    /** The most rows the balance list shows before it scrolls on its own. */
    private static final int MAX_BALANCE_ROWS = 10;
    private static final int OVERLAY_MARK_SIZE = 12;

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
    private final JList<String> skillList = new JList<>(skillListModel);
    /** Holds the balance list; its summary line is the banked total. */
    private final CollapsibleSection balanceSection;

    /**
     * Shows or hides the on-screen overlay, and says which it will do.
     *
     * <p>A button rather than a checkbox: the look-and-feel's dark grey tick on
     * a dark sidebar was nearly impossible to read. The icon shows the current
     * state (green tick while the overlay is showing, red cross while it is
     * hidden) and the words name what a click will do ("Hide…" or "Show…").</p>
     */
    private final JButton overlayToggle = new JButton();
    /** The overlay state the toggle currently displays; see {@link #renderOverlayToggle}. */
    private boolean overlayShown;

    /** The in-game-style grid of skill switches, hidden until its section is opened. */
    private final SkillTrackingGrid trackingGrid;
    private final JPanel trackingBody = new JPanel(new BorderLayout(0, 8));
    /** Holds the grid; its summary line says how many skills are switched on. */
    private final CollapsibleSection trackingSection;
    /**
     * The untracked set the deposit drop-down was last built from, so it is only
     * rebuilt when that set actually changes rather than on every XP drop.
     * Null until the first build.
     */
    private Set<Skill> depositSelectorUntracked;

    @Inject
    GokIrlBankedXpPanel(
        GokIrlBankedXpPlugin plugin,
        XpMultiplierManager multiplierManager,
        SkillIconManager skillIconManager)
    {
        this.plugin = plugin;
        this.multiplierManager = multiplierManager;

        // One column of sections, each as tall as its contents, so a section
        // that is folded away gives its space back to the ones below it.
        setLayout(new GridBagLayout());
        setBackground(IrlXpUi.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        // The section header names the form, so the two forms inside it carry
        // only a one-line hint rather than a second heading of their own.
        JPanel inputPanel = new JPanel(new BorderLayout(0, 10));
        inputPanel.setOpaque(false);
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

        addForm.add(IrlXpUi.mutedLabel("Add XP earned offline."), gc);

        gc.gridy = 1;
        JLabel skillLabel = new JLabel("Skill");
        skillLabel.setForeground(IrlXpUi.MUTED_TEXT);
        addForm.add(skillLabel, gc);

        gc.gridy = 2;
        skillSelector = new JComboBox<>();
        skillSelector.setPreferredSize(new Dimension(1, 32));
        skillSelector.setRenderer(IrlXpUi.skillNameRenderer());
        // A different skill can be on a different multiplier tier, so the
        // preview has to follow the selection, not just the typed amount.
        skillSelector.addActionListener(e -> refreshDepositPreview());
        IrlXpUi.styleField(skillSelector);
        addForm.add(skillSelector, gc);

        gc.gridy = 3;
        JLabel xpLabel = new JLabel("XP amount");
        xpLabel.setForeground(IrlXpUi.MUTED_TEXT);
        addForm.add(xpLabel, gc);

        gc.gridy = 4;
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

        gc.gridy = 5;
        addForm.add(depositPreview, gc);

        gc.gridy = 6;
        gc.insets = new Insets(8, 0, 0, 0);
        JButton addButton = new JButton("BANK XP");
        IrlXpUi.stylePrimaryButton(addButton);
        addButton.addActionListener(e -> onAddXp());
        addForm.add(addButton, gc);

        formCards.setOpaque(false);
        formCards.add(addForm, ADD_CARD);
        formCards.add(buildRemoveForm(), REMOVE_CARD);
        inputPanel.add(formCards, BorderLayout.CENTER);

        // Open by default: banking XP is what this tab is for.
        CollapsibleSection bankSection = new CollapsibleSection(
            "Bank XP", "Add XP to a skill, or take a mistaken deposit back out.", inputPanel, true);
        bankSection.setSummary("Add or remove XP");

        skillList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skillList.setVisibleRowCount(1);
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
        // Open by default, and the total is the summary line, so the one figure
        // most players want is on screen whether the list is open or not.
        balanceSection = new CollapsibleSection(
            "Balance", "See how much XP each skill has banked.", scrollPane, true);
        balanceSection.setSummary("0 XP");
        balanceSection.setSummaryColor(IrlXpUi.ACCENT);

        IrlXpUi.styleSecondaryButton(overlayToggle);
        overlayToggle.setIconTextGap(8);
        overlayToggle.setToolTipText("Hide banked XP and timers on screen while all tracking continues. Configure milestone popups in IRL XP settings.");
        // The button flips itself straight away, as the checkbox it replaced
        // did; the snapshot that follows the config change then confirms it.
        overlayToggle.addActionListener(e -> {
            boolean shown = !overlayShown;
            plugin.setOverlayShown(shown);
            renderOverlayToggle(shown);
        });
        renderOverlayToggle(true);

        trackingGrid = new SkillTrackingGrid(
            plugin.getTrackableSkills(), skillIconManager, plugin::setSkillTracked);
        trackingSection = buildTrackedSkillsSection();

        GridBagConstraints stack = new GridBagConstraints();
        stack.gridx = 0;
        stack.weightx = 1.0;
        stack.fill = GridBagConstraints.HORIZONTAL;
        stack.insets = SECTION_GAP;
        stack.gridy = 0;
        add(bankSection, stack);
        stack.gridy = 1;
        add(balanceSection, stack);
        stack.gridy = 2;
        add(overlayToggle, stack);
        stack.gridy = 3;
        add(trackingSection, stack);
        stack.gridy = 4;
        stack.insets = new Insets(0, 0, 0, 0);
        add(buildSettingsPointer(), stack);
        // GridBagLayout centres its rows when given more height than they need.
        // An empty row that takes all the spare height keeps them at the top.
        stack.gridy = 5;
        stack.weighty = 1.0;
        JPanel spare = new JPanel();
        spare.setOpaque(false);
        add(spare, stack);

        showMode(ADD_CARD);
        // Guice constructs this panel while injecting the plugin's fields, so
        // its config and tracking services may not exist yet. startUp() loads
        // those services and publishes a snapshot; updateSnapshot() then fills
        // the controls on the EDT using the saved profile state.
    }

    /**
     * The collapsible "Tracked skills" section.
     *
     * <p>Closed by default: choosing which skills count is something a player
     * sets once, and a 24-tile grid left permanently open would push the balance
     * list and the deposit form around for no benefit. The header stays visible
     * while closed and says how many skills are on, so a player who has switched
     * some off is not left wondering why a skill has vanished from the list.</p>
     */
    private CollapsibleSection buildTrackedSkillsSection()
    {
        trackingBody.setOpaque(false);
        trackingBody.add(IrlXpUi.wrappingNote(
            "Click a skill to turn its tracking off or on. A skill that is off is hidden, "
                + "gains and loses nothing, and keeps the XP it had for when you turn it back on.", 4),
            BorderLayout.NORTH);
        trackingBody.add(trackingGrid, BorderLayout.CENTER);

        return new CollapsibleSection(
            "Tracked skills", "Choose which skills IRL XP tracks.", trackingBody, false);
    }

    /**
     * Draws the overlay switch for the given overlay state.
     *
     * <p>Must run on the EDT. Icon and words deliberately point opposite ways:
     * the icon is the state now, the words are the action a click performs.</p>
     *
     * @param shown whether the on-screen overlay is currently showing
     */
    private void renderOverlayToggle(boolean shown)
    {
        overlayShown = shown;
        overlayToggle.setIcon(new StateMarkIcon(OVERLAY_MARK_SIZE, shown));
        overlayToggle.setText(shown ? "Hide on-screen overlay" : "Show on-screen overlay");
        // The outline repeats the icon's colour, so the state reads at a glance
        // even before the small mark is noticed.
        overlayToggle.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(shown ? StateMarkIcon.ON_COLOR : StateMarkIcon.OFF_COLOR),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)));
    }

    /**
     * Brings everything that depends on the tracked-skill choice in line with
     * the plugin: the grid's tiles, the "N of M on" summary, and the deposit
     * drop-down, which offers only skills that are switched on.
     *
     * <p>Must run on the EDT. The plugin is asked rather than a local copy kept,
     * so the grid can never show a state the plugin did not accept.</p>
     */
    private void refreshTrackedSkills()
    {
        Set<Skill> untracked = plugin.getUntrackedSkills();

        trackingGrid.setUntracked(untracked);

        int total = trackingGrid.getSkillCount();
        int on = 0;
        for (Skill skill : plugin.getTrackableSkills())
        {
            if (!untracked.contains(skill))
            {
                on++;
            }
        }
        trackingSection.setSummary(on == total
            ? "All " + total + " skills on"
            : on + " of " + total + " skills on");

        if (untracked.equals(depositSelectorUntracked))
        {
            return;
        }
        // Copied, because the comparison above has to be against the set as it
        // was at this build, whatever the caller later does with its own.
        depositSelectorUntracked = untracked.isEmpty()
            ? EnumSet.noneOf(Skill.class)
            : EnumSet.copyOf(untracked);

        Skill previous = (Skill) skillSelector.getSelectedItem();
        DefaultComboBoxModel<Skill> model = new DefaultComboBoxModel<>();
        for (Skill skill : plugin.getTrackableSkills())
        {
            if (!untracked.contains(skill))
            {
                model.addElement(skill);
            }
        }
        skillSelector.setModel(model);
        // Keep the user's selection when it is still on offer; otherwise the new
        // model's first entry is selected, or nothing when every skill is off.
        if (previous != null && !untracked.contains(previous))
        {
            skillSelector.setSelectedItem(previous);
        }
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
     *
     * <p>Closed by default like the other set-once sections, but its header is
     * always on screen and says what the hidden settings are, so the signpost
     * itself never folds away.</p>
     */
    private CollapsibleSection buildSettingsPointer()
    {
        CollapsibleSection section = new CollapsibleSection(
            "More settings",
            "Where to find the milestone popup and warning settings.",
            IrlXpUi.wrappingNote(
                "Configuration (the wrench) > IRL XP: choose \"Overlay and milestone popups\" "
                    + "for occasional XP and level-up reminders, even with the overlay hidden. "
                    + "Chat and screen-flash warnings have their own sections below it.", 4),
            false);
        section.setSummary("Alerts and popups");
        return section;
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

        removeForm.add(IrlXpUi.wrappingNote(
            "Banked to the wrong skill, or typed too much? Take it back out here.", 2), gc);

        gc.gridy = 1;
        JLabel skillLabel = new JLabel("Skill");
        skillLabel.setForeground(IrlXpUi.MUTED_TEXT);
        removeForm.add(skillLabel, gc);

        gc.gridy = 2;
        removeSkillSelector.setPreferredSize(new Dimension(1, 32));
        removeSkillSelector.setRenderer(IrlXpUi.skillNameRenderer());
        removeSkillSelector.addActionListener(e -> refreshRemoveBalanceLabel());
        IrlXpUi.styleField(removeSkillSelector);
        removeForm.add(removeSkillSelector, gc);

        gc.gridy = 3;
        removeForm.add(removeBalanceLabel, gc);

        gc.gridy = 4;
        JLabel amountLabel = new JLabel("XP to remove");
        amountLabel.setForeground(IrlXpUi.MUTED_TEXT);
        removeForm.add(amountLabel, gc);

        gc.gridy = 5;
        removeXpField.setToolTipText("Enter a positive whole number, no larger than the banked amount");
        removeXpField.setCaretColor(IrlXpUi.TEXT);
        removeXpField.setPreferredSize(new Dimension(1, 36));
        IrlXpUi.styleField(removeXpField);
        removeXpField.addActionListener(e -> onRemoveXp());
        removeXpField.addMouseListener(new FocusOnClick(removeXpField));
        removeForm.add(removeXpField, gc);

        gc.gridy = 6;
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
            // ConfigChanged and profile switches refresh the same snapshot path.
            // Redrawing the toggle does not fire its ActionListener, so this
            // cannot write back.
            renderOverlayToggle(plugin.isOverlayShown());
            skillListModel.clear();
            // Before the previews below: they read the deposit drop-down, which
            // this rebuilds when a skill has been switched on or off.
            refreshTrackedSkills();
            refreshRemovableSkills(snapshot);
            // A snapshot follows every XP change, and an XP change can be the
            // level-up that moves this skill onto a different multiplier tier.
            refreshDepositPreview();

            if (snapshot == null || !snapshot.hasData())
            {
                balanceSection.setSummary("0 XP");
                skillListModel.addElement("No banked XP yet.");
                fitBalanceListToRows();
                return;
            }

            balanceSection.setSummary(QuantityFormatter.formatNumber(snapshot.getTotalXp()) + " XP");

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
            fitBalanceListToRows();
        });
    }

    /**
     * Makes the balance list exactly as tall as the rows it holds, up to
     * {@link #MAX_BALANCE_ROWS}, after which it scrolls on its own.
     *
     * <p>The list used to reserve ten rows whatever it held, so a player with
     * two skills banked scrolled past eight empty rows to reach the settings
     * below. Must run on the EDT.</p>
     */
    private void fitBalanceListToRows()
    {
        int rows = Math.min(Math.max(skillListModel.size(), 1), MAX_BALANCE_ROWS);
        if (rows == skillList.getVisibleRowCount())
        {
            return;
        }
        skillList.setVisibleRowCount(rows);
        // The list's own scroll pane is a validation root, so the change in its
        // preferred height reaches the sidebar only if this panel asks for it.
        revalidate();
        repaint();
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
            // The drop-down only empties when every skill has been switched off.
            JOptionPane.showMessageDialog(
                this,
                "Every skill is switched off. Turn one on under Tracked skills below to bank XP.",
                "No skills tracked",
                JOptionPane.INFORMATION_MESSAGE
            );
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

        long banked = plugin.addManualXp(selectedSkill, xp);
        if (banked <= 0)
        {
            // The deposit was accepted but moved nothing. The only ordinary way
            // that happens is a 0.00x tier; saying so here beats a field that
            // clears itself as though the XP had gone somewhere.
            boolean zeroTier = multiplierManager.currentMultiplier(selectedSkill) == 0.0;
            JOptionPane.showMessageDialog(
                this,
                zeroTier
                    ? String.format(
                        Locale.US,
                        "Nothing was banked: %s is set to 0.00x at your level. "
                            + "Change that on the LEVEL MULTIPLIERS tab.",
                        selectedSkill.getName())
                    : "Nothing was banked. Please try again.",
                "Nothing banked",
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

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

    /**
     * Parses user-entered XP without relying on formatted-field edit state.
     *
     * <p>Shares the plugin's one number parser, so "1,000" is accepted here just
     * as it is in the action editor, and anything that is not entirely a whole
     * number reads as 0 rather than as its leading digits.</p>
     *
     * @return the amount, or 0 when the text is not a positive whole number
     */
    static long parsePositiveXp(String text)
    {
        OptionalLong parsed = IrlXpUi.parseWholeNumber(text);
        if (parsed.isEmpty() || parsed.getAsLong() <= 0)
        {
            return 0L;
        }
        return parsed.getAsLong();
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
