package com.gokirlbankedxp.service;

import com.google.common.math.LongMath;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Singleton;
import net.runelite.api.Skill;

/**
 * Predicts how much banked XP a skill's next few in-game actions will consume.
 *
 * <p>The plugin needs to warn the user <em>before</em> a skill's banked XP runs
 * out, expressed in the unit people actually think in: "about five more actions
 * left". There is no API that reports "one action", and what an action costs is
 * entirely skill- and content-dependent — a yew log, an Ardougne lap, and a hit
 * on a boss are all one action but nothing alike.</p>
 *
 * <p>Rather than special-casing skills, this learns the rate from the game's own
 * XP drops. Every {@link net.runelite.api.events.StatChanged} that reduces a
 * skill's bank is one action's worth of XP by definition, so keeping the most
 * recent few of those per skill gives a live, self-correcting estimate that works
 * the same for woodcutting, agility, and combat. Nothing is persisted: a fresh
 * client simply re-learns the rate from the first drops of the session.</p>
 *
 * <p>{@code @Singleton} for the same reason the other services carry it — the
 * plugin is the only consumer today, but a second unscoped instance would be a
 * second, always-empty sample history.</p>
 */
@Singleton
public class DepletionForecaster
{
    /**
     * Upper bound on retained samples per skill, independent of the configured
     * window. It only exists so a user typing an absurd window size cannot make
     * the deques grow without limit; the config itself is range-capped well below
     * this.
     */
    private static final int MAX_SAMPLES = 64;

    /**
     * Recent XP drops per skill, oldest first. Guarded by this instance's
     * monitor: XP drops arrive on the client thread while the sidebar can ask for
     * a forecast from the EDT.
     */
    private final Map<Skill, Deque<Long>> recentDrops = new EnumMap<>(Skill.class);

    /**
     * Records one action's worth of XP for a skill.
     *
     * @param window how many recent drops the forecast should consider; samples
     *               beyond this are discarded, so shrinking the window in config
     *               takes effect on the next drop
     */
    public synchronized void recordDrop(Skill skill, long xpDelta, int window)
    {
        if (skill == null || xpDelta <= 0 || window <= 0)
        {
            return;
        }

        Deque<Long> drops = recentDrops.computeIfAbsent(skill, key -> new ArrayDeque<>());
        drops.addLast(xpDelta);

        int limit = Math.min(window, MAX_SAMPLES);
        while (drops.size() > limit)
        {
            drops.removeFirst();
        }
    }

    /**
     * Estimates what the next {@code window} actions will cost this skill.
     *
     * <p>The estimate is the mean recorded drop multiplied by the window. Once
     * the window is full that is exactly the sum of the last {@code window}
     * drops; before it fills, scaling the mean keeps the answer meaningful
     * instead of under-reporting and warning too late. Using the mean also keeps
     * combat honest, where individual drops vary from hit to hit.</p>
     *
     * @return 0 when nothing has been recorded yet, which callers read as "no
     *         prediction available, do not warn"
     */
    public synchronized long forecast(Skill skill, int window)
    {
        if (window <= 0)
        {
            return 0L;
        }

        long average = averageDrop(skill);
        // Saturating: a colossal learned rate clamps at the long ceiling rather
        // than wrapping negative, which would read as "no XP will be consumed".
        return average <= 0 ? 0L : LongMath.saturatedMultiply(average, window);
    }

    /**
     * The mean XP of the retained drops for a skill, rounded down.
     *
     * <p>Exposed so callers can turn "XP remaining" back into "actions remaining"
     * for the warning text, using the same samples the forecast was built from.</p>
     *
     * @return 0 when the skill has no samples yet
     */
    public synchronized long averageDrop(Skill skill)
    {
        Deque<Long> drops = skill == null ? null : recentDrops.get(skill);
        if (drops == null || drops.isEmpty())
        {
            return 0L;
        }

        long total = 0L;
        for (Long drop : drops)
        {
            total = LongMath.saturatedAdd(total, drop);
        }

        return total / drops.size();
    }

    /** Number of drops currently retained for a skill; 0 when it has none. */
    public synchronized int sampleCount(Skill skill)
    {
        Deque<Long> drops = skill == null ? null : recentDrops.get(skill);
        return drops == null ? 0 : drops.size();
    }

    /** Drops every learned rate, so a restarted plugin starts from a clean slate. */
    public synchronized void clear()
    {
        recentDrops.clear();
    }
}
