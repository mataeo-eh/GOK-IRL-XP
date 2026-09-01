package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import com.gokirlbankedxp.model.XpMultiplierTier;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The level-based banking multiplier: how much a skill's in-game level changes
 * what a real-world action is worth.
 *
 * <p>Each skill carries its own ladder of {@link XpMultiplierTier tiers}, and the
 * user decides how many rungs it has, what level each rung starts at, and what
 * multiplier it applies. Resolution is deliberately simple and non-cumulative:
 * the tier in force is the highest one whose level the player has reached, and it
 * <em>replaces</em> every lower tier rather than compounding with them. Reaching
 * a level-90 tier means the level-70 tier stops applying entirely.</p>
 *
 * <p>Below the lowest tier — and for any skill with no tiers at all — the
 * multiplier is 1.0, so XP is banked exactly as entered. That is what makes the
 * feature invisible until someone opts into it.</p>
 *
 * <p>This class is the single authority on the arithmetic. The plugin applies it
 * once, at the one point where XP enters the bank, and the sidebar calls the same
 * methods to preview what a deposit will become, so the number shown and the
 * number banked can never disagree about the rules.</p>
 *
 * <p>{@code @Singleton}: the plugin, the log form, and the multipliers tab all
 * inject this. Unscoped, each would load its own copy and edits made in the tab
 * would never reach the plugin that banks the XP.</p>
 */
@Singleton
public class XpMultiplierManager
{
    private static final Logger log = LoggerFactory.getLogger(XpMultiplierManager.class);

    /** Hidden config key. The tiers are edited in the sidebar, not the settings panel. */
    private static final String CONFIG_KEY = "xpMultipliersJson";

    /** The multiplier applied when no tier is in force: bank exactly what was earned. */
    public static final double NEUTRAL_MULTIPLIER = 1.0;

    /**
     * Ceiling on how many rungs one skill's ladder may have. Not a game rule —
     * the user chooses the count — but an unbounded spinner invites a config
     * entry with thousands of rows that has to be parsed on every startup.
     */
    public static final int MAX_TIERS_PER_SKILL = 20;

    /**
     * Stored keyed by skill name rather than by {@link Skill} so that an entry
     * for a skill a future client no longer has is skipped on load instead of
     * failing the whole parse and silently discarding every other skill's tiers.
     */
    private static final Type TIER_MAP_TYPE = new TypeToken<Map<String, List<XpMultiplierTier>>>()
    {
    }.getType();

    private final ConfigManager configManager;
    private final GokIrlBankedXpConfig config;
    private final SkillLevelTracker levelTracker;
    private final Gson gson;

    private final Object lock = new Object();
    /** Every list held here is already normalized, deduplicated and sorted by level. */
    private final Map<Skill, List<XpMultiplierTier>> tiersBySkill = new EnumMap<>(Skill.class);

    /**
     * Gson is injected, not constructed: the Plugin Hub build rejects
     * plugin sources that construct their own Gson.
     */
    @Inject
    public XpMultiplierManager(
        ConfigManager configManager,
        GokIrlBankedXpConfig config,
        SkillLevelTracker levelTracker,
        Gson gson)
    {
        this.configManager = Objects.requireNonNull(configManager);
        this.config = Objects.requireNonNull(config);
        this.levelTracker = Objects.requireNonNull(levelTracker);
        this.gson = Objects.requireNonNull(gson);
    }

    /** Reads saved tiers back in, discarding anything that no longer makes sense. */
    public void load()
    {
        String raw = configManager.getConfiguration(GokIrlBankedXpConfig.GROUP, CONFIG_KEY);

        synchronized (lock)
        {
            tiersBySkill.clear();

            if (raw == null || raw.isBlank())
            {
                return;
            }

            try
            {
                Map<String, List<XpMultiplierTier>> parsed = gson.fromJson(raw, TIER_MAP_TYPE);
                if (parsed == null)
                {
                    return;
                }

                parsed.forEach((name, tiers) -> {
                    Skill skill = parseSkill(name);
                    if (skill == null)
                    {
                        log.debug("Ignoring multiplier tiers for unknown skill [{}]", name);
                        return;
                    }

                    List<XpMultiplierTier> sanitized = sanitize(tiers);
                    if (!sanitized.isEmpty())
                    {
                        tiersBySkill.put(skill, sanitized);
                    }
                });
            }
            catch (JsonParseException ex)
            {
                log.warn("Failed to parse level multipliers; every skill falls back to 1.00x", ex);
            }
        }
    }

