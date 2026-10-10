package com.gokirlbankedxp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.Test;

/**
 * Covers the saved on/off choice behind the sidebar's Tracked skills grid.
 *
 * <p>The default is the case that matters most: a player who never opens the
 * grid must have every skill tracked, exactly as before the feature existed.</p>
 */
class SkillTrackingManagerTest
{
    private static final String KEY = "gokirlbankedxp." + SkillTrackingManager.CONFIG_KEY;

    @Test
    void everySkillIsTrackedByDefault()
    {
        SkillTrackingManager manager = manager(new HashMap<>());

        for (Skill skill : Skill.values())
        {
            assertTrue(manager.isTracked(skill), skill + " should start out tracked");
        }
        assertTrue(manager.getUntrackedSkills().isEmpty());
    }

    @Test
    void aSkillCanBeSwitchedOffAndBackOn()
    {
        SkillTrackingManager manager = manager(new HashMap<>());

        assertTrue(manager.setTracked(Skill.SLAYER, false));
        assertFalse(manager.isTracked(Skill.SLAYER));
        assertTrue(manager.isTracked(Skill.MINING));
        assertEquals(Set.of(Skill.SLAYER), manager.getUntrackedSkills());

        assertTrue(manager.setTracked(Skill.SLAYER, true));
        assertTrue(manager.isTracked(Skill.SLAYER));
    }

    @Test
    void repeatingTheSameChoiceChangesAndWritesNothing()
    {
        Map<String, String> stored = new HashMap<>();
        SkillTrackingManager manager = manager(stored);

        assertFalse(manager.setTracked(Skill.SLAYER, true));
        assertFalse(stored.containsKey(KEY));

        manager.setTracked(Skill.SLAYER, false);
        assertFalse(manager.setTracked(Skill.SLAYER, false));
    }

    @Test
    void theChoiceSurvivesAReload()
    {
        Map<String, String> stored = new HashMap<>();
        SkillTrackingManager manager = manager(stored);
        manager.setTracked(Skill.SLAYER, false);
        manager.setTracked(Skill.ATTACK, false);

        // Declaration order, so the stored value is stable between saves.
        assertEquals("[\"ATTACK\",\"SLAYER\"]", stored.get(KEY));

        SkillTrackingManager reloaded = manager(stored);
        assertEquals(Set.of(Skill.ATTACK, Skill.SLAYER), reloaded.getUntrackedSkills());
    }

    @Test
    void anUnknownSkillNameIsIgnoredRatherThanFailingTheWholeList()
    {
        Map<String, String> stored = new HashMap<>();
        stored.put(KEY, "[\"SLAYER\",\"NOT_A_SKILL\",null]");

        assertEquals(Set.of(Skill.SLAYER), manager(stored).getUntrackedSkills());
    }

    @Test
    void anUnreadableValueFallsBackToTrackingEverything()
    {
        Map<String, String> stored = new HashMap<>();
        stored.put(KEY, "{not json");

        assertTrue(manager(stored).getUntrackedSkills().isEmpty());
    }

    @Test
    void theReportedSetCannotBeEditedByCallers()
    {
        SkillTrackingManager manager = manager(new HashMap<>());
        manager.setTracked(Skill.SLAYER, false);

        assertThrows(UnsupportedOperationException.class,
            () -> manager.getUntrackedSkills().add(Skill.MINING));
    }

    private static SkillTrackingManager manager(Map<String, String> stored)
    {
        ConfigManager configManager = TestMultipliers.inMemoryConfigManager(stored);
        SkillTrackingManager manager = new SkillTrackingManager(configManager, new Gson());
        manager.load();
        return manager;
    }
}
