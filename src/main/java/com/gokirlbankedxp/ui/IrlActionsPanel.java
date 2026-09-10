package com.gokirlbankedxp.ui;

import com.gokirlbankedxp.model.IrlAction;
import com.gokirlbankedxp.service.ActionLogManager;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Skill;
import net.runelite.client.util.QuantityFormatter;

/**
 * Action definitions, manual XP logging, and live timers, in the unified sidebar.
 *
 * <p>{@code @Singleton} for the same reason the services carry it: this panel
 * holds the visible list models, so a second unscoped instance would be a second
 * copy of the UI state that nothing ever refreshes.</p>
 */
@Singleton
public class IrlActionsPanel extends JPanel
{
    private static final String CARD_LIST = "LIST";
    private static final String CARD_EMPTY = "EMPTY";

    private final IrlActionManager actionManager;
    private final TimerManager timerManager;
    private final ActionLogManager actionLogManager;
    private final DefaultListModel<IrlAction> listModel = new DefaultListModel<>();
    private final JList<IrlAction> actionList = new JList<>(listModel);
    private final JLabel emptyLabel = new JLabel("No actions configured yet.");
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardContainer = new JPanel(cardLayout);
    private final JComboBox<IrlAction> timerActionSelector = new JComboBox<>();
    private final DefaultListModel<TimerManager.TimerSnapshot> activeTimersModel = new DefaultListModel<>();
    private final JList<TimerManager.TimerSnapshot> activeTimersList = new JList<>(activeTimersModel);
    private final Timer uiRefreshTimer;

    // "Log completed" controls: bank XP for work already finished, for actions
    // that have no timer to run (and, if the user prefers, for ones that do).
    private final JComboBox<IrlAction> logActionSelector = new JComboBox<>();
    private final JLabel logUnitsLabel = IrlXpUi.mutedLabel("UNITS IN ONE GO");
    private final JFormattedTextField logUnitsField = IrlXpUi.numberField(IrlXpUi.positiveLongFormatter());
    private final JFormattedTextField logRepetitionsField = IrlXpUi.numberField(IrlXpUi.positiveLongFormatter());
    // Four rows: the preview spells out the total and the resulting XP on
    // separate lines, a multi-skill action needs room to wrap, and a level
    // multiplier adds a line saying what the figures were before it applied.
    private final JTextArea logPreview = IrlXpUi.wrappingNote(" ", 4);
    private final JButton logButton = new JButton("BANK THIS XP");

    @Inject
    public IrlActionsPanel(IrlActionManager actionManager, TimerManager timerManager, ActionLogManager actionLogManager)
    {
        this.actionManager = actionManager;
        this.timerManager = timerManager;
        this.actionLogManager = actionLogManager;

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

        // Nested BorderLayouts rather than one vertical box: the log card keeps
        // its preferred height while the timers card absorbs any leftover space,
        // which is how the tab behaved before the log card was added.
        JPanel lowerCards = new JPanel(new BorderLayout(0, 10));
        lowerCards.setOpaque(false);
        lowerCards.add(buildLogControls(), BorderLayout.NORTH);
        lowerCards.add(buildTimerControls(), BorderLayout.CENTER);
        add(lowerCards, BorderLayout.CENTER);

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
                refreshSelectors(actions);
                return;
            }

            actions.forEach(listModel::addElement);
            cardLayout.show(cardContainer, CARD_LIST);
            refreshSelectors(actions);
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

    /**
     * Builds the "log completed work" card.
     *
     * <p>This is the manual route to banked XP: pick an action, say how many
     * units one go was worth and how many times it was done, and the plugin does
     * the arithmetic. It is what makes benchmark actions ("1 XP per pound
     * lifted") usable, since those never run on a clock.</p>
     *
     * <p>The two number fields multiply, which testers consistently failed to
     * infer from the controls alone. Every label here therefore states what it
     * wants in plain words, a worked example sits above the fields, and the
     * preview below is written as a sentence rather than a bare arrow — the
     * arithmetic is trivial once you see it, but nothing on screen used to say
     * that it was happening at all.</p>
     */
    private JPanel buildLogControls()
    {
        JPanel logPanel = IrlXpUi.card(new BorderLayout(0, 8));
        logPanel.add(IrlXpUi.sectionTitle("Log completed work"), BorderLayout.NORTH);

        IrlXpUi.styleField(logActionSelector);
        IrlXpUi.styleField(logUnitsField);
        IrlXpUi.styleField(logRepetitionsField);
        logActionSelector.setRenderer(new ActionNameCellRenderer());
        logActionSelector.addActionListener(e -> onLogSelectionChanged());

        // Recompute the preview on every keystroke. The preview reads the text
        // on screen (see parsePositive), so it can follow the document directly
        // rather than waiting for a commit — a committed value can lag behind
        // what the user has typed, and a preview that lags is a preview that
        // shows the wrong figure right before the button is pressed.
        DocumentListener previewOnEdit = new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent event)
            {
                refreshLogPreview();
            }

            @Override
            public void removeUpdate(DocumentEvent event)
            {
                refreshLogPreview();
            }

