package com.gokirlbankedxp.service;

import com.google.gson.Gson;
import com.gokirlbankedxp.GokIrlBankedXpPlugin;
import com.gokirlbankedxp.model.ActiveTimer;
import com.gokirlbankedxp.model.IrlAction;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Covers timer settlement, immutable earning terms, malformed saves and arithmetic limits. */
class TimerManagerRegressionTest
{
    private static IrlAction action(long seconds, long rate)
    {
        return IrlAction.builder().name("Audit action").unitName("unit")
            .secondsPerUnit(seconds).defaultXpPerUnit(rate)
            .skillMappings(Map.of(Skill.AGILITY, rate)).build();
    }

    @Test
    void shorteningAnActionMustNotAwardPreviouslyPaidTimeAgain()
    {
        IrlAction original = action(60, 10);
        ActiveTimer timer = ActiveTimer.start(original.getId(), 1000);
        assertEquals(100L, timer.applyTick(601000, original).get(Skill.AGILITY).longValue());
        IrlAction edited = original.toBuilder().secondsPerUnit(1).build();
        // Existing timers retain their original minute interval and rate.
        assertTrue(timer.applyTick(602000, edited).isEmpty());
        assertEquals(Map.of(Skill.AGILITY, 10L), timer.applyTick(661000, edited));
    }

    @Test
    void lengtheningAnActionMustNotSuspendAwardsUntilOldUnitCountIsReached()
    {
        IrlAction original = action(1, 10);
        ActiveTimer timer = ActiveTimer.start(original.getId(), 1000);
        timer.applyTick(601000, original);
        IrlAction edited = original.toBuilder().secondsPerUnit(60).build();
        // The existing timer still awards the original one-second units.
        assertEquals(Map.of(Skill.AGILITY, 600L), timer.applyTick(661000, edited));
    }

    @Test
    void stoppingSettlesAWholeUnitSinceTheLastSchedulerTick()
    {
        Fixture f = new Fixture();
        IrlAction action = f.actions.createAction(action(60, 10));
        UUID timer = f.timers.startTimer(action.getId()).orElseThrow();
        f.clock.addAndGet(59000);
        f.timers.tick();
        f.clock.addAndGet(1100);
        assertTrue(f.timers.stopTimer(timer));
        assertEquals(10L, f.awarded.get());
    }

    @Test
    void combinedTimerAwardsMustNotOverflowNegative()
    {
        Fixture f = new Fixture();
        IrlAction action = f.actions.createAction(action(1, Long.MAX_VALUE));
        f.timers.startTimer(action.getId());
        f.timers.startTimer(action.getId());
        f.clock.addAndGet(1000);
        f.timers.tick();
        assertEquals(Long.MAX_VALUE, f.awarded.get());
    }

    @Test
    void nullPersistedTimerMustNotPreventStartup()
    {
        Fixture f = new Fixture();
        f.values.put("gokirlbankedxp.activeTimersJson", "[null]");
        assertDoesNotThrow(f.timers::startUp);
    }

    @Test
    void originalTermsSurviveActionEditAndManagerRecreation()
    {
        Fixture f = new Fixture();
        IrlAction original = f.actions.createAction(action(60, 10));
        UUID timerId = f.timers.startTimer(original.getId()).orElseThrow();
        f.clock.addAndGet(59000);
        f.timers.tick();
        f.actions.updateAction(original.toBuilder().secondsPerUnit(1).skillMappings(Map.of(Skill.AGILITY, 1000L)).build());
        f.timers.shutDown();
        // Recreate both managers, not just the timer model, to exercise the JSON format.
        IrlActionManager reopenedActions = new IrlActionManager(f.config, new Gson());
        reopenedActions.loadActions();
        TimerManager reopened = new TimerManager(f.config, reopenedActions, f.plugin, null, new Gson(), f.clock::get);
        reopened.startUp();
        f.clock.addAndGet(1000);
        reopened.tick();
        assertEquals(10L, f.awarded.get());
        assertEquals(timerId, reopened.getActiveTimers().get(0).getId());
        assertEquals(60L, reopened.getActiveTimers().get(0).getActionSnapshot().getSecondsPerUnit());
        reopened.startTimer(original.getId());
        f.clock.addAndGet(1000);
        reopened.tick();
        assertEquals(1010L, f.awarded.get(), "Only new timers use edited rates");
    }

