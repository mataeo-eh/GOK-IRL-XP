package com.gokirlbankedxp.reporting;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the data crossing the privacy boundary and the actual save/reopen workflow. */
class BugReportingTest
{
    @TempDir Path directory;

    static BugReport report()
    {
        return new BugReport(UUID.randomUUID().toString(), BugReport.Category.ACTIONS,
            "Timer stopped", "It stopped after a pause.", "Start, pause, resume.", "Keep counting.", null);
    }

    @Test
    void payloadContainsOnlyAllowedFieldsAndRejectsUnexpectedSavedData()
    {
        JsonObject json = report().toJson();
        assertEquals(Set.of("schemaVersion", "submissionId", "category", "title", "description",
            "steps", "expectedResult"), json.keySet());
        assertEquals(json, BugReport.fromJson(json).toJson());
        json.addProperty("accountName", "never upload account identifiers");
        assertThrows(IllegalArgumentException.class, () -> BugReport.fromJson(json));
        assertThrows(IllegalArgumentException.class, () -> new BugReport(
            "00000000-0000-0000-0000-000000000000", BugReport.Category.OTHER, "", "", "", "", null));
    }

    @Test
    void validationAllowsUnfinishedDraftsButRequiresUsefulSubmissionAndBoundedUnicode()
    {
        BugReport empty = BugReport.empty();
        assertThrows(IllegalArgumentException.class, empty::validateForSubmission);
        assertThrows(IllegalArgumentException.class, () -> new BugReport(empty.getSubmissionId(),
            BugReport.Category.OTHER, "x".repeat(121), "description", "", "", null));
        assertThrows(IllegalArgumentException.class, () -> new BugReport(empty.getSubmissionId(),
            BugReport.Category.OTHER, "title", "\uD800", "", "", null));
        BugReport unicode = new BugReport(empty.getSubmissionId(), BugReport.Category.OTHER,
            "Unicode 🎮", "Text with line breaks\nand emojis 🙂", "", "", null);
        unicode.validateForSubmission();
        assertEquals(unicode.toJson(), BugReport.fromJson(unicode.toJson()).toJson());
    }

    @Test
    void savedDraftAndRetryIdSurviveStoreRecreationAndOldReceiptCannotEraseNewDraft() throws IOException
    {
        Path file = directory.resolve("report-draft.json");
        BugReport first = report();
        new BugReportDraftStore(new Gson(), file).save(first);
        BugReportDraftStore reopened = new BugReportDraftStore(new Gson(), file);
        assertEquals(first.toJson(), reopened.load().toJson());
        BugReport newer = report();
        reopened.save(newer);
        new BugReportDraftStore(new Gson(), file).clearIfMatches(first.getSubmissionId());
        assertEquals(newer.toJson(), reopened.load().toJson());
        reopened.clearIfMatches(newer.getSubmissionId());
        assertFalse(Files.exists(file));
        assertEquals("", new BugReportDraftStore(new Gson(), file).load().getTitle());
    }

    @Test
    void corruptAndOversizeDraftsRemainOnDiskForRecovery() throws IOException
    {
        Path file = directory.resolve("report-draft.json");
        BugReportDraftStore store = new BugReportDraftStore(new Gson(), file);
        Files.writeString(file, "not json");
        assertThrows(IOException.class, store::load);
        assertEquals("not json", Files.readString(file));
        Files.writeString(file, "x".repeat(BugReport.MAX_BODY_BYTES + 1));
        assertThrows(IOException.class, store::load);
        assertTrue(Files.exists(file));
    }

    @Test
    void formReviewsSamePayloadRequiresConsentAndPreservesRetryId() throws Exception
    {
        SwingUtilities.invokeAndWait(() ->
        {
            BugReportPanel panel = new BugReportPanel(true);
            BugReport draft = report();
            panel.load(draft);
            assertEquals(draft.toJson(), panel.snapshot().toJson());
            JButton send = field(panel, "send", JButton.class);
            field(panel, "review", JButton.class).doClick();
            assertFalse(send.isEnabled());
            String shown = field(panel, "preview", JTextArea.class).getText();
            assertTrue(shown.contains(draft.getDescription()));
            assertTrue(shown.contains(draft.getSubmissionId()));
            assertFalse(shown.contains("Technical details"));
            field(panel, "consent", JCheckBox.class).doClick();
            assertTrue(send.isEnabled());
            panel.setBusy(true);
            assertFalse(send.isEnabled());
            assertFalse(field(panel, "edit", JButton.class).isEnabled());
            panel.setBusy(false);
            assertEquals(draft.getSubmissionId(), panel.snapshot().getSubmissionId());
            field(panel, "edit", JButton.class).doClick();
            field(panel, "title", JTextField.class).setText("A different report");
            String changedId = panel.snapshot().getSubmissionId();
            assertNotEquals(draft.getSubmissionId(), changedId);
            assertEquals(changedId, panel.snapshot().getSubmissionId());
            assertTrue(panel.isDirty());
        });
    }

    @Test
    void diagnosticsOptOutRemovesAllTechnicalFieldsAndUnconfiguredFormCannotSend() throws Exception
    {
        SwingUtilities.invokeAndWait(() ->
        {
            BugReportPanel panel = new BugReportPanel(false);
            panel.load(report());
            JCheckBox details = field(panel, "details", JCheckBox.class);
            details.doClick();
            assertEquals(Set.of("featureRevision", "osName", "javaVersion", "runeLiteVersion"),
                panel.snapshot().getDiagnostics().keySet());
            details.doClick();
            assertFalse(panel.snapshot().toJson().has("diagnostics"));
            field(panel, "review", JButton.class).doClick();
            field(panel, "consent", JCheckBox.class).doClick();
            assertFalse(field(panel, "send", JButton.class).isEnabled());
        });
    }

    /** Test-only typed reflection exercises actual controls without widening production APIs. */
    static <T> T field(Object target, String name, Class<T> type)
    {
        try
        {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        }
        catch (ReflectiveOperationException ex)
        {
            throw new AssertionError(ex);
        }
    }
}
