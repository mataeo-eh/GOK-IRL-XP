package com.gokirlbankedxp;

import com.google.common.base.Strings;
import com.google.inject.Provides;
import com.gokirlbankedxp.service.IrlActionManager;
import com.gokirlbankedxp.service.TimerManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Value;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.ui.NavigationButton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "IRL XP",
    description = "Turn real-world actions into banked RuneScape XP and track it as you train.",
    tags = {"banked", "experience", "irl", "xp"}
)
public class GokIrlBankedXpPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(GokIrlBankedXpPlugin.class);

    private static final String STORAGE_KEY = "storedSkillXp";
    private static final String ENTRY_DELIMITER = ",";
    private static final String VALUE_DELIMITER = ":";
    // Skill.OVERALL is a deprecated null sentinel; Skill.values() already omits it.
    private static final Skill[] TRACKABLE_SKILLS = Skill.values();

    private static final BufferedImage NAV_ICON = buildIcon();

    private final Object dataLock = new Object();
    private final Map<Skill, Long> storedXp = new EnumMap<>(Skill.class);
    private final Map<Skill, Integer> lastKnownXp = new EnumMap<>(Skill.class);
    private final Set<Skill> warnedSkills = EnumSet.noneOf(Skill.class);

    private volatile BankedXpSnapshot currentSnapshot = BankedXpSnapshot.empty();
    private NavigationButton navigationButton;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ConfigManager configManager;

    @Inject
    private GokIrlBankedXpConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GokIrlBankedXpOverlay overlay;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private GokIrlXpPanel panel;

    @Inject
    private IrlActionManager irlActionManager;

    @Inject
    private TimerManager timerManager;

    @Provides
    GokIrlBankedXpConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GokIrlBankedXpConfig.class);
    }

    @Override
    protected void startUp()
    {
        overlayManager.add(overlay);
        navigationButton = NavigationButton.builder()
            .tooltip("IRL XP")
            .icon(NAV_ICON)
            .priority(5)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navigationButton);

        irlActionManager.loadActions();
        panel.refreshActions();
        loadStoredXp();
        timerManager.startUp();
        clientThread.invokeLater(() -> {
            refreshLastKnownXp();
            rebuildSnapshotAndNotify();
        });
    }

    @Override
    protected void shutDown()
    {
        panel.stopUiUpdates();
        if (timerManager != null)
        {
            timerManager.shutDown();
        }

        overlayManager.remove(overlay);
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }
        synchronized (dataLock)
        {
            storedXp.clear();
            lastKnownXp.clear();
            warnedSkills.clear();
            currentSnapshot = BankedXpSnapshot.empty();
        }

        SwingUtilities.invokeLater(() -> panel.updateSnapshot(BankedXpSnapshot.empty()));
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        Skill skill = event.getSkill();
        if (skill == null)
        {
            return;
        }

        int newXp = event.getXp();
        boolean changed = false;

        synchronized (dataLock)
        {
            Integer previous = lastKnownXp.put(skill, newXp);
            if (previous == null)
            {
                return;
            }

            int delta = newXp - previous;
            if (delta <= 0)
            {
                return;
            }

            changed = subtractXpLocked(skill, delta);
        }

        if (changed)
        {
            rebuildSnapshotAndNotify();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            clientThread.invokeLater(() -> {
                refreshLastKnownXp();
                rebuildSnapshotAndNotify();
            });
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!GokIrlBankedXpConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }

        boolean needsRefresh = false;
        if ("lowXpThreshold".equals(event.getKey()))
        {
            needsRefresh = true;
        }
        else if (STORAGE_KEY.equals(event.getKey()))
        {
            // Config manager changed outside the panel; reload.
            loadStoredXp();
            needsRefresh = true;
        }

        if (needsRefresh)
        {
            rebuildSnapshotAndNotify();
        }
    }

    void addManualXp(Skill skill, long amount)
    {
        if (skill == null || amount <= 0)
        {
            return;
        }

        synchronized (dataLock)
        {
            storedXp.merge(skill, amount, Long::sum);
            warnedSkills.remove(skill);
            persistStoredXpLocked();
        }

        rebuildSnapshotAndNotify();
    }

    public void addTimerXp(Skill skill, long amount)
    {
        addManualXp(skill, amount);
    }

    BankedXpSnapshot getCurrentSnapshot()
    {
        return currentSnapshot;
    }

    Skill[] getTrackableSkills()
    {
        return TRACKABLE_SKILLS.clone();
    }

    private void loadStoredXp()
    {
        String serialized = configManager.getConfiguration(GokIrlBankedXpConfig.GROUP, STORAGE_KEY);
        synchronized (dataLock)
        {
            storedXp.clear();
            warnedSkills.clear();

            if (Strings.isNullOrEmpty(serialized))
            {
                persistStoredXpLocked();
                return;
            }

            for (String entry : serialized.split(ENTRY_DELIMITER))
            {
                if (entry.isEmpty() || !entry.contains(VALUE_DELIMITER))
                {
                    continue;
                }

                String[] parts = entry.split(VALUE_DELIMITER);
                if (parts.length != 2)
                {
                    continue;
                }

                Skill skill = parseSkill(parts[0]);
                if (skill == null)
                {
                    continue;
                }

                try
                {
                    long value = Long.parseLong(parts[1]);
                    if (value > 0)
                    {
                        storedXp.put(skill, value);
                    }
                }
                catch (NumberFormatException ex)
                {
                    log.debug("Unable to parse banked XP entry [{}]", entry, ex);
                }
            }

            persistStoredXpLocked();
        }
    }

    private void refreshLastKnownXp()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        synchronized (dataLock)
        {
            for (Skill skill : TRACKABLE_SKILLS)
            {
                lastKnownXp.put(skill, client.getSkillExperience(skill));
            }
        }
    }

    private boolean subtractXpLocked(Skill skill, long delta)
    {
        if (delta <= 0)
        {
            return false;
        }

        Long current = storedXp.get(skill);
        if (current == null || current <= 0)
        {
            return false;
        }

        long updated = Math.max(0, current - delta);
        if (updated == 0)
        {
            storedXp.remove(skill);
        }
        else
        {
            storedXp.put(skill, updated);
        }

        handleThresholdLocked(skill, updated);
        persistStoredXpLocked();
        return updated != current;
    }

    private void handleThresholdLocked(Skill skill, long remaining)
    {
        int threshold = Math.max(0, config.lowXpThreshold());
        if (remaining > threshold)
        {
            warnedSkills.remove(skill);
            return;
        }

        if (remaining <= 0)
        {
            warnedSkills.remove(skill);
            return;
        }

        if (config.chatWarningEnabled() && warnedSkills.add(skill))
        {
            String message = String.format(
                Locale.US,
                "%s banked XP low: %s remaining",
                skill.getName(),
                QuantityFormatter.formatNumber(remaining)
            );
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
        }
    }

    private void rebuildSnapshotAndNotify()
    {
        BankedXpSnapshot snapshot;
        synchronized (dataLock)
        {
            snapshot = buildSnapshotLocked();
            currentSnapshot = snapshot;
        }

        final BankedXpSnapshot published = snapshot;
        SwingUtilities.invokeLater(() -> panel.updateSnapshot(published));
    }

    private BankedXpSnapshot buildSnapshotLocked()
    {
        if (storedXp.isEmpty())
        {
            return BankedXpSnapshot.empty();
        }

        int threshold = Math.max(0, config.lowXpThreshold());
        List<BankedSkill> skills = new ArrayList<>();
        long total = 0;

        for (Map.Entry<Skill, Long> entry : storedXp.entrySet())
        {
            long remaining = entry.getValue();
            if (remaining <= 0)
            {
                continue;
            }

            Skill skill = entry.getKey();
            total += remaining;
            skills.add(new BankedSkill(
                skill,
                skill.getName(),
                remaining,
                threshold,
                remaining <= threshold
            ));
        }

        if (skills.isEmpty())
        {
            return BankedXpSnapshot.empty();
        }

        skills.sort(Comparator.comparingLong(BankedSkill::getRemainingXp).reversed());
        return new BankedXpSnapshot(total, List.copyOf(skills));
    }

    private void persistStoredXpLocked()
    {
        String serialized = storedXp.entrySet().stream()
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey().name() + VALUE_DELIMITER + entry.getValue())
            .reduce((a, b) -> a + ENTRY_DELIMITER + b)
            .orElse("");

        configManager.setConfiguration(GokIrlBankedXpConfig.GROUP, STORAGE_KEY, serialized);
    }

    private static Skill parseSkill(String raw)
    {
        if (Strings.isNullOrEmpty(raw))
        {
            return null;
        }

        try
        {
            return Skill.valueOf(raw.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
    }

    private static BufferedImage buildIcon()
    {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();

        graphics.setColor(new Color(0xFF1F8A70, true));
        graphics.fillRect(0, 0, 16, 16);
        graphics.setColor(Color.WHITE);
        graphics.drawString("XP", 2, 12);
        graphics.dispose();

        return image;
    }

    @Value
    static class BankedXpSnapshot
    {
        long totalXp;
        List<BankedSkill> skills;

        static BankedXpSnapshot empty()
        {
            return new BankedXpSnapshot(0L, Collections.emptyList());
        }

        boolean hasData()
        {
            return totalXp > 0 && !skills.isEmpty();
        }
    }

    @Value
    static class BankedSkill
    {
        Skill skill;
        String displayName;
        long remainingXp;
        int threshold;
        boolean belowThreshold;
    }
}