    /**
     * This skill's ladder, lowest rung first.
     *
     * @return an immutable list, empty when the skill has no tiers configured
     */
    public List<XpMultiplierTier> getTiers(Skill skill)
    {
        if (skill == null)
        {
            return Collections.emptyList();
        }

        synchronized (lock)
        {
            return List.copyOf(tiersBySkill.getOrDefault(skill, Collections.emptyList()));
        }
    }

    /** Every skill that has at least one tier, for the sidebar's summary list. */
    public Map<Skill, List<XpMultiplierTier>> getAllTiers()
    {
        synchronized (lock)
        {
            Map<Skill, List<XpMultiplierTier>> copy = new EnumMap<>(Skill.class);
            tiersBySkill.forEach((skill, tiers) -> copy.put(skill, List.copyOf(tiers)));
            return copy;
        }
    }

    /**
     * Replaces one skill's ladder and saves it.
     *
     * <p>The incoming list is normalized before it is stored: out-of-range values
     * are clamped, two rungs at the same level collapse to the later one, and the
     * result is sorted by level. That means {@link #multiplierFor} can assume
     * sorted, unique levels and stay a simple scan. Passing an empty or null list
     * removes the skill's ladder entirely.</p>
     *
     * @return the ladder as it was actually stored
     */
    public List<XpMultiplierTier> setTiers(Skill skill, List<XpMultiplierTier> tiers)
    {
        if (skill == null)
        {
            return Collections.emptyList();
        }

        List<XpMultiplierTier> sanitized = sanitize(tiers);

        synchronized (lock)
        {
            if (sanitized.isEmpty())
            {
                tiersBySkill.remove(skill);
            }
            else
            {
                tiersBySkill.put(skill, sanitized);
            }

            persistLocked();
        }

        return List.copyOf(sanitized);
    }

    /** Removes a skill's ladder, so its XP banks unchanged again. */
    public void clearTiers(Skill skill)
    {
        setTiers(skill, Collections.emptyList());
    }

    /**
     * The multiplier a given level earns in a given skill.
     *
     * <p>Pure resolution against the saved ladder: it does not consult the
     * player's actual level, and it ignores the master on/off switch. Callers
     * that want "what applies right now" should use {@link #currentMultiplier}.
     * Kept separate so the rule itself is testable without a client.</p>
     *
     * @return the highest reached tier's multiplier, or {@link #NEUTRAL_MULTIPLIER}
     *     when the level is below every tier or the skill has none
     */
    public double multiplierFor(Skill skill, int level)
    {
        if (skill == null)
        {
            return NEUTRAL_MULTIPLIER;
        }

        List<XpMultiplierTier> tiers;
        synchronized (lock)
        {
            tiers = tiersBySkill.get(skill);
            if (tiers == null || tiers.isEmpty())
            {
                return NEUTRAL_MULTIPLIER;
            }

            // Sorted ascending by level, so the last tier at or below the
            // player's level is the one in force. Tiers never stack: this
            // overwrites rather than accumulating.
            double applicable = NEUTRAL_MULTIPLIER;
            for (XpMultiplierTier tier : tiers)
            {
                if (tier.getLevel() > level)
                {
                    break;
                }
                applicable = tier.getMultiplier();
            }

            return applicable;
        }
    }

