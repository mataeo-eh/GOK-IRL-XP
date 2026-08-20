package com.gokirlbankedxp.ui;

import com.gokirlbankedxp.model.IrlAction;
import com.gokirlbankedxp.service.IrlActionManager;
import com.gokirlbankedxp.service.TimerManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
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

/** Action definitions and their live timers, embedded in the unified sidebar. */
public class IrlActionsPanel extends JPanel
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

        setLayout(new BorderLayout(0, 10));
        setBackground(IrlXpUi.BACKGROUND);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));

        JPanel actionsCard = IrlXpUi.card(new BorderLayout(0, 8));
        JPanel actionHeader = new JPanel(new BorderLayout(0, 6));
        actionHeader.setOpaque(false);
        actionHeader.add(IrlXpUi.sectionTitle("Action library"), BorderLayout.NORTH);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        buttonRow.setOpaque(false);
        JButton newButton = new JButton("NEW");
        JButton editButton = new JButton("Edit");
        JButton deleteButton = new JButton("Delete");

        IrlXpUi.stylePrimaryButton(newButton);
        IrlXpUi.styleSecondaryButton(editButton);
        IrlXpUi.styleDangerButton(deleteButton);

        newButton.addActionListener(e -> onNewAction());
        editButton.addActionListener(e -> onEditAction());
        deleteButton.addActionListener(e -> onDeleteAction());

        buttonRow.add(newButton);
        buttonRow.add(editButton);
        buttonRow.add(deleteButton);
        actionHeader.add(buttonRow, BorderLayout.SOUTH);
        actionsCard.add(actionHeader, BorderLayout.NORTH);

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
        // A minimal preferred width lets RuneLite's fixed sidebar determine the
        // real width instead of clipping nearby labels and controls.
        scrollPane.setPreferredSize(new Dimension(1, 175));
        IrlXpUi.styleList(actionList, scrollPane);
        actionList.setFixedCellHeight(42);

        emptyLabel.setHorizontalAlignment(JLabel.CENTER);
        emptyLabel.setForeground(IrlXpUi.MUTED_TEXT);
        emptyLabel.setOpaque(true);
        emptyLabel.setBackground(IrlXpUi.INPUT_BACKGROUND);

        cardContainer.add(scrollPane, CARD_LIST);
        cardContainer.add(emptyLabel, CARD_EMPTY);
        cardContainer.setBackground(IrlXpUi.INPUT_BACKGROUND);
        actionsCard.add(cardContainer, BorderLayout.CENTER);
        add(actionsCard, BorderLayout.NORTH);
        add(buildTimerControls(), BorderLayout.CENTER);

        refreshActionList();
        refreshActiveTimers(false);

        uiRefreshTimer = new Timer(1000, e -> refreshActiveTimers(true));
    }

    /** Starts repaint-only work when the plugin becomes active. */
    public void startUiUpdates()
    {
        if (!uiRefreshTimer.isRunning())
        {
            uiRefreshTimer.start();
        }
    }

    /** Prevents the Swing timer from surviving plugin shutdown/reload. */
    public void stopUiUpdates()
    {
        uiRefreshTimer.stop();
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
        ActionEditorDialog dialog = new ActionEditorDialog(window, null, actionManager.getSavedUnits());
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
        ActionEditorDialog dialog = new ActionEditorDialog(window, selected, actionManager.getSavedUnits());
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
        JPanel timersPanel = IrlXpUi.card(new BorderLayout(0, 8));
        timersPanel.add(IrlXpUi.sectionTitle("Active timers"), BorderLayout.NORTH);

        IrlXpUi.styleField(timerActionSelector);
        timerActionSelector.setRenderer(new TimerActionCellRenderer());
        JButton startButton = new JButton("START SELECTED ACTION");
        IrlXpUi.stylePrimaryButton(startButton);
        startButton.addActionListener(e -> onStartTimer());

        JPanel startRow = new JPanel(new GridLayout(0, 1, 0, 6));
        startRow.setOpaque(false);
        JLabel actionLabel = IrlXpUi.mutedLabel("ACTION");
        startRow.add(actionLabel);
        startRow.add(timerActionSelector);
        startRow.add(startButton);

        activeTimersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        activeTimersList.setCellRenderer(new TimerListCellRenderer());
        JScrollPane timerScroll = new JScrollPane(activeTimersList);
        timerScroll.setPreferredSize(new Dimension(1, 140));
        IrlXpUi.styleList(activeTimersList, timerScroll);
        activeTimersList.setFixedCellHeight(42);

        JButton pauseButton = new JButton("Pause");
        JButton resumeButton = new JButton("Resume");
        JButton stopButton = new JButton("Stop");
        IrlXpUi.styleSecondaryButton(pauseButton);
        IrlXpUi.styleSecondaryButton(resumeButton);
        IrlXpUi.styleDangerButton(stopButton);

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

        JPanel controlRow = new JPanel(new GridLayout(1, 3, 5, 0));
        controlRow.setOpaque(false);
        controlRow.add(pauseButton);
        controlRow.add(resumeButton);
        controlRow.add(stopButton);

        JPanel timerBody = new JPanel(new BorderLayout(0, 8));
        timerBody.setOpaque(false);
        timerBody.add(startRow, BorderLayout.NORTH);
        timerBody.add(timerScroll, BorderLayout.CENTER);
        timerBody.add(controlRow, BorderLayout.SOUTH);
        timersPanel.add(timerBody, BorderLayout.CENTER);

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

        if (timerManager.startTimer(action.getId()).isEmpty())
        {
            JOptionPane.showMessageDialog(this, "That action is no longer available. Refresh the Actions tab and try again.",
                "Timer not started", JOptionPane.WARNING_MESSAGE);
            refreshActionList();
            return;
        }
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
                String unitName = action.getUnitName() == null ? "" : action.getUnitName();
                setText(String.format("%s (%s) - %d skill%s",
                    action.getName(),
                    unitName,
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

    /** Ensures the timer selector shows a user-facing action name, never a Java object identifier. */
    private static class TimerActionCellRenderer extends DefaultListCellRenderer
    {
        @Override
        public java.awt.Component getListCellRendererComponent(
            JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
        {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof IrlAction)
            {
                setText(((IrlAction) value).getName());
            }
            return this;
        }
    }
}
