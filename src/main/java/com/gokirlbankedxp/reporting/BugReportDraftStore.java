package com.gokirlbankedxp.reporting;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

/** Local, explicitly saved report text; deliberately outside synced RuneLite config. */
@Singleton
public final class BugReportDraftStore
{
    private final Gson gson;
    private final Path file;

    @Inject
    public BugReportDraftStore(Gson gson)
    {
        this(gson, RuneLite.RUNELITE_DIR.toPath().resolve("gok-irl-xp/report-draft.json"));
    }

    public BugReportDraftStore(Gson gson, Path file)
    {
        this.gson = gson;
        this.file = file;
    }

    public synchronized BugReport load() throws IOException
    {
        if (!Files.exists(file))
        {
            return BugReport.empty();
        }
        try (InputStream input = Files.newInputStream(file))
        {
            byte[] bytes = input.readNBytes(BugReport.MAX_BODY_BYTES + 1);
            if (bytes.length > BugReport.MAX_BODY_BYTES)
            {
                throw new IOException("Saved draft is too large.");
            }
            try
            {
                return BugReport.fromJson(gson.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class));
            }
            catch (RuntimeException ex)
            {
                // Do not log free text, or replace an unreadable user's draft.
                throw new IOException("The saved draft could not be read.");
            }
        }
    }

    public synchronized void save(BugReport report) throws IOException
    {
        byte[] bytes = gson.toJson(report.toJson()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > BugReport.MAX_BODY_BYTES)
        {
            throw new IOException("Draft exceeds the report size limit.");
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "report-draft-", ".tmp");
        try
        {
            Files.write(temporary, bytes);
            // If atomic replacement is unavailable, preserve the previous draft
            // and surface failure instead of silently weakening recovery guarantees.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized void clear() throws IOException
    {
        Files.deleteIfExists(file);
    }

    /** An old request must never delete a newer draft saved by another controller. */
    public synchronized void clearIfMatches(String submissionId) throws IOException
    {
        if (Files.exists(file) && load().getSubmissionId().equals(submissionId))
        {
            clear();
        }
    }
}
