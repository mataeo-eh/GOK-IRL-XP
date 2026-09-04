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
import net.runelite.api.Skill;
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
        ConfigManager configManager = mock(ConfigManager.class);
        ConfigProfile a = mock(ConfigProfile.class);
        ConfigProfile b = mock(ConfigProfile.class);
        when(a.getId()).thenReturn(1L);
        when(b.getId()).thenReturn(2L);
        Map<Long, Map<String, String>> profiles = Map.of(1L, new HashMap<>(), 2L, new HashMap<>());
        // ConfigManager activates the new profile before emitting its change events.
        when(configManager.getProfile()).thenReturn(a);
        when(configManager.getConfiguration(anyString(), anyString())).thenAnswer(call ->
            profiles.get(configManager.getProfile().getId()).get(call.getArgument(0) + "." + call.getArgument(1)));
        doAnswer(call -> {
            profiles.get(configManager.getProfile().getId()).put(
                call.getArgument(0) + "." + call.getArgument(1), String.valueOf((Object) call.getArgument(2)));
            return null;
        }).when(configManager).setConfiguration(anyString(), anyString(), any());

        Gson gson = new Gson();
        GokIrlBankedXpConfig config = mock(GokIrlBankedXpConfig.class);
        when(config.levelMultipliersEnabled()).thenReturn(true);
        GokIrlBankedXpPlugin plugin = new GokIrlBankedXpPlugin();
        IrlActionManager actions = new IrlActionManager(configManager, gson);
        SkillLevelTracker levels = new SkillLevelTracker(configManager, gson);
        XpMultiplierManager multipliers = new XpMultiplierManager(configManager, config, levels, gson);
        TimerManager timers = new TimerManager(configManager, actions, plugin, null, gson);
        BankedXpStore store = new BankedXpStore(configManager, gson, directory);
        inject(plugin, "configManager", configManager);
        inject(plugin, "config", config);
        inject(plugin, "irlActionManager", actions);
        inject(plugin, "skillLevelTracker", levels);
        inject(plugin, "xpMultiplierManager", multipliers);
        inject(plugin, "timerManager", timers);
        inject(plugin, "bankedXpStore", store);
        inject(plugin, "depletionForecaster", new DepletionForecaster());
        GokIrlXpPanel panel = mock(GokIrlXpPanel.class);
        inject(plugin, "panel", panel);

        plugin.onProfileChanged(new ProfileChanged());
        IrlAction actionA = actions.createAction(action("A", "minute", 60));
        UUID timerA = timers.startTimer(actionA.getId()).orElseThrow();
        levels.recordExperience(Skill.AGILITY, 1000000);
        multipliers.setTiers(Skill.AGILITY, List.of(new XpMultiplierTier(1, 2)));
        store.save(Map.of(Skill.AGILITY, 123L));
        Map<String, String> savedA = new HashMap<>(profiles.get(1L));

        // Prepare B using separate managers, as if it was last used in an earlier session.
        when(configManager.getProfile()).thenReturn(b);
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
        when(configManager.getProfile()).thenReturn(a);
        plugin.onProfileChanged(new ProfileChanged());
        assertEquals(actionA.getId(), actions.getAllActions().get(0).getId());
        assertEquals(timerA, timers.getActiveTimers().get(0).getId());
        assertTrue(levels.getLevel(Skill.AGILITY) > 1);
        assertEquals(2.0, multipliers.currentMultiplier(Skill.AGILITY));
        assertEquals(123L, plugin.getBankedXp(Skill.AGILITY));
        SwingUtilities.invokeAndWait(() -> {});
        verify(panel, atLeast(3)).refreshActions();
    }

    private static IrlAction action(String name, String unit, long seconds)
    {
        return IrlAction.builder().name(name).unitName(unit).secondsPerUnit(seconds)
            .defaultXpPerUnit(10).skillMappings(Map.of(Skill.AGILITY, 10L)).build();
    }

    /** Match RuneLite injection while keeping UI and the live client out of the fixture. */
    private static void inject(GokIrlBankedXpPlugin plugin, String name, Object value) throws Exception
    {
        Field field = GokIrlBankedXpPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
