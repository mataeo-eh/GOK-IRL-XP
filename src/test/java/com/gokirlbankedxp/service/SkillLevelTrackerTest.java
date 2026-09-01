package com.gokirlbankedxp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.Test;

/**
 * Covers the level cache the multipliers resolve against.
 *
 * <p>The persistence case is the one that matters: banking XP from the sidebar
 * works at the login screen, where the client cannot be asked for a level. If
 * levels were session-only, every such deposit would silently be multiplied as
 * though the player were level 1.</p>
 */
class SkillLevelTrackerTest
{
    @Test
    void levelsAreDerivedFromExperience()
    {
        SkillLevelTracker tracker = tracker(new HashMap<>());

        assertEquals(1, tracker.recordExperience(Skill.COOKING, 0));
        assertEquals(70, tracker.recordExperience(Skill.COOKING, Experience.getXpForLevel(70)));
        assertEquals(99, tracker.recordExperience(Skill.COOKING, Experience.getXpForLevel(99)));
        assertEquals(99, tracker.getLevel(Skill.COOKING));
    }

    @Test
    void virtualLevelsAreReportedPastNinetyNine()
    {
        SkillLevelTracker tracker = tracker(new HashMap<>());

        assertEquals(110, tracker.recordExperience(Skill.SLAYER, Experience.getXpForLevel(110)));
        assertEquals(Experience.MAX_VIRT_LEVEL,
            tracker.recordExperience(Skill.SLAYER, Experience.MAX_SKILL_XP));
    }

    @Test
    void anUnseenSkillReportsLevelOneAndSaysItWasNeverObserved()
    {
        SkillLevelTracker tracker = tracker(new HashMap<>());
        tracker.recordExperience(Skill.COOKING, Experience.getXpForLevel(50));

        assertEquals(SkillLevelTracker.UNKNOWN_LEVEL, tracker.getLevel(Skill.MINING));
        assertFalse(tracker.hasObserved(Skill.MINING));
        assertTrue(tracker.hasObserved(Skill.COOKING));
    }

    @Test
    void levelsSurviveAReloadSoLoggedOutDepositsStillMultiplyCorrectly()
    {
        Map<String, String> stored = new HashMap<>();
        SkillLevelTracker tracker = tracker(stored);
        tracker.recordExperience(Skill.AGILITY, Experience.getXpForLevel(85));

        SkillLevelTracker reloaded = tracker(stored);
        reloaded.load();

        assertEquals(85, reloaded.getLevel(Skill.AGILITY));
        assertTrue(reloaded.hasObserved(Skill.AGILITY));
    }

    /**
     * Levels change rarely and experience changes constantly, so only a level
     * change may write to config; otherwise every XP drop in the game would.
     */
    @Test
    void onlyALevelChangeWritesToConfig()
    {
        Map<String, String> stored = new HashMap<>();
        SkillLevelTracker tracker = tracker(stored);

        tracker.recordExperience(Skill.RANGED, Experience.getXpForLevel(60));
        String afterFirst = stored.get("gokirlbankedxp.observedSkillLevels");
        stored.remove("gokirlbankedxp.observedSkillLevels");

        // More experience, same level: nothing new should be written.
        tracker.recordExperience(Skill.RANGED, Experience.getXpForLevel(60) + 500);
        assertFalse(stored.containsKey("gokirlbankedxp.observedSkillLevels"));

        tracker.recordExperience(Skill.RANGED, Experience.getXpForLevel(61));
        assertTrue(stored.containsKey("gokirlbankedxp.observedSkillLevels"));
        assertTrue(afterFirst.contains("RANGED"));
    }

    @Test
    void unusableSavedLevelsAreIgnoredRatherThanThrowing()
    {
        Map<String, String> stored = new HashMap<>();
        stored.put("gokirlbankedxp.observedSkillLevels", "{\"NOT_A_SKILL\":50,\"MINING\":999}");

        SkillLevelTracker tracker = tracker(stored);
        tracker.load();

        // Clamped to the virtual ceiling rather than trusted or dropped.
        assertEquals(Experience.MAX_VIRT_LEVEL, tracker.getLevel(Skill.MINING));
    }

    private static SkillLevelTracker tracker(Map<String, String> stored)
    {
        ConfigManager configManager = TestMultipliers.inMemoryConfigManager(stored);
        SkillLevelTracker tracker = new SkillLevelTracker(configManager, new Gson());
        tracker.load();
        return tracker;
    }
}
