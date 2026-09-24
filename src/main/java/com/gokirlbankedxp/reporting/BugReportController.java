package com.gokirlbankedxp.reporting;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import okhttp3.Call;

/**
 * Owns the reporting window and its asynchronous side effects. All mutable UI
 * state is confined to the EDT; local disk work uses RuneLite's shared executor.
 * Closing a window does not erase its in-memory text or claim to unsend a report.
 */
@Singleton
public final class BugReportController
{
    private final BugReportClient client;
    private final BugReportDraftStore drafts;
    private final ScheduledExecutorService executor;
    private BugReportPanel panel;
    private JDialog window;
    private Call pendingCall;
    private boolean busy;
    private long lifecycle;

    @Inject
    public BugReportController(BugReportClient client, BugReportDraftStore drafts,
        ScheduledExecutorService executor)
    {
        this.client = client;
        this.drafts = drafts;
        this.executor = executor;
    }

    public void open(Component owner)
    {
        requireEdt();
        if (window != null)
        {
            window.toFront();
            window.requestFocus();
            return;
        }
        boolean firstOpen = panel == null;
        if (firstOpen)
        {
            panel = new BugReportPanel(client.isConfigured());
            panel.onSave(() -> save(false, false));
            panel.onSend(() -> save(true, false));
            panel.onDiscard(this::discard);
        }
        window = new JDialog(SwingUtilities.getWindowAncestor(owner), "IRL XP — Report a bug",
            Dialog.ModalityType.MODELESS);
        window.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        window.setContentPane(panel);
        window.setMinimumSize(new Dimension(520, 480));
        window.setSize(620, 760);
        window.setLocationRelativeTo(owner);
        window.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent event)
            {
                requestClose();
            }
        });
        window.setVisible(true);
        if (firstOpen)
        {
            setBusy(true, "Loading saved draft…");
            executor.execute(() ->
            {
                try
                {
                    BugReport restored = drafts.load();
                    SwingUtilities.invokeLater(() ->
                    {
                        panel.load(restored);
                        setBusy(false, client.isConfigured() ? "Nothing is sent until you review and confirm."
                            : "Reporting is not available yet. You can save a local draft.");
                    });
                }
                catch (IOException ex)
                {
                    SwingUtilities.invokeLater(() -> setBusy(false,
                        "The saved draft could not be read. It has been kept on disk. Saving a new draft will replace it."));
                }
            });
        }
    }

    private void save(boolean send, boolean closeAfter)
    {
        if (busy)
        {
            return;
        }
        final BugReport report;
        try
        {
            report = panel.snapshot();
            if (send)
            {
                report.validateForSubmission();
            }
        }
        catch (IllegalArgumentException ex)
        {
            panel.status(ex.getMessage());
            return;
        }
        final long startedAt = lifecycle;
        setBusy(true, send ? "Saving recovery copy…" : "Saving draft on this computer…");
        executor.execute(() ->
        {
            try
            {
                drafts.save(report);
                SwingUtilities.invokeLater(() ->
                {
                    panel.markSaved();
                    if (send && startedAt == lifecycle && window != null)
                    {
                        submit(report);
                    }
                    else
                    {
                        setBusy(false, "Draft saved on this computer. Nothing was sent.");
                        if (closeAfter && startedAt == lifecycle)
                        {
                            closeWindow();
                        }
                    }
                });
            }
            catch (IOException ex)
            {
                SwingUtilities.invokeLater(() -> setBusy(false,
                    "Could not save the draft. Your text is still here; nothing was sent."));
            }
        });
    }

    private void submit(BugReport report)
    {
        setBusy(true, "Sending report… Closing this window cannot undo server acceptance.");
        try
        {
            pendingCall = client.submit(report,
                outcome -> SwingUtilities.invokeLater(() -> completed(report, outcome)));
        }
        catch (RuntimeException ex)
        {
            setBusy(false, "Could not start submission. Your draft is saved; try again later.");
        }
    }

    private void completed(BugReport report, BugReportClient.Outcome outcome)
    {
        pendingCall = null;
        if (outcome == BugReportClient.Outcome.ACCEPTED)
        {
            // Keep the form locked until deletion finishes, so new text cannot be
            // cleared by completion of the old report. Store also compares IDs.
            executor.execute(() ->
            {
                boolean removed;
                try
                {
                    drafts.clearIfMatches(report.getSubmissionId());
                    removed = true;
                }
                catch (IOException ex)
                {
                    removed = false;
                }
                final boolean cleared = removed;
                SwingUtilities.invokeLater(() ->
                {
                    panel.load(BugReport.empty());
                    // Shown only after the server's receipt echoed this exact ID, so
                    // "submitted successfully" is a verified claim, not an optimistic one.
                    setBusy(false, "Thanks! Your bug report was submitted successfully. Reference: "
                        + report.getSubmissionId()
                        + (cleared ? "" : ". The local recovery copy could not be deleted; use Discard draft to remove it."));
                });
            });
            return;
        }
        String message;
        switch (outcome)
        {
            case INVALID:
                message = "The service rejected the report. Your draft is saved; review the text and its length.";
                break;
            case RATE_LIMITED:
                message = "The report limit was reached. Your draft is saved; try again later.";
                break;
            case UNAVAILABLE:
                message = "Reporting is temporarily unavailable. Your draft is saved; try again later.";
                break;
            case CONFLICT:
                message = "This reference belongs to different report text. Your draft is saved. "
                    + "Review and edit it to create a new report; an earlier version may already be accepted.";
                break;
            default:
                message = "Acceptance could not be confirmed. Your draft is saved. Retry unchanged to check the same report; "
                    + "editing it creates a new report that could duplicate an accepted one.";
                break;
        }
        setBusy(false, message);
    }

    private void discard()
    {
        if (busy || JOptionPane.showConfirmDialog(window, "Delete the saved draft and clear this form?",
            "Discard bug report", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
        {
            return;
        }
        setBusy(true, "Removing draft…");
        executor.execute(() ->
        {
            try
            {
                drafts.clear();
                SwingUtilities.invokeLater(() ->
                {
                    panel.load(BugReport.empty());
                    setBusy(false, "Draft removed.");
                });
            }
            catch (IOException ex)
            {
                SwingUtilities.invokeLater(() -> setBusy(false, "Could not remove the saved draft. Your text is still here."));
            }
        });
    }

    private void requestClose()
    {
        if (busy)
        {
            if (JOptionPane.showConfirmDialog(window,
                "Work is still in progress. Closing cannot undo a report already accepted. Close anyway?",
                "Close report window", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            {
                closeWindow();
            }
            return;
        }
        if (panel.isDirty())
        {
            Object[] choices = {"Save draft", "Discard changes", "Cancel"};
            int choice = JOptionPane.showOptionDialog(window,
                "Save this report on this computer before closing?", "Unfinished report",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
            if (choice == 0)
            {
                save(false, true);
                return;
            }
            if (choice != 1)
            {
                return;
            }
            // Reload the explicitly saved draft next time; discarded unsaved text
            // must not reappear just because the singleton controller survived.
            panel = null;
        }
        closeWindow();
    }

    private void setBusy(boolean busy, String message)
    {
        this.busy = busy;
        panel.setBusy(busy);
        panel.status(message);
    }

    private void closeWindow()
    {
        lifecycle++;
        if (pendingCall != null)
        {
            pendingCall.cancel();
        }
        if (window != null)
        {
            window.dispose();
            window = null;
        }
    }

    /** Called through the existing panel shutdown path on the EDT. */
    public void shutDown()
    {
        requireEdt();
        closeWindow();
    }

    private static void requireEdt()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            throw new IllegalStateException("Report UI must be used on the Swing event thread.");
        }
    }
}
