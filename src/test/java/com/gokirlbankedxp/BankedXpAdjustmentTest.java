package com.gokirlbankedxp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.gokirlbankedxp.service.DepletionForecaster;
import com.gokirlbankedxp.service.TestMultipliers;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Notification;
import org.junit.jupiter.api.Test;

/**
 * Covers the two ways banked XP moves other than a plain deposit: the user
 * correcting a mistake, and the plugin warning that a skill is about to run dry.
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

    /** A plugin wired to mocks, with banked XP kept in an in-memory config map. */
    private static final class Fixture
    {
        private final GokIrlBankedXpPlugin plugin = new GokIrlBankedXpPlugin();
        private final GokIrlBankedXpConfig config = mock(GokIrlBankedXpConfig.class);
        private final Notifier notifier = mock(Notifier.class);
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
            set("configManager", inMemoryConfigManager(stored));
            set("client", mock(Client.class));
            set("panel", mock(GokIrlXpPanel.class));

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
