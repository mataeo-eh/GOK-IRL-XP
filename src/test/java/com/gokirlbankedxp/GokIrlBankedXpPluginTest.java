package com.gokirlbankedxp;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Development entry point that injects the unpublished plugin before RuneLite
 * starts. Gradle keeps this launcher out of the production plugin artifact.
 */
public final class GokIrlBankedXpPluginTest
{
    private GokIrlBankedXpPluginTest()
    {
        // Utility entry point; it is never instantiated.
    }

    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(GokIrlBankedXpPlugin.class);
        RuneLite.main(args);
    }
}
