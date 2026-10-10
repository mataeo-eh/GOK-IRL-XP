package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers which skills the player has switched tracking off for.
 *
 * <p>Every skill is tracked unless it is listed here. Turning a skill off freezes
 * it rather than resetting it: the plugin stops charging in-game XP against it,
 * stops accepting deposits into it, and stops showing it, but the balance it had
 * is left exactly where it was and comes back untouched when the skill is turned
 * on again. This class only holds the on/off answer; the plugin is what acts on
 * it at each of those points.</p>
 *
 * <p>The <em>untracked</em> skills are what is stored, not the tracked ones, so
 * that the out-of-the-box state is an empty value and a skill added to the game
 * later starts out tracked like every other.</p>
 *
 * <p>The answer is read from three threads — the client thread on every XP drop,
 * the timer scheduler on every deposit, and the Swing thread when the sidebar
 * draws — so the set is never mutated in place. Each change swaps in a fresh
 * unmodifiable copy through a {@code volatile} field, which lets every reader
 * skip locking entirely.</p>
 *
 * <p>{@code @Singleton} because the plugin and the sidebar must consult the same
 * instance; unscoped, the sidebar would toggle a copy the plugin never reads.</p>
 */
@Singleton
public class SkillTrackingManager
{
    private static final Logger log = LoggerFactory.getLogger(SkillTrackingManager.class);

    /** Hidden config key; edited through the sidebar's skill grid, never in the settings panel. */
    public static final String CONFIG_KEY = "untrackedSkillsJson";

    /**
     * Persisted as a list of skill names rather than a {@code Set<Skill>} so that
     * a skill removed from a future RuneLite release degrades to an ignored entry
     * instead of failing the whole parse.
     */
    private static final Type NAME_LIST_TYPE = new TypeToken<List<String>>()
    {
    }.getType();

    private final ConfigManager configManager;
    private final Gson gson;

    /** Serialises writers only; readers go straight to {@link #untracked}. */
    private final Object writeLock = new Object();

    /** Always an unmodifiable snapshot, replaced whole on every change. */
    private volatile Set<Skill> untracked = Collections.emptySet();

    /**
     * The shared Gson instance is injected rather than constructed; the Plugin
     * Hub build fails on plugin sources that construct their own Gson.
     */
    @Inject
    public SkillTrackingManager(ConfigManager configManager, Gson gson)
    {
        this.configManager = Objects.requireNonNull(configManager);
        this.gson = Objects.requireNonNull(gson);
    }

    /**
     * Reads the saved choice back in. Called as the plugin starts up, after a
     * profile switch, and whenever RuneLite reports the stored value changed.
     *
     * <p>A missing, blank or unreadable value means "every skill is tracked",
     * which is the default and the safest reading of a damaged one.</p>
     */
    public void load()
    {
        String raw = configManager.getConfiguration(GokIrlBankedXpConfig.GROUP, CONFIG_KEY);
        Set<Skill> loaded = EnumSet.noneOf(Skill.class);

        if (raw != null && !raw.isBlank())
        {
            try
            {
                List<String> names = gson.fromJson(raw, NAME_LIST_TYPE);
                if (names != null)
                {
                    for (String name : names)
                    {
                        Skill skill = parseSkill(name);
                        if (skill != null)
                        {
                            loaded.add(skill);
                        }
                    }
                }
            }
            catch (JsonParseException ex)
            {
                log.warn("Failed to parse the untracked skill list; tracking every skill", ex);
            }
        }

        synchronized (writeLock)
        {
            untracked = Collections.unmodifiableSet(loaded);
        }
    }

    /** Whether the plugin should be tracking this skill. True for every skill by default. */
    public boolean isTracked(Skill skill)
    {
        return skill != null && !untracked.contains(skill);
    }

    /** The skills switched off, for display. An unmodifiable snapshot, never the live set. */
    public Set<Skill> getUntrackedSkills()
    {
        return untracked;
    }

    /**
     * Switches tracking for one skill on or off and saves the choice.
     *
     * @return whether anything changed; false when the skill was already in the
     *     requested state, in which case nothing is written
     */
    public boolean setTracked(Skill skill, boolean tracked)
    {
        if (skill == null)
        {
            return false;
        }

        synchronized (writeLock)
        {
            Set<Skill> updated = EnumSet.noneOf(Skill.class);
            updated.addAll(untracked);
            // Untracked is what is stored, so turning tracking on removes the entry.
            boolean changed = tracked ? updated.remove(skill) : updated.add(skill);
            if (!changed)
            {
                return false;
            }

            untracked = Collections.unmodifiableSet(updated);

            // An EnumSet iterates in declaration order, so the stored value is
            // stable and does not churn the config file between saves.
            List<String> names = new ArrayList<>();
            for (Skill entry : updated)
            {
                names.add(entry.name());
            }
            configManager.setConfiguration(GokIrlBankedXpConfig.GROUP, CONFIG_KEY, gson.toJson(names));
            return true;
        }
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
