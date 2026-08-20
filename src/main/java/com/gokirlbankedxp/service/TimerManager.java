package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import com.gokirlbankedxp.GokIrlBankedXpPlugin;
import com.gokirlbankedxp.model.ActiveTimer;
import com.gokirlbankedxp.model.IrlAction;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import javax.inject.Inject;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private ScheduledExecutorService scheduler;
    private final boolean autoSchedule;
    private final Gson gson;

    private final Object lock = new Object();
    private final Map<UUID, ActiveTimer> activeTimers = new LinkedHashMap<>();
    private int ticksSinceSave = 0;

    @Inject
    public TimerManager(ConfigManager configManager, IrlActionManager actionManager, GokIrlBankedXpPlugin plugin)
    {
        this(configManager, actionManager, plugin, System::currentTimeMillis, Executors.newSingleThreadScheduledExecutor());
    }

    TimerManager(ConfigManager configManager, IrlActionManager actionManager, GokIrlBankedXpPlugin plugin, Supplier<Long> timeSupplier, ScheduledExecutorService scheduler)
    {
        this.configManager = Objects.requireNonNull(configManager);
        this.actionManager = Objects.requireNonNull(actionManager);
        this.plugin = Objects.requireNonNull(plugin);
        this.timeSupplier = Objects.requireNonNull(timeSupplier);
        this.scheduler = scheduler;
        this.autoSchedule = scheduler != null;
        this.gson = new GsonBuilder().create();
    }

    public void startUp()
    {
        synchronized (lock)
        {
            loadTimersLocked();
        }

        if (autoSchedule)
        {
            if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated())
            {
                scheduler = Executors.newSingleThreadScheduledExecutor();
            }
            scheduler.scheduleAtFixedRate(this::tick, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    public void shutDown()
    {
        if (autoSchedule && scheduler != null)
        {
            scheduler.shutdownNow();
        }

        saveTimers();
    }

    public Optional<UUID> startTimer(UUID actionId)
    {
        Optional<IrlAction> action = actionManager.getAction(actionId);
        if (action.isEmpty())
        {
            return Optional.empty();
        }

        ActiveTimer timer = ActiveTimer.start(actionId, timeSupplier.get());
        timer.alignAwardedUnits(action.get().getSecondsPerUnit());

        synchronized (lock)
        {
            activeTimers.put(timer.getId(), timer);
            saveTimersLocked();
        }

        return Optional.of(timer.getId());
    }

    public boolean stopTimer(UUID timerId)
    {
        if (timerId == null)
        {
            return false;
        }

        synchronized (lock)
        {
            boolean removed = activeTimers.remove(timerId) != null;
            if (removed)
            {
                saveTimersLocked();
            }
            return removed;
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
                if (action.isPresent())
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
        }

        earnedBeforePause.forEach((skill, amount) -> plugin.addTimerXp(skill, amount));
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

                IrlAction value = action.get();
                snapshots.add(new TimerSnapshot(
                    timer.getId(),
                    value.getName(),
                    timer.getDisplayElapsedSeconds(now),
                    timer.isPaused(),
                    value.getSkillMappings(),
                    value.getUnitName()
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
            List<UUID> missingActions = new ArrayList<>();

            for (ActiveTimer timer : activeTimers.values())
            {
                Optional<IrlAction> actionOptional = actionManager.getAction(timer.getActionId());
                if (actionOptional.isEmpty())
                {
                    missingActions.add(timer.getId());
                    continue;
                }

                IrlAction action = actionOptional.get();
                Map<Skill, Long> earned = timer.applyTick(now, action);
                for (Map.Entry<Skill, Long> entry : earned.entrySet())
                {
                    pendingXp.merge(entry.getKey(), entry.getValue(), Long::sum);
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
        }

        if (!pendingXp.isEmpty())
        {
            pendingXp.forEach((skill, amount) -> plugin.addTimerXp(skill, amount));
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
                    Optional<IrlAction> action = actionManager.getAction(entry.actionId);
                    if (action.isEmpty())
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
                    timer.alignAwardedUnits(action.get().getSecondsPerUnit());
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
        List<StoredTimer> stored = new ArrayList<>();
        for (ActiveTimer timer : activeTimers.values())
        {
            stored.add(new StoredTimer(timer.getId(), timer.getActionId(), timer.getElapsedSeconds(), timer.isPaused()));
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

        StoredTimer()
        {
        }

        StoredTimer(UUID id, UUID actionId, long elapsedSeconds, boolean paused)
        {
            this.id = id;
            this.actionId = actionId;
            this.elapsedSeconds = elapsedSeconds;
            this.paused = paused;
        }
    }
}
