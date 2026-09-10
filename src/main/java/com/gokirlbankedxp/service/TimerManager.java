package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import com.gokirlbankedxp.GokIrlBankedXpPlugin;
import com.gokirlbankedxp.model.ActiveTimer;
import com.gokirlbankedxp.model.IrlAction;
import com.google.common.math.LongMath;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the live timers that convert elapsed real time into banked XP.
 *
 * <p>{@code @Singleton} is required, not decorative: the plugin and the actions
 * panel both inject this class, and an unscoped binding would give each of them
 * a separate manager. Only the plugin's copy has {@link #startUp()} called on
 * it, so timers started from the panel would never tick.</p>
 */
@Singleton
public class TimerManager
{
    private static final Logger log = LoggerFactory.getLogger(TimerManager.class);
    private static final String CONFIG_KEY = "activeTimersJson";
    private static final Type STORED_TIMER_LIST = new TypeToken<List<StoredTimer>>()
    {
    }.getType();

    private final ConfigManager configManager;
    private final IrlActionManager actionManager;
    private final GokIrlBankedXpPlugin plugin;
    private final Supplier<Long> timeSupplier;
    private final Gson gson;

    /**
     * RuneLite's shared scheduler, owned by the client and used by every plugin.
     * This class therefore only ever cancels the single task it submitted, and
     * must never shut the executor down. Tests pass null and drive {@link #tick()}
     * by hand so timing stays deterministic.
     */
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> tickFuture;

    private final Object lock = new Object();
    private final Map<UUID, ActiveTimer> activeTimers = new LinkedHashMap<>();
    private int ticksSinceSave = 0;
    /** Identity of the config profile which owns the in-memory timers. */
    private Long loadedProfileId;

    private Long currentProfileId()
    {
        ConfigProfile profile = configManager.getProfile();
        return profile == null ? null : profile.getId();
    }

    /** Reload all dependent state while timer ticks are excluded. Never save old timers into the new profile. */
    public void reloadProfile(Runnable reloadDependencies)
    {
        synchronized (lock)
        {
            activeTimers.clear();
            reloadDependencies.run();
            loadTimersLocked();
        }
    }

    @Inject
    public TimerManager(ConfigManager configManager, IrlActionManager actionManager, GokIrlBankedXpPlugin plugin,
        ScheduledExecutorService executor, Gson gson)
    {
        this(configManager, actionManager, plugin, executor, gson, System::currentTimeMillis);
    }

    TimerManager(ConfigManager configManager, IrlActionManager actionManager, GokIrlBankedXpPlugin plugin,
        ScheduledExecutorService executor, Gson gson, Supplier<Long> timeSupplier)
    {
        this.configManager = Objects.requireNonNull(configManager);
        this.actionManager = Objects.requireNonNull(actionManager);
        this.plugin = Objects.requireNonNull(plugin);
        this.gson = Objects.requireNonNull(gson);
        this.timeSupplier = Objects.requireNonNull(timeSupplier);
        this.executor = executor;
    }