    /**
     * The multiplier in force for a skill right now, honouring the master switch.
     *
     * <p>Uses the last level {@link SkillLevelTracker} observed, which is what the
     * banking path uses too, so the sidebar's preview and the actual deposit agree
     * even at the login screen.</p>
     */
    public double currentMultiplier(Skill skill)
    {
        if (!isEnabled())
        {
            return NEUTRAL_MULTIPLIER;
        }

        return multiplierFor(skill, levelTracker.getLevel(skill));
    }

    /**
     * Converts a base XP amount into the amount that should actually be banked.
     *
     * <p>The one place this conversion happens. Everything that banks XP goes
     * through the plugin, which calls this exactly once per deposit.</p>
     */
    public long applyTo(Skill skill, long baseXp)
    {
        return scale(baseXp, currentMultiplier(skill));
    }

    /** Whether the feature is switched on in the plugin's settings panel. */
    public boolean isEnabled()
    {
        return config.levelMultipliersEnabled();
    }

    /**
     * Scales an XP amount by a multiplier, in whole XP.
     *
     * <p>Three deliberate edge cases:</p>
     * <ul>
     *   <li>A multiplier of exactly zero banks nothing. That is the point of
     *       allowing zero — "this skill earns nothing at this level".</li>
     *   <li>Any other positive multiplier banks at least 1 XP, so a small deposit
     *       under a fractional multiplier is reduced rather than erased. Rounding
     *       a real deposit away to nothing reads as the plugin having lost it.</li>
     *   <li>A result past the {@code long} ceiling clamps instead of wrapping
     *       negative, matching how the plugin's banked totals saturate.</li>
     * </ul>
     */
    public static long scale(long baseXp, double multiplier)
    {
        if (baseXp <= 0)
        {
            return 0L;
        }

        if (Double.isNaN(multiplier) || multiplier <= 0.0)
        {
            return 0L;
        }

        if (multiplier == NEUTRAL_MULTIPLIER)
        {
            return baseXp;
        }

        double scaled = (double) baseXp * multiplier;
        if (Double.isInfinite(scaled) || scaled >= (double) Long.MAX_VALUE)
        {
            return Long.MAX_VALUE;
        }

        return Math.max(1L, Math.round(scaled));
    }

    /**
     * Puts a user-supplied ladder into the canonical shape the rest of the class
     * assumes: valid values only, one tier per level, sorted by level.
     *
     * <p>A {@link TreeMap} keyed by level does the deduplication and the sort in
     * one step; a later tier at the same level wins, which matches the editor,
     * where the lower row is the one the user filled in most recently.</p>
     */
    private static List<XpMultiplierTier> sanitize(List<XpMultiplierTier> tiers)
    {
        if (tiers == null || tiers.isEmpty())
        {
            return Collections.emptyList();
        }

        Map<Integer, XpMultiplierTier> byLevel = new TreeMap<>();
        for (XpMultiplierTier tier : tiers)
        {
            if (tier == null)
            {
                continue;
            }

            XpMultiplierTier usable = tier.isValid() ? tier : tier.normalized();
            byLevel.put(usable.getLevel(), usable);
        }

        List<XpMultiplierTier> sorted = new ArrayList<>(byLevel.values());
        if (sorted.size() > MAX_TIERS_PER_SKILL)
        {
            // Keep the lowest rungs, which are the ones a player reaches first.
            return new ArrayList<>(sorted.subList(0, MAX_TIERS_PER_SKILL));
        }

        return sorted;
    }

    /**
     * Writes every skill's ladder out.
     *
     * <p>Keyed and ordered by skill name so the serialized value is stable
     * between runs and does not churn the config file when nothing changed.</p>
     */
    private void persistLocked()
    {
        Map<String, List<XpMultiplierTier>> serializable = new TreeMap<>();
        tiersBySkill.forEach((skill, tiers) -> serializable.put(skill.name(), tiers));
        configManager.setConfiguration(
            GokIrlBankedXpConfig.GROUP, CONFIG_KEY, gson.toJson(new LinkedHashMap<>(serializable)));
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
