package com.gokirlbankedxp.ui;

import com.gokirlbankedxp.model.IrlAction;
import com.gokirlbankedxp.model.TimeUnit;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.NumberFormatter;
import net.runelite.api.Skill;

class ActionEditorDialog extends JDialog
{
    private static final Color ERROR_COLOR = new Color(0xFFCCCC);

    private final JTextField nameField = new JTextField();
    private final JComboBox<TimeUnit> timeUnitCombo = new JComboBox<>(TimeUnit.values());
    private final JFormattedTextField defaultXpField = new JFormattedTextField(createPositiveLongFormatter());
    private final JLabel errorLabel = new JLabel(" ");
    private final Map<Skill, SkillRow> skillRows = new LinkedHashMap<>();

    private IrlAction resultAction;
    private final IrlAction existingAction;

    ActionEditorDialog(Window owner, IrlAction existingAction)
    {
        super(owner, existingAction == null ? "New IRL Action" : "Edit IRL Action", ModalityType.APPLICATION_MODAL);
        this.existingAction = existingAction;

        setLayout(new BorderLayout(8, 8));
        setPreferredSize(new Dimension(560, 640));
        setResizable(true);

        add(buildFormPanel(), BorderLayout.NORTH);
        add(buildSkillsPanel(), BorderLayout.CENTER);
        add(buildButtonRow(), BorderLayout.SOUTH);

        if (existingAction != null)
        {
            populateFromExisting(existingAction);
        }
        else
        {
            timeUnitCombo.setSelectedItem(TimeUnit.MINUTES);
            defaultXpField.setValue(50L);
        }

        defaultXpField.addPropertyChangeListener("value", evt -> propagateDefaultRate());

        pack();
        setLocationRelativeTo(owner);
    }

    IrlAction getResultAction()
    {
        return resultAction;
    }

    private JPanel buildFormPanel()
    {
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        panel.add(new JLabel("Action name"));
        panel.add(nameField);

        panel.add(new JLabel("Time unit"));
        panel.add(timeUnitCombo);

        panel.add(new JLabel("Default XP per unit"));
        defaultXpField.setValue(1L);
        panel.add(defaultXpField);

        errorLabel.setForeground(Color.RED);
        panel.add(new JLabel());
        panel.add(errorLabel);

        return panel;
    }

