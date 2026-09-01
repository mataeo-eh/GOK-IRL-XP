package com.gokirlbankedxp.ui;

import com.gokirlbankedxp.model.XpMultiplierTier;
import com.gokirlbankedxp.service.SkillLevelTracker;
import com.gokirlbankedxp.service.XpMultiplierManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;

/**
 * The sidebar's MULTIPLIERS tab: level-based banking multipliers, per skill.
 *
 * <p>This tab is only the entry point. Picking a skill shows what that skill is
 * banking at right now, and the thresholds themselves are edited in
 * {@link XpMultiplierEditorDialog}, which has room for a level field, a
 * multiplier field and a legible label on one line — the roughly 225px sidebar
 * does not.</p>
 *
 * <p>The list underneath exists so a user can see, without clicking through
 * twenty-three skills, which ones they have actually set up and what each is
 * paying at their current level. A feature configured one skill at a time needs
 * somewhere that shows the whole picture, or it becomes impossible to remember
 * what you did.</p>
 *
 * <p>{@code @Singleton} for the same reason the other panels carry it: this holds
 * live list models, and a second unscoped instance would be UI state that nothing
 * ever refreshes.</p>
 */
@Singleton
public class XpMultipliersPanel extends JPanel
{
    private final XpMultiplierManager multiplierManager;
    private final SkillLevelTracker levelTracker;

    private final JComboBox<Skill> skillSelector = new JComboBox<>(Skill.values());
    private final JLabel currentStatusLabel = new JLabel(" ");
    private final JTextArea skillSummary = IrlXpUi.wrappingNote(" ", 3);
    private final JButton editButton = new JButton("SET THRESHOLDS");
    private final JButton clearButton = new JButton("Clear");
    private final JTextArea disabledNote = IrlXpUi.wrappingNote(" ", 3);

    private final DefaultListModel<String> configuredModel = new DefaultListModel<>();

