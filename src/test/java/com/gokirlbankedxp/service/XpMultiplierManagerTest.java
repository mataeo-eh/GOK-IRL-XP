package com.gokirlbankedxp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gokirlbankedxp.model.XpMultiplierTier;
import java.util.List;
import net.runelite.api.Skill;
import org.junit.jupiter.api.Test;

/**
 * Covers the level multiplier rule itself: which threshold applies, what it does
 * to a deposit, and what survives a save and reload.
 *
 * <p>The single most important case here is that thresholds do not stack.
 * Reaching a higher one replaces the lower one outright rather than compounding
 * with it, which is the behaviour the feature was specified with and the one a
 * reader is most likely to "fix" into a bug.</p>
 */
class XpMultiplierManagerTest
{
    @Test
    void aSkillWithNoThresholdsBanksXpUnchanged()
    {
        TestMultipliers fixture = new TestMultipliers().atLevel(Skill.COOKING, 92);

        assertEquals(1.0, fixture.multiplierManager.currentMultiplier(Skill.COOKING));
        assertEquals(500L, fixture.multiplierManager.applyTo(Skill.COOKING, 500L));
    }

    @Test
    void theHighestReachedThresholdApplies()
    {
        TestMultipliers fixture = new TestMultipliers()
            .withTier(Skill.WOODCUTTING, 50, 1.5)
            .withTier(Skill.WOODCUTTING, 70, 2.0)
            .withTier(Skill.WOODCUTTING, 90, 3.0);

        assertEquals(1.0, fixture.multiplierManager.multiplierFor(Skill.WOODCUTTING, 49));
        assertEquals(1.5, fixture.multiplierManager.multiplierFor(Skill.WOODCUTTING, 50));
        assertEquals(1.5, fixture.multiplierManager.multiplierFor(Skill.WOODCUTTING, 69));
        assertEquals(2.0, fixture.multiplierManager.multiplierFor(Skill.WOODCUTTING, 70));
        assertEquals(3.0, fixture.multiplierManager.multiplierFor(Skill.WOODCUTTING, 90));
        assertEquals(3.0, fixture.multiplierManager.multiplierFor(Skill.WOODCUTTING, 126));
    }

    /**
     * The non-stacking guarantee, stated as arithmetic.
     *
     * <p>A ladder of 1.5x, 2x and 3x would compound to 9x if the tiers were
     * multiplied together. At level 90 the answer must be exactly 3x.</p>
     */
    @Test
    void thresholdsReplaceEachOtherRatherThanCompounding()
    {
        TestMultipliers fixture = new TestMultipliers()
            .withTier(Skill.MINING, 50, 1.5)
            .withTier(Skill.MINING, 70, 2.0)
            .withTier(Skill.MINING, 90, 3.0)
            .atLevel(Skill.MINING, 90);

        assertEquals(3.0, fixture.multiplierManager.currentMultiplier(Skill.MINING));
        assertEquals(3_000L, fixture.multiplierManager.applyTo(Skill.MINING, 1_000L));
    }

    @Test
    void aZeroMultiplierBanksNothing()
    {
        TestMultipliers fixture = new TestMultipliers()
            .withTier(Skill.SLAYER, 99, 0.0)
            .atLevel(Skill.SLAYER, 99);

        assertEquals(0L, fixture.multiplierManager.applyTo(Skill.SLAYER, 10_000L));
    }

    /**
     * A fractional multiplier is a penalty, not a delete. Rounding a real deposit
     * away to nothing would read as the plugin having lost it, so any positive
     * multiplier banks at least 1 XP.
     */
    @Test
    void aFractionalMultiplierReducesADepositWithoutErasingIt()
    {
        TestMultipliers fixture = new TestMultipliers()
            .withTier(Skill.FISHING, 1, 0.5)
            .atLevel(Skill.FISHING, 40);

        assertEquals(500L, fixture.multiplierManager.applyTo(Skill.FISHING, 1_000L));
        assertEquals(1L, fixture.multiplierManager.applyTo(Skill.FISHING, 1L));
        assertEquals(1L, XpMultiplierManager.scale(1L, 0.001));
        assertEquals(0L, XpMultiplierManager.scale(1_000L, 0.0));
    }

    @Test
    void skillsHaveEntirelySeparateLadders()
    {
        TestMultipliers fixture = new TestMultipliers()
            .withTier(Skill.COOKING, 50, 2.0)
            .atLevel(Skill.COOKING, 60)
            .atLevel(Skill.FIREMAKING, 99);

        assertEquals(2.0, fixture.multiplierManager.currentMultiplier(Skill.COOKING));
        assertEquals(1.0, fixture.multiplierManager.currentMultiplier(Skill.FIREMAKING));
    }

