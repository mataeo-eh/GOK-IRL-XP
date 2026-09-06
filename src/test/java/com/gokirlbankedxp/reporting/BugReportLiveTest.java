package com.gokirlbankedxp.reporting;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit opt-in deployment check. Normal builds never submit real reports. */
class BugReportLiveTest
{
    @Test
    void explicitlyAuthorizedDeploymentSmokeTest() throws Exception
    {
        assumeTrue("true".equals(System.getenv("IRL_XP_LIVE_REPORT_TEST")),
            "Set IRL_XP_LIVE_REPORT_TEST=true only when authorized to submit a real test report");
        Path file = Path.of("build", "reports", "reporting-ui", "live-report.json");
        BugReportDraftStore store = new BugReportDraftStore(new Gson(), file);
        if (!Files.exists(file))
        {
            store.save(new BugReport(UUID.randomUUID().toString(), BugReport.Category.OTHER,
                "Deployment verification — not a user bug",
                "Authorized test of the native IRL XP reporting client and private inbox delivery. No player data included.",
                "Submit through the real Java client, restart the receiver, then retry the identical report.",
                "One private issue and the same acceptance reference after the restart.", null));
        }
        BugReport report = store.load();
        OkHttpClient shared = new OkHttpClient();
        try
        {
            BugReportClient client = new BugReportClient(shared, new Gson());
            CountDownLatch complete = new CountDownLatch(1);
            AtomicReference<BugReportClient.Outcome> outcome = new AtomicReference<>();
            client.submit(report, result -> { outcome.set(result); complete.countDown(); });
            assertTrue(complete.await(25, TimeUnit.SECONDS));
            assertEquals(BugReportClient.Outcome.ACCEPTED, outcome.get());
            System.out.println("Accepted deployment test reference: " + report.getSubmissionId());
        }
        finally
        {
            shared.dispatcher().executorService().shutdownNow();
            shared.connectionPool().evictAll();
        }
    }
}
