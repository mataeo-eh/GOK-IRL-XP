package com.gokirlbankedxp;

import com.gokirlbankedxp.ui.IrlXpUi;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import javax.inject.Inject;
import javax.swing.BorderFactory;
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
import javax.swing.JTextField;
import net.runelite.api.Skill;
import net.runelite.client.util.QuantityFormatter;

/** Focused banked-XP view embedded inside the unified IRL XP sidebar. */
class GokIrlBankedXpPanel extends JPanel
{
    private static final Insets FIELD_INSETS = new Insets(4, 0, 4, 8);

    private final GokIrlBankedXpPlugin plugin;
    private final JComboBox<Skill> skillSelector;
    private final JTextField xpField;
    private final DefaultListModel<String> skillListModel = new DefaultListModel<>();
    private final JLabel totalXpLabel = new JLabel("0 XP");

    @Inject
    GokIrlBankedXpPanel(GokIrlBankedXpPlugin plugin)
    {
        this.plugin = plugin;

        setLayout(new BorderLayout(0, 10));
        setBackground(IrlXpUi.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        JPanel inputPanel = IrlXpUi.card(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = FIELD_INSETS;
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridwidth = GridBagConstraints.REMAINDER;

        JLabel formTitle = IrlXpUi.sectionTitle("Bank a chunk");
        inputPanel.add(formTitle, gc);

        gc.gridy = 1;
        JLabel formHint = IrlXpUi.mutedLabel("Add XP earned offline.");
        inputPanel.add(formHint, gc);

        gc.gridy = 2;
        JLabel skillLabel = new JLabel("Skill");
        skillLabel.setForeground(IrlXpUi.MUTED_TEXT);
        inputPanel.add(skillLabel, gc);

        gc.gridy = 3;
        skillSelector = new JComboBox<>(plugin.getTrackableSkills());
        skillSelector.setPreferredSize(new Dimension(1, 32));
        IrlXpUi.styleField(skillSelector);
        inputPanel.add(skillSelector, gc);

        gc.gridy = 4;
        JLabel xpLabel = new JLabel("XP amount");
        xpLabel.setForeground(IrlXpUi.MUTED_TEXT);
        inputPanel.add(xpLabel, gc);

        gc.gridy = 5;
        xpField = new JTextField();
        xpField.setToolTipText("Enter a positive whole-number XP amount");
        xpField.setEditable(true);
        xpField.setFocusable(true);
        xpField.setCaretColor(IrlXpUi.TEXT);
        xpField.setPreferredSize(new Dimension(1, 36));
        IrlXpUi.styleField(xpField);
        xpField.addActionListener(e -> onAddXp());
        xpField.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent event)
            {
                // RuneLite normally transfers focus automatically, but making
                // it explicit prevents the game canvas from retaining keyboard
                // focus when this sidebar control is clicked.
                xpField.requestFocusInWindow();
            }
        });
        inputPanel.add(xpField, gc);

        gc.gridy = 6;
        gc.insets = new Insets(8, 0, 0, 0);
        JButton addButton = new JButton("BANK XP");
        IrlXpUi.stylePrimaryButton(addButton);
        addButton.addActionListener(e -> onAddXp());
        inputPanel.add(addButton, gc);

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
            public java.awt.Component getListCellRendererComponent(
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
    }

    void updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot snapshot)
    {
        SwingUtilities.invokeLater(() -> {
            skillListModel.clear();
            if (snapshot == null || !snapshot.hasData())
            {
                totalXpLabel.setText("0 XP");
                skillListModel.addElement("No banked XP yet — add your first chunk above.");
                return;
            }

            totalXpLabel.setText(QuantityFormatter.formatNumber(snapshot.getTotalXp()) + " XP");

            for (GokIrlBankedXpPlugin.BankedSkill entry : snapshot.getSkills())
            {
                String line = String.format(
                    Locale.US,
                    "%s   •   %s XP%s",
                    entry.getDisplayName(),
                    QuantityFormatter.formatNumber(entry.getRemainingXp()),
                    entry.isBelowThreshold() ? "   LOW" : ""
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
}