    @Test
    void thresholdsAreSortedAndDeduplicatedOnSave()
    {
        TestMultipliers fixture = new TestMultipliers();
        fixture.multiplierManager.setTiers(Skill.MAGIC, List.of(
            new XpMultiplierTier(90, 3.0),
            new XpMultiplierTier(50, 1.5),
            // Same level twice: the later entry is the one the editor filled in
            // most recently, so it is the one that survives.
            new XpMultiplierTier(50, 2.5)));

        List<XpMultiplierTier> stored = fixture.multiplierManager.getTiers(Skill.MAGIC);
        assertEquals(2, stored.size());
        assertEquals(50, stored.get(0).getLevel());
        assertEquals(2.5, stored.get(0).getMultiplier());
        assertEquals(90, stored.get(1).getLevel());
    }

    @Test
    void thresholdsSurviveASaveAndReload()
    {
        TestMultipliers fixture = new TestMultipliers()
            .withTier(Skill.HERBLORE, 70, 2.25)
            .withTier(Skill.HERBLORE, 99, 4.0);

        XpMultiplierManager reloaded = new XpMultiplierManager(
            fixture.configManager, fixture.config, fixture.levelTracker, new com.google.gson.Gson());
        reloaded.load();

        assertEquals(2, reloaded.getTiers(Skill.HERBLORE).size());
        assertEquals(2.25, reloaded.multiplierFor(Skill.HERBLORE, 70));
        assertEquals(4.0, reloaded.multiplierFor(Skill.HERBLORE, 99));
    }

    /**
     * Config can be hand-edited, and a skill removed from a future client would
     * leave an entry that no longer parses. Neither may take the whole file down
     * with it, since that would silently wipe every other skill's ladder.
     */
    @Test
    void unusableSavedDataIsRepairedRatherThanDiscardingEverything()
    {
        TestMultipliers fixture = new TestMultipliers();
        fixture.storedValues().put("gokirlbankedxp.xpMultipliersJson",
            "{\"COOKING\":[{\"level\":999,\"multiplier\":-4.0},{\"level\":70,\"multiplier\":2.0}],"
                + "\"NOT_A_SKILL\":[{\"level\":50,\"multiplier\":2.0}]}");

        XpMultiplierManager reloaded = new XpMultiplierManager(
            fixture.configManager, fixture.config, fixture.levelTracker, new com.google.gson.Gson());
        reloaded.load();

        List<XpMultiplierTier> cooking = reloaded.getTiers(Skill.COOKING);
        assertEquals(2, cooking.size());
        assertEquals(70, cooking.get(0).getLevel());
        // Level clamped to the virtual ceiling, multiplier clamped up to zero.
        assertEquals(XpMultiplierTier.MAX_LEVEL, cooking.get(1).getLevel());
        assertEquals(0.0, cooking.get(1).getMultiplier());
    }

    @Test
    void virtualLevelsPastNinetyNineStillTriggerThresholds()
    {
        TestMultipliers fixture = new TestMultipliers()
            .withTier(Skill.THIEVING, 120, 5.0)
            .atLevel(Skill.THIEVING, 120);

        assertEquals(5.0, fixture.multiplierManager.currentMultiplier(Skill.THIEVING));
    }

    /**
     * Switching the feature off must not destroy anything. The ladder stays
     * exactly where it was so it comes back intact when the switch goes on again.
     */
    @Test
    void theMasterSwitchSuspendsMultipliersWithoutDeletingThem()
    {
        TestMultipliers fixture = new TestMultipliers(false)
            .withTier(Skill.RUNECRAFT, 50, 3.0)
            .atLevel(Skill.RUNECRAFT, 77);

        assertFalse(fixture.multiplierManager.isEnabled());
        assertEquals(1.0, fixture.multiplierManager.currentMultiplier(Skill.RUNECRAFT));
        assertEquals(500L, fixture.multiplierManager.applyTo(Skill.RUNECRAFT, 500L));

        // The ladder is untouched: the rule still resolves, it is just not used.
        assertEquals(3.0, fixture.multiplierManager.multiplierFor(Skill.RUNECRAFT, 77));
        assertEquals(1, fixture.multiplierManager.getTiers(Skill.RUNECRAFT).size());
    }

    @Test
    void clearingASkillReturnsItToBankingUnchanged()
    {
        TestMultipliers fixture = new TestMultipliers()
            .withTier(Skill.SMITHING, 40, 2.0)
            .atLevel(Skill.SMITHING, 60);

        fixture.multiplierManager.clearTiers(Skill.SMITHING);

        assertTrue(fixture.multiplierManager.getTiers(Skill.SMITHING).isEmpty());
        assertEquals(1.0, fixture.multiplierManager.currentMultiplier(Skill.SMITHING));
    }

    /** A huge multiplier on a huge deposit clamps instead of wrapping negative. */
    @Test
    void anEnormousResultSaturatesRatherThanOverflowing()
    {
        assertEquals(Long.MAX_VALUE, XpMultiplierManager.scale(Long.MAX_VALUE / 2, 1000.0));
    }
}