    public void startUp()
    {
        synchronized (lock)
        {
            loadTimersLocked();
        }

        // Guarding on tickFuture keeps a startUp() after a shutDown() (the plugin
        // being toggled off and on) from stacking a second ticking task.
        if (executor != null && tickFuture == null)
        {
            tickFuture = executor.scheduleAtFixedRate(this::runScheduledTick, 1, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * The entry point the scheduler calls once a second.
     *
     * <p>{@link ScheduledExecutorService#scheduleAtFixedRate} cancels a task for
     * good the first time it throws, and does so silently. Without this guard a
     * single failure anywhere under {@link #tick()} — a config write that threw,
     * a listener on the event bus, a disk error surfacing as a runtime exception
     * — would stop every timer until the plugin was restarted, with nothing on
     * screen to say why. The failure is logged and the next second's tick runs
     * as normal.</p>
     *
     * <p>A unit whose award threw is not re-awarded: the timer had already
     * counted it as paid before the exception. That loses at most one unit's XP
     * on an error that should never happen, against timers that otherwise stop
     * for the whole session.</p>
     */
    void runScheduledTick()
    {
        try
        {
            tick();
        }
        catch (RuntimeException ex)
        {
            log.warn("Timer tick failed; timers will keep running", ex);
        }
    }

    public void shutDown()
    {
        // Cancel only the task this plugin submitted. The executor belongs to the
        // client and is shared with every other plugin, so shutting it down here
        // would silently break them until the client restarts.
        if (tickFuture != null)
        {
            tickFuture.cancel(false);
            tickFuture = null;
        }

        saveTimers();
    }

    /**
     * Starts a timer for a timed action.
     *
     * <p>Returns empty when the action no longer exists, or when it is an
     * untimed (benchmark-style) action. Untimed actions have no seconds-per-unit
     * to accrue against, so a timer on one would run forever awarding nothing;
     * those are banked through {@link ActionLogManager} instead.</p>
     */
    public Optional<UUID> startTimer(UUID actionId)
    {
        synchronized (lock)
        {
            if (!Objects.equals(loadedProfileId, currentProfileId()))
            {
                return Optional.empty();
            }
            // Resolve inside the reload boundary so a captured action cannot
            // outlive its profile and seed a timer in the next one.
            Optional<IrlAction> action = actionManager.getAction(actionId);
            if (action.isEmpty() || !action.get().isTimed())
            {
                return Optional.empty();
            }
            ActiveTimer timer = ActiveTimer.start(actionId, timeSupplier.get());
            timer.bindAction(action.get());
            timer.alignAwardedUnits(action.get().getSecondsPerUnit());
            activeTimers.put(timer.getId(), timer);
            saveTimersLocked();
            return Optional.of(timer.getId());
        }
    }

    public boolean stopTimer(UUID timerId)
    {
        if (timerId == null)
        {
            return false;
        }

        synchronized (lock)
        {
            if (!Objects.equals(loadedProfileId, currentProfileId()))
            {
                return false;
            }
            ActiveTimer timer = activeTimers.get(timerId);
            if (timer == null)
            {
                return false;
            }
            // Match Pause: settle completed units, but do not round up an unfinished unit.
            actionManager.getAction(timer.getActionId()).filter(IrlAction::isTimed).ifPresent(action ->
                timer.applyTick(timeSupplier.get(), action).forEach(plugin::addActionXp));
            activeTimers.remove(timerId);
            saveTimersLocked();
            return true;
        }
    }

    public boolean pauseTimer(UUID timerId)
    {
        return changePauseState(timerId, true);
    }

    public boolean resumeTimer(UUID timerId)
    {
        return changePauseState(timerId, false);
    }

    private boolean changePauseState(UUID timerId, boolean paused)
    {
        if (timerId == null)
        {
            return false;
        }

        Map<Skill, Long> earnedBeforePause = Collections.emptyMap();
        synchronized (lock)
        {
            if (!Objects.equals(loadedProfileId, currentProfileId()))
            {
                return false;
            }
            ActiveTimer timer = activeTimers.get(timerId);
            if (timer == null)
            {
                return false;
            }

            if (paused && timer.isPaused())
            {
                return false;
            }

            if (!paused && !timer.isPaused())
            {
                return false;
            }

            long now = timeSupplier.get();
            if (paused)
            {
                // Settle any whole units since the scheduler's last tick before
                // freezing the timer, so a quick pause/stop cannot lose XP.
                Optional<IrlAction> action = actionManager.getAction(timer.getActionId());
                if (action.isPresent() && action.get().isTimed())
                {
                    earnedBeforePause = timer.applyTick(now, action.get());
                }
                timer.pause(now);
            }
            else
            {
                timer.resume(now);
            }

            saveTimersLocked();
            // Keep settlement inside the profile reload boundary.
            earnedBeforePause.forEach((skill, amount) -> plugin.addActionXp(skill, amount));
        }
        return true;
    }

    public List<ActiveTimer> getActiveTimers()
    {
        synchronized (lock)
        {
            List<ActiveTimer> copy = new ArrayList<>();
            for (ActiveTimer timer : activeTimers.values())
            {
                copy.add(timer.copy());
            }
            return copy;
        }
    }

    public List<TimerSnapshot> getTimerSnapshots()
    {
        long now = timeSupplier.get();
        List<TimerSnapshot> snapshots = new ArrayList<>();
        synchronized (lock)
        {
            for (ActiveTimer timer : activeTimers.values())
            {
                Optional<IrlAction> action = actionManager.getAction(timer.getActionId());
                if (action.isEmpty())
                {
                    continue;
                }

                // The rates and unit come from the frozen terms the timer is
                // actually paying out at, so what is shown matches what is
                // earned even after the action was edited. The name is the
                // live one: a renamed action should not keep its old name in
                // the timer list, and the name changes nothing about the XP.
                IrlAction terms = timer.getActionSnapshot() == null ? action.get() : timer.getActionSnapshot();
                snapshots.add(new TimerSnapshot(
                    timer.getId(),
                    action.get().getName(),
                    timer.getDisplayElapsedSeconds(now),
                    timer.isPaused(),
                    terms.getSkillMappings(),
                    terms.getUnitName()
                ));
            }
        }
        return snapshots;
    }

    public void tick()
    {
        long now = timeSupplier.get();
        Map<Skill, Long> pendingXp = new EnumMap<>(Skill.class);
        boolean changed = false;

        synchronized (lock)
        {
            // ConfigManager changes its active profile before posting ProfileChanged.
            // A scheduler tick in that interval must not write the old profile's state.
            if (!Objects.equals(loadedProfileId, currentProfileId()))
            {
                return;
            }
            List<UUID> missingActions = new ArrayList<>();

            for (ActiveTimer timer : activeTimers.values())
            {
                Optional<IrlAction> actionOptional = actionManager.getAction(timer.getActionId());
                // A timer is stale once its action is deleted or edited to be
                // untimed; either way it can no longer accrue units.
                if (actionOptional.isEmpty() || !actionOptional.get().isTimed())
                {
                    missingActions.add(timer.getId());
                    continue;
                }

                IrlAction action = actionOptional.get();
                Map<Skill, Long> earned = timer.applyTick(now, action);
                for (Map.Entry<Skill, Long> entry : earned.entrySet())
                {
                    pendingXp.merge(entry.getKey(), entry.getValue(), LongMath::saturatedAdd);
                }
            }

            if (!missingActions.isEmpty())
            {
                missingActions.forEach(activeTimers::remove);
                changed = true;
            }

            ticksSinceSave++;
            if (ticksSinceSave >= 10 || changed)
            {
                saveTimersLocked();
                ticksSinceSave = 0;
            }
            // A profile reload cannot interleave between accruing and crediting XP.
            pendingXp.forEach((skill, amount) -> plugin.addActionXp(skill, amount));
        }
    }

    public void saveTimers()
    {
        synchronized (lock)
        {
            saveTimersLocked();
        }
    }

    private void loadTimersLocked()
    {
        activeTimers.clear();
        ticksSinceSave = 0;
        loadedProfileId = currentProfileId();

        String raw = configManager.getConfiguration(GokIrlBankedXpConfig.GROUP, CONFIG_KEY);
        if (raw == null || raw.isBlank())
        {
            saveTimersLocked();
            return;
        }

        try
        {
            List<StoredTimer> stored = gson.fromJson(raw, STORED_TIMER_LIST);
            if (stored != null)
            {
                for (StoredTimer entry : stored)
                {
                    if (entry == null)
                    {
                        continue;
                    }
                    Optional<IrlAction> action = actionManager.getAction(entry.actionId);
                    // Drop timers whose action was deleted, or converted to an
                    // untimed action, while the client was closed.
                    if (action.isEmpty() || !action.get().isTimed())
                    {
                        continue;
                    }

                    ActiveTimer timer = new ActiveTimer(
                        entry.id,
                        entry.actionId,
                        Math.max(0, entry.elapsedSeconds),
                        entry.paused,
                        timeSupplier.get(),
                        0L
                    );
                    // Gson bypasses constructors. Rebuild to sanitize nullable fields and mappings.
                    IrlAction terms = entry.actionSnapshot == null ? action.get() : entry.actionSnapshot.toBuilder().build();
                    if (!terms.isValid() || !terms.isTimed() || !Objects.equals(terms.getId(), entry.actionId))
                    {
                        terms = action.get();
                    }
                    timer.bindAction(terms);
                    timer.alignAwardedUnits(terms.getSecondsPerUnit());
                    activeTimers.put(timer.getId(), timer);
                }
            }
        }
        catch (JsonParseException ex)
        {
            log.warn("Unable to parse active timers config; clearing timers", ex);
        }

        saveTimersLocked();
    }

    private void saveTimersLocked()
    {
        if (!Objects.equals(loadedProfileId, currentProfileId()))
        {
            return;
        }
        List<StoredTimer> stored = new ArrayList<>();
        for (ActiveTimer timer : activeTimers.values())
        {
            stored.add(new StoredTimer(timer.getId(), timer.getActionId(), timer.getElapsedSeconds(), timer.isPaused(), timer.getActionSnapshot()));
        }
        String json = gson.toJson(stored);
        configManager.setConfiguration(GokIrlBankedXpConfig.GROUP, CONFIG_KEY, json);
    }

    /**
     * Immutable view data for the timer list. This intentionally uses an ordinary
     * class instead of a Java record because RuneLite Plugin Hub targets Java 11.
     */
    public static final class TimerSnapshot
    {
        private final UUID id;
        private final String actionName;
        private final long elapsedSeconds;
        private final boolean paused;
        private final Map<Skill, Long> rates;
        private final String unitName;

        TimerSnapshot(UUID id, String actionName, long elapsedSeconds, boolean paused,
                      Map<Skill, Long> rates, String unitName)
        {
            this.id = id;
            this.actionName = actionName;
            this.elapsedSeconds = elapsedSeconds;
            this.paused = paused;
            this.rates = rates;
            this.unitName = unitName;
        }

        public UUID id()
        {
            return id;
        }

        public String actionName()
        {
            return actionName;
        }

        public boolean paused()
        {
            return paused;
        }

        public String formatRates()
        {
            if (rates == null || rates.isEmpty())
            {
                return "";
            }

            String unit = unitName == null ? "unit" : unitName.toLowerCase(Locale.US);
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Skill, Long> entry : rates.entrySet())
            {
                parts.add(String.format("%s +%d/%s", entry.getKey().getName(), entry.getValue(), unit));
            }
            return String.join(", ", parts);
        }

        public String formatElapsed()
        {
            long hours = elapsedSeconds / 3600;
            long minutes = (elapsedSeconds % 3600) / 60;
            long seconds = elapsedSeconds % 60;
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
    }

    private static class StoredTimer
    {
        UUID id;
        UUID actionId;
        long elapsedSeconds;
        boolean paused;
        // Optional for legacy JSON; new saves retain the original earning terms across restart.
        IrlAction actionSnapshot;

        StoredTimer()
        {
        }

        StoredTimer(UUID id, UUID actionId, long elapsedSeconds, boolean paused, IrlAction actionSnapshot)
        {
            this.id = id;
            this.actionId = actionId;
            this.elapsedSeconds = elapsedSeconds;
            this.paused = paused;
            this.actionSnapshot = actionSnapshot;
        }
    }
}
