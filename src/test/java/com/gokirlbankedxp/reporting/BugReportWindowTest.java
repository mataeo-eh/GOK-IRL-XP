package com.gokirlbankedxp.reporting;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import okhttp3.Call;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Native window lifecycle checks; headless CI retains the separate panel/transport tests. */
class BugReportWindowTest
{
    @TempDir Path directory;

    @Test
    void nativeWindowReusesDraftCancelsOwnCallAndRestoresAfterControllerRecreation() throws Exception
    {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Native window requires a display");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BugReportClient client = mock(BugReportClient.class);
        when(client.isConfigured()).thenReturn(true);
        Call call = mock(Call.class);
        AtomicReference<Consumer<BugReportClient.Outcome>> completion = new AtomicReference<>();
        CountDownLatch submitted = new CountDownLatch(1);
        when(client.submit(any(), any())).thenAnswer(invocation ->
        {
            completion.set(invocation.getArgument(1));
            submitted.countDown();
            return call;
        });
        Path draftFile = directory.resolve("report-draft.json");
        BugReportDraftStore store = new BugReportDraftStore(new Gson(), draftFile);
        BugReport original = BugReportingTest.report();
        store.save(original);
        BugReportController controller = new BugReportController(client, store, executor);
        BugReportController reopened = new BugReportController(client,
            new BugReportDraftStore(new Gson(), draftFile), executor);
        try
        {
            SwingUtilities.invokeAndWait(() -> controller.open(new JPanel()));
            drain(executor);
            SwingUtilities.invokeAndWait(() ->
            {
                BugReportPanel panel = BugReportingTest.field(controller, "panel", BugReportPanel.class);
                assertEquals(original.toJson(), panel.snapshot().toJson());
                JDialog first = BugReportingTest.field(controller, "window", JDialog.class);
                controller.open(new JPanel());
                assertSame(first, BugReportingTest.field(controller, "window", JDialog.class));
                capture(first, "bug-report-form.png");
                BugReportingTest.field(panel, "review", JButton.class).doClick();
                capture(first, "bug-report-review.png");
                BugReportingTest.field(panel, "consent", JCheckBox.class).doClick();
                BugReportingTest.field(panel, "send", JButton.class).doClick();
                BugReportingTest.field(panel, "send", JButton.class).doClick();
            });
            assertTrue(submitted.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(controller::shutDown);
            verify(call).cancel();
            verify(client, times(1)).submit(any(), any());
            completion.get().accept(BugReportClient.Outcome.UNKNOWN);
            SwingUtilities.invokeAndWait(() -> reopened.open(new JPanel()));
            drain(executor);
            SwingUtilities.invokeAndWait(() ->
            {
                BugReportPanel restored = BugReportingTest.field(reopened, "panel", BugReportPanel.class);
                assertEquals(original.toJson(), restored.snapshot().toJson());
            });
            assertEquals(original.toJson(), new BugReportDraftStore(new Gson(), draftFile).load().toJson());
        }
        finally
        {
            SwingUtilities.invokeAndWait(() -> { controller.shutDown(); reopened.shutDown(); });
            executor.shutdownNow();
        }
    }

    /** Wait for known work completion and then drain the EDT, without timing guesses. */
    private static void drain(ScheduledExecutorService executor) throws Exception
    {
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {});
    }

    /** Render the actual laid-out native content for visual review without a desktop screenshot. */
    private static void capture(JDialog dialog, String name)
    {
        dialog.validate();
        BufferedImage image = new BufferedImage(dialog.getContentPane().getWidth(),
            dialog.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            dialog.getContentPane().printAll(graphics);
            Path output = Path.of("build", "reports", "reporting-ui", name);
            Files.createDirectories(output.getParent());
            ImageIO.write(image, "png", output.toFile());
        }
        catch (java.io.IOException ex)
        {
            throw new AssertionError(ex);
        }
        finally
        {
            graphics.dispose();
        }
    }
}
