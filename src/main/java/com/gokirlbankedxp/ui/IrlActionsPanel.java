package com.gokirlbankedxp.ui;

import com.gokirlbankedxp.model.IrlAction;
import com.gokirlbankedxp.service.IrlActionManager;
import com.gokirlbankedxp.service.TimerManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

public class IrlActionsPanel extends PluginPanel
{
    private static final String CARD_LIST = "LIST";
    private static final String CARD_EMPTY = "EMPTY";

    private final IrlActionManager actionManager;
    private final TimerManager timerManager;
    private final DefaultListModel<IrlAction> listModel = new DefaultListModel<>();
    private final JList<IrlAction> actionList = new JList<>(listModel);
    private final JLabel emptyLabel = new JLabel("No actions configured yet.");
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardContainer = new JPanel(cardLayout);
    private final JComboBox<IrlAction> timerActionSelector = new JComboBox<>();
    private final DefaultListModel<TimerManager.TimerSnapshot> activeTimersModel = new DefaultListModel<>();
    private final JList<TimerManager.TimerSnapshot> activeTimersList = new JList<>(activeTimersModel);
    private final Timer uiRefreshTimer;

    @Inject
    public IrlActionsPanel(IrlActionManager actionManager, TimerManager timerManager)
    {
        this.actionManager = actionManager;
        this.timerManager = timerManager;

        setLayout(new BorderLayout(0, 8));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton newButton = new JButton("New Action");
        JButton editButton = new JButton("Edit");
        JButton deleteButton = new JButton("Delete");

        newButton.addActionListener(e -> onNewAction());
        editButton.addActionListener(e -> onEditAction());
        deleteButton.addActionListener(e -> onDeleteAction());

        buttonRow.add(newButton);
        buttonRow.add(editButton);
        buttonRow.add(deleteButton);
        add(buttonRow, BorderLayout.NORTH);

        actionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        actionList.setCellRenderer(new ActionListCellRenderer());
        actionList.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e))
                {
                    onEditAction();
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(actionList);
        scrollPane.setPreferredSize(new Dimension(320, 220));

        emptyLabel.setHorizontalAlignment(JLabel.CENTER);

        cardContainer.add(scrollPane, CARD_LIST);
        cardContainer.add(emptyLabel, CARD_EMPTY);
        add(cardContainer, BorderLayout.CENTER);
        add(buildTimerControls(), BorderLayout.SOUTH);

        refreshActionList();
        refreshActiveTimers(false);

        uiRefreshTimer = new Timer(1000, e -> refreshActiveTimers(true));
        uiRefreshTimer.start();
    }

    public void refreshActionList()
    {
        SwingUtilities.invokeLater(() -> {
            listModel.clear();
            List<IrlAction> actions = actionManager.getAllActions();
            if (actions.isEmpty())
            {
                cardLayout.show(cardContainer, CARD_EMPTY);
                refreshTimerSelector(actions);
                return;
            }

            actions.forEach(listModel::addElement);
            cardLayout.show(cardContainer, CARD_LIST);
            refreshTimerSelector(actions);
        });
    }

    private void onNewAction()
    {
        Window window = SwingUtilities.getWindowAncestor(this);
        ActionEditorDialog dialog = new ActionEditorDialog(window, null);
        dialog.setVisible(true);
        IrlAction created = dialog.getResultAction();
        if (created != null)
        {
            try
            {
                actionManager.createAction(created);
                refreshActionList();
            }
            catch (IllegalArgumentException ex)
            {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid action", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void onEditAction()
    {
        IrlAction selected = actionList.getSelectedValue();
        if (selected == null)
        {
            return;
        }

        Window window = SwingUtilities.getWindowAncestor(this);
        ActionEditorDialog dialog = new ActionEditorDialog(window, selected);
        dialog.setVisible(true);
        IrlAction updated = dialog.getResultAction();
        if (updated != null)
        {
            boolean success = actionManager.updateAction(updated);
            if (!success)
            {
                JOptionPane.showMessageDialog(this, "Unable to update action.", "Update failed", JOptionPane.ERROR_MESSAGE);
            }
            refreshActionList();
        }
    }

    private void onDeleteAction()
    {
        IrlAction selected = actionList.getSelectedValue();
        if (selected == null)
        {
            return;
        }

        int response = JOptionPane.showConfirmDialog(
            this,
            String.format("Delete action \"%s\"?", selected.getName()),
            "Confirm delete",
            JOptionPane.YES_NO_OPTION
        );

        if (response == JOptionPane.YES_OPTION)
        {
            actionManager.deleteAction(selected.getId());
            refreshActionList();
        }
    }

    private JPanel buildTimerControls()
    {
        JPanel timersPanel = new JPanel(new BorderLayout(0, 6));
        timersPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Timers"));

        timerActionSelector.setPreferredSize(new Dimension(200, 24));
        JButton startButton = new JButton("Start Timer");
        startButton.addActionListener(e -> onStartTimer());

        JPanel startRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        startRow.add(new JLabel("Action"));
        startRow.add(timerActionSelector);
        startRow.add(startButton);

        activeTimersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        activeTimersList.setCellRenderer(new TimerListCellRenderer());
        JScrollPane timerScroll = new JScrollPane(activeTimersList);
        timerScroll.setPreferredSize(new Dimension(320, 160));

        JButton pauseButton = new JButton("Pause");
        JButton resumeButton = new JButton("Resume");
        JButton stopButton = new JButton("Stop");

        pauseButton.addActionListener(e -> {
            onPauseTimer();
            updateTimerButtons(pauseButton, resumeButton, stopButton);
        });
        resumeButton.addActionListener(e -> {
            onResumeTimer();
            updateTimerButtons(pauseButton, resumeButton, stopButton);
        });
        stopButton.addActionListener(e -> {
            onStopTimer();
            updateTimerButtons(pauseButton, resumeButton, stopButton);
        });

        activeTimersList.addListSelectionListener(e -> updateTimerButtons(pauseButton, resumeButton, stopButton));

        JPanel controlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controlRow.add(pauseButton);
        controlRow.add(resumeButton);
        controlRow.add(stopButton);

        timersPanel.add(startRow, BorderLayout.NORTH);
        timersPanel.add(timerScroll, BorderLayout.CENTER);
        timersPanel.add(controlRow, BorderLayout.SOUTH);

        updateTimerButtons(pauseButton, resumeButton, stopButton);
        return timersPanel;
    }

    private void refreshTimerSelector(List<IrlAction> actions)
    {
        SwingUtilities.invokeLater(() -> {
            DefaultComboBoxModel<IrlAction> model = new DefaultComboBoxModel<>();
            actions.forEach(model::addElement);
            timerActionSelector.setModel(model);
            if (model.getSize() > 0)
            {
                timerActionSelector.setSelectedIndex(0);
            }
        });
    }

    private void onStartTimer()
    {
        IrlAction action = (IrlAction) timerActionSelector.getSelectedItem();
        if (action == null)
        {
            JOptionPane.showMessageDialog(this, "Select an action to start a timer.", "No action selected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        timerManager.startTimer(action.getId());
        refreshActiveTimers(false);
    }

    private void onPauseTimer()
    {
        TimerManager.TimerSnapshot selected = activeTimersList.getSelectedValue();
        if (selected != null)
        {
            timerManager.pauseTimer(selected.id());
            refreshActiveTimers(true);
        }
    }

    private void onResumeTimer()
    {
        TimerManager.TimerSnapshot selected = activeTimersList.getSelectedValue();
        if (selected != null)
        {
            timerManager.resumeTimer(selected.id());
            refreshActiveTimers(true);
        }
    }

    private void onStopTimer()
    {
        TimerManager.TimerSnapshot selected = activeTimersList.getSelectedValue();
        if (selected != null)
        {
            timerManager.stopTimer(selected.id());
            refreshActiveTimers(false);
        }
    }

    private void refreshActiveTimers(boolean preserveSelection)
    {
        SwingUtilities.invokeLater(() -> {
            UUID selectedId = preserveSelection ? getSelectedTimerId() : null;

            activeTimersModel.clear();
            List<TimerManager.TimerSnapshot> timers = timerManager.getTimerSnapshots();
            for (TimerManager.TimerSnapshot snapshot : timers)
            {
                activeTimersModel.addElement(snapshot);
            }

            if (selectedId != null)
            {
                for (int i = 0; i < activeTimersModel.size(); i++)
                {
                    if (activeTimersModel.get(i).id().equals(selectedId))
                    {
                        activeTimersList.setSelectedIndex(i);
                        break;
                    }
                }
            }

            activeTimersList.repaint();
        });
    }

    private UUID getSelectedTimerId()
    {
        TimerManager.TimerSnapshot selected = activeTimersList.getSelectedValue();
        return selected == null ? null : selected.id();
    }

    private void updateTimerButtons(JButton pauseButton, JButton resumeButton, JButton stopButton)
    {
        TimerManager.TimerSnapshot selected = activeTimersList.getSelectedValue();
        boolean hasSelection = selected != null;
        pauseButton.setEnabled(hasSelection && !selected.paused());
        resumeButton.setEnabled(hasSelection && selected.paused());
        stopButton.setEnabled(hasSelection);
    }

    private static class ActionListCellRenderer extends DefaultListCellRenderer
    {
        @Override
        public java.awt.Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus)
        {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof IrlAction)
            {
                IrlAction action = (IrlAction) value;
                int skillCount = action.getSkillMappings() == null ? 0 : action.getSkillMappings().size();
                String timeUnit = action.getTimeUnit() != null ? action.getTimeUnit().getDisplayName() : "";
                setText(String.format("%s (%s) - %d skill%s",
                    action.getName(),
                    timeUnit,
                    skillCount,
                    skillCount == 1 ? "" : "s"));
            }
            return this;
        }
    }

    private static class TimerListCellRenderer extends DefaultListCellRenderer
    {
        @Override
        public java.awt.Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus)
        {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof TimerManager.TimerSnapshot)
            {
                TimerManager.TimerSnapshot snapshot = (TimerManager.TimerSnapshot) value;
                String status = snapshot.paused() ? "Paused" : "Running";
                String rates = snapshot.formatRates();
                String rateText = rates.isEmpty() ? "" : " " + rates;
                setText(String.format("%s - %s (%s)%s",
                    snapshot.actionName(),
                    snapshot.formatElapsed(),
                    status,
                    rateText));
            }
            return this;
        }
    }
}
