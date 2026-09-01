package com.gokirlbankedxp.ui;

import com.gokirlbankedxp.model.XpMultiplierTier;
import com.gokirlbankedxp.service.XpMultiplierManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import net.runelite.api.Skill;

/**
 * The per-skill level multiplier editor.
 *
 * <p>Laid out to answer, in order, the three questions the feature poses: how
 * many thresholds do you want, what level does each one start at, and what does
 * each one multiply your banked XP by. The count spinner at the top is the single
 * control that adds and removes rows, so there is exactly one place to decide how
 * big the ladder is rather than a scatter of add/remove buttons.</p>
 *
 * <p>A live summary underneath restates the resulting ladder as sentences,
 * including the "below your first threshold" case that has no row of its own.
 * Thresholds replace each other rather than stacking, which is the single fact
 * about this feature most likely to be assumed wrong, so the summary spells out
 * what each band actually pays and the note above it says so outright.</p>
 *
 * <p>This is a dialog, not a sidebar form, for the same reason the action editor
 * is: RuneLite's sidebar is roughly 225px wide, and a level field, a multiplier
 * field and a readable label do not fit on one line there.</p>
 */
class XpMultiplierEditorDialog extends JDialog
{
    private static final Color ERROR_COLOR = new Color(0x522B2D);

    /** Offered for the first row so a new ladder starts from a sensible place. */
    private static final int DEFAULT_FIRST_LEVEL = 50;
    private static final double DEFAULT_MULTIPLIER = 2.0;

    private final Skill skill;
    private final int currentLevel;
    private final boolean levelObserved;

    private final JSpinner thresholdCountSpinner = new JSpinner(
        new SpinnerNumberModel(0, 0, XpMultiplierManager.MAX_TIERS_PER_SKILL, 1));
    private final JPanel rowContainer = new JPanel();
    private final List<TierRow> rows = new ArrayList<>();
    private final JTextArea summary = IrlXpUi.wrappingNote(" ", 8);
    private final JLabel errorLabel = new JLabel(" ");

    /** Null until the user saves; the caller reads it to know whether to persist. */
    private List<XpMultiplierTier> resultTiers;

    /** Guards {@link #refreshSummary()} against re-entering itself. */
    private boolean refreshingSummary;

