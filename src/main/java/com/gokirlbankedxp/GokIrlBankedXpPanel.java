package com.gokirlbankedxp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.text.NumberFormatter;
import net.runelite.api.Skill;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

class GokIrlBankedXpPanel extends PluginPanel
{
    private static final Insets FIELD_INSETS = new Insets(4, 4, 4, 4);

    private final GokIrlBankedXpPlugin plugin;
    private final JComboBox<Skill> skillSelector;
    private final JFormattedTextField xpField;
    private final DefaultListModel<String> skillListModel = new DefaultListModel<>();

    @Inject
    private GokIrlBankedXpPanel(GokIrlBankedXpPlugin plugin)
    {
        this.plugin = plugin;

        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = FIELD_INSETS;
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;

        inputPanel.add(new JLabel("Skill"), gc);

        gc.gridx = 1;
        skillSelector = new JComboBox<>(plugin.getTrackableSkills());
        skillSelector.setPreferredSize(new Dimension(160, 24));
        inputPanel.add(skillSelector, gc);

        gc.gridx = 0;
        gc.gridy = 1;
        inputPanel.add(new JLabel("XP chunk"), gc);

        gc.gridx = 1;
        NumberFormat numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        numberFormat.setGroupingUsed(false);
        NumberFormatter formatter = new NumberFormatter(numberFormat);
        formatter.setAllowsInvalid(false);
        formatter.setMinimum(1L);
        formatter.setValueClass(Long.class);

        xpField = new JFormattedTextField(formatter);
        xpField.setColumns(12);
        xpField.setFocusLostBehavior(JFormattedTextField.PERSIST);
        inputPanel.add(xpField, gc);

        gc.gridx = 1;
        gc.gridy = 2;
        gc.anchor = GridBagConstraints.EAST;
        JButton addButton = new JButton("Add");
        addButton.addActionListener(e -> onAddXp());
        inputPanel.add(addButton, gc);

        add(inputPanel, BorderLayout.NORTH);

        JList<String> skillList = new JList<>(skillListModel);
        skillList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skillList.setVisibleRowCount(10);
        JScrollPane scrollPane = new JScrollPane(skillList);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Banked XP"));
        add(scrollPane, BorderLayout.CENTER);

        JLabel hintLabel = new JLabel("Use Add to bank XP chunks per skill.");
        hintLabel.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2));
        add(hintLabel, BorderLayout.SOUTH);
    }

    void updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot snapshot)
    {
        SwingUtilities.invokeLater(() -> {
            skillListModel.clear();
            if (snapshot == null || !snapshot.hasData())
            {
                skillListModel.addElement("No banked XP tracked yet.");
                return;
            }

            skillListModel.addElement(String.format(
                Locale.US,
                "Total: %s XP",
                QuantityFormatter.formatNumber(snapshot.getTotalXp())
            ));

            for (GokIrlBankedXpPlugin.BankedSkill entry : snapshot.getSkills())
            {
                String line = String.format(
                    Locale.US,
                    "%s — %s XP remaining%s",
                    entry.getDisplayName(),
                    QuantityFormatter.formatNumber(entry.getRemainingXp()),
                    entry.isBelowThreshold() ? " (low)" : ""
                );
                skillListModel.addElement(line);
            }
        });
    }

    private void onAddXp()
    {
        Skill selectedSkill = (Skill) skillSelector.getSelectedItem();
        if (selectedSkill == null)
        {
            return;
        }

        try
        {
            xpField.commitEdit();
        }
        catch (ParseException ex)
        {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a valid positive whole number.",
                "Invalid input",
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        Object value = xpField.getValue();
        if (!(value instanceof Number))
        {
            return;
        }

        long xp = ((Number) value).longValue();
        if (xp <= 0)
        {
            return;
        }

        plugin.addManualXp(selectedSkill, xp);
        xpField.setValue(null);
    }
}
