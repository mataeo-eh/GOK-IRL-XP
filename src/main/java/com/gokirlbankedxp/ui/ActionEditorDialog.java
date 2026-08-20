package com.gokirlbankedxp.ui;

import com.gokirlbankedxp.model.IrlAction;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.text.ParseException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
    private static final Color ERROR_COLOR = new Color(0x522B2D);

    private final JTextField nameField = new JTextField();
    private final JComboBox<String> unitCombo = new JComboBox<>();
    private final JFormattedTextField secondsPerUnitField = new JFormattedTextField(IrlXpUi.positiveLongFormatter());
    private final JFormattedTextField defaultXpField = new JFormattedTextField(IrlXpUi.positiveLongFormatter());
    private final JLabel errorLabel = new JLabel(" ");
    private final Map<Skill, SkillRow> skillRows = new LinkedHashMap<>();
    private final Map<String, Long> savedUnits;

    private IrlAction resultAction;
    private final IrlAction existingAction;

    ActionEditorDialog(Window owner, IrlAction existingAction, Map<String, Long> savedUnits)
    {
        super(owner, existingAction == null ? "New IRL Action" : "Edit IRL Action", ModalityType.APPLICATION_MODAL);
        this.existingAction = existingAction;
        this.savedUnits = new LinkedHashMap<>(savedUnits);

        // Editable input provides one deterministic path for either choosing a
        // previously saved unit or typing a new one.
        savedUnits.keySet().forEach(unitCombo::addItem);
        unitCombo.setEditable(true);
        unitCombo.setToolTipText("Type a new unit or select one you previously saved.");
        if (unitCombo.getEditor().getEditorComponent() instanceof javax.swing.JComponent)
        {
            ((javax.swing.JComponent) unitCombo.getEditor().getEditorComponent())
                .setToolTipText("Type a new unit or select one you previously saved.");
        }
        unitCombo.addActionListener(e -> populateSavedUnitDuration());

        setLayout(new BorderLayout(8, 8));
        getContentPane().setBackground(IrlXpUi.BACKGROUND);
        ((javax.swing.JComponent) getContentPane()).setBorder(
            BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(520, 640));
        setResizable(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        add(buildFormPanel(), BorderLayout.NORTH);
        add(buildSkillsPanel(), BorderLayout.CENTER);
        add(buildButtonRow(), BorderLayout.SOUTH);

        if (existingAction != null)
        {
            populateFromExisting(existingAction);
        }
        else
        {
            unitCombo.setSelectedItem(null);
            secondsPerUnitField.setValue(60L);
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
        JPanel panel = IrlXpUi.card(new BorderLayout(0, 10));
        panel.add(IrlXpUi.sectionTitle(existingAction == null ? "Create an action" : "Edit action"), BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 8));
        fields.setOpaque(false);

        IrlXpUi.styleField(nameField);
        IrlXpUi.styleField(unitCombo);
        IrlXpUi.styleField(secondsPerUnitField);
        IrlXpUi.styleField(defaultXpField);

        fields.add(fieldLabel("Action name"));
        fields.add(nameField);

        fields.add(fieldLabel("Units"));
        fields.add(unitCombo);

        fields.add(fieldLabel("Seconds per unit"));
        fields.add(secondsPerUnitField);

        fields.add(fieldLabel("Default XP per unit"));
        defaultXpField.setValue(1L);
        fields.add(defaultXpField);

        errorLabel.setForeground(IrlXpUi.DANGER);
        fields.add(new JLabel());
        fields.add(errorLabel);

        panel.add(fields, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildSkillsPanel()
    {
        JPanel skillList = new JPanel();
        skillList.setLayout(new BoxLayout(skillList, BoxLayout.Y_AXIS));
        skillList.setBackground(IrlXpUi.INPUT_BACKGROUND);

        for (Skill skill : Skill.values())
        {
            SkillRow row = new SkillRow(skill, IrlXpUi.positiveLongFormatter());
            skillRows.put(skill, row);
            skillList.add(row.panel);
        }

        skillList.add(Box.createVerticalGlue());

        JScrollPane scrollPane = new JScrollPane(skillList);
        scrollPane.setBorder(BorderFactory.createLineBorder(IrlXpUi.BORDER));
        scrollPane.getViewport().setBackground(IrlXpUi.INPUT_BACKGROUND);
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);

        JPanel wrapper = IrlXpUi.card(new BorderLayout(0, 8));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(IrlXpUi.sectionTitle("Skill rewards"), BorderLayout.WEST);
        heading.add(IrlXpUi.mutedLabel("Select one or more"), BorderLayout.EAST);
        wrapper.add(heading, BorderLayout.NORTH);
        wrapper.add(scrollPane, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildButtonRow()
    {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        row.setBackground(IrlXpUi.BACKGROUND);
        JButton cancel = new JButton("Cancel");
        JButton ok = new JButton(existingAction == null ? "CREATE ACTION" : "SAVE CHANGES");
        IrlXpUi.styleSecondaryButton(cancel);
        IrlXpUi.stylePrimaryButton(ok);

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

        Object unitValue = unitCombo.getEditor().getItem();
        String unitName = unitValue == null ? "" : unitValue.toString().trim();
        if (unitName.isEmpty())
        {
            markInvalid(unitCombo, "Unit is required.");
            return null;
        }

        long secondsPerUnit = parseLong(secondsPerUnitField);
        if (secondsPerUnit <= 0)
        {
            markInvalid(secondsPerUnitField, "Seconds per unit must be positive.");
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
            .unitName(unitName)
            .secondsPerUnit(secondsPerUnit)
            .defaultXpPerUnit(defaultXp)
            .skillMappings(mappings)
            .build();
    }

    private void populateFromExisting(IrlAction action)
    {
        nameField.setText(action.getName());
        unitCombo.setSelectedItem(action.getUnitName());
        secondsPerUnitField.setValue(action.getSecondsPerUnit());
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

    private void populateSavedUnitDuration()
    {
        Object selected = unitCombo.getSelectedItem();
        if (selected == null)
        {
            return;
        }

        String selectedName = selected.toString().trim();
        savedUnits.forEach((name, seconds) -> {
            if (name.equalsIgnoreCase(selectedName))
            {
                secondsPerUnitField.setValue(seconds);
            }
        });
    }

    private void clearValidationHighlights()
    {
        errorLabel.setText(" ");
        nameField.setBackground(IrlXpUi.INPUT_BACKGROUND);
        secondsPerUnitField.setBackground(IrlXpUi.INPUT_BACKGROUND);
        defaultXpField.setBackground(IrlXpUi.INPUT_BACKGROUND);
        skillRows.values().forEach(row -> row.xpField.setBackground(IrlXpUi.INPUT_BACKGROUND));
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

    private JLabel fieldLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(IrlXpUi.MUTED_TEXT);
        return label;
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
            IrlXpUi.styleField(xpField);
            skillCheck.setForeground(IrlXpUi.TEXT);
            skillCheck.setOpaque(false);
            useDefault.setForeground(IrlXpUi.MUTED_TEXT);
            useDefault.setOpaque(false);

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
            panel.setBackground(IrlXpUi.INPUT_BACKGROUND);
            panel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, IrlXpUi.BORDER));
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
                xpField.setBackground(IrlXpUi.INPUT_BACKGROUND);
            }
        }
    }
}
