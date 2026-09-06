package com.gokirlbankedxp.reporting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.runelite.client.RuneLiteProperties;

/**
 * Explicit, versioned report payload shared by the editor, preview and transport.
 * No game/account objects are reachable through this model. Blank drafts are
 * allowed; validateForSubmission is the boundary before any network request.
 */
public final class BugReport
{
    public static final int MAX_BODY_BYTES = 49152;
    public static final String FEATURE_REVISION = "native-reporting-v1";

    public enum Category
    {
        BANKED_XP("Banked XP"), ACTIONS("Actions and timers"),
        MULTIPLIERS("Level multipliers"), OTHER("Other");

        private final String label;

        Category(String label)
        {
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }

    private final String submissionId;
    private final Category category;
    private final String title;
    private final String description;
    private final String steps;
    private final String expectedResult;
    private final JsonObject diagnostics;

    public BugReport(String submissionId, Category category, String title,
        String description, String steps, String expectedResult, JsonObject diagnostics)
    {
        if (submissionId == null || !UUID.fromString(submissionId).toString().equals(submissionId)
            || UUID.fromString(submissionId).version() != 4 || UUID.fromString(submissionId).variant() != 2)
        {
            throw new IllegalArgumentException("Invalid report reference.");
        }
        if (category == null)
        {
            throw new IllegalArgumentException("Choose an affected area.");
        }
        checkText(title, 120, "Title");
        checkText(description, 4000, "What happened");
        checkText(steps, 4000, "Steps to reproduce");
        checkText(expectedResult, 2000, "Expected result");
        if (diagnostics != null)
        {
            requireFields(diagnostics, "featureRevision", "osName", "javaVersion", "runeLiteVersion");
            if (!FEATURE_REVISION.equals(readString(diagnostics, "featureRevision")))
            {
                throw new IllegalArgumentException("Unsupported report revision.");
            }
            for (String key : diagnostics.keySet())
            {
                checkText(readString(diagnostics, key), 100, key);
            }
        }
        this.submissionId = submissionId;
        this.category = category;
        this.title = title;
        this.description = description;
        this.steps = steps;
        this.expectedResult = expectedResult;
        this.diagnostics = diagnostics == null ? null : diagnostics.deepCopy();
    }

    public static BugReport empty()
    {
        return new BugReport(UUID.randomUUID().toString(), Category.OTHER, "", "", "", "", null);
    }

    /** Only these three documented platform/version accessors are read, on opt-in. */
    public static JsonObject collectDiagnostics()
    {
        JsonObject data = new JsonObject();
        data.addProperty("featureRevision", FEATURE_REVISION);
        data.addProperty("osName", System.getProperty("os.name", "Unknown"));
        data.addProperty("javaVersion", System.getProperty("java.version", "Unknown"));
        String version = RuneLiteProperties.getVersion();
        data.addProperty("runeLiteVersion", version == null ? "Unknown" : version);
        return data;
    }

    public void validateForSubmission()
    {
        if (title.isBlank() || description.isBlank())
        {
            throw new IllegalArgumentException("Enter a title and describe what happened.");
        }
    }

    private static void checkText(String text, int limit, String name)
    {
        if (text == null || text.length() > limit)
        {
            throw new IllegalArgumentException(name + " must be at most " + limit + " characters.");
        }
        // JSON encoders must not disagree about unpaired UTF-16 surrogates.
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c))
            {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                {
                    throw new IllegalArgumentException(name + " contains an incomplete character.");
                }
            }
            else if (Character.isLowSurrogate(c))
            {
                throw new IllegalArgumentException(name + " contains an incomplete character.");
            }
        }
    }

    /** Each wire field is added deliberately; no reflective object graph export. */
    public JsonObject toJson()
    {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("submissionId", submissionId);
        json.addProperty("category", category.name());
        json.addProperty("title", title);
        json.addProperty("description", description);
        json.addProperty("steps", steps);
        json.addProperty("expectedResult", expectedResult);
        if (diagnostics != null)
        {
            json.add("diagnostics", diagnostics.deepCopy());
        }
        return json;
    }

    /** Saved drafts use the wire schema; unexpected fields/types never enter the UI. */
    public static BugReport fromJson(JsonObject json)
    {
        Set<String> fields = new HashSet<>(Arrays.asList("schemaVersion", "submissionId",
            "category", "title", "description", "steps", "expectedResult"));
        if (json.has("diagnostics"))
        {
            fields.add("diagnostics");
        }
        if (!json.keySet().equals(fields) || !json.get("schemaVersion").isJsonPrimitive()
            || !json.getAsJsonPrimitive("schemaVersion").isNumber()
            || !"1".equals(json.get("schemaVersion").getAsString()))
        {
            throw new IllegalArgumentException("Unsupported draft format.");
        }
        JsonObject details = null;
        if (json.has("diagnostics"))
        {
            if (!json.get("diagnostics").isJsonObject())
            {
                throw new IllegalArgumentException("Invalid technical details.");
            }
            details = json.getAsJsonObject("diagnostics");
        }
        return new BugReport(readString(json, "submissionId"),
            Category.valueOf(readString(json, "category")), readString(json, "title"),
            readString(json, "description"), readString(json, "steps"),
            readString(json, "expectedResult"), details);
    }

    private static void requireFields(JsonObject json, String... fields)
    {
        if (!json.keySet().equals(new HashSet<>(Arrays.asList(fields))))
        {
            throw new IllegalArgumentException("Unexpected technical details.");
        }
    }

    static String readString(JsonObject json, String field)
    {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
        {
            throw new IllegalArgumentException("Invalid report field: " + field);
        }
        return value.getAsString();
    }

    public String getSubmissionId() { return submissionId; }
    public Category getCategory() { return category; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getSteps() { return steps; }
    public String getExpectedResult() { return expectedResult; }
    public JsonObject getDiagnostics() { return diagnostics == null ? null : diagnostics.deepCopy(); }
}
