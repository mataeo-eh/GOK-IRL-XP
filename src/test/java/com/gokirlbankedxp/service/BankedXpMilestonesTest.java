package com.gokirlbankedxp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import org.junit.jupiter.api.Test;

/** Verifies milestone crossings and level coverage against RuneLite's XP table. */
class BankedXpMilestonesTest
{
    private final AtomicLong now = new AtomicLong();
    private final BankedXpMilestones milestones = new BankedXpMilestones(now::get);
    private final GokIrlBankedXpConfig config = mock(GokIrlBankedXpConfig.class);

    @Test
    void thresholdFiresOnceOnCrossingAndRearmsAfterTopUp()
    {
        when(config.xpMilestoneEnabled()).thenReturn(true);
        when(config.milestoneXpThreshold()).thenReturn(500);
        milestones.onXpGain(Skill.MINING, 1000, 1100, 600, 500, config);
        assertFalse(milestones.getLines().isEmpty());
        milestones.clear();
        milestones.onXpGain(Skill.MINING, 1100, 1200, 500, 400, config);
        assertTrue(milestones.getLines().isEmpty());
        // A deposit raises the balance above the threshold; no reset flag is needed.
        milestones.onXpGain(Skill.MINING, 1200, 1800, 1000, 400, config);
        assertFalse(milestones.getLines().isEmpty());
    }

    @Test
    void realLevelUpShowsTwoThenOneThenZeroFurtherLevels()
    {
        when(config.levelMilestoneEnabled()).thenReturn(true);
        when(config.milestoneLevelsRemaining()).thenReturn(2);
        int target = Experience.getXpForLevel(53);
        for (int level = 51; level <= 53; level++)
        {
            int xp = Experience.getXpForLevel(level);
            milestones.onXpGain(Skill.MINING, xp - 1, xp, target - xp + 1, target - xp, config);
            int remaining = 53 - level;
            assertTrue(milestones.getLines().contains(remaining + " further "
                + (remaining == 1 ? "level" : "levels") + " banked"));
            milestones.clear();
        }
    }

    @Test
    void filtersLargeCoverageAndXpDropsWithoutLevelUps()
    {
        when(config.levelMilestoneEnabled()).thenReturn(true);
        when(config.milestoneLevelsRemaining()).thenReturn(2);
        int xp = Experience.getXpForLevel(51);
        long balance = Experience.getXpForLevel(60) - xp;
        milestones.onXpGain(Skill.MINING, xp - 1, xp, balance + 1, balance, config);
        assertTrue(milestones.getLines().isEmpty());
        milestones.onXpGain(Skill.MINING, xp, xp + 1, 100, 99, config);
        assertTrue(milestones.getLines().isEmpty());
    }

    @Test
    void multipleSkillsExpireIndependentlyAndZeroDurationRequiresDismissal()
    {
        when(config.xpMilestoneEnabled()).thenReturn(true);
        when(config.milestoneXpThreshold()).thenReturn(500);
        when(config.milestonePopupSeconds()).thenReturn(30);
        milestones.onXpGain(Skill.MINING, 1000, 1100, 600, 500, config);
        now.set(1000);
        milestones.onXpGain(Skill.FISHING, 1000, 1100, 600, 500, config);
        assertEquals(4, milestones.getLines().size());
        now.set(30_000);
        assertEquals(2, milestones.getLines().size());
        now.set(31_000);
        assertTrue(milestones.getLines().isEmpty());
        when(config.milestonePopupSeconds()).thenReturn(0);
        milestones.onXpGain(Skill.MINING, 1000, 1100, 600, 500, config);
        now.set(1_000_000);
        assertFalse(milestones.getLines().isEmpty());
        milestones.clear();
        assertTrue(milestones.getLines().isEmpty());
    }

    @Test
    void handlesEmptyBanksDebtSkippedLevelsMaxLevelAndHugeBalances()
    {
        when(config.xpMilestoneEnabled()).thenReturn(true);
        when(config.levelMilestoneEnabled()).thenReturn(true);
        when(config.milestoneLevelsRemaining()).thenReturn(98);
        milestones.onXpGain(Skill.MINING, 0, 1000, 0, -1000, config);
        assertTrue(milestones.getLines().isEmpty());
        milestones.onXpGain(Skill.MINING, 0, 1000, 100, -900, config);
        assertTrue(milestones.getLines().contains("0 further levels banked"));
        milestones.clear();
        int max = Experience.getXpForLevel(99);
        milestones.onXpGain(Skill.MINING, max - 1, max, Long.MAX_VALUE, Long.MAX_VALUE - 1, config);
        assertTrue(milestones.getLines().contains("Maximum real level reached"));
        milestones.clear();
        milestones.onXpGain(Skill.MINING, max, max + 100, 1000, 900, config);
        assertTrue(milestones.getLines().isEmpty());
    }
}
