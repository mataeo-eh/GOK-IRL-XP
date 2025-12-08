package com.gokirlbankedxp.service;

import com.gokirlbankedxp.GokIrlBankedXpConfig;
import com.gokirlbankedxp.model.IrlAction;
import com.gokirlbankedxp.model.TimeUnit;
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
    private static final Type ACTION_LIST_TYPE = new TypeToken<List<IrlAction>>()
    {
    }.getType();

    private final ConfigManager configManager;
    private final Gson gson;

    private final Object lock = new Object();
    private final Map<UUID, IrlAction> actions = new LinkedHashMap<>();

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
                        }
                    }
                }
                catch (JsonParseException ex)
                {
                    log.warn("Failed to parse IRL actions config; restoring defaults", ex);
                }
            }

            if (actions.isEmpty())
            {
                applyDefaultsLocked();
            }
            else
            {
                saveActionsLocked();
            }

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

    private void applyDefaultsLocked()
    {
        List<IrlAction> defaults = buildDefaultActions();
        for (IrlAction action : defaults)
        {
            actions.put(action.getId(), action);
        }
        saveActionsLocked();
    }

    private IrlAction normalize(IrlAction action)
    {
        if (action == null)
        {
            return IrlAction.builder()
                .id(UUID.randomUUID())
                .name("")
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
            .timeUnit(action.getTimeUnit())
            .defaultXpPerUnit(action.getDefaultXpPerUnit())
            .skillMappings(sanitized)
            .build();
    }

    private void saveActionsLocked()
    {
        String json = gson.toJson(actions.values());
        configManager.setConfiguration(GokIrlBankedXpConfig.GROUP, CONFIG_KEY, json);
    }

    private List<IrlAction> snapshot()
    {
        return new ArrayList<>(actions.values());
    }

    private List<IrlAction> buildDefaultActions()
    {
        Map<Skill, Long> walking = new EnumMap<>(Skill.class);
        walking.put(Skill.AGILITY, 50L);

        Map<Skill, Long> pushUps = new EnumMap<>(Skill.class);
        pushUps.put(Skill.STRENGTH, 5L);

        List<IrlAction> defaults = new ArrayList<>();
        defaults.add(IrlAction.builder()
            .name("Walking")
            .timeUnit(TimeUnit.MINUTES)
            .defaultXpPerUnit(50L)
            .skillMappings(walking)
            .build());
        defaults.add(IrlAction.builder()
            .name("Push-ups")
            .timeUnit(TimeUnit.SECONDS)
            .defaultXpPerUnit(5L)
            .skillMappings(pushUps)
            .build());
        return defaults;
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
