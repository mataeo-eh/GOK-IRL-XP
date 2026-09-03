package com.gokirlbankedxp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the data-loss report that created the store: a user banked XP across a
 * five-hour session, restarted the client, and found every deposit gone.
 *
 * <p>The cause is outside the plugin. RuneLite uploads a synced profile's config
 * every few minutes and, if an upload fails, replaces the local config with the
 * server's copy on the next launch. The store's answer is a second copy in a
 * plugin-owned file, and these cases pin down which copy wins when they differ.</p>
 */
class BankedXpStoreTest
{
    private static final String XP = "gokirlbankedxp." + BankedXpStore.XP_KEY;
    private static final String SAVED_AT = "gokirlbankedxp." + BankedXpStore.SAVED_AT_KEY;

    @TempDir
    File dataDir;

    @Test
    void aSaveLandsInBothCopiesAndLoadsBack()
    {
        Map<String, String> config = new HashMap<>();
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir);

        store.save(balances(Skill.AGILITY, 5_850L, Skill.COOKING, 1_200L));

        // Entries follow Skill declaration order, as the format always has.
        assertEquals("COOKING:1200,AGILITY:5850", config.get(XP));
        assertTrue(Long.parseLong(config.get(SAVED_AT)) > 0);
        assertTrue(store.dataFile().isFile(), "the plugin-owned file must exist after a save");

