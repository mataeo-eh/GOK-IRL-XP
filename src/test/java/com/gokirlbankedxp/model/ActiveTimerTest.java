package com.gokirlbankedxp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import net.runelite.api.Skill;
import org.junit.jupiter.api.Test;

class ActiveTimerTest
{
    private static final IrlAction SECOND_BASED_ACTION = IrlAction.builder()
        .name("Test action")
        .timeUnit(TimeUnit.SECONDS)
        .defaultXpPerUnit(2L)
        .skillMappings(Map.of(Skill.AGILITY, 2L))
        .build();

    @Test
    void schedulerJitterDoesNotDiscardFractionalTime()
    {
        ActiveTimer timer = ActiveTimer.start(UUID.randomUUID(), 10_000L);

        assertEquals(Map.of(), timer.applyTick(10_600L, SECOND_BASED_ACTION));
        assertEquals(2L, timer.applyTick(11_200L, SECOND_BASED_ACTION).get(Skill.AGILITY));
        assertEquals(1L, timer.getElapsedSeconds());
        assertEquals(1L, timer.getDisplayElapsedSeconds(11_200L));
    }

    @Test
    void pausingCommitsWholeSecondsSinceLastTick()
    {
        ActiveTimer timer = ActiveTimer.start(UUID.randomUUID(), 20_000L);

        timer.pause(22_400L);

        assertEquals(2L, timer.getElapsedSeconds());
        assertEquals(2L, timer.getDisplayElapsedSeconds(30_000L));
    }
}
