package com.gokirlbankedxp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gokirlbankedxp.GokIrlBankedXpPlugin;
import com.gokirlbankedxp.model.IrlAction;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.Test;

/** Verifies the same config-backed save/load boundary used across client sessions. */
class PersistenceTest
{
    @Test
    void legacyPresetUnitIsMigratedToTheCustomUnitShape()
    {
        UUID id = UUID.randomUUID();
        Map<String, String> persistedConfig = new HashMap<>();
        persistedConfig.put("gokirlbankedxp.irlActionsJson", String.format(
            "[{\"id\":\"%s\",\"name\":\"Walking\",\"timeUnit\":\"MINUTES\","
                + "\"defaultXpPerUnit\":50,\"skillMappings\":{\"AGILITY\":50}}]", id));

        IrlActionManager manager = new IrlActionManager(inMemoryConfigManager(persistedConfig));
        manager.loadActions();

        IrlAction migrated = manager.getAllActions().get(0);
        assertEquals("Minutes", migrated.getUnitName());
        assertEquals(60L, migrated.getSecondsPerUnit());
        assertFalse(persistedConfig.get("gokirlbankedxp.irlActionsJson").contains("timeUnit"));
    }

    @Test
    void customActionsUnitsAndTimersSurviveManagerRecreation()
    {
        Map<String, String> persistedConfig = new HashMap<>();
        ConfigManager configManager = inMemoryConfigManager(persistedConfig);

        IrlActionManager firstActions = new IrlActionManager(configManager);
        firstActions.loadActions();
        assertTrue(firstActions.getAllActions().isEmpty());

        IrlAction action = firstActions.createAction(IrlAction.builder()
            .name("Read a chapter")
            .unitName("Chapter")
            .secondsPerUnit(300L)
            .defaultXpPerUnit(25L)
            .skillMappings(Map.of(Skill.MAGIC, 25L))
            .build());

        AtomicLong clock = new AtomicLong(10_000L);
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        TimerManager firstTimers = new TimerManager(configManager, firstActions, plugin, clock::get, null);
        firstTimers.startUp();
        assertTrue(firstTimers.startTimer(action.getId()).isPresent());
        clock.addAndGet(5_000L);
        firstTimers.tick();
        firstTimers.shutDown();

        // Recreate both managers to model closing RuneLite and opening it again.
        IrlActionManager reopenedActions = new IrlActionManager(configManager);
        reopenedActions.loadActions();
        assertEquals(1, reopenedActions.getAllActions().size());
        IrlAction reopenedAction = reopenedActions.getAllActions().get(0);
        assertEquals(action.getId(), reopenedAction.getId());
        assertEquals("Chapter", reopenedAction.getUnitName());
        assertEquals(300L, reopenedAction.getSecondsPerUnit());
        assertEquals(Map.of("Chapter", 300L), reopenedActions.getSavedUnits());

        TimerManager reopenedTimers = new TimerManager(configManager, reopenedActions, plugin, clock::get, null);
        reopenedTimers.startUp();
        assertEquals(1, reopenedTimers.getTimerSnapshots().size());
        assertEquals(action.getId(), reopenedTimers.getActiveTimers().get(0).getActionId());
        assertEquals(5L, reopenedTimers.getActiveTimers().get(0).getElapsedSeconds());

        // Units are a persistent user library, not merely a projection of the
        // actions that currently happen to reference them.
        assertTrue(reopenedActions.deleteAction(action.getId()));
        IrlActionManager afterDeletion = new IrlActionManager(configManager);
        afterDeletion.loadActions();
        assertTrue(afterDeletion.getAllActions().isEmpty());
        assertEquals(Map.of("Chapter", 300L), afterDeletion.getSavedUnits());
    }

    private static ConfigManager inMemoryConfigManager(Map<String, String> values)
    {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfiguration(anyString(), anyString())).thenAnswer(invocation ->
            values.get(invocation.getArgument(0) + "." + invocation.getArgument(1)));
        doAnswer(invocation -> {
            String key = invocation.getArgument(0) + "." + invocation.getArgument(1);
            Object value = invocation.getArgument(2);
            values.put(key, String.valueOf(value));
            return null;
        }).when(configManager).setConfiguration(anyString(), anyString(), any());
        return configManager;
    }
}
