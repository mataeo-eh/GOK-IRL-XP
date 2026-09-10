package com.gokirlbankedxp;

import com.google.gson.Gson;
import com.gokirlbankedxp.model.IrlAction;
import com.gokirlbankedxp.model.XpMultiplierTier;
import com.gokirlbankedxp.service.*;
import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.swing.SwingUtilities;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise complete profile transitions and on-disk restoration, without a live client. */
class ProfileSwitchTest
{
    @TempDir File directory;

    @Test
    void switchingReloadsEveryManagerWithoutWritingOldTimersIntoTheNewProfile() throws Exception
    {
        Harness h = new Harness(directory);
        ConfigManager configManager = h.configManager;
        Map<Long, Map<String, String>> profiles = h.profiles;
        GokIrlBankedXpPlugin plugin = h.plugin;
        IrlActionManager actions = h.actions;
        SkillLevelTracker levels = h.levels;
        XpMultiplierManager multipliers = h.multipliers;
        TimerManager timers = h.timers;
        BankedXpStore store = h.store;
        Gson gson = h.gson;
        GokIrlBankedXpConfig config = h.config;

        plugin.onProfileChanged(new ProfileChanged());
        IrlAction actionA = actions.createAction(action("A", "minute", 60));
        UUID timerA = timers.startTimer(actionA.getId()).orElseThrow();
        levels.recordExperience(Skill.AGILITY, 1000000);
        multipliers.setTiers(Skill.AGILITY, List.of(new XpMultiplierTier(1, 2)));
        store.save(Map.of(Skill.AGILITY, 123L));
        Map<String, String> savedA = new HashMap<>(profiles.get(1L));

        // Prepare B using separate managers, as if it was last used in an earlier session.
        h.useProfile(h.b);
        IrlActionManager seedActions = new IrlActionManager(configManager, gson);
        seedActions.loadActions();
        IrlAction actionB = seedActions.createAction(action("B", "second", 1));
        SkillLevelTracker seedLevels = new SkillLevelTracker(configManager, gson);
        seedLevels.recordExperience(Skill.AGILITY, 0);
        XpMultiplierManager seedMultipliers = new XpMultiplierManager(configManager, config, seedLevels, gson);
        seedMultipliers.setTiers(Skill.AGILITY, List.of(new XpMultiplierTier(1, 3)));
        new BankedXpStore(configManager, gson, directory).save(Map.of(Skill.AGILITY, 456L));
        // Equal config balance strings must not suppress restoring B's newer local file.
        profiles.get(2L).put("gokirlbankedxp.storedSkillXp", "AGILITY:123");
        profiles.get(2L).put("gokirlbankedxp.storedSkillXpSavedAt", "0");

        Map<String, String> beforeTransition = new HashMap<>(profiles.get(2L));
        for (int i = 0; i < 10; i++) timers.tick();
        timers.shutDown();
        assertEquals(0L, plugin.addManualXp(Skill.AGILITY, 100L));
        assertEquals(beforeTransition, profiles.get(2L), "Old timers cannot write in the event-delivery gap");
        ConfigChanged keyEvent = new ConfigChanged();
        keyEvent.setGroup(GokIrlBankedXpConfig.GROUP);
        keyEvent.setKey(BankedXpStore.XP_KEY);
        keyEvent.setNewValue("AGILITY:123");
        plugin.onConfigChanged(keyEvent);
        plugin.onProfileChanged(new ProfileChanged());
        assertEquals(List.of(actionB.getId()), List.of(actions.getAllActions().get(0).getId()));
        assertEquals(Map.of("second", 1L), actions.getSavedUnits());
        assertTrue(timers.getActiveTimers().isEmpty());
        assertEquals(1, levels.getLevel(Skill.AGILITY));
        assertEquals(3.0, multipliers.currentMultiplier(Skill.AGILITY));
        assertEquals(456L, plugin.getBankedXp(Skill.AGILITY));
        assertEquals(savedA, profiles.get(1L));

        // Switching back recovers A's timer and terms rather than B's action library.
        h.useProfile(h.a);
        plugin.onProfileChanged(new ProfileChanged());
        assertEquals(actionA.getId(), actions.getAllActions().get(0).getId());
        assertEquals(timerA, timers.getActiveTimers().get(0).getId());
        assertTrue(levels.getLevel(Skill.AGILITY) > 1);
        assertEquals(2.0, multipliers.currentMultiplier(Skill.AGILITY));
        assertEquals(123L, plugin.getBankedXp(Skill.AGILITY));
        SwingUtilities.invokeAndWait(() -> {});
        verify(h.panel, atLeast(3)).refreshActions();
    }