    /**
     * @param existingTiers the skill's saved ladder, lowest level first
     * @param currentLevel the player's last observed level in this skill
     * @param levelObserved false when the plugin has never seen this skill's
     *     experience, so the level shown is a default rather than a fact
     */
    XpMultiplierEditorDialog(
        Window owner,
        Skill skill,
        List<XpMultiplierTier> existingTiers,
        int currentLevel,
        boolean levelObserved)
    {
        super(owner, skill.getName() + " level multipliers", ModalityType.APPLICATION_MODAL);
        this.skill = skill;
        this.currentLevel = currentLevel;
        this.levelObserved = levelObserved;

        setLayout(new BorderLayout(8, 8));
        getContentPane().setBackground(IrlXpUi.BACKGROUND);
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(560, 660));
        setResizable(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        add(buildHeader(), BorderLayout.NORTH);
        add(buildRowsCard(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        populateFrom(existingTiers);

        pack();
        setLocationRelativeTo(owner);
    }

    /** The ladder the user saved, or null if they cancelled. */
    List<XpMultiplierTier> getResultTiers()
    {
        return resultTiers;
    }

    private JPanel buildHeader()
    {
        JPanel card = IrlXpUi.card(new BorderLayout(0, 8));

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(IrlXpUi.sectionTitle(skill.getName() + " multipliers"), BorderLayout.WEST);

        JLabel levelLabel = new JLabel(levelObserved
            ? String.format(Locale.US, "Your level: %d", currentLevel)
            : "Level not seen yet");
        levelLabel.setForeground(IrlXpUi.ACCENT);
        levelLabel.setFont(levelLabel.getFont().deriveFont(Font.BOLD, 12f));
        heading.add(levelLabel, BorderLayout.EAST);
        card.add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = GridBagConstraints.REMAINDER;
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.insets = new Insets(0, 0, 6, 0);

        body.add(IrlXpUi.wrappingNote(
            "Each threshold multiplies the " + skill.getName() + " XP you bank, once you reach its level. "
                + "Thresholds never stack: only the highest one you have reached applies, and reaching it "
                + "replaces the one below it completely.", 3), gc);

        gc.gridy = 1;
        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        JLabel countLabel = new JLabel("How many thresholds do you want?");
        countLabel.setForeground(IrlXpUi.TEXT);
        body.add(countLabel, gc);

        gc.gridx = 1;
        gc.insets = new Insets(0, 8, 6, 0);
        thresholdCountSpinner.setPreferredSize(new Dimension(64, 28));
        thresholdCountSpinner.setToolTipText(
            "Set to 0 to bank " + skill.getName() + " XP unchanged. Up to "
                + XpMultiplierManager.MAX_TIERS_PER_SKILL + " thresholds.");
        // The spinner is the only control that changes how many rows exist, so
        // every row add/remove funnels through one listener.
        thresholdCountSpinner.addChangeListener(e -> syncRowCount());
        body.add(thresholdCountSpinner, gc);

        gc.gridx = 2;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        body.add(IrlXpUi.mutedLabel("0 means no multiplier for this skill"), gc);

        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildRowsCard()
    {
        rowContainer.setLayout(new BoxLayout(rowContainer, BoxLayout.Y_AXIS));
        rowContainer.setBackground(IrlXpUi.INPUT_BACKGROUND);

        JScrollPane scrollPane = new JScrollPane(rowContainer);
        scrollPane.setBorder(BorderFactory.createLineBorder(IrlXpUi.BORDER));
        scrollPane.getViewport().setBackground(IrlXpUi.INPUT_BACKGROUND);
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);

        JPanel card = IrlXpUi.card(new BorderLayout(0, 8));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(IrlXpUi.sectionTitle("Your thresholds"), BorderLayout.WEST);
        heading.add(IrlXpUi.mutedLabel("Level it starts at, and what it pays"), BorderLayout.EAST);
        card.add(heading, BorderLayout.NORTH);
        card.add(scrollPane, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildFooter()
    {
        JPanel footer = new JPanel(new BorderLayout(0, 6));
        footer.setBackground(IrlXpUi.BACKGROUND);

        JPanel summaryCard = IrlXpUi.card(new BorderLayout(0, 6));
        summaryCard.add(IrlXpUi.sectionTitle("What this means"), BorderLayout.NORTH);
        summaryCard.add(summary, BorderLayout.CENTER);
        footer.add(summaryCard, BorderLayout.NORTH);

        errorLabel.setForeground(new Color(0xF0B6B3));
        footer.add(errorLabel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.setBackground(IrlXpUi.BACKGROUND);
        JButton cancel = new JButton("Cancel");
        JButton save = new JButton("SAVE MULTIPLIERS");
        IrlXpUi.styleSecondaryButton(cancel);
        IrlXpUi.stylePrimaryButton(save);

        cancel.addActionListener(e -> {
            resultTiers = null;
            dispose();
        });

        save.addActionListener(e -> {
            List<XpMultiplierTier> candidate = buildTiersFromForm();
            if (candidate != null)
            {
                resultTiers = candidate;
                dispose();
            }
        });

        buttons.add(cancel);
        buttons.add(save);
        footer.add(buttons, BorderLayout.SOUTH);
        return footer;
    }

    /** Fills the form in from the saved ladder, or leaves it empty for a new one. */
    private void populateFrom(List<XpMultiplierTier> existingTiers)
    {
        List<XpMultiplierTier> tiers = existingTiers == null ? Collections.emptyList() : existingTiers;

        // Setting the spinner fires syncRowCount(), which creates the rows; the
        // values are written afterwards so they are not overwritten by defaults.
        thresholdCountSpinner.setValue(Math.min(tiers.size(), XpMultiplierManager.MAX_TIERS_PER_SKILL));

        for (int i = 0; i < rows.size() && i < tiers.size(); i++)
        {
            rows.get(i).setValues(tiers.get(i));
        }

        refreshSummary();
    }

    /**
     * Makes the number of rows match the spinner.
     *
     * <p>Growing the ladder seeds the new row rather than leaving it blank: a new
     * threshold starts one level above the previous one and keeps its multiplier,
     * which is a valid ladder the user can edit down, instead of an empty row that
     * would fail validation the moment they pressed save. Shrinking simply drops
     * the trailing rows, which is what "I want fewer thresholds" means.</p>
     */
    private void syncRowCount()
    {
        int wanted = (Integer) thresholdCountSpinner.getValue();

        while (rows.size() > wanted)
        {
            TierRow removed = rows.remove(rows.size() - 1);
            rowContainer.remove(removed.panel);
        }

        while (rows.size() < wanted)
        {
            TierRow row = new TierRow(rows.size() + 1);
            row.setValues(seedForRow(rows.size()));
            rows.add(row);
            rowContainer.add(row.panel);
        }

        rowContainer.revalidate();
        rowContainer.repaint();
        refreshSummary();
    }

    /** A starting level and multiplier for a newly added row, based on the row before it. */
    private XpMultiplierTier seedForRow(int index)
    {
        if (index == 0)
        {
            return new XpMultiplierTier(
                Math.min(XpMultiplierTier.MAX_LEVEL, DEFAULT_FIRST_LEVEL), DEFAULT_MULTIPLIER);
        }

        TierRow previous = rows.get(index - 1);
        int previousLevel = previous.readLevel();
        double previousMultiplier = previous.readMultiplier();
        return new XpMultiplierTier(
            Math.min(XpMultiplierTier.MAX_LEVEL, previousLevel + 1),
            Double.isNaN(previousMultiplier) ? DEFAULT_MULTIPLIER : previousMultiplier);
    }

    /**
     * Validates the form and turns it into a ladder.
     *
     * <p>Two rows at the same level is rejected here rather than silently
     * collapsed. The service would keep only one of them, and a user who typed
     * two thresholds and got one would reasonably read that as lost work.</p>
     *
     * @return the tiers to save, or null when the form was rejected
     */
    private List<XpMultiplierTier> buildTiersFromForm()
    {
        clearValidationHighlights();

        List<XpMultiplierTier> tiers = new ArrayList<>();
        Set<Integer> seenLevels = new HashSet<>();

        for (TierRow row : rows)
        {
            int level = row.readLevel();
            double multiplier = row.readMultiplier();

            if (Double.isNaN(multiplier))
            {
                markInvalid(row.multiplierField,
                    "Threshold " + row.index + ": enter a multiplier of 0 or more.");
                return null;
            }

            if (multiplier < XpMultiplierTier.MIN_MULTIPLIER || multiplier > XpMultiplierTier.MAX_MULTIPLIER)
            {
                markInvalid(row.multiplierField, String.format(Locale.US,
                    "Threshold %d: the multiplier must be between %.0f and %.0f.",
                    row.index, XpMultiplierTier.MIN_MULTIPLIER, XpMultiplierTier.MAX_MULTIPLIER));
                return null;
            }

            if (!seenLevels.add(level))
            {
                markInvalid(row.multiplierField, String.format(Locale.US,
                    "Two thresholds both start at level %d. Give each one its own level.", level));
                return null;
            }

            tiers.add(new XpMultiplierTier(level, multiplier));
        }

        Collections.sort(tiers);
        return tiers;
    }

    /**
     * Restates the ladder as the bands it actually produces.
     *
     * <p>Reads the rows in whatever order they are on screen, sorted by level, so
     * a user who fills the second row in first still sees the truth. The band
     * below the lowest threshold is always listed, because it is the one case
     * with no row to look at and the one people forget exists.</p>
     */
    private void refreshSummary()
    {
        // Reading a row commits its editor, which can fire the very "value"
        // listener that called this method. One level of re-entry is harmless but
        // pointless, so the second pass is dropped: the outer one is still
        // running and will read the committed value anyway.
        if (refreshingSummary)
        {
            return;
        }

        refreshingSummary = true;
        try
        {
            rebuildSummary();
        }
        finally
        {
            refreshingSummary = false;
        }
    }

    private void rebuildSummary()
    {
        List<XpMultiplierTier> tiers = new ArrayList<>();
        for (TierRow row : rows)
        {
            double multiplier = row.readMultiplier();
            tiers.add(new XpMultiplierTier(row.readLevel(), Double.isNaN(multiplier) ? 1.0 : multiplier));
        }
        Collections.sort(tiers);

        StringBuilder text = new StringBuilder();
        if (tiers.isEmpty())
        {
            text.append(skill.getName())
                .append(" XP is banked exactly as earned, at 1.00x.");
        }
        else
        {
            int firstLevel = tiers.get(0).getLevel();
            if (firstLevel > XpMultiplierTier.MIN_LEVEL)
            {
                text.append("Below level ").append(firstLevel).append(": 1.00x (banked unchanged)\n");
            }

            for (int i = 0; i < tiers.size(); i++)
            {
                XpMultiplierTier tier = tiers.get(i);
                String band = i + 1 < tiers.size()
                    ? String.format(Locale.US, "Levels %d-%d", tier.getLevel(), tiers.get(i + 1).getLevel() - 1)
                    : String.format(Locale.US, "Level %d and above", tier.getLevel());
                text.append(band)
                    .append(": ")
                    .append(IrlXpUi.formatMultiplier(tier.getMultiplier()));

                if (tier.getMultiplier() == 0.0)
                {
                    text.append(" (banks nothing)");
                }

                text.append('\n');
            }

            if (levelObserved)
            {
                double now = resolveMultiplier(tiers, currentLevel);
                text.append("\nAt your level (")
                    .append(currentLevel)
                    .append("), 1,000 XP would bank as ")
                    .append(String.format(Locale.US, "%,d", XpMultiplierManager.scale(1000L, now)))
                    .append(" XP.");
            }
        }

        summary.setText(text.toString());
    }

    /**
     * The same non-stacking rule the service applies, run against unsaved rows.
     *
     * <p>Duplicated deliberately: the service resolves against what is stored, and
     * this preview has to describe what is on screen but not yet saved. Both are
     * the same three lines — the highest tier at or below the level wins.</p>
     */
    private static double resolveMultiplier(List<XpMultiplierTier> sortedTiers, int level)
    {
        double applicable = XpMultiplierManager.NEUTRAL_MULTIPLIER;
        for (XpMultiplierTier tier : sortedTiers)
        {
            if (tier.getLevel() > level)
            {
                break;
            }
            applicable = tier.getMultiplier();
        }
        return applicable;
    }

    private void clearValidationHighlights()
    {
        errorLabel.setText(" ");
        for (TierRow row : rows)
        {
            row.multiplierField.setBackground(IrlXpUi.INPUT_BACKGROUND);
        }
    }

    private void markInvalid(JComponent field, String message)
    {
        field.setBackground(ERROR_COLOR);
        errorLabel.setText(message);
        field.requestFocusInWindow();
    }

    /**
     * One threshold: the level it starts at, and what it multiplies by.
     *
     * <p>The level is a spinner rather than a text field so it cannot be set
     * outside 1 to 126 at all, which removes a whole class of validation error
     * from the save path.</p>
     */
    private class TierRow
    {
        private final int index;
        private final JPanel panel = new JPanel(new GridBagLayout());
        private final JSpinner levelSpinner = new JSpinner(new SpinnerNumberModel(
            XpMultiplierTier.MIN_LEVEL,
            XpMultiplierTier.MIN_LEVEL,
            XpMultiplierTier.MAX_LEVEL,
            1));
        private final JFormattedTextField multiplierField =
            new JFormattedTextField(IrlXpUi.multiplierFormatter(XpMultiplierTier.MAX_MULTIPLIER));

        TierRow(int index)
        {
            this.index = index;

            panel.setBackground(IrlXpUi.INPUT_BACKGROUND);
            panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, IrlXpUi.BORDER),
                BorderFactory.createEmptyBorder(7, 9, 7, 9)));
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

            GridBagConstraints gc = new GridBagConstraints();
            gc.gridy = 0;
            gc.anchor = GridBagConstraints.WEST;
            gc.insets = new Insets(0, 0, 0, 6);

            gc.gridx = 0;
            JLabel name = new JLabel("Threshold " + index);
            name.setForeground(IrlXpUi.TEXT);
            name.setPreferredSize(new Dimension(90, 24));
            panel.add(name, gc);

            gc.gridx = 1;
            JLabel fromLabel = new JLabel("from level");
            fromLabel.setForeground(IrlXpUi.MUTED_TEXT);
            panel.add(fromLabel, gc);

            gc.gridx = 2;
            levelSpinner.setPreferredSize(new Dimension(66, 26));
            levelSpinner.setToolTipText("The level at which this threshold takes over (1 to "
                + XpMultiplierTier.MAX_LEVEL + ", virtual levels included).");
            levelSpinner.addChangeListener(e -> refreshSummary());
            panel.add(levelSpinner, gc);

            gc.gridx = 3;
            JLabel bankLabel = new JLabel("bank XP at");
            bankLabel.setForeground(IrlXpUi.MUTED_TEXT);
            panel.add(bankLabel, gc);

            gc.gridx = 4;
            multiplierField.setPreferredSize(new Dimension(76, 26));
            multiplierField.setToolTipText(
                "Banked XP is multiplied by this. 2 means double, 0.5 means half, 0 means nothing is banked.");
            IrlXpUi.styleField(multiplierField);
            multiplierField.addPropertyChangeListener("value", e -> refreshSummary());
            panel.add(multiplierField, gc);

            gc.gridx = 5;
            JLabel times = new JLabel("x");
            times.setForeground(IrlXpUi.ACCENT);
            times.setFont(times.getFont().deriveFont(Font.BOLD, 13f));
            panel.add(times, gc);

            // A weighted, empty trailing cell absorbs the spare width so the
            // controls stay left-aligned instead of spreading out when the
            // dialog is resized.
            gc.gridx = 6;
            gc.weightx = 1.0;
            gc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(Box.createHorizontalGlue(), gc);
        }

        void setValues(XpMultiplierTier tier)
        {
            levelSpinner.setValue(Math.min(XpMultiplierTier.MAX_LEVEL,
                Math.max(XpMultiplierTier.MIN_LEVEL, tier.getLevel())));
            multiplierField.setValue(tier.getMultiplier());
        }

        int readLevel()
        {
            return (Integer) levelSpinner.getValue();
        }

        /**
         * The typed multiplier.
         *
         * <p>Commits the editor first, because a field the user is still typing in
         * holds its last committed value, not what is on screen — saving without
         * this would silently store a stale number.</p>
         *
         * @return the value, or NaN when the text is not a usable number
         */
        double readMultiplier()
        {
            try
            {
                multiplierField.commitEdit();
            }
            catch (ParseException ex)
            {
                return Double.NaN;
            }

            Object value = multiplierField.getValue();
            if (!(value instanceof Number))
            {
                return Double.NaN;
            }

            return ((Number) value).doubleValue();
        }
    }
}
