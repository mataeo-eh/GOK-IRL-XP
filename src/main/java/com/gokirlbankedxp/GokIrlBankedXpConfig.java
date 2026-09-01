package com.gokirlbankedxp;

import java.awt.Color;
import java.awt.TrayIcon;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.FlashNotification;
import net.runelite.client.config.Notification;
import net.runelite.client.config.NotificationSound;
import net.runelite.client.config.Range;
import net.runelite.client.config.RequestFocusType;

/**
 * Everything about IRL XP that lives in RuneLite's settings panel.
 *
 * <p>The visible items are grouped into named, self-describing sections so a user
 * looking for a specific behaviour — "stop the screen flashing at me", "warn me
 * sooner" — can find it by reading the section titles instead of scanning a flat
 * list. Each section's description says, in plain language, what the whole group
 * controls; each item's description says what that one setting does and what the
 * value means.</p>
 *
 * <p>Anything the user edits through the sidebar instead (their action library,
 * their banked totals, their level multiplier tiers) is stored here as a hidden
 * item. Hidden items never appear in the settings panel, which keeps the panel
 * limited to things that are actually meant to be edited there.</p>
 */
@ConfigGroup(GokIrlBankedXpConfig.GROUP)
public interface GokIrlBankedXpConfig extends Config
{
    String GROUP = "gokirlbankedxp";

    String LOW_XP_SECTION = "lowXpSection";
    String ALMOST_OUT_SECTION = "almostOutSection";
    String MULTIPLIER_SECTION = "multiplierSection";

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

    // ------------------------------------------------------------------
    // Section 1 — the plain chat warning at a fixed XP figure
    // ------------------------------------------------------------------

    @ConfigSection(
        name = "Low banked XP warning (chat)",
        description = "A one-off chat message when a skill's banked XP drops below a set amount. "
            + "This is the quiet warning. The red screen flash is in the next section.",
        position = 0
    )
    String lowXpSection = LOW_XP_SECTION;

    @ConfigItem(
        keyName = "chatWarningEnabled",
        name = "Send the chat warning",
        description = "Turn this off to stop the chat message entirely. "
            + "The message is sent once per skill, and only becomes possible again after that skill is topped up.",
        section = LOW_XP_SECTION,
        position = 1
    )
    default boolean chatWarningEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "lowXpThreshold",
        name = "Warn below this much XP",
        description = "The banked XP figure that counts as low. A skill at or under this amount is marked LOW "
            + "in the sidebar and on the overlay, and triggers the chat warning above. Default: 500.",
        section = LOW_XP_SECTION,
        position = 2
    )
    default int lowXpThreshold()
    {
        return 500;
    }

    // ------------------------------------------------------------------
    // Section 2 — the red screen flash as a skill is about to run dry
    // ------------------------------------------------------------------

    @ConfigSection(
        name = "Almost-out warning (red screen flash)",
        description = "Flashes your screen red when a skill is only a few more actions from running out of banked XP. "
            + "Use 'Warn me this many actions early' to change how much notice you get, "
            + "and 'How you are warned' to change the flash colour, add a sound, or switch the flash off.",
        position = 10
    )
    String almostOutSection = ALMOST_OUT_SECTION;

    @Range(min = 0, max = 50)
    @ConfigItem(
        keyName = "depletionWarningActions",
        name = "Warn me this many actions early",
        description = "How much notice you get. One action means one XP drop: a log chopped, a lap run, a hit landed. "
            + "Higher means an earlier warning. Set this to 0 to switch the whole almost-out warning off. Default: 5.",
        section = ALMOST_OUT_SECTION,
        position = 11
    )
    default int depletionWarningActions()
    {
        return 5;
    }

    @ConfigItem(
        keyName = "depletionNotification",
        name = "How you are warned",
        description = "The full notification editor for the almost-out warning. "
            + "Out of the box it flashes the screen red for two seconds and prints a chat line. "
            + "Open it to change the flash colour, add a sound or tray popup, or turn the flash off "
            + "while keeping the chat line.",
        section = ALMOST_OUT_SECTION,
        position = 12
    )
    default Notification depletionNotification()
    {
        return defaultDepletionNotification();
    }

    // ------------------------------------------------------------------
    // Section 3 — level-based banking multipliers
    // ------------------------------------------------------------------

    @ConfigSection(
        name = "Level multipliers",
        description = "Bank more (or less) XP as your in-game levels climb. "
            + "The multipliers themselves are set per skill in the sidebar: "
            + "open the IRL XP sidebar and pick the MULTIPLIERS tab. This switch turns them all on or off at once.",
        position = 20
    )
    String multiplierSection = MULTIPLIER_SECTION;

    @ConfigItem(
        keyName = "levelMultipliersEnabled",
        name = "Use level multipliers",
        description = "When on, XP you bank is multiplied by whatever your level in that skill has earned, "
            + "as set on the sidebar's MULTIPLIERS tab. When off, XP is banked exactly as entered "
            + "and your thresholds are kept for later. Skills you never set a multiplier for are unaffected either way.",
        section = MULTIPLIER_SECTION,
        position = 21
    )
    default boolean levelMultipliersEnabled()
    {
        return true;
    }

    // ------------------------------------------------------------------
    // Hidden storage — edited through the sidebar, never in this panel
    // ------------------------------------------------------------------

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

    @ConfigItem(
        keyName = "xpMultipliersJson",
        name = "Level multiplier thresholds",
        description = "Serialized representation of each skill's level multiplier thresholds.",
        hidden = true
    )
    default String xpMultipliersJson()
    {
        return "{}";
    }

    @ConfigItem(
        keyName = "observedSkillLevels",
        name = "Observed skill levels",
        description = "Serialized representation of the last skill levels seen in-game, "
            + "so multipliers still resolve while logged out.",
        hidden = true
    )
    default String observedSkillLevels()
    {
        return "{}";
    }
}