    @Test
    void nullEntryDoesNotDiscardAdjacentValidTimer()
    {
        Fixture f = new Fixture();
        IrlAction action = f.actions.createAction(action(60, 10));
        UUID timerId = f.timers.startTimer(action.getId()).orElseThrow();
        String key = "gokirlbankedxp.activeTimersJson";
        com.google.gson.JsonArray saved = new Gson().fromJson(f.values.get(key), com.google.gson.JsonArray.class);
        saved.add(com.google.gson.JsonNull.INSTANCE);
        f.values.put(key, saved.toString());
        f.timers.startUp();
        assertEquals(timerId, f.timers.getActiveTimers().get(0).getId());
        assertEquals(1, f.timers.getActiveTimers().size());
    }

    @Test
    void pauseThenStopDoesNotAwardTwiceOrRoundUpPartialUnits()
    {
        Fixture f = new Fixture();
        IrlAction action = f.actions.createAction(action(60, 10));
        UUID timer = f.timers.startTimer(action.getId()).orElseThrow();
        f.clock.addAndGet(61000);
        assertTrue(f.timers.pauseTimer(timer));
        f.clock.addAndGet(60000);
        assertTrue(f.timers.stopTimer(timer));
        assertEquals(10L, f.awarded.get());
        UUID partial = f.timers.startTimer(action.getId()).orElseThrow();
        f.clock.addAndGet(59000);
        assertTrue(f.timers.stopTimer(partial));
        assertFalse(f.timers.stopTimer(partial));
        assertEquals(10L, f.awarded.get());
    }

    @Test
    void legacyTimerWithoutSnapshotMigratesWithoutReawardingCompletedUnits()
    {
        Fixture f = new Fixture();
        IrlAction action = f.actions.createAction(action(60, 10));
        UUID timerId = UUID.randomUUID();
        // This is the previous persisted schema: no frozen action field.
        f.values.put("gokirlbankedxp.activeTimersJson", new Gson().toJson(java.util.List.of(Map.of(
            "id", timerId, "actionId", action.getId(), "elapsedSeconds", 119L, "paused", false))));
        f.timers.startUp();
        assertEquals(60L, f.timers.getActiveTimers().get(0).getActionSnapshot().getSecondsPerUnit());
        f.clock.addAndGet(1000);
        f.timers.tick();
        assertEquals(10L, f.awarded.get(), "Only the newly completed second minute is due");
    }

    /** Mimics the repository's existing persistence fixtures with a controlled clock. */
    private static final class Fixture
    {
        final Map<String, String> values = new HashMap<>();
        final AtomicLong clock = new AtomicLong(1000);
        final AtomicLong awarded = new AtomicLong();
        final IrlActionManager actions;
        final TimerManager timers;
        final ConfigManager config = mock(ConfigManager.class);
        final GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);

        Fixture()
        {
            // ConfigManager's two-argument getter returns a nullable String.
            when(config.getConfiguration(anyString(), anyString())).thenAnswer(call ->
                values.get(call.getArgument(0) + "." + call.getArgument(1)));
            // Its three-argument setter is void; retain the exact serialized value.
            doAnswer(call -> {
                values.put(call.getArgument(0) + "." + call.getArgument(1), String.valueOf((Object) call.getArgument(2)));
                return null;
            }).when(config).setConfiguration(anyString(), anyString(), any());
            // Capture the amount handed to the plugin before its positive-value guard.
            when(plugin.addActionXp(any(Skill.class), anyLong())).thenAnswer(call -> {
                long amount = call.getArgument(1);
                awarded.addAndGet(amount);
                return amount;
            });
            actions = new IrlActionManager(config, new Gson());
            actions.loadActions();
            timers = new TimerManager(config, actions, plugin, null, new Gson(), clock::get);
            timers.startUp();
        }
    }
}