            @Override
            public void changedUpdate(DocumentEvent event)
            {
                refreshLogPreview();
            }
        };
        logUnitsField.getDocument().addDocumentListener(previewOnEdit);
        logRepetitionsField.getDocument().addDocumentListener(previewOnEdit);
        logUnitsField.setValue(1L);
        logRepetitionsField.setValue(1L);
        logUnitsField.setToolTipText("How many units one round of this action was worth, e.g. 20 push-ups per set");
        logRepetitionsField.setToolTipText("How many rounds you did, e.g. 3 sets. Leave at 1 if you did it all in one go");

        IrlXpUi.stylePrimaryButton(logButton);
        logButton.addActionListener(e -> onLogCompletion());

        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 6));
        rows.setOpaque(false);
        rows.add(IrlXpUi.wrappingNote(
            "Bank XP for work you already finished. Say how much you did in one go, "
                + "then how many times you repeated it. Did it all at once? Leave "
                + "\"times repeated\" at 1.", 4));
        rows.add(IrlXpUi.mutedLabel("WHICH ACTION"));
        rows.add(logActionSelector);
        rows.add(logUnitsLabel);
        rows.add(logUnitsField);
        rows.add(IrlXpUi.mutedLabel("TIMES REPEATED"));
        rows.add(logRepetitionsField);
        rows.add(logPreview);
        rows.add(logButton);

        logPanel.add(rows, BorderLayout.CENTER);
        return logPanel;
    }

    private JPanel buildTimerControls()
    {
        JPanel timersPanel = IrlXpUi.card(new BorderLayout(0, 8));
        timersPanel.add(IrlXpUi.sectionTitle("Active timers"), BorderLayout.NORTH);

        IrlXpUi.styleField(timerActionSelector);
        timerActionSelector.setRenderer(new ActionNameCellRenderer());
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

    /**
     * Repopulates both action drop-downs.
     *
     * <p>The timer selector lists only timed actions: an untimed one has no
     * seconds-per-unit, so a timer on it could never award anything. The log
     * selector lists everything, because recording completed units is valid for
     * a timed action too.</p>
     *
     * <p>Already on the EDT — both callers come through
     * {@link #refreshActionList()}'s invokeLater — so this must not queue another
     * round trip, or the selection restored below would be undone by the
     * listeners firing afterwards.</p>
     */
    private void refreshSelectors(List<IrlAction> actions)
    {
        UUID previouslyLogged = selectedActionId(logActionSelector);
        UUID previouslyTimed = selectedActionId(timerActionSelector);

        DefaultComboBoxModel<IrlAction> timerModel = new DefaultComboBoxModel<>();
        DefaultComboBoxModel<IrlAction> logModel = new DefaultComboBoxModel<>();
        for (IrlAction action : actions)
        {
            if (action.isTimed())
            {
                timerModel.addElement(action);
            }
            logModel.addElement(action);
        }

        // Both selectors keep the user's choice across a refresh. The list is
        // rebuilt after every create, edit and delete, and losing the chosen
        // timer action each time meant starting the wrong one after an edit.
        timerActionSelector.setModel(timerModel);
        restoreSelection(timerActionSelector, timerModel, previouslyTimed);

        logActionSelector.setModel(logModel);
        restoreSelection(logActionSelector, logModel, previouslyLogged);
        onLogSelectionChanged();
    }

    /** Keeps the user's chosen action selected across a list refresh. */
    private void restoreSelection(JComboBox<IrlAction> selector, DefaultComboBoxModel<IrlAction> model, UUID actionId)
    {
        if (model.getSize() == 0)
        {
            return;
        }

        for (int i = 0; i < model.getSize(); i++)
        {
            if (model.getElementAt(i).getId().equals(actionId))
            {
                selector.setSelectedIndex(i);
                return;
            }
        }

        selector.setSelectedIndex(0);
    }

    private UUID selectedActionId(JComboBox<IrlAction> selector)
    {
        IrlAction selected = (IrlAction) selector.getSelectedItem();
        return selected == null ? null : selected.getId();
    }

    private void onStartTimer()
    {
        IrlAction action = (IrlAction) timerActionSelector.getSelectedItem();
        if (action == null)
        {
            JOptionPane.showMessageDialog(this,
                "No timed actions to start. Tick \"Run this action on a timer\" when creating or editing an action.",
                "No action selected", JOptionPane.INFORMATION_MESSAGE);
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

    /**
     * Relabels the units field with the selected action's own unit name.
     *
     * <p>Naming the unit is what makes the row concrete — "PUSH-UPS IN ONE GO"
     * asks a question the user can answer, where "UNITS" does not.</p>
     */
    private void onLogSelectionChanged()
    {
        IrlAction action = (IrlAction) logActionSelector.getSelectedItem();
        String unit = action == null ? "UNITS" : action.getUnitName().toUpperCase(Locale.US);
        logUnitsLabel.setText(unit + " IN ONE GO");
        refreshLogPreview();
    }

    /**
     * Shows what the current entry would bank, without banking it.
     *
     * <p>The numbers come from the same service call that performs the award, so
     * the preview can never disagree with what the button does.</p>
     */
    private void refreshLogPreview()
    {
        IrlAction action = (IrlAction) logActionSelector.getSelectedItem();
        Optional<ActionLogManager.LoggedAward> award = action == null
            ? Optional.empty()
            : actionLogManager.preview(action.getId(), parsePositive(logUnitsField), parsePositive(logRepetitionsField));

        if (award.isEmpty())
        {
            logPreview.setForeground(IrlXpUi.MUTED_TEXT);
            logPreview.setText("Fill in both boxes above to see what this will bank.");
            logButton.setEnabled(false);
            return;
        }

        // Written out as two labelled lines rather than "60 push-ups -> Strength
        // +300": the first line shows the multiplication's result so the user can
        // check it, the second names it as the XP the button is about to bank.
        ActionLogManager.LoggedAward value = award.get();
        logPreview.setForeground(IrlXpUi.SUCCESS);
        logPreview.setText(String.format(
            Locale.US,
            // A literal \n, not %n: JTextArea treats \n as its line break on every
            // platform, while %n would inject a stray \r on Windows.
            "That is %s %s in total.\nBanks: %s%s",
            QuantityFormatter.formatNumber(value.getTotalUnits()),
            value.getUnitName(),
            describeAward(value),
            // Only mentioned when it changed something, so a user with no
            // multipliers set never sees a line about a feature they do not use.
            value.isMultiplied()
                ? "\n(level multipliers applied — was " + describeBase(value) + ")"
                : ""));
        logButton.setEnabled(true);
    }

    private void onLogCompletion()
    {
        IrlAction action = (IrlAction) logActionSelector.getSelectedItem();
        if (action == null)
        {
            JOptionPane.showMessageDialog(this, "Create an action first, then log the work you completed.",
                "No action selected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Optional<ActionLogManager.LoggedAward> award =
            actionLogManager.log(action.getId(), parsePositive(logUnitsField), parsePositive(logRepetitionsField));

        if (award.isEmpty())
        {
            JOptionPane.showMessageDialog(this,
                "Both boxes need a positive whole number. Enter how much you did in one go, "
                    + "and how many times you repeated it.",
                "Nothing banked", JOptionPane.WARNING_MESSAGE);
            refreshActionList();
            return;
        }

        ActionLogManager.LoggedAward value = award.get();
        JOptionPane.showMessageDialog(this,
            String.format(
                Locale.US,
                "Logged %s: %s %s in total.\nBanked %s.",
                value.getActionName(),
                QuantityFormatter.formatNumber(value.getTotalUnits()),
                value.getUnitName(),
                describeAward(value)),
            "XP banked", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Renders what is actually banked, as "Agility +6,250, Hitpoints +2,000". */
    private static String describeAward(ActionLogManager.LoggedAward award)
    {
        return describeXp(award.getAwardedXp());
    }

    /** Renders the pre-multiplier figures, shown only when a multiplier changed them. */
    private static String describeBase(ActionLogManager.LoggedAward award)
    {
        return describeXp(award.getBaseXp());
    }

    private static String describeXp(Map<Skill, Long> xp)
    {
        return xp.entrySet().stream()
            .map(entry -> entry.getKey().getName() + " +" + QuantityFormatter.formatNumber(entry.getValue()))
            .collect(Collectors.joining(", "));
    }

    /**
     * Reads a formatted field as a positive count.
     *
     * <p>Returns 0 for blank or half-typed input, which the caller treats as
     * "nothing to bank yet" rather than as an error. Reads the text on screen,
     * not the field's last committed value: after an invalid edit the committed
     * value is still the previous good number, and banking that would bank an
     * amount the user has just deleted.</p>
     */
    private static long parsePositive(JFormattedTextField field)
    {
        Number value = IrlXpUi.readNumber(field);
        return value == null ? 0L : Math.max(0L, value.longValue());
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

    /**
     * Rebuilds the timer list from the manager's current snapshots.
     *
     * <p>Runs immediately when already on the EDT. The pause, resume and stop
     * handlers call this and then read the selected snapshot back to decide
     * which buttons to enable; if the rebuild were queued instead, that read
     * would still see the snapshot from before the click, and Resume stayed
     * greyed out for up to a second after pressing Pause.</p>
     */
    private void refreshActiveTimers(boolean preserveSelection)
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            rebuildActiveTimers(preserveSelection);
        }
        else
        {
            SwingUtilities.invokeLater(() -> rebuildActiveTimers(preserveSelection));
        }
    }

    /** The EDT-only body of {@link #refreshActiveTimers}. */
    private void rebuildActiveTimers(boolean preserveSelection)
    {
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
                int skillCount = action.getSkillMappings().size();
                String unitName = action.getUnitName() == null ? "" : action.getUnitName();
                // The timed/untimed marker tells the user at a glance which
                // actions can be started as a timer and which must be logged.
                setText(String.format("%s (%s, %s) - %d skill%s",
                    action.getName(),
                    unitName,
                    action.isTimed() ? "timed" : "logged",
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

    /** Ensures the action drop-downs show a user-facing name, never a Java object identifier. */
    private static class ActionNameCellRenderer extends DefaultListCellRenderer
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
