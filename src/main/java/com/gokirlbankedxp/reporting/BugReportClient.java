package com.gokirlbankedxp.reporting;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** User-initiated HTTPS transport. It never starts requests during construction/startup. */
@Singleton
public final class BugReportClient
{
    // Public receiver URL only. Delivery credentials exist exclusively on Railway.
    private static final String ENDPOINT = "https://receiver-production-5b81.up.railway.app/v1/reports";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final Gson gson;
    private final HttpUrl endpoint;

    public enum Outcome
    {
        ACCEPTED, INVALID, CONFLICT, RATE_LIMITED, UNAVAILABLE, UNKNOWN
    }

    @Inject
    public BugReportClient(OkHttpClient client, Gson gson)
    {
        this(client, gson, ENDPOINT);
    }

    /** Explicit endpoint injection permits isolated HTTPS transport tests. */
    public BugReportClient(OkHttpClient client, Gson gson, String endpoint)
    {
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build();
        this.gson = gson;
        this.endpoint = endpoint.isEmpty() ? null : HttpUrl.parse(endpoint);
        if (!endpoint.isEmpty() && (this.endpoint == null || !this.endpoint.isHttps()
            || !this.endpoint.username().isEmpty() || !this.endpoint.password().isEmpty()
            || this.endpoint.fragment() != null || this.endpoint.query() != null))
        {
            throw new IllegalArgumentException("Reporting requires a fixed HTTPS endpoint without credentials.");
        }
    }

    public boolean isConfigured()
    {
        return endpoint != null;
    }

    public Call submit(BugReport report, Consumer<Outcome> completion)
    {
        if (!isConfigured())
        {
            throw new IllegalStateException("Reporting is not available yet.");
        }
        report.validateForSubmission();
        byte[] payload = gson.toJson(report.toJson()).getBytes(StandardCharsets.UTF_8);
        if (payload.length > BugReport.MAX_BODY_BYTES)
        {
            throw new IllegalArgumentException("Report is too large. Shorten the text and try again.");
        }
        Request request = new Request.Builder().url(endpoint)
            .header("Accept", "application/json")
            .post(RequestBody.create(JSON, payload)).build();
        Call call = client.newCall(request);
        call.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call ignored, IOException error)
            {
                // A timeout/cancellation cannot prove the server did not accept it.
                completion.accept(Outcome.UNKNOWN);
            }

            @Override
            public void onResponse(Call ignored, Response response)
            {
                Outcome outcome;
                try (Response closed = response)
                {
                    outcome = readOutcome(report, response);
                }
                catch (IOException | RuntimeException ex)
                {
                    outcome = Outcome.UNKNOWN;
                }
                completion.accept(outcome);
            }
        });
        return call;
    }

    private Outcome readOutcome(BugReport report, Response response) throws IOException
    {
        switch (response.code())
        {
            case 400:
            case 413:
            case 415:
                return Outcome.INVALID;
            case 429:
                return Outcome.RATE_LIMITED;
            case 409:
                return Outcome.CONFLICT;
            case 503:
                return Outcome.UNAVAILABLE;
            case 202:
                break;
            default:
                return Outcome.UNKNOWN;
        }
        ResponseBody body = response.body();
        if (body == null)
        {
            return Outcome.UNKNOWN;
        }
        byte[] bytes = body.byteStream().readNBytes(4097);
        if (bytes.length > 4096)
        {
            return Outcome.UNKNOWN;
        }
        JsonObject receipt = new JsonObject();
        try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8))))
        {
            // Streaming strict parsing also rejects duplicate fields and avoids
            // constructing arbitrary nested trees from an untrusted response.
            reader.setLenient(false);
            Set<String> seen = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext())
            {
                String field = reader.nextName();
                if (!seen.add(field))
                {
                    return Outcome.UNKNOWN;
                }
                if (field.equals("submissionId") || field.equals("reference") || field.equals("status"))
                {
                    if (reader.peek() != JsonToken.STRING)
                    {
                        return Outcome.UNKNOWN;
                    }
                    receipt.addProperty(field, reader.nextString());
                }
                else
                {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT)
            {
                return Outcome.UNKNOWN;
            }
        }
        // Only these typed fields determine acceptance. Extra response fields are
        // intentionally ignored and never shown, logged, or persisted.
        return report.getSubmissionId().equals(BugReport.readString(receipt, "submissionId"))
            && report.getSubmissionId().equals(BugReport.readString(receipt, "reference"))
            && "accepted".equals(BugReport.readString(receipt, "status"))
            ? Outcome.ACCEPTED : Outcome.UNKNOWN;
    }
}
