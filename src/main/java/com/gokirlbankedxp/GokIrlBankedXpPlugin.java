package com.gokirlbankedxp;

import com.google.common.math.LongMath;
import com.google.inject.Provides;
import com.gokirlbankedxp.service.BankedXpStore;
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

@PluginDescriptor(
    name = "IRL XP",
    description = "Turn real-world actions into banked RuneScape XP and track it as you train.",
    tags = {"banked", "experience", "irl", "xp"}
)
public class GokIrlBankedXpPlugin extends Plugin
{
    // Skill.OVERALL is a deprecated null sentinel; Skill.values() already omits it.
    private static final Skill[] TRACKABLE_SKILLS = Skill.values();

    private static final BufferedImage NAV_ICON = buildIcon();

    private final Object dataLock = new Object();
    /**
     * The balance of every skill that has one. Positive is XP banked and waiting
     * to be spent in-game; negative is XP owed, because in-game gains outran what
     * was banked (see {@link #subtractXpLocked}). Zero is never stored — a skill
     * with nothing banked and nothing owed simply has no entry.
     */
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

    /**
     * Where balances are saved and loaded. The plugin never touches config for
     * banked XP directly; see {@link BankedXpStore} for why there are two copies.
     */
    @Inject
    private BankedXpStore bankedXpStore;

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
            // Already spent, or in debt. There is nothing left to warn about, and
            // re-arming here means the next top-up gets a fresh warning. The drop
            // was still recorded above: the rate it teaches is just as valid for
            // when the skill is topped up again.
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
        else if (BankedXpStore.XP_KEY.equals(event.getKey()))
        {
            // ConfigManager posts this synchronously for every write, including the
            // store's own. Reloading on that echo re-parsed what was just saved and,
            // worse, cleared the warn-once sets on every XP drop. Only a value this
            // plugin did not write — a profile switch, a sync from the server, or a
            // reset from the settings panel — is a reason to reload.
            if (bankedXpStore.isOwnConfigValue(event.getNewValue()))
            {
                return;
            }
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
     * <p>A deposit into a skill that is in debt pays the debt off first. Owing
     * 5,000 and banking 3,000 leaves the skill owing 2,000, and nothing shows as
     * banked until the whole debt is cleared. That is the point of the debt: XP
     * earned in-game ahead of the bank is still paid for with real-world effort,
     * just after the fact.</p>
     *
     * @param amount the base XP earned, before any multiplier
     * @return how much was actually credited after the multiplier, which is 0 when
     *     the skill's current tier multiplies by zero. This is the figure that
     *     moved the balance, whether it paid down a debt or added to the bank.
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
            // The deposit lands on whatever the balance is, debt included.
            // Saturating: a huge total clamps at the long ceiling rather than
            // wrapping negative, which would now read as an enormous debt.
            long updated = LongMath.saturatedAdd(storedXp.getOrDefault(skill, 0L), banked);
            storeBalanceLocked(skill, updated);
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
     * ever reduce a balance the user already has, never create or deepen a
     * negative one. A skill that is in debt has nothing to take back, so removal
     * refuses it outright. The caller asks for an amount and is told what was
     * actually removed, so a request larger than the balance empties the skill
     * instead of failing or going below zero.</p>
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
            storeBalanceLocked(skill, current - removed);

            // Re-arm both warnings rather than firing them: the balance changed
            // because the user edited it, not because they trained.
            warnedSkills.remove(skill);
            depletionWarnedSkills.remove(skill);
            persistStoredXpLocked();
        }

