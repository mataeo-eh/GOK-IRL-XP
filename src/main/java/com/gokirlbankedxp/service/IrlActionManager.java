package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import com.gokirlbankedxp.model.IrlAction;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.inject.Inject;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IrlActionManager
{
    private static final Logger log = LoggerFactory.getLogger(IrlActionManager.class);
    private static final String CONFIG_KEY = "irlActionsJson";
    private static final String UNITS_CONFIG_KEY = "irlActionUnitsJson";
    private static final Type ACTION_LIST_TYPE = new TypeToken<List<IrlAction>>()
    {
    }.getType();
    private static final Type UNIT_MAP_TYPE = new TypeToken<Map<String, Long>>()
    {
    }.getType();

    private final ConfigManager configManager;
    private final Gson gson;

    private final Object lock = new Object();
    private final Map<UUID, IrlAction> actions = new LinkedHashMap<>();
    private final Map<String, Long> savedUnits = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    @Inject
    public IrlActionManager(ConfigManager configManager)
    {
        this(configManager, new GsonBuilder().create());
    }

    IrlActionManager(ConfigManager configManager, Gson gson)
    {
        this.configManager = Objects.requireNonNull(configManager);
        this.gson = Objects.requireNonNull(gson);
    }

    public List<IrlAction> loadActions()
    {
        synchronized (lock)
        {
            actions.clear();
            loadSavedUnitsLocked();
            String raw = configManager.getConfiguration(GokIrlBankedXpConfig.GROUP, CONFIG_KEY);

            if (raw != null && !raw.isBlank())
            {
                try
                {
                    List<IrlAction> parsed = gson.fromJson(raw, ACTION_LIST_TYPE);
                    if (parsed != null)
                    {
                        for (IrlAction action : parsed)
                        {
                            IrlAction normalized = normalize(action).ensureId(UUID.randomUUID());
                            if (!normalized.isValid())
                            {
                                log.warn("Skipping invalid IRL action from config: {}", normalized.getName());
                                continue;
                            }

                            warnOnDuplicateNameLocked(normalized);
                            actions.put(normalized.getId(), normalized);
                            rememberUnitLocked(normalized);
                        }
                    }
                }
                catch (JsonParseException ex)
                {
                    log.warn("Failed to parse IRL actions config; keeping the action library empty", ex);
                }
            }

            // Persist the normalized representation (including migrations from
            // legacy enum-backed units). An empty library intentionally remains
            // empty; unit choices now come only from actions the user saved.
            saveActionsLocked();

            return snapshot();
        }
    }

    public List<IrlAction> getAllActions()
    {
        synchronized (lock)
        {
            return List.copyOf(actions.values());
        }
    }

    /** Returns only unit definitions represented by actions the user has saved. */
    public Map<String, Long> getSavedUnits()
    {
        synchronized (lock)
        {
            return new LinkedHashMap<>(savedUnits);
        }
    }

    public Optional<IrlAction> getAction(UUID id)
    {
        if (id == null)
        {
            return Optional.empty();
        }

        synchronized (lock)
        {
            return Optional.ofNullable(actions.get(id));
        }
    }

    public IrlAction createAction(IrlAction action)
    {
        IrlAction normalized = normalize(action).ensureId(UUID.randomUUID());
        if (!normalized.isValid())
        {
            throw new IllegalArgumentException("Cannot create invalid IRL action");
        }

        synchronized (lock)
        {
            warnOnDuplicateNameLocked(normalized);
            actions.put(normalized.getId(), normalized);
            rememberUnitLocked(normalized);
            saveActionsLocked();
            return normalized;
        }
    }

    public boolean updateAction(IrlAction action)
    {
        IrlAction normalized = normalize(action);
        if (normalized.getId() == null || !normalized.isValid())
        {
            return false;
        }

        synchronized (lock)
        {
            if (!actions.containsKey(normalized.getId()))
            {
                return false;
            }

            warnOnDuplicateNameLocked(normalized);
            actions.put(normalized.getId(), normalized);
            rememberUnitLocked(normalized);
            saveActionsLocked();
            return true;
        }
    }

    public boolean deleteAction(UUID id)
    {
        if (id == null)
        {
            return false;
        }

        synchronized (lock)
        {
            boolean removed = actions.remove(id) != null;
            if (removed)
            {
                saveActionsLocked();
            }
            return removed;
        }
    }

    public void saveActions()
    {
        synchronized (lock)
        {
            saveActionsLocked();
        }
    }

    private IrlAction normalize(IrlAction action)
    {
        if (action == null)
        {
            return IrlAction.builder()
                .id(UUID.randomUUID())
                .name("")
                .unitName("")
                .secondsPerUnit(0L)
                .timeUnit(null)
                .defaultXpPerUnit(0L)
                .skillMappings(Map.of())
                .build();
        }

        Map<Skill, Long> sanitized = new EnumMap<>(Skill.class);
        if (action.getSkillMappings() != null)
        {
            for (Map.Entry<Skill, Long> entry : action.getSkillMappings().entrySet())
            {
                Skill skill = entry.getKey();
                Long rate = entry.getValue();
                if (skill == null || rate == null || rate <= 0)
                {
                    continue;
                }
                sanitized.put(skill, rate);
            }
        }

        return IrlAction.builder()
            .id(action.getId())
            .name(action.getName())
            .unitName(action.getUnitName())
            .secondsPerUnit(action.getSecondsPerUnit())
            .timeUnit(null)
            .defaultXpPerUnit(action.getDefaultXpPerUnit())
            .skillMappings(sanitized)
            .build();
    }

    private void saveActionsLocked()
    {
        String json = gson.toJson(actions.values());
        configManager.setConfiguration(GokIrlBankedXpConfig.GROUP, CONFIG_KEY, json);
        configManager.setConfiguration(GokIrlBankedXpConfig.GROUP, UNITS_CONFIG_KEY, gson.toJson(savedUnits));
    }

    /** Loads the independently persisted unit library before actions are migrated. */
    private void loadSavedUnitsLocked()
    {
        savedUnits.clear();
        String raw = configManager.getConfiguration(GokIrlBankedXpConfig.GROUP, UNITS_CONFIG_KEY);
        if (raw == null || raw.isBlank())
        {
            return;
        }

        try
        {
            Map<String, Long> parsed = gson.fromJson(raw, UNIT_MAP_TYPE);
            if (parsed != null)
            {
                parsed.forEach((name, seconds) -> {
                    if (name != null && !name.isBlank() && seconds != null && seconds > 0)
                    {
                        savedUnits.put(name.trim(), seconds);
                    }
                });
            }
        }
        catch (JsonParseException ex)
        {
            log.warn("Failed to parse saved action units; rebuilding them from saved actions", ex);
        }
    }

    private void rememberUnitLocked(IrlAction action)
    {
        String name = action.getUnitName();
        long seconds = action.getSecondsPerUnit();
        if (name != null && !name.isBlank() && seconds > 0)
        {
            savedUnits.put(name.trim(), seconds);
        }
    }

    private List<IrlAction> snapshot()
    {
        return new ArrayList<>(actions.values());
    }

    private void warnOnDuplicateNameLocked(IrlAction candidate)
    {
        if (candidate.getName() == null || candidate.getName().isEmpty())
        {
            return;
        }

        String normalizedName = candidate.getName().toLowerCase(Locale.ROOT);
        boolean duplicate = actions.values().stream()
            .anyMatch(existing ->
                !existing.getId().equals(candidate.getId())
                    && existing.getName() != null
                    && existing.getName().toLowerCase(Locale.ROOT).equals(normalizedName));

        if (duplicate)
        {
            log.warn("Duplicate IRL action name detected: {}", candidate.getName());
        }
    }
}
