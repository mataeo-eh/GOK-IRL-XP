package com.gokirlbankedxp;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(GokIrlBankedXpConfig.GROUP)
public interface GokIrlBankedXpConfig extends Config
{
    String GROUP = "gokirlbankedxp";

    @ConfigItem(
        keyName = "lowXpThreshold",
        name = "Low XP threshold",
        description = "Trigger warnings when remaining banked XP for a skill falls below this amount.",
        position = 0
    )
    default int lowXpThreshold()
    {
        return 500;
    }

    @ConfigItem(
        keyName = "chatWarningEnabled",
        name = "Chat warning",
        description = "Send a chat message the first time a skill drops below the threshold.",
        position = 1
    )
    default boolean chatWarningEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "irlActionsJson",
        name = "IRL actions",
        description = "Serialized representation of custom IRL actions.",
        hidden = true
    )
    default String irlActionsJson()
    {
        return "[]";
    }

    @ConfigItem(
        keyName = "activeTimersJson",
        name = "Active timers",
        description = "Serialized representation of active IRL timers.",
        hidden = true
    )
    default String activeTimersJson()
    {
        return "[]";
    }

    @ConfigItem(
        keyName = "storedSkillXp",
        name = "Stored skill XP",
        description = "Serialized representation of banked XP per skill.",
        hidden = true
    )
    default String storedSkillXp()
    {
        return "";
    }
}

