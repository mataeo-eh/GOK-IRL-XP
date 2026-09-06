package com.gokirlbankedxp.reporting;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises real OkHttp asynchronous calls with an interceptor, never an external inbox. */
class BugReportClientTest
{
    private static final String ENDPOINT = "https://reports.example.invalid/v1/reports";

    @Test
    void idleClientDoesNotSendAndAcceptedPayloadMatchesReviewedModel() throws Exception
    {
        BugReport report = BugReportingTest.report();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> sent = new AtomicReference<>();
        ResponseBody responseBody = spy(ResponseBody.create(MediaType.parse("application/json"), receipt(report)));
        OkHttpClient shared = new OkHttpClient.Builder().addInterceptor(chain ->
        {
            calls.incrementAndGet();
            Buffer bytes = new Buffer();
            chain.request().body().writeTo(bytes);
            sent.set(bytes.readUtf8());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(202).message("Accepted").body(responseBody).build();
        }).build();
        try
        {
            BugReportClient client = new BugReportClient(shared, new Gson(), ENDPOINT);
            assertEquals(0, calls.get());
            assertEquals(BugReportClient.Outcome.ACCEPTED, submit(client, report));
            assertEquals(report.toJson(), new Gson().fromJson(sent.get(), com.google.gson.JsonObject.class));
            assertEquals(1, calls.get());
            verify(responseBody).close();
        }
        finally
        {
            shared.dispatcher().executorService().shutdownNow();
            shared.connectionPool().evictAll();
        }
    }

    @Test
    void statusVariantsMalformedReceiptsAndRedirectsNeverClaimSuccess() throws Exception
    {
        BugReport report = BugReportingTest.report();
        assertOutcome(report, 400, "{}", BugReportClient.Outcome.INVALID);
        assertOutcome(report, 413, "{}", BugReportClient.Outcome.INVALID);
        assertOutcome(report, 409, "{}", BugReportClient.Outcome.CONFLICT);
        assertOutcome(report, 429, "{}", BugReportClient.Outcome.RATE_LIMITED);
        assertOutcome(report, 503, "{}", BugReportClient.Outcome.UNAVAILABLE);
        assertOutcome(report, 500, "{}", BugReportClient.Outcome.UNKNOWN);
        assertOutcome(report, 302, "", BugReportClient.Outcome.UNKNOWN);
        assertOutcome(report, 202, "{}", BugReportClient.Outcome.UNKNOWN);
        assertOutcome(report, 202, "{status:accepted}", BugReportClient.Outcome.UNKNOWN);
        assertOutcome(report, 202, "[]", BugReportClient.Outcome.UNKNOWN);
        assertOutcome(report, 202, receipt(BugReportingTest.report()), BugReportClient.Outcome.UNKNOWN);
        assertOutcome(report, 202, "x".repeat(4097), BugReportClient.Outcome.UNKNOWN);
        assertOutcome(report, 202, receipt(report) + "{}", BugReportClient.Outcome.UNKNOWN);
        assertOutcome(report, 202, receipt(report).replace("\"status\":", "\"status\":\"accepted\",\"status\":"),
            BugReportClient.Outcome.UNKNOWN);
    }

    @Test
    void failureIsUnknownAndUnsafeEndpointCannotBeConfigured() throws Exception
    {
        OkHttpClient shared = new OkHttpClient.Builder().addInterceptor(chain ->
        {
            throw new IOException("Simulated timeout after possible acceptance");
        }).build();
        try
        {
            assertThrows(IllegalArgumentException.class,
                () -> new BugReportClient(shared, new Gson(), "http://reports.example.invalid/v1/reports"));
            assertThrows(IllegalArgumentException.class,
                () -> new BugReportClient(shared, new Gson(), "https://user:pass@reports.example.invalid/v1/reports"));
            BugReportClient disabled = new BugReportClient(shared, new Gson(), "");
            assertFalse(disabled.isConfigured());
            assertThrows(IllegalStateException.class, () -> disabled.submit(BugReportingTest.report(), ignored -> {}));
            assertEquals(BugReportClient.Outcome.UNKNOWN,
                submit(new BugReportClient(shared, new Gson(), ENDPOINT), BugReportingTest.report()));
        }
        finally
        {
            shared.dispatcher().executorService().shutdownNow();
            shared.connectionPool().evictAll();
        }
    }

    private static void assertOutcome(BugReport report, int code, String body,
        BugReportClient.Outcome expected) throws Exception
    {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient shared = new OkHttpClient.Builder().addInterceptor(chain ->
        {
            requests.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Test response").header("Location", "http://reports.example.invalid/leak")
                .body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
        }).build();
        try
        {
            assertEquals(expected, submit(new BugReportClient(shared, new Gson(), ENDPOINT), report));
            assertEquals(1, requests.get());
        }
        finally
        {
            shared.dispatcher().executorService().shutdownNow();
            shared.connectionPool().evictAll();
        }
    }

    private static BugReportClient.Outcome submit(BugReportClient client, BugReport report) throws Exception
    {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<BugReportClient.Outcome> result = new AtomicReference<>();
        Call call = client.submit(report, outcome ->
        {
            result.set(outcome);
            finished.countDown();
        });
        if (!finished.await(5, TimeUnit.SECONDS))
        {
            call.cancel();
            fail("Reporting callback timed out");
        }
        return result.get();
    }

    private static String receipt(BugReport report)
    {
        return "{\"submissionId\":\"" + report.getSubmissionId() + "\",\"reference\":\""
            + report.getSubmissionId() + "\",\"status\":\"accepted\"}";
    }
}
