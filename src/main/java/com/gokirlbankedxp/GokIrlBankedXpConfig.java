package com.gokirlbankedxp;

import java.awt.Color;
import java.awt.TrayIcon;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.FlashNotification;
import net.runelite.client.config.Notification;
import net.runelite.client.config.NotificationSound;
import net.runelite.client.config.Range;
import net.runelite.client.config.RequestFocusType;

@ConfigGroup(GokIrlBankedXpConfig.GROUP)
public interface GokIrlBankedXpConfig extends Config
{
    String GROUP = "gokirlbankedxp";

    /**
     * The out-of-the-box depletion warning: a red screen flash, plus a chat line.
     *
     * <p>RuneLite's {@link Notification} normally inherits whatever the user set
     * under Settings &gt; Notifications, where screen flashing is off by default.
     * That default would make this feature do nothing visible, so the notification
     * is built with {@code override} set, which tells
     * {@link net.runelite.client.Notifier} to use exactly these settings instead of
     * the global ones. Users still get the full notification editor for this item
     * and can change or disable any part of it.</p>
     *
     * <p>Every field has to be stated explicitly. {@code Notifier} switches on
     * {@code requestFocus} and {@code sound} without a null check, and it returns
     * early when {@code sendWhenFocused} is false and the client has focus — which
     * is exactly when someone training in-game would be looking at the screen.</p>
     */
    static Notification defaultDepletionNotification()
    {
        return Notification.ON
            .withInitialized(true)
            .withOverride(true)
            .withSendWhenFocused(true)
            .withRequestFocus(RequestFocusType.OFF)
            .withSound(NotificationSound.OFF)
            .withTrayIconType(TrayIcon.MessageType.NONE)
            .withGameMessage(true)
            // The same translucent red RuneLite uses for its own flash default, so
            // the warning is unmistakable without blacking out the game.
            .withFlash(FlashNotification.FLASH_TWO_SECONDS)
            .withFlashColor(new Color(255, 0, 0, 70));
    }

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

    @Range(min = 0, max = 50)
    @ConfigItem(
        keyName = "depletionWarningActions",
        name = "Warn N actions early",
        description = "Warn when a skill's banked XP is within this many more actions of running out. "
            + "One action means one XP drop: a log chopped, a lap run, a hit landed. "
            + "Set to 0 to turn the warning off.",
        position = 2
    )
    default int depletionWarningActions()
    {
        return 5;
    }

    @ConfigItem(
        keyName = "depletionNotification",
        name = "Depletion warning",
        description = "How to warn you when banked XP is about to run out. Flashes the screen red by default.",
        position = 3
    )
    default Notification depletionNotification()
    {
        return defaultDepletionNotification();
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
        keyName = "irlActionUnitsJson",
        name = "IRL action units",
        description = "Serialized representation of user-created action units.",
        hidden = true
    )
    default String irlActionUnitsJson()
    {
        return "{}";
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