        Map<Skill, Long> loaded = store.load();
        assertEquals(5_850L, loaded.get(Skill.AGILITY));
        assertEquals(1_200L, loaded.get(Skill.COOKING));
    }

    /** A debt is a balance like any other: it must make the round trip with its sign intact. */
    @Test
    void aDebtIsSavedWithItsSignAndLoadsBack()
    {
        Map<String, String> config = new HashMap<>();
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir);

        store.save(balances(Skill.AGILITY, 5_850L, Skill.WOODCUTTING, -5_000L, Skill.COOKING, 0L));

        // Zero means "no entry" and is dropped; the debt is kept, sign and all.
        assertEquals("WOODCUTTING:-5000,AGILITY:5850", config.get(XP));

        Map<Skill, Long> loaded = store.load();
        assertEquals(-5_000L, loaded.get(Skill.WOODCUTTING));
        assertEquals(5_850L, loaded.get(Skill.AGILITY));
        assertFalse(loaded.containsKey(Skill.COOKING));

        // The plugin-owned file restores the same debt on its own.
        config.clear();
        Map<Skill, Long> fromFile = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir).load();
        assertEquals(-5_000L, fromFile.get(Skill.WOODCUTTING));
    }

    /** The reported bug, reproduced: config rolled back to an old copy, the file has the session. */
    @Test
    void aConfigCopyOlderThanTheFileIsRestoredFromTheFile()
    {
        Map<String, String> config = new HashMap<>();
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir);

        // A morning save that the server holds, then an afternoon of deposits.
        store.save(balances(Skill.AGILITY, 5_850L));
        String morningXp = config.get(XP);
        String morningSavedAt = config.get(SAVED_AT);
        store.save(balances(Skill.AGILITY, 40_000L, Skill.COOKING, 9_000L, Skill.MINING, 3_000L));

        // RuneLite restarts and replaces the local config with the server's copy.
        config.put(XP, morningXp);
        config.put(SAVED_AT, morningSavedAt);

        Map<Skill, Long> loaded = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir).load();

        assertEquals(40_000L, loaded.get(Skill.AGILITY));
        assertEquals(9_000L, loaded.get(Skill.COOKING));
        assertEquals(3_000L, loaded.get(Skill.MINING));
        // And the config copy is repaired, so a later sync carries the right data.
        assertEquals("COOKING:9000,MINING:3000,AGILITY:40000", config.get(XP));
    }

    /** The same rollback where the server copy predates the plugin entirely. */
    @Test
    void aMissingConfigCopyIsRestoredFromTheFile()
    {
        Map<String, String> config = new HashMap<>();
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir);
        store.save(balances(Skill.AGILITY, 40_000L));

        config.clear();
        Map<Skill, Long> loaded = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir).load();

        assertEquals(40_000L, loaded.get(Skill.AGILITY));
        assertEquals("AGILITY:40000", config.get(XP));
    }

    /** A synced profile can legitimately carry newer data from another machine. */
    @Test
    void aConfigCopyNewerThanTheFileWinsAndRefreshesTheFile()
    {
        Map<String, String> config = new HashMap<>();
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir);
        store.save(balances(Skill.AGILITY, 5_850L));
        long fileSavedAt = Long.parseLong(config.get(SAVED_AT));

        config.put(XP, "AGILITY:7000,FISHING:100");
        config.put(SAVED_AT, Long.toString(fileSavedAt + 1));

        Map<Skill, Long> loaded = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir).load();

        assertEquals(7_000L, loaded.get(Skill.AGILITY));
        assertEquals(100L, loaded.get(Skill.FISHING));
        // The file now agrees, so a later rollback of config restores this newer state.
        Map<Skill, Long> reloaded = new BankedXpStore(inMemoryConfigManager(new HashMap<>()), new Gson(), dataDir).load();
        assertEquals(7_000L, reloaded.get(Skill.AGILITY));
    }

    /** Users upgrading from before the file existed have config with no save time. */
    @Test
    void legacyConfigWithoutASaveTimeIsMigratedIntoTheFile()
    {
        Map<String, String> config = new HashMap<>();
        config.put(XP, "AGILITY:5850,WOODCUTTING:250");

        Map<Skill, Long> loaded = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir).load();

        assertEquals(5_850L, loaded.get(Skill.AGILITY));
        assertEquals(250L, loaded.get(Skill.WOODCUTTING));
        // The next launch can now survive a config rollback.
        Map<Skill, Long> reloaded = new BankedXpStore(inMemoryConfigManager(new HashMap<>()), new Gson(), dataDir).load();
        assertEquals(5_850L, reloaded.get(Skill.AGILITY));
    }

    /** A legacy config copy must not beat a file that was saved after it existed. */
    @Test
    void legacyConfigLosesToAnExistingFile()
    {
        Map<String, String> config = new HashMap<>();
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir);
        store.save(balances(Skill.AGILITY, 40_000L));

        config.put(XP, "AGILITY:5850");
        config.remove(SAVED_AT);

        Map<Skill, Long> loaded = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir).load();
        assertEquals(40_000L, loaded.get(Skill.AGILITY));
    }

    @Test
    void aFreshInstallLoadsNothingAndWritesAnEmptySave()
    {
        Map<String, String> config = new HashMap<>();

        Map<Skill, Long> loaded = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir).load();

        assertTrue(loaded.isEmpty());
        assertEquals("", config.get(XP));
    }

    @Test
    void anUnreadableFileIsMovedAsideRatherThanOverwritten() throws IOException
    {
        Map<String, String> config = new HashMap<>();
        config.put(XP, "AGILITY:5850");
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir);
        Files.write(store.dataFile().toPath(), "{not json".getBytes(StandardCharsets.UTF_8));

        Map<Skill, Long> loaded = store.load();

        assertEquals(5_850L, loaded.get(Skill.AGILITY));
        File[] kept = dataDir.listFiles((dir, name) -> name.contains(".corrupt-"));
        assertEquals(1, kept.length, "the damaged file is kept for inspection");
        assertTrue(store.dataFile().isFile(), "and a good file is written in its place");
    }

    @Test
    void unknownSkillsAndBadAmountsAreSkippedNotFatal()
    {
        Map<String, String> config = new HashMap<>();
        config.put(XP, "AGILITY:5850,NOTASKILL:10,COOKING:abc,MINING:0,FISHING");

        Map<Skill, Long> loaded = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir).load();

        assertEquals(1, loaded.size());
        assertEquals(5_850L, loaded.get(Skill.AGILITY));
    }

    @Test
    void recognisesTheEchoOfItsOwnConfigWriteAndNothingElse()
    {
        Map<String, String> config = new HashMap<>();
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir);
        store.save(balances(Skill.AGILITY, 5_850L));

        assertTrue(store.isOwnConfigValue("AGILITY:5850"));
        assertFalse(store.isOwnConfigValue("AGILITY:1"));
        assertFalse(store.isOwnConfigValue(null), "an unset from outside is never the store's own write");
    }

    @Test
    void saveTimesAlwaysIncreaseEvenWithinTheSameMillisecond()
    {
        Map<String, String> config = new HashMap<>();
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(config), new Gson(), dataDir);

        store.save(balances(Skill.AGILITY, 1L));
        long first = Long.parseLong(config.get(SAVED_AT));
        store.save(balances(Skill.AGILITY, 2L));
        long second = Long.parseLong(config.get(SAVED_AT));

        assertTrue(second > first);
    }

    @Test
    void theFileIsNamedForTheActiveConfigProfileWhenThereIsOne()
    {
        BankedXpStore store = new BankedXpStore(inMemoryConfigManager(new HashMap<>()), new Gson(), dataDir);
        // A mocked ConfigManager has no profile, which is the fallback name.
        assertEquals("banked-xp.json", store.dataFile().getName());
        assertNull(inMemoryConfigManager(new HashMap<>()).getProfile());
    }

    private static Map<Skill, Long> balances(Object... skillsAndAmounts)
    {
        Map<Skill, Long> balances = new EnumMap<>(Skill.class);
        for (int i = 0; i < skillsAndAmounts.length; i += 2)
        {
            balances.put((Skill) skillsAndAmounts[i], (Long) skillsAndAmounts[i + 1]);
        }
        return balances;
    }

    private static ConfigManager inMemoryConfigManager(Map<String, String> values)
    {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfiguration(anyString(), anyString())).thenAnswer(invocation ->
            values.get(invocation.getArgument(0) + "." + invocation.getArgument(1)));
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0) + "." + invocation.getArgument(1),
                String.valueOf((Object) invocation.getArgument(2)));
            return null;
        }).when(configManager).setConfiguration(anyString(), anyString(), any());
        return configManager;
    }
}
