package com.gokirlbankedxp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gokirlbankedxp.service.DepletionForecaster;
import com.gokirlbankedxp.service.TestMultipliers;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Notification;
import org.junit.jupiter.api.Test;

/**
 * Covers the level multiplier where it actually bites: the plugin's deposit path.
 *
 * <p>{@code XpMultiplierManagerTest} proves the rule; this proves the plugin
 * applies it, applies it exactly once, and does not apply it to the things it
 * must leave alone — corrections, and XP the game itself consumes.</p>
 *
 * <p>RuneLite builds the plugin through its own child injector, which a unit test
 * cannot start, so the {@code @Inject} fields are set reflectively. That is
 * confined to test code, which the Plugin Hub never compiles.</p>
 */
class LevelMultiplierBankingTest
{
    @Test
    void aManualDepositIsMultipliedByTheSkillsCurrentThreshold()
    {
        Fixture fixture = new Fixture();
        fixture.multipliers.withTier(Skill.COOKING, 70, 2.0).atLevel(Skill.COOKING, 82);

        assertEquals(1_000L, fixture.plugin.addManualXp(Skill.COOKING, 500L));
        assertEquals(1_000L, fixture.plugin.getBankedXp(Skill.COOKING));
    }

    @Test
    void actionXpTakesTheSameMultiplierAsAManualDeposit()
    {
        Fixture fixture = new Fixture();
        fixture.multipliers.withTier(Skill.AGILITY, 50, 3.0).atLevel(Skill.AGILITY, 50);

        assertEquals(750L, fixture.plugin.addActionXp(Skill.AGILITY, 250L));
        assertEquals(750L, fixture.plugin.getBankedXp(Skill.AGILITY));
    }

    /**
     * The multiplier lives at one choke point precisely so it cannot be applied
     * twice. Two separate deposits must scale individually, not compound.
     */
    @Test
    void repeatedDepositsEachScaleOnceRatherThanCompounding()
    {
        Fixture fixture = new Fixture();
        fixture.multipliers.withTier(Skill.MINING, 1, 2.0).atLevel(Skill.MINING, 30);

        fixture.plugin.addManualXp(Skill.MINING, 100L);
        fixture.plugin.addManualXp(Skill.MINING, 100L);

        assertEquals(400L, fixture.plugin.getBankedXp(Skill.MINING));
    }

    /**
     * A correction takes back a literal figure. The user is undoing an amount
     * they can read off their own balance, so multiplying it would remove
     * something other than what they asked for.
     */
    @Test
    void removalIsNeverMultiplied()
    {
        Fixture fixture = new Fixture();
        fixture.multipliers.withTier(Skill.FISHING, 1, 4.0).atLevel(Skill.FISHING, 60);

        fixture.plugin.addManualXp(Skill.FISHING, 250L);
        assertEquals(1_000L, fixture.plugin.getBankedXp(Skill.FISHING));

        assertEquals(400L, fixture.plugin.removeManualXp(Skill.FISHING, 400L));
        assertEquals(600L, fixture.plugin.getBankedXp(Skill.FISHING));
    }

    /** In-game XP drains the balance one for one; a multiplier is a deposit rule. */
    @Test
    void inGameXpStillDrainsTheBalanceOneForOne()
    {
        Fixture fixture = new Fixture();
        fixture.multipliers.withTier(Skill.WOODCUTTING, 1, 5.0).atLevel(Skill.WOODCUTTING, 40);

        fixture.plugin.addManualXp(Skill.WOODCUTTING, 200L);
        assertEquals(1_000L, fixture.plugin.getBankedXp(Skill.WOODCUTTING));

        fixture.gainXp(Skill.WOODCUTTING, 300);

        assertEquals(700L, fixture.plugin.getBankedXp(Skill.WOODCUTTING));
    }

    @Test
    void aZeroThresholdBanksNothingAndReportsThat()
    {
        Fixture fixture = new Fixture();
        fixture.multipliers.withTier(Skill.SLAYER, 1, 0.0).atLevel(Skill.SLAYER, 50);

        assertEquals(0L, fixture.plugin.addManualXp(Skill.SLAYER, 5_000L));
        assertEquals(0L, fixture.plugin.getBankedXp(Skill.SLAYER));
    }

    @Test
    void aSkillWithNoThresholdsIsUntouched()
    {
        Fixture fixture = new Fixture();
        fixture.multipliers.withTier(Skill.COOKING, 1, 10.0).atLevel(Skill.COOKING, 50);

        assertEquals(500L, fixture.plugin.addManualXp(Skill.FLETCHING, 500L));
    }

    /**
     * Levelling up mid-session moves the skill onto the next threshold, and the
     * plugin learns that from the same {@link StatChanged} the game already sends.
     */
    @Test
    void levellingUpInGameMovesLaterDepositsOntoTheNextThreshold()
    {
        Fixture fixture = new Fixture();
        fixture.multipliers
            .withTier(Skill.HERBLORE, 50, 2.0)
            .withTier(Skill.HERBLORE, 70, 4.0)
            .atLevel(Skill.HERBLORE, 69);

        assertEquals(200L, fixture.plugin.addManualXp(Skill.HERBLORE, 100L));

        // The game reports enough experience for level 70; the tracker picks the
        // new level up from the event, with no client query of its own.
        fixture.plugin.onStatChanged(new StatChanged(
            Skill.HERBLORE, Experience.getXpForLevel(70), 70, 70));

        assertEquals(400L, fixture.plugin.addManualXp(Skill.HERBLORE, 100L));
    }

    /** A plugin wired to mocks, with real multiplier services behind it. */
    private static final class Fixture
    {
        private final GokIrlBankedXpPlugin plugin = new GokIrlBankedXpPlugin();
        private final GokIrlBankedXpConfig config = mock(GokIrlBankedXpConfig.class);
        private final TestMultipliers multipliers = new TestMultipliers();
        private final Map<String, String> stored = new HashMap<>();

        Fixture()
        {
            // The warnings are covered by their own suite; silenced here so these
            // cases measure only what lands in the bank.
            when(config.lowXpThreshold()).thenReturn(0);
            when(config.chatWarningEnabled()).thenReturn(false);
            when(config.depletionWarningActions()).thenReturn(0);
            when(config.depletionNotification()).thenReturn(Notification.ON);

            set("config", config);
            set("notifier", mock(Notifier.class));
            set("depletionForecaster", new DepletionForecaster());
            set("configManager", inMemoryConfigManager(stored));
            set("client", mock(Client.class));
            set("panel", mock(GokIrlXpPanel.class));
            set("skillLevelTracker", multipliers.levelTracker);
            set("xpMultiplierManager", multipliers.multiplierManager);
        }

        /**
         * Fires an in-game XP drop for a skill.
         *
         * <p>The plugin ignores the first {@link StatChanged} it sees for a skill,
         * using it only to establish the baseline later drops are measured
         * against, so a priming event is sent first.</p>
         */
        void gainXp(Skill skill, int delta)
        {
            int baseline = Experience.getXpForLevel(50);
            plugin.onStatChanged(new StatChanged(skill, baseline, 50, 50));
            plugin.onStatChanged(new StatChanged(skill, baseline + delta, 50, 50));
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
