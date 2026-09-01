package com.gokirlbankedxp.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import com.gokirlbankedxp.model.XpMultiplierTier;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;

/**
 * A ready-made {@link XpMultiplierManager} over an in-memory config, for tests.
 *
 * <p>Public and shared rather than duplicated per test class: several suites now
 * need a multiplier manager purely to satisfy a constructor, and every one of
 * them wants the same default — real code, real persistence, and no tiers, so
 * XP passes through untouched and the case under test is unaffected.</p>
 *
 * <p>Building a {@link Gson} here is fine. The Plugin Hub never compiles
 * {@code src/test}, so its ban on constructing shared services does not reach
 * this file.</p>
 */
public final class TestMultipliers
{
    private final Map<String, String> values = new HashMap<>();

    public final ConfigManager configManager;
    public final GokIrlBankedXpConfig config;
    public final SkillLevelTracker levelTracker;
    public final XpMultiplierManager multiplierManager;

    /** A manager with the feature switched on and no tiers configured. */
    public TestMultipliers()
    {
        this(true);
    }

    public TestMultipliers(boolean levelMultipliersEnabled)
    {
        configManager = inMemoryConfigManager(values);
        config = mock(GokIrlBankedXpConfig.class);
        when(config.levelMultipliersEnabled()).thenReturn(levelMultipliersEnabled);

        levelTracker = new SkillLevelTracker(configManager, new Gson());
        levelTracker.load();

        multiplierManager = new XpMultiplierManager(configManager, config, levelTracker, new Gson());
        multiplierManager.load();
    }

    /**
     * Puts the player at a given level in a skill.
     *
     * <p>Goes through the same {@code recordExperience} path the plugin uses, so
     * the level is derived exactly as it is in production rather than injected.</p>
     */
    public TestMultipliers atLevel(Skill skill, int level)
    {
        levelTracker.recordExperience(skill, Experience.getXpForLevel(level));
        return this;
    }

    /** Appends one threshold to a skill's ladder. */
    public TestMultipliers withTier(Skill skill, int level, double multiplier)
    {
        List<XpMultiplierTier> tiers = new ArrayList<>(multiplierManager.getTiers(skill));
        tiers.add(new XpMultiplierTier(level, multiplier));
        multiplierManager.setTiers(skill, tiers);
        return this;
    }

    /** The raw config values written so far, for asserting on persistence. */
    public Map<String, String> storedValues()
    {
        return values;
    }

    /** Mirrors the in-memory config manager the other service tests use. */
    public static ConfigManager inMemoryConfigManager(Map<String, String> values)
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