    private JPanel buildSkillsPanel()
    {
        JPanel skillList = new JPanel();
        skillList.setLayout(new BoxLayout(skillList, BoxLayout.Y_AXIS));

        for (Skill skill : Skill.values())
        {
            SkillRow row = new SkillRow(skill, createPositiveLongFormatter());
            skillRows.put(skill, row);
            skillList.add(row.panel);
        }

        skillList.add(Box.createVerticalGlue());

        JScrollPane scrollPane = new JScrollPane(skillList);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Skill mappings"));
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scrollPane, BorderLayout.CENTER);
        wrapper.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        return wrapper;
    }

    private JPanel buildButtonRow()
    {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("Cancel");
        JButton ok = new JButton("OK");

        cancel.addActionListener(e -> {
            resultAction = null;
            dispose();
        });

        ok.addActionListener(e -> {
            IrlAction candidate = buildActionFromForm();
            if (candidate != null)
            {
                resultAction = candidate;
                dispose();
            }
        });

        row.add(cancel);
        row.add(ok);
        return row;
    }

    private IrlAction buildActionFromForm()
    {
        clearValidationHighlights();

        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty())
        {
            markInvalid(nameField, "Name is required.");
            return null;
        }

        long defaultXp = parseLong(defaultXpField);
        if (defaultXp <= 0)
        {
            markInvalid(defaultXpField, "Default XP must be a positive number.");
            return null;
        }

        TimeUnit timeUnit = (TimeUnit) timeUnitCombo.getSelectedItem();
        if (timeUnit == null)
        {
            markInvalid(timeUnitCombo, "Time unit is required.");
            return null;
        }

        Map<Skill, Long> mappings = new EnumMap<>(Skill.class);
        for (Map.Entry<Skill, SkillRow> entry : skillRows.entrySet())
        {
            SkillRow row = entry.getValue();
            if (!row.skillCheck.isSelected())
            {
                continue;
            }

            long rate = row.resolveXp(defaultXp);
            if (rate <= 0)
            {
                markInvalid(row.xpField, "XP rate must be positive.");
                return null;
            }

            mappings.put(entry.getKey(), rate);
        }

        if (mappings.isEmpty())
        {
            showError("Select at least one skill and provide an XP rate.");
            return null;
        }

        UUID id = existingAction == null ? null : existingAction.getId();
        return IrlAction.builder()
            .id(id)
            .name(name)
            .timeUnit(timeUnit)
            .defaultXpPerUnit(defaultXp)
            .skillMappings(mappings)
            .build();
    }

    private void populateFromExisting(IrlAction action)
    {
        nameField.setText(action.getName());
        timeUnitCombo.setSelectedItem(action.getTimeUnit());
        defaultXpField.setValue(action.getDefaultXpPerUnit());

        Map<Skill, Long> mappings = action.getSkillMappings() == null
            ? Map.of()
            : action.getSkillMappings();

        for (Map.Entry<Skill, SkillRow> entry : skillRows.entrySet())
        {
            Skill skill = entry.getKey();
            SkillRow row = entry.getValue();

            if (mappings.containsKey(skill))
            {
                long rate = mappings.get(skill);
                boolean useDefault = rate == action.getDefaultXpPerUnit();
                row.skillCheck.setSelected(true);
                row.useDefault.setSelected(useDefault);
                row.xpField.setValue(useDefault ? action.getDefaultXpPerUnit() : rate);
            }
            row.refreshEnabledState();
        }
    }

    private void propagateDefaultRate()
    {
        long defaultXp = parseLong(defaultXpField);
        if (defaultXp <= 0)
        {
            return;
        }

        skillRows.values().forEach(row -> row.syncWithDefault(defaultXp));
    }

    private void clearValidationHighlights()
    {
        errorLabel.setText(" ");
        nameField.setBackground(Color.WHITE);
        defaultXpField.setBackground(Color.WHITE);
        skillRows.values().forEach(row -> row.xpField.setBackground(Color.WHITE));
    }

    private void markInvalid(javax.swing.JComponent component, String message)
    {
        component.setBackground(ERROR_COLOR);
        showError(message);
        component.requestFocusInWindow();
    }

    private void showError(String message)
    {
        errorLabel.setText(message);
    }

    private NumberFormatter createPositiveLongFormatter()
    {
        NumberFormat numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        numberFormat.setGroupingUsed(false);
        NumberFormatter formatter = new NumberFormatter(numberFormat);
        formatter.setAllowsInvalid(false);
        formatter.setMinimum(1L);
        formatter.setValueClass(Long.class);
        return formatter;
    }

    private long parseLong(JFormattedTextField field)
    {
        try
        {
            field.commitEdit();
        }
        catch (ParseException ignored)
        {
            // handled below
        }

        Object value = field.getValue();
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private class SkillRow
    {
        private final JCheckBox skillCheck;
        private final JFormattedTextField xpField;
        private final JCheckBox useDefault;
        private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));

        SkillRow(Skill skill, NumberFormatter formatter)
        {
            this.skillCheck = new JCheckBox(skill.getName());
            this.xpField = new JFormattedTextField(formatter);
            this.useDefault = new JCheckBox("Use default");

            xpField.setColumns(7);
            useDefault.setSelected(true);

            skillCheck.addActionListener(e -> refreshEnabledState());
            useDefault.addActionListener(e -> {
                if (useDefault.isSelected())
                {
                    xpField.setValue(defaultXpField.getValue());
                }
                refreshEnabledState();
            });

            panel.add(skillCheck);
            panel.add(new JLabel("XP:"));
            panel.add(xpField);
            panel.add(useDefault);
            panel.setAlignmentX(LEFT_ALIGNMENT);
            refreshEnabledState();
        }

        long resolveXp(long defaultXp)
        {
            if (!skillCheck.isSelected())
            {
                return 0L;
            }

            if (useDefault.isSelected())
            {
                return defaultXp;
            }

            return parseLong(xpField);
        }

        void syncWithDefault(long defaultXp)
        {
            if (useDefault.isSelected() && skillCheck.isSelected())
            {
                xpField.setValue(defaultXp);
            }
        }

        void refreshEnabledState()
        {
            boolean selected = skillCheck.isSelected();
            useDefault.setEnabled(selected);
            xpField.setEnabled(selected && !useDefault.isSelected());
            if (!selected)
            {
                xpField.setBackground(Color.WHITE);
            }
        }
    }
}
