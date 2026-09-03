package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Skill;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable storage for banked XP, and the only place balances are written to or
 * read from.
 *
 * <p>Banked XP is user data, not a setting, but until this store existed it lived
 * solely in RuneLite's profile config. That config is not a safe home on its
 * own: RuneLite uploads it to the user's account every few minutes, and if one
 * upload fails it marks the profile as out of date and, on the next launch,
 * replaces the local file with the server's copy. Everything banked since the
 * last successful upload silently rolls back. That is what a user hit after a
 * five-hour session — every deposit gone, one stale balance left.</p>
 *
 * <p>So every save is written to two places:</p>
 * <ul>
 *   <li><b>RuneLite config</b> ({@value #XP_KEY} and {@value #SAVED_AT_KEY} in the
 *       plugin's group). Kept because it is what existing users already have, and
 *       because it follows a synced profile between machines.</li>
 *   <li><b>A plugin-owned file</b> under {@code ~/.runelite/gok-irl-xp/}, one per
 *       RuneLite config profile. RuneLite never touches it, so nothing in its sync
 *       or profile machinery can roll it back.</li>
 * </ul>
 *
 * <p>Both copies carry the time they were saved. {@link #load()} reads both and
 * keeps the newest, then rewrites the other so they agree again. If config was
 * rolled back, the file is newer and wins. If the config was synced from another
 * machine where the user banked more recently, the config is newer and wins. A
 * copy that does not exist always loses to one that does.</p>
 *
 * <p>Every method here runs on whichever thread the plugin mutates balances on —
 * the Swing thread for deposits, the timer executor for accrual, and the client
 * thread for XP drops. The file write is a few hundred bytes with no fsync, and
 * is done atomically (temp file plus rename) so a crash mid-write leaves the old
 * file, never a half-written one.</p>
 *
 * <p>{@code @Singleton}: the plugin is the only consumer today, but the last
 * written config value is what lets it tell its own {@code ConfigChanged} echo
 * from a real external change, and that memory has to be shared with whoever
 * else might write in future.</p>
 */
@Singleton
public class BankedXpStore
{
    private static final Logger log = LoggerFactory.getLogger(BankedXpStore.class);

    /** Config key holding the balances, as {@code SKILL:xp} entries joined by commas. */
    public static final String XP_KEY = "storedSkillXp";
    /** Config key holding the epoch-millisecond time {@link #XP_KEY} was last written. */
    public static final String SAVED_AT_KEY = "storedSkillXpSavedAt";

    private static final String ENTRY_DELIMITER = ",";
    private static final String VALUE_DELIMITER = ":";
    private static final String DATA_DIR_NAME = "gok-irl-xp";
    private static final String FILE_PREFIX = "banked-xp";
    private static final String FILE_SUFFIX = ".json";

    /** Save time reported for a copy that does not exist, so it loses to any real copy. */
    static final long ABSENT = -1L;
    /**
     * Save time reported for a config copy written before timestamps existed. It
     * beats nothing but an absent copy, so a user's first launch after this change
     * migrates their existing config balances into the file rather than losing them.
     */
    static final long LEGACY = 0L;

    private final ConfigManager configManager;
    private final Gson gson;
    private final File dataDir;

    /**
     * The exact string most recently written to {@link #XP_KEY}. RuneLite posts a
     * {@code ConfigChanged} synchronously for every write, including this store's
     * own; the plugin compares the event's new value against this to ignore the
     * echo. Volatile because the write and the event can be on different threads
     * from the one that later asks.
     */
    private volatile String lastWrittenConfigValue;

    /** Save time of the last copy written or loaded; keeps save times strictly increasing. */
    private long lastSavedAt = ABSENT;

    @Inject
    public BankedXpStore(ConfigManager configManager, Gson gson)
    {
        this(configManager, gson, new File(RuneLite.RUNELITE_DIR, DATA_DIR_NAME));
    }

    /**
     * @param dataDir where the per-profile JSON files live. Production uses
     *     {@code ~/.runelite/gok-irl-xp}; tests point this at a temporary directory.
     */
    public BankedXpStore(ConfigManager configManager, Gson gson, File dataDir)
    {
        this.configManager = configManager;
        this.gson = gson;
        this.dataDir = dataDir;
    }

    /**
     * Reads both copies, keeps the newest, and brings the other up to date.
     *
     * @return the balances to use, only ever holding skills with a non-zero
     *     amount. A negative amount is XP the skill owes after in-game gains
     *     outran the bank; the plugin nets later deposits against it.
     */
    public synchronized Map<Skill, Long> load()
    {
        Copy fromConfig = readConfig();
        Copy fromFile = readFile();

        // Strictly newer wins. On a tie — normally both copies of the same save —
        // the file is preferred because RuneLite cannot have altered it.
        Copy winner = fromConfig.savedAt > fromFile.savedAt ? fromConfig : fromFile;
        if (winner.savedAt == ABSENT)
        {
            log.debug("No banked XP saved anywhere yet");
        }
        else if (winner == fromFile && fromConfig.savedAt < fromFile.savedAt)
        {
            log.info("Banked XP config copy ({}) is older than the local file ({}); restoring from file",
                describe(fromConfig), describe(fromFile));
        }

        write(winner);
        // Built empty then filled: EnumMap's copy constructor rejects an empty
        // plain map, which is exactly what an absent copy carries.
        Map<Skill, Long> result = new EnumMap<>(Skill.class);
        result.putAll(winner.balances);
        return result;
    }

    /**
     * Writes the balances to both copies with a fresh save time.
     *
     * @param balances skills with their balance, positive for banked XP and
     *     negative for XP owed; entries at exactly zero are dropped
     */
    public synchronized void save(Map<Skill, Long> balances)
    {
        // Wall-clock time, nudged forward if the clock went backwards, so a later
        // save always compares as newer than an earlier one.
        long savedAt = Math.max(System.currentTimeMillis(), lastSavedAt + 1);
        write(new Copy(savedAt, balances));
    }

    /**
     * Whether a {@code ConfigChanged} new value is the echo of this store's own
     * most recent config write, as opposed to a change made by RuneLite itself
     * (a profile switch, a sync from the server, or a reset from the settings panel).
     */
    public boolean isOwnConfigValue(String configValue)
    {
        return configValue != null && configValue.equals(lastWrittenConfigValue);
    }

    /** The file holding the active config profile's balances. */
    File dataFile()
    {
        // Balances are scoped to the RuneLite config profile, exactly as the config
        // copy is, so a user who keeps separate profiles keeps separate banks. The
        // profile is read on every call rather than cached because RuneLite can
        // switch profiles while the plugin is running.
        ConfigProfile profile = configManager.getProfile();
        String name = profile == null
            ? FILE_PREFIX + FILE_SUFFIX
            : FILE_PREFIX + "-" + profile.getId() + FILE_SUFFIX;
        return new File(dataDir, name);
    }

    // ---- writing ----------------------------------------------------------

    private void write(Copy copy)
    {
        String serialized = serializeBalances(copy.balances);

        // Recorded before the config write, because ConfigManager posts the
        // ConfigChanged event synchronously from inside setConfiguration and the
        // plugin's listener compares against this field while handling it.
        lastWrittenConfigValue = serialized;
        configManager.setConfiguration(GokIrlBankedXpConfig.GROUP, XP_KEY, serialized);
        configManager.setConfiguration(GokIrlBankedXpConfig.GROUP, SAVED_AT_KEY, Long.toString(copy.savedAt));

        writeFile(copy);
        lastSavedAt = copy.savedAt;
    }

    /**
     * {@code SKILL:xp} entries in {@link Skill} declaration order (the order the
     * previous format used too), or the empty string when nothing is banked or
     * owed. A debt is written with its sign, e.g. {@code WOODCUTTING:-5000}.
     */
    private static String serializeBalances(Map<Skill, Long> balances)
    {
        StringBuilder out = new StringBuilder();
        // TreeMap so the order is stable regardless of the input map type.
        for (Map.Entry<Skill, Long> entry : new TreeMap<>(balances).entrySet())
        {
            if (entry.getValue() == null || entry.getValue() == 0)
            {
                continue;
            }
            if (out.length() > 0)
            {
                out.append(ENTRY_DELIMITER);
            }
            out.append(entry.getKey().name()).append(VALUE_DELIMITER).append(entry.getValue());
        }
        return out.toString();
    }

    private void writeFile(Copy copy)
    {
        FileContents contents = new FileContents();
        contents.savedAt = copy.savedAt;
        contents.skills = new LinkedHashMap<>();
        for (Map.Entry<Skill, Long> entry : new TreeMap<>(copy.balances).entrySet())
        {
            if (entry.getValue() != null && entry.getValue() != 0)
            {
                contents.skills.put(entry.getKey().name(), entry.getValue());
            }
        }

        File target = dataFile();
        try
        {
            Files.createDirectories(dataDir.toPath());
            // Write beside the target and rename over it, so the file on disk is
            // always either the previous complete save or this one.
            Path temp = new File(dataDir, target.getName() + ".tmp").toPath();
            Files.write(temp, gson.toJson(contents).getBytes(StandardCharsets.UTF_8));
            try
            {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException ex)
            {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException ex)
        {
            // The config copy was written first, so nothing is lost yet; the file
            // simply stays at its previous save until the next write succeeds.
            log.warn("Unable to write banked XP to {}", target, ex);
        }
    }

    // ---- reading ----------------------------------------------------------

    private Copy readConfig()
    {
        String serialized = configManager.getConfiguration(GokIrlBankedXpConfig.GROUP, XP_KEY);
        if (serialized == null)
        {
            return Copy.absent();
        }

        String savedAtRaw = configManager.getConfiguration(GokIrlBankedXpConfig.GROUP, SAVED_AT_KEY);
        long savedAt = LEGACY;
        if (savedAtRaw != null)
        {
            try
            {
                savedAt = Long.parseLong(savedAtRaw.trim());
            }
            catch (NumberFormatException ex)
            {
                log.debug("Ignoring unparseable banked XP save time [{}]", savedAtRaw);
            }
        }

        Map<Skill, Long> balances = new EnumMap<>(Skill.class);
        for (String entry : serialized.split(ENTRY_DELIMITER))
        {
            String[] parts = entry.split(VALUE_DELIMITER);
            if (parts.length != 2)
            {
                continue;
            }
            putIfValid(balances, parts[0], parts[1]);
        }
        return new Copy(savedAt, balances);
    }

    private Copy readFile()
    {
        File file = dataFile();
        String json;
        try
        {
            json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (NoSuchFileException ex)
        {
            return Copy.absent();
        }
        catch (IOException ex)
        {
            log.warn("Unable to read banked XP from {}", file, ex);
            return Copy.absent();
        }

        FileContents contents;
        try
        {
            contents = gson.fromJson(json, FileContents.class);
        }
        catch (JsonParseException ex)
        {
            contents = null;
        }
        if (contents == null || contents.skills == null)
        {
            // Keep the damaged file for the user to inspect instead of overwriting
            // it on the next save; the config copy carries on as the source.
            File aside = new File(dataDir, file.getName() + ".corrupt-" + System.currentTimeMillis());
            log.warn("Banked XP file {} is unreadable; moving it to {}", file, aside);
            if (!file.renameTo(aside))
            {
                log.warn("Unable to move {} aside", file);
            }
            return Copy.absent();
        }

        Map<Skill, Long> balances = new EnumMap<>(Skill.class);
        for (Map.Entry<String, Long> entry : contents.skills.entrySet())
        {
            if (entry.getValue() != null)
            {
                putIfValid(balances, entry.getKey(), Long.toString(entry.getValue()));
            }
        }
        return new Copy(contents.savedAt, balances);
    }

    /**
     * Adds one entry when the skill name is known and the amount is a non-zero
     * number. Negative amounts are debts and are kept; only an explicit zero is
     * dropped, because it carries the same meaning as no entry.
     */
    private static void putIfValid(Map<Skill, Long> into, String skillName, String amountRaw)
    {
        Skill skill = parseSkill(skillName);
        if (skill == null)
        {
            return;
        }
        try
        {
            long amount = Long.parseLong(amountRaw.trim());
            if (amount != 0)
            {
                into.put(skill, amount);
            }
        }
        catch (NumberFormatException ex)
        {
            log.debug("Ignoring banked XP entry [{}:{}]", skillName, amountRaw);
        }
    }

    /** Null for a name no {@link Skill} carries, so a removed skill drops out rather than failing the load. */
    private static Skill parseSkill(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return null;
        }
        try
        {
            return Skill.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
    }

    private static String describe(Copy copy)
    {
        return copy.savedAt == ABSENT ? "absent" : "saved at " + copy.savedAt;
    }

    /** One copy of the balances together with when it was saved. */
    private static final class Copy
    {
        final long savedAt;
        final Map<Skill, Long> balances;

        Copy(long savedAt, Map<Skill, Long> balances)
        {
            this.savedAt = savedAt;
            this.balances = balances;
        }

        static Copy absent()
        {
            return new Copy(ABSENT, Collections.emptyMap());
        }
    }

    /**
     * On-disk shape of the JSON file: {@code {"savedAt": 1725000000000, "skills": {"AGILITY": 5850}}}.
     * Skill names are stored as strings rather than the enum so an unknown name is
     * skipped on read instead of failing the whole parse.
     */
    private static class FileContents
    {
        long savedAt;
        Map<String, Long> skills;

        FileContents()
        {
        }
    }
}