        rebuildSnapshotAndNotify();
        return removed;
    }

    /**
     * The authoritative balance for a skill, used to bound removals. Negative
     * while the skill is in debt, and 0 for a skill with no entry at all.
     */
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

    /**
     * Replaces the in-memory balances with whatever the store says is current.
     *
     * <p>Runs at start-up and whenever RuneLite changes the config copy from
     * outside this plugin. The read happens inside the lock: a read taken before
     * it could be overtaken by a deposit on another thread, and the stale result
     * would then be written back over the deposit.</p>
     */
    private void loadStoredXp()
    {
        synchronized (dataLock)
        {
            Map<Skill, Long> loaded = bankedXpStore.load();
            storedXp.clear();
            storedXp.putAll(loaded);
            // Balances may have changed under the warnings' feet, so both re-arm.
            warnedSkills.clear();
            depletionWarnedSkills.clear();
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

    /**
     * Applies an in-game XP drop to a skill's balance, one for one.
     *
     * <p>This is the only place a balance can go below zero. When the drop is
     * bigger than what is banked — a quest reward, a lamp, or simply training a
     * skill with nothing banked — the balance keeps falling into a debt rather
     * than stopping at zero, and the next deposits pay that debt back before
     * anything shows as banked again (see {@link #addManualXp}). Deposits and
     * removals both refuse to create a debt, so one can only ever be earned in
     * the game. That is what keeps the bank honest: every XP the character gains
     * is eventually matched by real-world effort, whether banked before or
     * after.</p>
     *
     * <p>Saturating at the long floor, for the same reason deposits saturate at
     * the ceiling: a wrapped value would flip the sign and turn a debt into a
     * fortune.</p>
     *
     * @return whether the balance moved, which every positive drop makes it do
     */
    private boolean subtractXpLocked(Skill skill, long delta)
    {
        if (delta <= 0)
        {
            return false;
        }

        long current = storedXp.getOrDefault(skill, 0L);
        long updated = LongMath.saturatedSubtract(current, delta);
        storeBalanceLocked(skill, updated);

        handleThresholdLocked(skill, updated);
        persistStoredXpLocked();
        return updated != current;
    }

    /**
     * Writes a balance into {@link #storedXp}, dropping the entry when it is
     * exactly zero so "nothing banked, nothing owed" and "no entry" stay the same
     * state everywhere the map is read. Called with {@link #dataLock} held.
     */
    private void storeBalanceLocked(Skill skill, long balance)
    {
        if (balance == 0)
        {
            storedXp.remove(skill);
        }
        else
        {
            storedXp.put(skill, balance);
        }
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
            // Spent, or in debt. The low warning is about a balance that is
            // about to run out, not one that already has; the sidebar and
            // overlay show the debt itself. Re-arm so the next top-up warns again.
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
            if (remaining == 0)
            {
                // Never stored (see storeBalanceLocked), but harmless to skip.
                continue;
            }

            Skill skill = entry.getKey();
            // The total is the net position: debts count against banked XP, so a
            // user with 10,000 banked in one skill and 4,000 owed in another sees
            // 6,000, which is what their real-world effort has actually covered.
            total = LongMath.saturatedAdd(total, remaining);
            skills.add(new BankedSkill(
                skill,
                skill.getName(),
                remaining,
                threshold,
                // LOW is a warning that a positive balance is nearly spent. A debt
                // is its own state, flagged by BankedSkill.isInDebt(), not a
                // very low balance.
                remaining > 0 && remaining <= threshold
            ));
        }

        if (skills.isEmpty())
        {
            return BankedXpSnapshot.empty();
        }

        // Largest balance first, which puts every debt at the bottom of the list.
        skills.sort(Comparator.comparingLong(BankedSkill::getRemainingXp).reversed());
        return new BankedXpSnapshot(total, List.copyOf(skills));
    }

    /** Saves the current balances; called with {@link #dataLock} held after every change. */
    private void persistStoredXpLocked()
    {
        bankedXpStore.save(storedXp);
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

    /**
     * An immutable read of every balance, published to the sidebar and overlay
     * after each change so neither has to take {@link #dataLock}.
     */
    @Value
    static class BankedXpSnapshot
    {
        /** Net of every balance: banked XP minus XP owed. Can be zero or negative while any skill is in debt. */
        long totalXp;
        List<BankedSkill> skills;

        static BankedXpSnapshot empty()
        {
            return new BankedXpSnapshot(0L, Collections.emptyList());
        }

        /**
         * Whether there is anything to show. Judged on the skill list rather than
         * the total, because a bank that is entirely debt nets to zero or less
         * and is exactly the thing the user needs to see.
         */
        boolean hasData()
        {
            return !skills.isEmpty();
        }
    }

    @Value
    static class BankedSkill
    {
        Skill skill;
        String displayName;
        /** Positive: banked XP left to spend. Negative: XP owed after in-game gains outran the bank. */
        long remainingXp;
        int threshold;
        /** Only ever true for a positive balance; a debt is reported by {@link #isInDebt()} instead. */
        boolean belowThreshold;

        /** Whether in-game XP has outrun what was banked for this skill. */
        boolean isInDebt()
        {
            return remainingXp < 0;
        }
    }
}
