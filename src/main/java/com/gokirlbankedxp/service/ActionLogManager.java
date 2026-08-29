package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpPlugin;
import com.gokirlbankedxp.model.IrlAction;
import com.google.common.math.LongMath;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Skill;

/**
 * Banks XP for work the user already finished, without running a timer.
 *
 * <p>This is the manual counterpart to {@link TimerManager}. Where a timer
 * accrues units as real time passes, this converts a completed session the user
 * describes after the fact — "I did {@code repetitions} sets of
 * {@code unitsPerRepetition} units" — into banked XP in one step. It exists so
 * benchmark-style actions ("1 XP per pound lifted", "250 XP per km run") are
 * usable at all: those have no duration for a timer to count against.</p>
 *
 * <p>The per-skill arithmetic itself lives on {@link IrlAction#xpForUnits(long)},
 * shared with the timer path so both routes to banked XP always agree.</p>
 */
@Singleton
public class ActionLogManager
{
    private final IrlActionManager actionManager;
    private final GokIrlBankedXpPlugin plugin;

    @Inject
    public ActionLogManager(IrlActionManager actionManager, GokIrlBankedXpPlugin plugin)
    {
        this.actionManager = Objects.requireNonNull(actionManager);
        this.plugin = Objects.requireNonNull(plugin);
    }

    /**
     * Computes what a completed session is worth without banking anything.
     *
     * <p>Used to preview the award in the UI as the user types, so the numbers
     * shown before pressing the button are produced by the same code that
     * actually grants the XP.</p>
     *
     * @return empty when the action is unknown or either count is not positive
     */
    public Optional<LoggedAward> preview(UUID actionId, long unitsPerRepetition, long repetitions)
    {
        if (unitsPerRepetition <= 0 || repetitions <= 0)
        {
            return Optional.empty();
        }

        Optional<IrlAction> action = actionManager.getAction(actionId);
        if (action.isEmpty())
        {
            return Optional.empty();
        }

        IrlAction value = action.get();
        // Saturating rather than throwing: a nonsensical count clamps to the
        // long ceiling instead of wrapping negative and subtracting banked XP.
        long totalUnits = LongMath.saturatedMultiply(unitsPerRepetition, repetitions);
        Map<Skill, Long> awarded = value.xpForUnits(totalUnits);
        if (awarded.isEmpty())
        {
            return Optional.empty();
        }

        return Optional.of(new LoggedAward(value.getName(), value.getUnitName(), totalUnits, awarded));
    }

    /**
     * Banks the XP for a completed session and returns what was granted.
     *
     * <p>Nothing is persisted about the session itself; the plugin's banked XP
     * totals are the only record, exactly as with timer-earned XP.</p>
     *
     * @return empty when nothing was banked, in which case no XP was granted
     */
    public Optional<LoggedAward> log(UUID actionId, long unitsPerRepetition, long repetitions)
    {
        Optional<LoggedAward> award = preview(actionId, unitsPerRepetition, repetitions);
        award.ifPresent(value -> value.getAwardedXp()
            .forEach((skill, amount) -> plugin.addActionXp(skill, amount)));
        return award;
    }

    /**
     * The outcome of one logged session.
     *
     * <p>An ordinary class rather than a record because the Plugin Hub targets
     * Java 11.</p>
     */
    public static final class LoggedAward
    {
        private final String actionName;
        private final String unitName;
        private final long totalUnits;
        private final Map<Skill, Long> awardedXp;

        LoggedAward(String actionName, String unitName, long totalUnits, Map<Skill, Long> awardedXp)
        {
            this.actionName = actionName;
            this.unitName = unitName;
            this.totalUnits = totalUnits;
            this.awardedXp = Collections.unmodifiableMap(awardedXp);
        }

        public String getActionName()
        {
            return actionName;
        }

        public String getUnitName()
        {
            return unitName;
        }

        public long getTotalUnits()
        {
            return totalUnits;
        }

        public Map<Skill, Long> getAwardedXp()
        {
            return awardedXp;
        }
    }
}