    @Inject
    public XpMultipliersPanel(XpMultiplierManager multiplierManager, SkillLevelTracker levelTracker)
    {
        this.multiplierManager = multiplierManager;
        this.levelTracker = levelTracker;

        setLayout(new BorderLayout(0, 10));
        setBackground(IrlXpUi.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        add(buildEditorCard(), BorderLayout.NORTH);
        add(buildConfiguredCard(), BorderLayout.CENTER);

        refresh();
    }

    /** The per-skill picker and the buttons that act on the chosen skill. */
    private JPanel buildEditorCard()
    {
        JPanel card = IrlXpUi.card(new BorderLayout(0, 8));

        JPanel heading = new JPanel(new BorderLayout(0, 6));
        heading.setOpaque(false);
        heading.add(IrlXpUi.sectionTitle("Level multipliers"), BorderLayout.NORTH);
        heading.add(IrlXpUi.wrappingNote(
            "Bank more XP as your levels climb. Pick a skill, choose how many thresholds you want, "
                + "then set the level each one starts at and what it multiplies your banked XP by.", 4),
            BorderLayout.CENTER);
        card.add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setOpaque(false);

        JPanel selectorRow = new JPanel(new BorderLayout(0, 4));
        selectorRow.setOpaque(false);
        JLabel skillLabel = new JLabel("Skill");
        skillLabel.setForeground(IrlXpUi.MUTED_TEXT);
        selectorRow.add(skillLabel, BorderLayout.NORTH);

        skillSelector.setPreferredSize(new Dimension(1, 32));
        skillSelector.setRenderer(IrlXpUi.skillNameRenderer());
        skillSelector.setToolTipText("Multipliers are set per skill; each skill has its own thresholds.");
        skillSelector.addActionListener(e -> refreshSelectedSkill());
        IrlXpUi.styleField(skillSelector);
        selectorRow.add(skillSelector, BorderLayout.CENTER);
        body.add(selectorRow, BorderLayout.NORTH);

        JPanel status = new JPanel(new BorderLayout(0, 4));
        status.setOpaque(false);
        currentStatusLabel.setForeground(IrlXpUi.ACCENT);
        currentStatusLabel.setFont(currentStatusLabel.getFont().deriveFont(Font.BOLD, 13f));
        status.add(currentStatusLabel, BorderLayout.NORTH);
        status.add(skillSummary, BorderLayout.CENTER);
        body.add(status, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(1, 2, 5, 0));
        buttons.setOpaque(false);
        IrlXpUi.stylePrimaryButton(editButton);
        IrlXpUi.styleDangerButton(clearButton);
        editButton.addActionListener(e -> onEditThresholds());
        clearButton.addActionListener(e -> onClearThresholds());
        buttons.add(editButton);
        buttons.add(clearButton);
        body.add(buttons, BorderLayout.SOUTH);

        card.add(body, BorderLayout.CENTER);

        // Shown only while the feature is switched off in the settings panel.
        // Silently ignoring a user's carefully built ladder because of a checkbox
        // they set months ago is exactly the kind of thing this tab has to say
        // out loud, and it names where the switch is.
        disabledNote.setForeground(IrlXpUi.DANGER);
        card.add(disabledNote, BorderLayout.SOUTH);

        return card;
    }

    /** The overview list: every skill that has a ladder, and what it pays now. */
    private JPanel buildConfiguredCard()
    {
        JPanel card = IrlXpUi.card(new BorderLayout(0, 8));

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(IrlXpUi.sectionTitle("Skills with multipliers"), BorderLayout.WEST);
        card.add(heading, BorderLayout.NORTH);

        JList<String> configuredList = new JList<>(configuredModel);
        configuredList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        configuredList.setVisibleRowCount(8);
        configuredList.setCellRenderer(new DefaultListCellRenderer()
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

        JScrollPane scrollPane = new JScrollPane(configuredList);
        IrlXpUi.styleList(configuredList, scrollPane);
        configuredList.setFixedCellHeight(30);
        card.add(scrollPane, BorderLayout.CENTER);
        return card;
    }

    /**
     * Rebuilds everything on this tab.
     *
     * <p>Called on the EDT after any change that could move a multiplier: a save
     * from the editor, and every banked-XP snapshot the plugin publishes — an XP
     * drop can be a level-up, which silently changes what the next deposit is
     * worth, and this tab is the only place that says so.</p>
     */
    public void refresh()
    {
        SwingUtilities.invokeLater(() -> {
            refreshSelectedSkill();
            refreshConfiguredList();
        });
    }

    /** Updates the status line, the per-skill summary, and the buttons' enabled state. */
    private void refreshSelectedSkill()
    {
        Skill selected = (Skill) skillSelector.getSelectedItem();
        boolean enabled = multiplierManager.isEnabled();

        disabledNote.setText(enabled
            ? ""
            : "Level multipliers are switched OFF in this plugin's settings panel "
                + "(the gear beside IRL XP in RuneLite's plugin list). Your thresholds are kept, "
                + "but XP is banked unchanged until you switch them back on.");
        disabledNote.setVisible(!enabled);

        if (selected == null)
        {
            currentStatusLabel.setText(" ");
            skillSummary.setText(" ");
            editButton.setEnabled(false);
            clearButton.setEnabled(false);
            return;
        }

        List<XpMultiplierTier> tiers = multiplierManager.getTiers(selected);
        int level = levelTracker.getLevel(selected);
        boolean observed = levelTracker.hasObserved(selected);

        // The multiplier the tiers would give, ignoring the master switch, so the
        // status line describes the ladder itself; the note above covers the case
        // where the switch is stopping it from being used.
        double tierMultiplier = multiplierManager.multiplierFor(selected, level);
        double effective = enabled ? tierMultiplier : XpMultiplierManager.NEUTRAL_MULTIPLIER;

        currentStatusLabel.setText(String.format(
            Locale.US,
            "%s %s — banking at %s",
            selected.getName(),
            observed ? "level " + level : "(level not seen yet)",
            IrlXpUi.formatMultiplier(effective)));

        skillSummary.setText(describeLadder(selected, tiers, level, observed));

        editButton.setEnabled(true);
        editButton.setText(tiers.isEmpty() ? "SET THRESHOLDS" : "EDIT THRESHOLDS");
        clearButton.setEnabled(!tiers.isEmpty());
    }

    /** One or two sentences describing this skill's ladder in plain language. */
    private String describeLadder(Skill skill, List<XpMultiplierTier> tiers, int level, boolean observed)
    {
        if (tiers.isEmpty())
        {
            return skill.getName() + " has no thresholds yet, so its XP is banked exactly as earned.";
        }

        StringBuilder text = new StringBuilder();
        text.append(tiers.size())
            .append(tiers.size() == 1 ? " threshold: " : " thresholds: ");

        List<String> parts = new ArrayList<>();
        for (XpMultiplierTier tier : tiers)
        {
            parts.add(String.format(Locale.US, "lvl %d %s",
                tier.getLevel(), IrlXpUi.formatMultiplier(tier.getMultiplier())));
        }
        text.append(String.join(", ", parts)).append('.');

        if (observed)
        {
            int firstLevel = tiers.get(0).getLevel();
            if (level < firstLevel)
            {
                text.append(" You are ")
                    .append(firstLevel - level)
                    .append(level + 1 == firstLevel ? " level" : " levels")
                    .append(" from the first one.");
            }
        }

        return text.toString();
    }

    /** Rebuilds the overview list from every skill that currently has a ladder. */
    private void refreshConfiguredList()
    {
        configuredModel.clear();

        Map<Skill, List<XpMultiplierTier>> all = multiplierManager.getAllTiers();
        if (all.isEmpty())
        {
            configuredModel.addElement("No multipliers set yet — pick a skill above.");
            return;
        }

        // EnumMap iterates in Skill declaration order, which is the order the
        // in-game skill guide uses, so the list reads the way players expect.
        all.forEach((skill, tiers) -> configuredModel.addElement(String.format(
            Locale.US,
            "%s   •   %d threshold%s   •   now %s",
            skill.getName(),
            tiers.size(),
            tiers.size() == 1 ? "" : "s",
            IrlXpUi.formatMultiplier(multiplierManager.currentMultiplier(skill)))));
    }

    private void onEditThresholds()
    {
        Skill selected = (Skill) skillSelector.getSelectedItem();
        if (selected == null)
        {
            return;
        }

        Window window = SwingUtilities.getWindowAncestor(this);
        XpMultiplierEditorDialog dialog = new XpMultiplierEditorDialog(
            window,
            selected,
            multiplierManager.getTiers(selected),
            levelTracker.getLevel(selected),
            levelTracker.hasObserved(selected));
        dialog.setVisible(true);

        List<XpMultiplierTier> saved = dialog.getResultTiers();
        if (saved != null)
        {
            multiplierManager.setTiers(selected, saved);
            refresh();
        }
    }

    private void onClearThresholds()
    {
        Skill selected = (Skill) skillSelector.getSelectedItem();
        if (selected == null || multiplierManager.getTiers(selected).isEmpty())
        {
            return;
        }

        int response = JOptionPane.showConfirmDialog(
            this,
            String.format("Remove every %s threshold? Its XP will be banked unchanged again.", selected.getName()),
            "Confirm clear",
            JOptionPane.YES_NO_OPTION);

        if (response == JOptionPane.YES_OPTION)
        {
            multiplierManager.clearTiers(selected);
            refresh();
        }
    }
}
