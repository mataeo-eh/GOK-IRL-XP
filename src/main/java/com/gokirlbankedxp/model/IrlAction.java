package com.gokirlbankedxp.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import net.runelite.api.Skill;

@Getter
public class IrlAction
{
    private final UUID id;
    private final String name;
    private final TimeUnit timeUnit;
    private final long defaultXpPerUnit;
    private final Map<Skill, Long> skillMappings;

    @Builder(toBuilder = true)
    private IrlAction(
        UUID id,
        String name,
        TimeUnit timeUnit,
        long defaultXpPerUnit,
        Map<Skill, Long> skillMappings
    )
    {
        this.id = id == null ? UUID.randomUUID() : id;
        this.name = name == null ? "" : name.trim();
        this.timeUnit = timeUnit;
        this.defaultXpPerUnit = defaultXpPerUnit;
        this.skillMappings = Collections.unmodifiableMap(sanitizeMappings(skillMappings));
    }

    public boolean isValid()
    {
        return !name.isEmpty()
            && timeUnit != null
            && defaultXpPerUnit > 0
            && !skillMappings.isEmpty();
    }

    public boolean hasSkillMapping(Skill skill)
    {
        return skill != null && skillMappings.containsKey(skill);
    }

    public long getXpForSkill(Skill skill)
    {
        if (skill == null)
        {
            return 0L;
        }

        Long override = skillMappings.get(skill);
        if (override != null && override > 0)
        {
            return override;
        }

        return Math.max(0L, defaultXpPerUnit);
    }

    private static Map<Skill, Long> sanitizeMappings(Map<Skill, Long> mappings)
    {
        if (mappings == null || mappings.isEmpty())
        {
            return Collections.emptyMap();
        }

        Map<Skill, Long> cleaned = new EnumMap<>(Skill.class);
        for (Map.Entry<Skill, Long> entry : mappings.entrySet())
        {
            Skill skill = entry.getKey();
            Long rate = entry.getValue();
            if (skill == null || rate == null || rate <= 0)
            {
                continue;
            }

            cleaned.put(skill, rate);
        }

        return cleaned;
    }

    public IrlAction ensureId(UUID fallbackId)
    {
        return id != null ? this : toBuilder().id(Objects.requireNonNull(fallbackId)).build();
    }
}
