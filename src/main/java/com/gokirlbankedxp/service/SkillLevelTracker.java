package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers what level the player is in each skill, including while logged out.
 *
 * <p>{@link com.gokirlbankedxp.service.XpMultiplierManager} needs a level to pick
 * a tier, and XP is banked from three places that are all off the client thread:
 * the Swing sidebar, the timer scheduler, and the log-completed-work form. Rather
 * than reach into {@link net.runelite.api.Client} from those threads, the plugin
 * pushes every experience reading it already observes on the client thread into
 * this tracker, and everything else reads the cached answer.</p>
 *
 * <p>Levels are persisted, unlike the plugin's in-memory {@code lastKnownXp} map.
 * The sidebar works at the login screen — logging a run before you log in is a
 * normal thing to do — and without a saved level every such deposit would be
 * multiplied as though the player were level 1. Writes only happen when a level
 * actually changes, so a level-up (rare) costs a config write and ordinary XP
 * drops (constant) cost nothing.</p>
 *
 * <p>{@code @Singleton} because the plugin writes to it and the multiplier
 * service and sidebar read from it; unscoped, each would hold its own map and
 * only the plugin's would ever be filled in.</p>
 */
@Singleton
public class SkillLevelTracker
{
    private static final Logger log = LoggerFactory.getLogger(SkillLevelTracker.class);

    /** Hidden config key; the levels are observed data, never user-edited. */
    private static final String CONFIG_KEY = "observedSkillLevels";

    /**
     * Persisted as skill name to level rather than as a {@code Map<Skill, Integer>}
     * so that a skill removed from a future RuneLite release degrades to an
     * ignored entry instead of failing the whole parse.
     */
    private static final Type LEVEL_MAP_TYPE = new TypeToken<Map<String, Integer>>()
    {
    }.getType();

    /** What an unobserved skill reports, matching a fresh account. */
    public static final int UNKNOWN_LEVEL = 1;

    private final ConfigManager configManager;
    private final Gson gson;

    private final Object lock = new Object();
    private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);

    /**
     * The shared Gson instance is injected rather than constructed; the Plugin
     * Hub build fails on plugin sources that construct their own Gson.
     */
    @Inject
    public SkillLevelTracker(ConfigManager configManager, Gson gson)
    {
        this.configManager = Objects.requireNonNull(configManager);
        this.gson = Objects.requireNonNull(gson);
    }

    /** Reads the saved levels back in. Called once as the plugin starts up. */
    public void load()
    {
        String raw = configManager.getConfiguration(GokIrlBankedXpConfig.GROUP, CONFIG_KEY);

        synchronized (lock)
        {
            levels.clear();

            if (raw == null || raw.isBlank())
            {
                return;
            }

            try
            {
                Map<String, Integer> parsed = gson.fromJson(raw, LEVEL_MAP_TYPE);
                if (parsed == null)
                {
                    return;
                }

                parsed.forEach((name, level) -> {
                    Skill skill = parseSkill(name);
                    if (skill != null && level != null)
                    {
                        levels.put(skill, clampLevel(level));
                    }
                });
            }
            catch (JsonParseException ex)
            {
                log.warn("Failed to parse observed skill levels; relearning them from the client", ex);
            }
        }
    }

    /**
     * Records an experience reading for a skill and derives its level.
     *
     * <p>Must be called from the client thread, because that is where the
     * experience value comes from. Nothing is written to config unless the
     * derived level differs from what was already stored.</p>
     *
     * @param experience total experience in the skill, as the client reports it
     * @return the level now recorded for the skill
     */
    public int recordExperience(Skill skill, int experience)
    {
        if (skill == null)
        {
            return UNKNOWN_LEVEL;
        }

        // Experience.getLevelForXp returns virtual levels up to 126 and throws on
        // a negative argument, so a nonsensical reading is floored rather than
        // allowed to blow up the caller's event handler.
        int level = clampLevel(Experience.getLevelForXp(Math.max(0, experience)));

        boolean changed;
        synchronized (lock)
        {
            Integer previous = levels.put(skill, level);
            changed = previous == null || previous != level;
            if (changed)
            {
                persistLocked();
            }
        }

        return level;
    }

    /**
     * The last level observed for a skill.
     *
     * @return the observed level, or {@link #UNKNOWN_LEVEL} for a skill this
     *     plugin has never seen an experience reading for
     */
    public int getLevel(Skill skill)
    {
        if (skill == null)
        {
            return UNKNOWN_LEVEL;
        }

        synchronized (lock)
        {
            return levels.getOrDefault(skill, UNKNOWN_LEVEL);
        }
    }

    /** Whether a real reading has been seen for this skill, as opposed to the level-1 default. */
    public boolean hasObserved(Skill skill)
    {
        if (skill == null)
        {
            return false;
        }

        synchronized (lock)
        {
            return levels.containsKey(skill);
        }
    }

    /** Every observed level, for display. Never the live map. */
    public Map<Skill, Integer> getObservedLevels()
    {
        synchronized (lock)
        {
            return new EnumMap<>(levels);
        }
    }

    /**
     * Writes the observed levels out.
     *
     * <p>Sorted by skill name so the stored value is stable: an unordered map
     * would reserialize differently run to run and churn the config file even
     * when nothing changed.</p>
     */
    private void persistLocked()
    {
        Map<String, Integer> serializable = new TreeMap<>();
        levels.forEach((skill, level) -> serializable.put(skill.name(), level));
        configManager.setConfiguration(
            GokIrlBankedXpConfig.GROUP, CONFIG_KEY, gson.toJson(new LinkedHashMap<>(serializable)));
    }

    private static int clampLevel(int level)
    {
        return Math.min(Experience.MAX_VIRT_LEVEL, Math.max(UNKNOWN_LEVEL, level));
    }

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
}
