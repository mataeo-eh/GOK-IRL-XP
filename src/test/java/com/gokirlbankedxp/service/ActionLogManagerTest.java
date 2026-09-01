package com.gokirlbankedxp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gokirlbankedxp.GokIrlBankedXpPlugin;
import com.gokirlbankedxp.model.IrlAction;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.Test;

/** Covers banking XP for work the user logs after finishing it. */
class ActionLogManagerTest
{
    @Test
    void logsRepetitionsOfUnitsAsBankedXp()
    {
        Fixture fixture = new Fixture();
        // "250 XP per km", the benchmark-style shape that has no timer at all.
        IrlAction running = fixture.createAction("Running", "km", 250L, Skill.AGILITY);

        // 12 km, nine times over.
        ActionLogManager.LoggedAward award =
            fixture.logManager.log(running.getId(), 12L, 9L).orElseThrow();

        assertEquals(108L, award.getTotalUnits());
        assertEquals(Map.of(Skill.AGILITY, 27_000L), award.getAwardedXp());
        verify(fixture.plugin).addActionXp(Skill.AGILITY, 27_000L);
    }

    @Test
    void everyMappedSkillIsAwardedAtItsOwnRate()
    {
        Fixture fixture = new Fixture();
        IrlAction lifting = fixture.actionManager.createAction(IrlAction.builder()
            .name("Weight lifting")
            .unitName("pounds")
            .timed(Boolean.FALSE)
            .defaultXpPerUnit(1L)
            .skillMappings(Map.of(Skill.STRENGTH, 2L, Skill.HITPOINTS, 1L))
            .build());

        ActionLogManager.LoggedAward award =
            fixture.logManager.log(lifting.getId(), 100L, 3L).orElseThrow();

        assertEquals(300L, award.getTotalUnits());
        assertEquals(Map.of(Skill.STRENGTH, 600L, Skill.HITPOINTS, 300L), award.getAwardedXp());
        verify(fixture.plugin).addActionXp(Skill.STRENGTH, 600L);
        verify(fixture.plugin).addActionXp(Skill.HITPOINTS, 300L);
    }

    @Test
    void previewMatchesTheAwardWithoutBankingAnything()
    {
        Fixture fixture = new Fixture();
        IrlAction running = fixture.createAction("Running", "km", 250L, Skill.AGILITY);

        ActionLogManager.LoggedAward preview =
            fixture.logManager.preview(running.getId(), 25L, 1L).orElseThrow();

        assertEquals(25L, preview.getTotalUnits());
        assertEquals(Map.of(Skill.AGILITY, 6_250L), preview.getAwardedXp());
        // A preview must never touch banked totals.
        verifyNoInteractions(fixture.plugin);
    }

    @Test
    void nothingIsBankedForUnknownActionsOrNonPositiveCounts()
    {
        Fixture fixture = new Fixture();
        IrlAction running = fixture.createAction("Running", "km", 250L, Skill.AGILITY);

        assertTrue(fixture.logManager.log(UUID.randomUUID(), 5L, 1L).isEmpty());
        assertTrue(fixture.logManager.log(running.getId(), 0L, 1L).isEmpty());
        assertTrue(fixture.logManager.log(running.getId(), 5L, 0L).isEmpty());
        assertTrue(fixture.logManager.log(running.getId(), -5L, 2L).isEmpty());
        verifyNoInteractions(fixture.plugin);
    }

    @Test
    void timedActionsCanAlsoBeLoggedByHand()
    {
        Fixture fixture = new Fixture();
        IrlAction reading = fixture.actionManager.createAction(IrlAction.builder()
            .name("Read a chapter")
            .unitName("Chapter")
            .secondsPerUnit(300L)
            .timed(Boolean.TRUE)
            .defaultXpPerUnit(25L)
            .skillMappings(Map.of(Skill.MAGIC, 25L))
            .build());

        ActionLogManager.LoggedAward award =
            fixture.logManager.log(reading.getId(), 4L, 2L).orElseThrow();

        assertEquals(200L, award.getAwardedXp().get(Skill.MAGIC));
    }

    /** Wires a real action manager over an in-memory config with a mocked plugin. */
    private static final class Fixture
    {
        private final GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        private final IrlActionManager actionManager;
        private final ActionLogManager logManager;

        Fixture()
        {
            // The real plugin returns what it banked, and the log manager reports
            // that figure rather than its own estimate, so the mock has to answer
            // the same way or every award reads as zero.
            when(plugin.addActionXp(any(Skill.class), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1, Long.class));

            actionManager = new IrlActionManager(inMemoryConfigManager(new HashMap<>()), new Gson());
            actionManager.loadActions();
            logManager = new ActionLogManager(actionManager, plugin, new TestMultipliers().multiplierManager);
        }

        IrlAction createAction(String name, String unit, long xpPerUnit, Skill skill)
        {
            return actionManager.createAction(IrlAction.builder()
                .name(name)
                .unitName(unit)
                .timed(Boolean.FALSE)
                .defaultXpPerUnit(xpPerUnit)
                .skillMappings(Map.of(skill, xpPerUnit))
                .build());
        }
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
