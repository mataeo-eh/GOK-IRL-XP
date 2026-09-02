package com.gokirlbankedxp;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * A throwaway directory for {@code BankedXpStore}'s JSON file in tests that build
 * the plugin by hand rather than through JUnit's {@code @TempDir}.
 *
 * <p>Production points the store at {@code ~/.runelite/gok-irl-xp}; a test must
 * never write there, because it would overwrite the developer's own banked XP.</p>
 */
public final class TestDataDir
{
    private TestDataDir()
    {
    }

    public static File create()
    {
        try
        {
            File dir = Files.createTempDirectory("gok-irl-xp-test").toFile();
            dir.deleteOnExit();
            return dir;
        }
        catch (IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
    }
}
