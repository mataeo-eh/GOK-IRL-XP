package com.gokirlbankedxp.model;

import java.util.Objects;
import net.runelite.api.Experience;

/**
 * One rung of a skill's level-based banking multiplier.
 *
 * <p>A tier says: "once this skill reaches {@code level}, multiply every XP
 * deposit for it by {@code multiplier}". Tiers deliberately do <em>not</em>
 * stack. Exactly one tier is ever in force — the highest one the player has
 * reached — so crossing into a new tier replaces the previous multiplier rather
 * than compounding with it. Below the lowest tier, XP is banked unchanged.</p>
 *
 * <p>Levels run to {@link Experience#MAX_VIRT_LEVEL} (126) rather than stopping
 * at 99, because levels here are derived from experience and keep climbing past
 * the in-game cap. A player who never passes 99 simply never sets a tier there.</p>
 *
 * <p>Immutable, and deliberately tolerant on the way in: Gson reconstructs saved
 * tiers by writing fields directly without running this constructor, so anything
 * read back from config is re-validated through {@link #isValid()} and
 * {@link #normalized()} rather than trusted.</p>
 */
public class XpMultiplierTier implements Comparable<XpMultiplierTier>
{
    /** The lowest level a tier can trigger at — level 1 means "always on". */
    public static final int MIN_LEVEL = 1;

    /** Virtual level ceiling, so tiers stay meaningful past 99. */
    public static final int MAX_LEVEL = Experience.MAX_VIRT_LEVEL;

    /**
     * Multipliers are non-negative by rule; zero is legal and means "bank
     * nothing for this skill at this level". The upper bound is not a game rule,
     * only a guard against a typo (a stray extra digit) silently banking an
     * absurd amount, and against overflow when scaling large deposits.
     */
    public static final double MIN_MULTIPLIER = 0.0;
    public static final double MAX_MULTIPLIER = 1000.0;

    private final int level;
    private final double multiplier;

    public XpMultiplierTier(int level, double multiplier)
    {
        this.level = level;
        this.multiplier = multiplier;
    }

    public int getLevel()
    {
        return level;
    }

    public double getMultiplier()
    {
        return multiplier;
    }

    /**
     * Whether this tier can be used as-is.
     *
     * <p>NaN and infinity are rejected explicitly: neither is caught by the range
     * comparisons below (every comparison against NaN is false), and either one
     * would turn a banked total into garbage.</p>
     */
    public boolean isValid()
    {
        if (Double.isNaN(multiplier) || Double.isInfinite(multiplier))
        {
            return false;
        }

        return level >= MIN_LEVEL
            && level <= MAX_LEVEL
            && multiplier >= MIN_MULTIPLIER
            && multiplier <= MAX_MULTIPLIER;
    }

    /**
     * The nearest usable tier to this one, clamped into range.
     *
     * <p>Used when loading saved data so a hand-edited or out-of-date config
     * yields a sane tier instead of being silently dropped. A multiplier that is
     * not a number at all has no nearest value, so it falls back to 1.0 — XP
     * banked unchanged, the same as having no tier.</p>
     */
    public XpMultiplierTier normalized()
    {
        int clampedLevel = Math.min(MAX_LEVEL, Math.max(MIN_LEVEL, level));
        double clampedMultiplier;
        if (Double.isNaN(multiplier))
        {
            clampedMultiplier = 1.0;
        }
        else
        {
            clampedMultiplier = Math.min(MAX_MULTIPLIER, Math.max(MIN_MULTIPLIER, multiplier));
        }

        return new XpMultiplierTier(clampedLevel, clampedMultiplier);
    }

    /** Sorted by the level they trigger at, which is the order they take effect in. */
    @Override
    public int compareTo(XpMultiplierTier other)
    {
        return Integer.compare(level, other.level);
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof XpMultiplierTier))
        {
            return false;
        }
        XpMultiplierTier that = (XpMultiplierTier) other;
        return level == that.level && Double.compare(multiplier, that.multiplier) == 0;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(level, multiplier);
    }

    @Override
    public String toString()
    {
        return "XpMultiplierTier{level=" + level + ", multiplier=" + multiplier + '}';
    }
}
