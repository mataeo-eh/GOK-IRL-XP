package com.gokirlbankedxp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gokirlbankedxp.service.BankedXpStore;
import com.gokirlbankedxp.service.DepletionForecaster;
import com.gokirlbankedxp.service.TestMultipliers;
import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Notification;
import org.junit.jupiter.api.Test;

/**
 * Covers the ways banked XP moves other than a plain deposit: the user
 * correcting a mistake, the plugin warning that a skill is about to run dry, and
 * in-game XP outrunning the bank, which leaves the skill in debt.
 *
 * <p>RuneLite builds the plugin through its own child injector, which a unit test
 * cannot start, so the {@code @Inject} fields are set reflectively here. That is
 * confined to test code, which the Plugin Hub never compiles.</p>
 */
class BankedXpAdjustmentTest
{
    @Test
    void removingXpReducesTheBalance()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.COOKING, 500L);

        assertEquals(200L, fixture.plugin.removeManualXp(Skill.COOKING, 200L));
        assertEquals(300L, fixture.plugin.getBankedXp(Skill.COOKING));
        assertEquals("COOKING:300", fixture.storedXp());
    }

    @Test
    void removingMoreThanIsBankedEmptiesTheSkillRatherThanGoingNegative()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.AGILITY, 500L);

        // The caller is told what was actually taken, not what it asked for.
        assertEquals(500L, fixture.plugin.removeManualXp(Skill.AGILITY, 999_999L));
        assertEquals(0L, fixture.plugin.getBankedXp(Skill.AGILITY));
        assertEquals("", fixture.storedXp());
    }

    @Test
    void removingFromASkillWithNothingBankedDoesNothing()
    {
        Fixture fixture = new Fixture();

        assertEquals(0L, fixture.plugin.removeManualXp(Skill.AGILITY, 500L));
        assertEquals(0L, fixture.plugin.getBankedXp(Skill.AGILITY));
    }

    @Test
    void removalRejectsNonPositiveAmounts()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.MINING, 500L);

        // Removal is the only way a balance goes down by hand, so it must not
        // become a back door for adding XP through a negative amount.
        assertEquals(0L, fixture.plugin.removeManualXp(Skill.MINING, -100L));
        assertEquals(0L, fixture.plugin.removeManualXp(Skill.MINING, 0L));
        assertEquals(500L, fixture.plugin.getBankedXp(Skill.MINING));
    }

    @Test
    void bankingStillRefusesNegativeAmounts()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.MINING, 500L);

        fixture.plugin.addManualXp(Skill.MINING, -500L);

        assertEquals(500L, fixture.plugin.getBankedXp(Skill.MINING));
    }

    @Test
    void bankedBalanceIsReadableForBoundingRemovals()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.FISHING, 1_234L);

        assertEquals(1_234L, fixture.plugin.getBankedXp(Skill.FISHING));
        assertEquals(0L, fixture.plugin.getBankedXp(Skill.HUNTER));
        assertEquals(0L, fixture.plugin.getBankedXp(null));
        assertTrue(fixture.storedXp().contains("FISHING:1234"));
    }

    @Test
    void warnsOnceWhenTheNextFewActionsWouldEmptyTheSkill()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.WOODCUTTING, 1_000L);

        // Four drops leave 600, against a forecast of five more at 100 each.
        fixture.gainXp(Skill.WOODCUTTING, 100, 4);
        verify(fixture.notifier, never()).notify(any(Notification.class), anyString());

        // The fifth takes it to 500, which no longer clears the forecast.
        fixture.gainXp(Skill.WOODCUTTING, 100, 1);
        verify(fixture.notifier, times(1)).notify(any(Notification.class), contains("Woodcutting"));

        // Still draining, already warned: it must not repeat on every drop.
        fixture.gainXp(Skill.WOODCUTTING, 100, 2);
        verify(fixture.notifier, times(1)).notify(any(Notification.class), anyString());
    }

    @Test
    void theWarningNamesTheSkillAndWhatIsLeft()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.AGILITY, 1_000L);

        fixture.gainXp(Skill.AGILITY, 100, 5);

        verify(fixture.notifier).notify(
            eq(Notification.ON),
            contains("Agility banked XP is nearly gone"));
    }

    /**
     * In combat the learned rate moves on every hit, so the forecast can dip
     * back under the balance right after a warning. That must not count as a
     * recovery: the balance only ever falls between deposits, and re-arming on
     * a forecast wobble fired the red flash on every other drop all the way down.
     */
    @Test
    void aFluctuatingForecastDoesNotRepeatTheWarning()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.WOODCUTTING, 1_000L);

        // Five drops of 100 leave 500 against a forecast of 500: warned once.
        fixture.gainXp(Skill.WOODCUTTING, 100, 5);
        verify(fixture.notifier, times(1)).notify(any(Notification.class), anyString());

        // A small drop pulls the average down: 490 left against a forecast of
        // 410, which the old code read as "recovered" and re-armed on.
        fixture.gainXp(Skill.WOODCUTTING, 10, 1);
        // The next normal drop puts the forecast back over the balance.
        fixture.gainXp(Skill.WOODCUTTING, 100, 1);

        verify(fixture.notifier, times(1)).notify(any(Notification.class), anyString());
    }

    @Test
    void toppingUpReArmsTheWarning()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.WOODCUTTING, 1_000L);
        fixture.gainXp(Skill.WOODCUTTING, 100, 5);
        verify(fixture.notifier, times(1)).notify(any(Notification.class), anyString());

        // Back up to 700, above the 500 forecast, then drained under it again.
        fixture.plugin.addManualXp(Skill.WOODCUTTING, 200L);
        fixture.gainXp(Skill.WOODCUTTING, 100, 2);

        verify(fixture.notifier, times(2)).notify(any(Notification.class), anyString());
    }

    @Test
    void correctingABalanceNeverFiresTheWarning()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.WOODCUTTING, 10_000L);
        // Teach the forecaster a rate without getting anywhere near depletion.
        fixture.gainXp(Skill.WOODCUTTING, 100, 5);

        // Emptying the bank by hand is an edit, not something the user did
        // in-game, so it must not flash the screen at them.
        fixture.plugin.removeManualXp(Skill.WOODCUTTING, 9_400L);

        verify(fixture.notifier, never()).notify(any(Notification.class), anyString());
    }

    @Test
    void correctionsDoNotTeachTheForecasterARate()
    {
        Fixture fixture = new Fixture();

        fixture.plugin.addManualXp(Skill.SMITHING, 5_000L);
        fixture.plugin.removeManualXp(Skill.SMITHING, 4_000L);

        assertEquals(0, fixture.forecaster.sampleCount(Skill.SMITHING));
    }

    @Test
    void aZeroWindowTurnsTheWarningOff()
    {
        Fixture fixture = new Fixture();
        when(fixture.config.depletionWarningActions()).thenReturn(0);
        fixture.plugin.addManualXp(Skill.WOODCUTTING, 300L);

        fixture.gainXp(Skill.WOODCUTTING, 100, 2);

        verify(fixture.notifier, never()).notify(any(Notification.class), anyString());
        assertEquals(0, fixture.forecaster.sampleCount(Skill.WOODCUTTING));
    }

    // ---- debt: in-game XP outrunning the bank ------------------------------

    @Test
    void gainingXpWithNothingBankedPutsTheSkillInDebt()
    {
        Fixture fixture = new Fixture();

        // A quest reward of 5,000 Woodcutting XP with nothing banked for it.
        fixture.gainXp(Skill.WOODCUTTING, 5_000, 1);

        assertEquals(-5_000L, fixture.plugin.getBankedXp(Skill.WOODCUTTING));
        assertEquals("WOODCUTTING:-5000", fixture.storedXp());
    }

    @Test
    void trainingPastTheBalanceCarriesTheOverflowIntoDebt()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.WOODCUTTING, 300L);

        fixture.gainXp(Skill.WOODCUTTING, 500, 1);

        assertEquals(-200L, fixture.plugin.getBankedXp(Skill.WOODCUTTING));
    }

    @Test
    void depositsPayOffTheDebtBeforeAnythingShowsAsBanked()
    {
        Fixture fixture = new Fixture();
        fixture.gainXp(Skill.WOODCUTTING, 5_000, 1);

        fixture.plugin.addManualXp(Skill.WOODCUTTING, 3_000L);
        assertEquals(-2_000L, fixture.plugin.getBankedXp(Skill.WOODCUTTING));

        // Exactly clearing the debt leaves no entry at all, the same as never
        // having banked or owed anything.
        fixture.plugin.addManualXp(Skill.WOODCUTTING, 2_000L);
        assertEquals(0L, fixture.plugin.getBankedXp(Skill.WOODCUTTING));
        assertEquals("", fixture.storedXp());

        // Only now does the balance start counting as banked again.
        fixture.plugin.addManualXp(Skill.WOODCUTTING, 100L);
        assertEquals(100L, fixture.plugin.getBankedXp(Skill.WOODCUTTING));
    }

    @Test
    void removalCannotDeepenADebt()
    {
        Fixture fixture = new Fixture();
        fixture.gainXp(Skill.WOODCUTTING, 5_000, 1);

        // Debt is only ever earned in the game; the sidebar cannot add to it.
        assertEquals(0L, fixture.plugin.removeManualXp(Skill.WOODCUTTING, 100L));
        assertEquals(-5_000L, fixture.plugin.getBankedXp(Skill.WOODCUTTING));
    }

    @Test
    void aDebtIsShownInTheSnapshotAndNettedIntoTheTotal()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.COOKING, 10_000L);
        fixture.gainXp(Skill.WOODCUTTING, 4_000, 1);

        GokIrlBankedXpPlugin.BankedXpSnapshot snapshot = fixture.plugin.getCurrentSnapshot();

        assertTrue(snapshot.hasData());
        assertEquals(6_000L, snapshot.getTotalXp());
        assertEquals(2, snapshot.getSkills().size());
        // Largest balance first, so the debt sits at the bottom of the list.
        GokIrlBankedXpPlugin.BankedSkill debt = snapshot.getSkills().get(1);
        assertEquals(Skill.WOODCUTTING, debt.getSkill());
        assertEquals(-4_000L, debt.getRemainingXp());
        assertTrue(debt.isInDebt());
        assertFalse(debt.isBelowThreshold(), "a debt is its own state, not a low balance");
    }

    @Test
    void aBankThatIsEntirelyDebtStillHasSomethingToShow()
    {
        Fixture fixture = new Fixture();
        fixture.gainXp(Skill.WOODCUTTING, 4_000, 1);

        GokIrlBankedXpPlugin.BankedXpSnapshot snapshot = fixture.plugin.getCurrentSnapshot();

        // The total is below zero, but the overlay and sidebar must not go
        // blank: the debt is exactly what the user needs to see.
        assertTrue(snapshot.hasData());
        assertEquals(-4_000L, snapshot.getTotalXp());
    }

    @Test
    void goingIntoDebtFiresNoWarnings()
    {
        Fixture fixture = new Fixture();
        when(fixture.config.chatWarningEnabled()).thenReturn(true);
        when(fixture.config.lowXpThreshold()).thenReturn(500);

        fixture.gainXp(Skill.WOODCUTTING, 1_000, 5);

        // Both warnings are about a balance that is about to run out; a skill
        // with nothing banked has nothing to run out of.
        verify(fixture.notifier, never()).notify(any(Notification.class), anyString());
        verify(fixture.client, never()).addChatMessage(any(), anyString(), anyString(), any());
    }

    /**
     * A freshly started client reports 0 XP for every skill until it has
     * processed the login's stat sync, which lands after LOGGED_IN is announced.
     * The plugin used to take its baseline at LOGGED_IN, record those zeros, and
     * then charge the sync's own StatChanged burst against the bank: a player
     * with 15,185 Agility XP lost exactly 15,185 banked Agility XP on every
     * restart, and skills with less banked than earned were wiped outright.
     */
    @Test
    void loggingInOnAFreshClientDoesNotChargeTheBankForXpAlreadyEarned()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.AGILITY, 32_409L);
        fixture.plugin.addManualXp(Skill.COOKING, 1_000L);

        Map<Skill, Integer> totals = new HashMap<>();
        totals.put(Skill.AGILITY, 15_185);
        totals.put(Skill.COOKING, 40_000);
        fixture.logInOnFreshClient(totals);

        assertEquals(32_409L, fixture.plugin.getBankedXp(Skill.AGILITY));
        assertEquals(1_000L, fixture.plugin.getBankedXp(Skill.COOKING));
        // The bug also announced itself: a login-sized "drop" looked like the
        // skill was about to run dry, so the depletion warning fired at once.
        verify(fixture.notifier, never()).notify(any(Notification.class), anyString());

        // Only XP earned after the login is charged, and only the difference.
        fixture.gainXp(Skill.AGILITY, 50, 1);
        assertEquals(32_359L, fixture.plugin.getBankedXp(Skill.AGILITY));
    }

    /**
     * The two-tick wait is what RuneLite's own XP tracker uses, but the baseline
     * must still never be taken from a client that has not received the sync.
     * Every account has at least level 10 Hitpoints, so 0 Hitpoints XP is proof
     * the sync has not landed, and the plugin keeps waiting rather than record it.
     */
    @Test
    void baselineWaitsUntilTheClientActuallyHoldsTheSyncedXp()
    {
        Fixture fixture = new Fixture();
        fixture.plugin.addManualXp(Skill.AGILITY, 32_409L);

        fixture.plugin.onGameStateChanged(gameState(GameState.LOGGING_IN));
        when(fixture.client.getGameState()).thenReturn(GameState.LOGGED_IN);
        fixture.plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));
        // The wait runs out while the client still reports 0 XP everywhere.
        fixture.plugin.onGameTick(new GameTick());
        fixture.plugin.onGameTick(new GameTick());
        fixture.plugin.onGameTick(new GameTick());

        // Now the sync lands, then another tick passes.
        fixture.syncSkill(Skill.HITPOINTS, 1_154);
        fixture.syncSkill(Skill.AGILITY, 15_185);
        fixture.plugin.onGameTick(new GameTick());

        assertEquals(32_409L, fixture.plugin.getBankedXp(Skill.AGILITY));

        fixture.gainXp(Skill.AGILITY, 50, 1);
        assertEquals(32_359L, fixture.plugin.getBankedXp(Skill.AGILITY));
    }

    private static GameStateChanged gameState(GameState state)
    {
        GameStateChanged event = new GameStateChanged();
        event.setGameState(state);
        return event;
    }

    /** A plugin wired to mocks, with banked XP kept in an in-memory config map. */
    private static final class Fixture
    {
        private final GokIrlBankedXpPlugin plugin = new GokIrlBankedXpPlugin();
        private final GokIrlBankedXpConfig config = mock(GokIrlBankedXpConfig.class);
        private final Notifier notifier = mock(Notifier.class);
        private final Client client = mock(Client.class);
        private final DepletionForecaster forecaster = new DepletionForecaster();
        private final Map<String, String> stored = new HashMap<>();

        /** Mirrors the in-game total per skill, so XP drops can be derived from it. */
        private final Map<Skill, Integer> inGameXp = new HashMap<>();

        Fixture()
        {
            // A zero low-XP threshold keeps the older, unrelated chat warning out
            // of these cases; it is covered by its own behaviour, not by this.
            when(config.lowXpThreshold()).thenReturn(0);
            when(config.chatWarningEnabled()).thenReturn(false);
            when(config.depletionWarningActions()).thenReturn(5);
            when(config.depletionNotification()).thenReturn(Notification.ON);

            set("config", config);
            set("notifier", notifier);
            set("depletionForecaster", forecaster);
            // The real store, pointed at a throwaway directory, so these cases run
            // the genuine save path without touching the developer's ~/.runelite.
            set("bankedXpStore", new BankedXpStore(inMemoryConfigManager(stored), new Gson(), TestDataDir.create()));
            set("client", client);
            set("panel", mock(GokIrlXpPanel.class));
            // The client reports whatever the in-game map holds, and 0 for a
            // skill it has never been told about — exactly what a freshly started
            // client returns before the login's stat sync has been processed.
            when(client.getSkillExperience(any(Skill.class)))
                .thenAnswer(invocation -> inGameXp.getOrDefault(invocation.getArgument(0), 0));

            // Real services rather than mocks: with no tiers configured they
            // multiply by 1.00x, so every case below still measures the exact
            // figures it deposits. XpMultiplierTest covers the scaled path.
            TestMultipliers multipliers = new TestMultipliers();
            set("skillLevelTracker", multipliers.levelTracker);
            set("xpMultiplierManager", multipliers.multiplierManager);
        }

        /**
         * Fires {@code count} in-game XP drops of {@code delta} for a skill.
         *
         * <p>The plugin ignores the first {@link StatChanged} it sees for a skill,
         * using it only to establish the baseline it measures later drops against,
         * exactly as the first real event after login does. A priming event is
         * therefore sent once per skill before the requested drops.</p>
         */
        void gainXp(Skill skill, int delta, int count)
        {
            if (!inGameXp.containsKey(skill))
            {
                inGameXp.put(skill, 1_000_000);
                plugin.onStatChanged(new StatChanged(skill, 1_000_000, 99, 99));
            }

            for (int i = 0; i < count; i++)
            {
                int updated = inGameXp.get(skill) + delta;
                inGameXp.put(skill, updated);
                plugin.onStatChanged(new StatChanged(skill, updated, 99, 99));
            }
        }

        /**
         * Replays a login on a client that was only just started, in the order
         * the client delivers it: LOGGING_IN, then LOGGED_IN while every skill
         * still reads 0 XP, then the server's stat sync as one {@link StatChanged}
         * per skill, then the game ticks on which the client holds real figures.
         *
         * <p>Hitpoints is always part of the sync, because every account has it,
         * so it is added when the caller's totals leave it out.</p>
         */
        void logInOnFreshClient(Map<Skill, Integer> totals)
        {
            inGameXp.clear();
            when(client.getGameState()).thenReturn(GameState.LOGGING_IN);
            plugin.onGameStateChanged(gameState(GameState.LOGGING_IN));
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));

            Map<Skill, Integer> synced = new HashMap<>(totals);
            synced.putIfAbsent(Skill.HITPOINTS, 1_154);
            synced.forEach(this::syncSkill);

            plugin.onGameTick(new GameTick());
            plugin.onGameTick(new GameTick());
        }

        /**
         * Delivers one skill of the login stat sync: the client's own figure is
         * updated first, then the event announcing it fires, as in the client.
         */
        void syncSkill(Skill skill, int xp)
        {
            inGameXp.put(skill, xp);
            plugin.onStatChanged(new StatChanged(skill, xp, 1, 1));
        }

        String storedXp()
        {
            return stored.getOrDefault("gokirlbankedxp.storedSkillXp", "");
        }

        private void set(String fieldName, Object value)
        {
            try
            {
                Field field = GokIrlBankedXpPlugin.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(plugin, value);
            }
            catch (ReflectiveOperationException ex)
            {
                throw new IllegalStateException("Unable to inject " + fieldName, ex);
            }
        }
    }

    private static ConfigManager inMemoryConfigManager(Map<String, String> backing)
    {
        ConfigManager configManager = mock(ConfigManager.class);
        doAnswer(invocation -> backing.put(
            invocation.getArgument(0) + "." + invocation.getArgument(1),
            String.valueOf((Object) invocation.getArgument(2))))
            .when(configManager).setConfiguration(anyString(), anyString(), any());
        when(configManager.getConfiguration(anyString(), anyString()))
            .thenAnswer(invocation -> backing.get(invocation.getArgument(0) + "." + invocation.getArgument(1)));
        return configManager;
    }
}