    /**
     * Levels belong to the logged-in character, not to the config profile. A
     * profile that has never seen this character would otherwise report level 1
     * for every skill, and the first deposit after a switch would be multiplied
     * as though the player were brand new.
     */
    @Test
    void switchingProfilesKeepsTheLevelsSeenThisSession() throws Exception
    {
        Harness h = new Harness(directory);
        h.plugin.onProfileChanged(new ProfileChanged());

        // The plugin observes the character's Agility XP in-game while on profile A.
        int agilityXp = Experience.getXpForLevel(80);
        h.plugin.onStatChanged(new StatChanged(Skill.AGILITY, agilityXp, 80, 80));
        h.plugin.onStatChanged(new StatChanged(Skill.AGILITY, agilityXp, 80, 80));
        assertEquals(80, h.levels.getLevel(Skill.AGILITY));

        // Profile B has no saved levels at all.
        h.useProfile(h.b);
        h.plugin.onProfileChanged(new ProfileChanged());

        assertEquals(80, h.levels.getLevel(Skill.AGILITY));
        assertTrue(h.levels.hasObserved(Skill.AGILITY));
    }

    private static IrlAction action(String name, String unit, long seconds)
    {
        return IrlAction.builder().name(name).unitName(unit).secondsPerUnit(seconds)
            .defaultXpPerUnit(10).skillMappings(Map.of(Skill.AGILITY, 10L)).build();
    }

    /**
     * A plugin and its services over a two-profile in-memory config manager.
     *
     * <p>Matches RuneLite injection while keeping UI and the live client out of
     * the fixture: the {@code @Inject} fields are set reflectively, which is
     * confined to test code the Plugin Hub never compiles.</p>
     */
    private static final class Harness
    {
        final ConfigManager configManager = mock(ConfigManager.class);
        final ConfigProfile a = mock(ConfigProfile.class);
        final ConfigProfile b = mock(ConfigProfile.class);
        final Map<Long, Map<String, String>> profiles = Map.of(1L, new HashMap<>(), 2L, new HashMap<>());
        final Gson gson = new Gson();
        final GokIrlBankedXpConfig config = mock(GokIrlBankedXpConfig.class);
        final GokIrlBankedXpPlugin plugin = new GokIrlBankedXpPlugin();
        final IrlActionManager actions;
        final SkillLevelTracker levels;
        final XpMultiplierManager multipliers;
        final TimerManager timers;
        final BankedXpStore store;
        final GokIrlXpPanel panel = mock(GokIrlXpPanel.class);

        Harness(File directory) throws Exception
        {
            when(a.getId()).thenReturn(1L);
            when(b.getId()).thenReturn(2L);
            // ConfigManager activates the new profile before emitting its change events.
            useProfile(a);
            when(configManager.getConfiguration(anyString(), anyString())).thenAnswer(call ->
                profiles.get(configManager.getProfile().getId()).get(call.getArgument(0) + "." + call.getArgument(1)));
            doAnswer(call -> {
                profiles.get(configManager.getProfile().getId()).put(
                    call.getArgument(0) + "." + call.getArgument(1), String.valueOf((Object) call.getArgument(2)));
                return null;
            }).when(configManager).setConfiguration(anyString(), anyString(), any());

            when(config.levelMultipliersEnabled()).thenReturn(true);
            actions = new IrlActionManager(configManager, gson);
            levels = new SkillLevelTracker(configManager, gson);
            multipliers = new XpMultiplierManager(configManager, config, levels, gson);
            timers = new TimerManager(configManager, actions, plugin, null, gson);
            store = new BankedXpStore(configManager, gson, directory);
            inject(plugin, "configManager", configManager);
            inject(plugin, "config", config);
            inject(plugin, "irlActionManager", actions);
            inject(plugin, "skillLevelTracker", levels);
            inject(plugin, "xpMultiplierManager", multipliers);
            inject(plugin, "timerManager", timers);
            inject(plugin, "bankedXpStore", store);
            inject(plugin, "depletionForecaster", new DepletionForecaster());
            inject(plugin, "panel", panel);
        }

        void useProfile(ConfigProfile profile)
        {
            when(configManager.getProfile()).thenReturn(profile);
        }

        private static void inject(GokIrlBankedXpPlugin plugin, String name, Object value) throws Exception
        {
            Field field = GokIrlBankedXpPlugin.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(plugin, value);
        }
    }
}
