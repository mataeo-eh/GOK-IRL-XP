package com.gokirlbankedxp;

import com.google.common.base.Strings;
import com.google.common.math.LongMath;
import com.google.inject.Provides;
import com.gokirlbankedxp.service.DepletionForecaster;
import com.gokirlbankedxp.service.IrlActionManager;
import com.gokirlbankedxp.service.SkillLevelTracker;
import com.gokirlbankedxp.service.TimerManager;
import com.gokirlbankedxp.service.XpMultiplierManager;
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
import net.runelite.client.Notifier;
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
    /** Skills already warned about crossing {@code lowXpThreshold}, so the chat line is sent once. */
    private final Set<Skill> warnedSkills = EnumSet.noneOf(Skill.class);
    /**
     * Skills already warned about imminent depletion.
     *
     * <p>Kept separate from {@link #warnedSkills} because the two warnings answer
     * different questions — a fixed XP threshold versus "how many more actions can
     * you take" — and each has to be able to re-arm without silencing the other.</p>
     */
    private final Set<Skill> depletionWarnedSkills = EnumSet.noneOf(Skill.class);

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

    @Inject
    private DepletionForecaster depletionForecaster;

    @Inject
    private SkillLevelTracker skillLevelTracker;

    @Inject
    private XpMultiplierManager xpMultiplierManager;

    @Inject
    private Notifier notifier;

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
        // Both are read before the first deposit can happen. A level multiplier
        // needs the saved tiers and the player's last observed level to resolve
        // at all, and an unloaded manager would quietly bank everything at 1.00x.
        skillLevelTracker.load();
        xpMultiplierManager.load();
        // startUp() runs on the client thread, so every Swing mutation below has
        // to be handed to the EDT rather than performed inline.
        SwingUtilities.invokeLater(panel::refreshActions);
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
        // Like startUp(), this runs on the client thread; the panel's Swing
        // refresh timer must be stopped from the EDT.
        SwingUtilities.invokeLater(panel::stopUiUpdates);
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
        // Learned XP-drop rates are session state, not saved data: a reloaded
        // plugin relearns them from the first few drops rather than acting on
        // rates from whatever the user was training hours ago.
        depletionForecaster.clear();
        synchronized (dataLock)
        {
            storedXp.clear();
            lastKnownXp.clear();
            warnedSkills.clear();
            depletionWarnedSkills.clear();
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

        // Fed to the tracker outside the data lock, and before anything else,
        // because this is the client thread — the only place a live experience
        // reading is available. Everything that banks XP runs on some other
        // thread and reads the level back from the tracker instead of the client.
        skillLevelTracker.recordExperience(skill, newXp);

        boolean changed = false;
        String depletionWarning = null;

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
            if (changed)
            {
                depletionWarning = evaluateDepletionLocked(skill, delta);
            }
        }

        // Fired outside the lock on purpose: the notifier posts to the event bus
        // and touches the client UI, and neither should run while this plugin's
        // data lock is held.
        if (depletionWarning != null)
        {
            notifier.notify(config.depletionNotification(), depletionWarning);
        }

        if (changed)
        {
            rebuildSnapshotAndNotify();
        }
    }

    /**
     * Decides whether this XP drop means the skill is about to run dry.
     *
     * <p>Feeds the drop to {@link DepletionForecaster} and compares what the next
     * {@code depletionWarningActions} actions are predicted to cost against what
     * is actually left. Called with {@link #dataLock} held so the remaining total
     * it reads is the one this drop just produced.</p>
     *
     * @return the message to notify with, or null when nothing should be sent
     */
    private String evaluateDepletionLocked(Skill skill, long delta)
    {
        int window = Math.max(0, config.depletionWarningActions());
        if (window <= 0)
        {
            // Feature switched off: forget any standing warning so re-enabling it
            // starts clean rather than staying silent on an old flag.
            depletionWarnedSkills.remove(skill);
            return null;
        }

        depletionForecaster.recordDrop(skill, delta, window);

        long remaining = storedXp.getOrDefault(skill, 0L);
        if (remaining <= 0)
        {
            // Already spent. There is nothing left to warn about, and re-arming
            // here means the next top-up gets a fresh warning.
            depletionWarnedSkills.remove(skill);
            return null;
        }

        long forecast = depletionForecaster.forecast(skill, window);
        if (forecast <= 0 || remaining > forecast)
        {
            // Either no rate learned yet, or comfortably more than the next
            // `window` actions will consume. Re-arm so a later dip warns again.
            depletionWarnedSkills.remove(skill);
            return null;
        }

        if (!depletionWarnedSkills.add(skill))
        {
            // Already warned and the balance has not recovered since.
            return null;
        }

        // Turn "XP left" back into "actions left" using the same samples the
        // forecast came from, because that is the unit the warning promises.
        long averageDrop = depletionForecaster.averageDrop(skill);
        long actionsLeft = averageDrop <= 0 ? window : Math.max(1L, remaining / averageDrop);

        return String.format(
            Locale.US,
            "%s banked XP is nearly gone: %s left, about %d more action%s.",
            skill.getName(),
            QuantityFormatter.formatNumber(remaining),
            actionsLeft,
            actionsLeft == 1 ? "" : "s"
        );
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
        else if ("depletionWarningActions".equals(event.getKey()))
        {
            // A new window means a new prediction, so any standing warning is
            // stale. Re-arm rather than leaving skills silenced under the old one.
            synchronized (dataLock)
            {
                depletionWarnedSkills.clear();
            }
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

    /**
     * The one door XP comes in through, and therefore the one place the level
     * multiplier is applied.
     *
     * <p>Every route into the bank — the sidebar's "bank a chunk" form, a running
     * timer, and a logged completed session — lands here, so the multiplier is
     * applied exactly once per deposit no matter which route was taken. Putting it
     * anywhere further out would mean applying it in three places, and any preview
     * that scaled the amount itself would double it.</p>
     *
     * <p>Removals are pointedly not the inverse of this; see
     * {@link #removeManualXp}. A correction takes back a literal figure, because
     * the user is undoing an amount they can see in their balance, not re-earning
     * one.</p>
     *
     * @param amount the base XP earned, before any multiplier
     * @return how much was actually banked after the multiplier, which is 0 when
     *     the skill's current tier multiplies by zero
     */
    long addManualXp(Skill skill, long amount)
    {
        if (skill == null || amount <= 0)
        {
            return 0L;
        }

        long banked = xpMultiplierManager.applyTo(skill, amount);
        if (banked <= 0)
        {
            // A deliberate 0x tier. Nothing is stored, and no warning state is
            // touched, because the balance did not move.
            return 0L;
        }

        synchronized (dataLock)
        {
            // Saturating: banked totals clamp at the long ceiling rather than
            // wrapping negative, which would read as "no XP banked".
            storedXp.merge(skill, banked, LongMath::saturatedAdd);
            warnedSkills.remove(skill);
            depletionWarnedSkills.remove(skill);
            persistStoredXpLocked();
        }

        rebuildSnapshotAndNotify();
        return banked;
    }

    /**
     * Takes banked XP back out of a skill, for corrections only.
     *
     * <p>This is the undo for a mistyped or misdirected deposit, and it is
     * deliberately <em>not</em> the inverse of {@link #addManualXp}: it can only
     * ever reduce a balance the user already has, never create a negative one.
     * The caller asks for an amount and is told what was actually removed, so a
     * request larger than the balance empties the skill instead of failing or
     * going below zero.</p>
     *
     * <p>A correction is not gameplay consumption, so it is kept out of the
     * warning machinery entirely: no low-XP chat line, no depletion flash, and no
     * sample handed to {@link DepletionForecaster} — an edit the user made in the
     * sidebar says nothing about what an in-game action costs.</p>
     *
     * @return how much was actually removed; 0 when the skill has no banked XP
     */
    long removeManualXp(Skill skill, long amount)
    {
        if (skill == null || amount <= 0)
        {
            return 0L;
        }

        long removed;
        synchronized (dataLock)
        {
            long current = storedXp.getOrDefault(skill, 0L);
            if (current <= 0)
            {
                return 0L;
            }

            removed = Math.min(amount, current);
            long updated = current - removed;
            if (updated <= 0)
            {
                storedXp.remove(skill);
            }
            else
            {
                storedXp.put(skill, updated);
            }

            // Re-arm both warnings rather than firing them: the balance changed
            // because the user edited it, not because they trained.
            warnedSkills.remove(skill);
            depletionWarnedSkills.remove(skill);
            persistStoredXpLocked();
        }

        rebuildSnapshotAndNotify();
        return removed;
    }

    /** The authoritative banked total for a skill, used to bound removals. */
    long getBankedXp(Skill skill)
    {
        if (skill == null)
        {
            return 0L;
        }

        synchronized (dataLock)
        {
            return storedXp.getOrDefault(skill, 0L);
        }
    }

    /**
     * Banks XP earned from an IRL action, whether accrued by a live timer or
     * logged after the fact. Public because it is the services' entry point;
     * {@link #addManualXp} stays package-private for the XP tab's direct entry.
     *
     * @param amount the action's base XP, before the skill's level multiplier
     * @return how much was actually banked once the multiplier was applied, so
     *     callers can report the real figure rather than the one they asked for
     */
    public long addActionXp(Skill skill, long amount)
    {
        return addManualXp(skill, amount);
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
            depletionWarnedSkills.clear();

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

        Map<Skill, Integer> observed = new EnumMap<>(Skill.class);
        for (Skill skill : TRACKABLE_SKILLS)
        {
            observed.put(skill, client.getSkillExperience(skill));
        }

        synchronized (dataLock)
        {
            lastKnownXp.putAll(observed);
        }

        // Deliberately outside the lock: recording a level writes to config when
        // it changes, and a config write is dispatched synchronously on the event
        // bus. Keeping it out here means the plugin's own config listener never
        // runs while this method holds the data lock.
        observed.forEach(skillLevelTracker::recordExperience);
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
