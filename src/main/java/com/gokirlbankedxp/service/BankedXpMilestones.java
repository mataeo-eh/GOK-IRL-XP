package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.util.QuantityFormatter;

/**
 * Session-only milestone reminders, independent of the regular overlay's visibility.
 * The plugin supplies genuine XP gains after login synchronization and balance
 * subtraction. No polling, guessed level-up widgets, or saved notification state
 * is needed: threshold crossings re-arm naturally when a balance is topped up.
 */
public final class BankedXpMilestones
{
    private final Map<Skill, Popup> popups = new EnumMap<>(Skill.class);
    private final LongSupplier clockMillis;

    public BankedXpMilestones()
    {
        // Monotonic time prevents wall-clock corrections from changing popup duration.
        this(() -> System.nanoTime() / 1_000_000L);
    }

    BankedXpMilestones(LongSupplier clockMillis)
    {
        this.clockMillis = clockMillis;
    }

    /**
     * RuneLite's StatChanged provides skill, total XP, real level and boosted
     * level. The caller passes skill and total XP before/after a positive gain;
     * real levels are calculated from those totals with Experience, capped at 99.
     * Boosted levels are intentionally ignored so potions cannot trigger popups.
     */
    public synchronized void onXpGain(Skill skill, int oldXp, int newXp,
        long oldBalance, long balance, GokIrlBankedXpConfig config)
    {
        if (skill == null || newXp <= oldXp || oldBalance <= 0)
        {
            return;
        }
        int threshold = Math.max(0, config.milestoneXpThreshold());
        boolean crossed = config.xpMilestoneEnabled()
            && oldBalance > threshold && balance <= threshold;
        int oldLevel = realLevel(oldXp);
        int level = realLevel(newXp);
        // Cap before adding: banked balances are longs and may exceed the game's XP cap.
        int reachableXp = (int) Math.min(Experience.MAX_SKILL_XP,
            (long) newXp + Math.min(Experience.MAX_SKILL_XP, Math.max(0, balance)));
        int levelsRemaining = Math.max(0, realLevel(reachableXp) - level);
        boolean levelUp = config.levelMilestoneEnabled() && level > oldLevel
            && levelsRemaining <= Math.max(0, config.milestoneLevelsRemaining());
        if (!crossed && !levelUp)
        {
            return;
        }

        List<String> lines = new ArrayList<>();
        lines.add(skill.getName() + (levelUp ? ": level " + level + " reached" : ": XP milestone"));
        // These are a snapshot of the milestone, not a second live balance display.
        lines.add(QuantityFormatter.formatNumber(balance) + " XP remaining at milestone");
        if (levelUp)
        {
            lines.add(level == Experience.MAX_REAL_LEVEL ? "Maximum real level reached"
                : levelsRemaining + " further " + (levelsRemaining == 1 ? "level" : "levels") + " banked");
            if (level < Experience.MAX_REAL_LEVEL && levelsRemaining == 0)
            {
                long needed = Experience.getXpForLevel(level + 1) - (long) newXp;
                lines.add("Next level needs " + QuantityFormatter.formatNumber(needed) + " XP");
            }
        }
        int seconds = Math.max(0, Math.min(300, config.milestonePopupSeconds()));
        popups.put(skill, new Popup(lines, seconds == 0 ? Long.MAX_VALUE
            : clockMillis.getAsLong() + seconds * 1_000L));
    }

    /** Rendering gets a copy; simultaneous skill gains remain separate reminders. */
    public synchronized List<String> getLines()
    {
        long now = clockMillis.getAsLong();
        popups.values().removeIf(popup -> now >= popup.expiresAt);
        List<String> lines = new ArrayList<>();
        popups.values().forEach(popup -> lines.addAll(popup.lines));
        return lines;
    }

    /** Used for dismissal and all session/profile boundaries; never changes banked XP. */
    public synchronized void clear()
    {
        popups.clear();
    }

    private static int realLevel(int xp)
    {
        return Math.min(Experience.MAX_REAL_LEVEL, Experience.getLevelForXp(Math.max(0, xp)));
    }

    private static final class Popup
    {
        private final List<String> lines;
        private final long expiresAt;

        private Popup(List<String> lines, long expiresAt)
        {
            this.lines = lines;
            this.expiresAt = expiresAt;
        }
    }
}
