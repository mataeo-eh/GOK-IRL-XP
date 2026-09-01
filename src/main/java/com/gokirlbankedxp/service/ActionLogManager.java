package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpPlugin;
import com.gokirlbankedxp.model.IrlAction;
import com.google.common.math.LongMath;
import java.util.Collections;
import java.util.EnumMap;
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
    private final XpMultiplierManager multiplierManager;

    @Inject
    public ActionLogManager(
        IrlActionManager actionManager,
        GokIrlBankedXpPlugin plugin,
        XpMultiplierManager multiplierManager)
    {
        this.actionManager = Objects.requireNonNull(actionManager);
        this.plugin = Objects.requireNonNull(plugin);
        this.multiplierManager = Objects.requireNonNull(multiplierManager);
    }

    /**
     * Computes what a completed session is worth without banking anything.
     *
     * <p>Used to preview the award in the UI as the user types, so the numbers
     * shown before pressing the button are produced by the same code that
     * actually grants the XP.</p>
     *
     * <p>The preview applies the level multiplier itself, through the same
     * service the plugin uses, because it has to show what will land in the bank
     * rather than what the action's rates say on paper. It only ever estimates:
     * the authoritative figure is whatever {@link #log} gets back from the
     * plugin, which is the one place XP is actually multiplied and stored.</p>
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
        Map<Skill, Long> base = value.xpForUnits(totalUnits);
        if (base.isEmpty())
        {
            return Optional.empty();
        }

        Map<Skill, Long> banked = new EnumMap<>(Skill.class);
        base.forEach((skill, amount) -> banked.put(skill, multiplierManager.applyTo(skill, amount)));

        return Optional.of(new LoggedAward(value.getName(), value.getUnitName(), totalUnits, base, banked));
    }

    /**
     * Banks the XP for a completed session and returns what was granted.
     *
     * <p>Nothing is persisted about the session itself; the plugin's banked XP
     * totals are the only record, exactly as with timer-earned XP.</p>
     *
     * <p>What comes back reports the plugin's own return values, not the preview's
     * estimate. The two agree in every ordinary case, but the plugin is the only
     * thing that knows what it actually stored, and a caller that tells the user
     * "banked 2,000 XP" should be quoting the bank rather than a prediction.</p>
     *
     * @return empty when nothing was banked, in which case no XP was granted
     */
    public Optional<LoggedAward> log(UUID actionId, long unitsPerRepetition, long repetitions)
    {
        Optional<LoggedAward> preview = preview(actionId, unitsPerRepetition, repetitions);
        if (preview.isEmpty())
        {
            return Optional.empty();
        }

        LoggedAward estimate = preview.get();
        Map<Skill, Long> banked = new EnumMap<>(Skill.class);
        estimate.getBaseXp().forEach((skill, amount) -> banked.put(skill, plugin.addActionXp(skill, amount)));

        return Optional.of(new LoggedAward(
            estimate.getActionName(),
            estimate.getUnitName(),
            estimate.getTotalUnits(),
            estimate.getBaseXp(),
            banked));
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
        private final Map<Skill, Long> baseXp;
        private final Map<Skill, Long> awardedXp;

        LoggedAward(
            String actionName,
            String unitName,
            long totalUnits,
            Map<Skill, Long> baseXp,
            Map<Skill, Long> awardedXp)
        {
            this.actionName = actionName;
            this.unitName = unitName;
            this.totalUnits = totalUnits;
            this.baseXp = Collections.unmodifiableMap(baseXp);
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

        /**
         * What the action's own XP rates produce, before any level multiplier.
         *
         * <p>Kept alongside the awarded figure so the UI can say "1,000 becomes
         * 2,000" rather than only showing the end result, and so {@link #log}
         * has an unmultiplied amount to hand the plugin — the plugin applies the
         * multiplier itself, and passing it an already-multiplied figure would
         * double it.</p>
         */
        public Map<Skill, Long> getBaseXp()
        {
            return baseXp;
        }

        /**
         * What actually goes into the bank: the base XP after the skill's level
         * multiplier. Equal to {@link #getBaseXp()} whenever no multiplier is in
         * force, which is the case for any skill the user never configured.
         */
        public Map<Skill, Long> getAwardedXp()
        {
            return awardedXp;
        }

        /** Whether a level multiplier changed any of this award's figures. */
        public boolean isMultiplied()
        {
            return !baseXp.equals(awardedXp);
        }
    }
}
