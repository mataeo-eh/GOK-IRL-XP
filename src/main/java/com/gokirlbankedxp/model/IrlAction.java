package com.gokirlbankedxp.model;

import com.google.common.math.LongMath;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import net.runelite.api.Skill;

/**
 * A single user-defined real-world activity and the XP it is worth.
 *
 * <p>An action is either <em>timed</em> or <em>untimed</em>:</p>
 * <ul>
 *   <li><b>Timed</b> actions can be run on a live timer. {@code secondsPerUnit}
 *       says how much real time makes up one unit, so the timer knows when to
 *       award another unit's worth of XP.</li>
 *   <li><b>Untimed</b> actions are benchmark-style ("XP per pound lifted",
 *       "XP per kilometre run"). There is no meaningful duration for a unit, so
 *       {@code secondsPerUnit} is zero and the user banks XP by logging how many
 *       units they completed instead.</li>
 * </ul>
 */
@Getter
public class IrlAction
{
    private final UUID id;
    private final String name;
    /** User-defined unit label used in the action editor and timer summaries. */
    private final String unitName;
    /**
     * Exact timer interval represented by one unit. Zero for untimed actions,
     * which have no duration to convert.
     */
    private final long secondsPerUnit;
    /**
     * Boxed on purpose: Gson leaves it null for actions saved before untimed
     * actions existed, which lets {@link #isTimed()} tell "saved by an older
     * version" apart from "the user explicitly unticked Timed". Every legacy
     * action was timer-driven, so a null value migrates to timed.
     */
    @Getter(AccessLevel.NONE)
    private final Boolean timed;
    /**
     * Legacy field retained only so Gson can migrate actions saved by older
     * versions, where units were restricted to the TimeUnit enum.
     */
    private final TimeUnit timeUnit;
    private final long defaultXpPerUnit;
    private final Map<Skill, Long> skillMappings;

    @Builder(toBuilder = true)
    private IrlAction(
        UUID id,
        String name,
        String unitName,
        long secondsPerUnit,
        Boolean timed,
        TimeUnit timeUnit,
        long defaultXpPerUnit,
        Map<Skill, Long> skillMappings
    )
    {
        this.id = id == null ? UUID.randomUUID() : id;
        this.name = name == null ? "" : name.trim();
        String normalizedUnitName = unitName == null ? "" : unitName.trim();
        this.unitName = normalizedUnitName.isEmpty() && timeUnit != null
            ? timeUnit.getDisplayName()
            : normalizedUnitName;

        long resolvedSeconds = secondsPerUnit > 0
            ? secondsPerUnit
            : timeUnit == null ? 0L : timeUnit.getSecondsPerUnit();
        // A null flag means "not stated" and is resolved from the duration, which
        // is how pre-existing saved actions keep working. An explicit flag always
        // wins, and an untimed action never keeps a duration around to confuse
        // the timer code.
        boolean resolvedTimed = timed == null ? resolvedSeconds > 0 : timed;
        this.timed = resolvedTimed;
        this.secondsPerUnit = resolvedTimed ? resolvedSeconds : 0L;

        this.timeUnit = timeUnit;
        this.defaultXpPerUnit = defaultXpPerUnit;
        this.skillMappings = Collections.unmodifiableMap(sanitizeMappings(skillMappings));
    }

    public boolean isValid()
    {
        return !name.isEmpty()
            && !getUnitName().isEmpty()
            // Only timed actions need a duration; untimed ones are logged by hand.
            && (!isTimed() || getSecondsPerUnit() > 0)
            && defaultXpPerUnit > 0
            && !getSkillMappings().isEmpty();
    }

    /** Resolves legacy enum-backed actions even when Gson bypasses the constructor. */
    public String getUnitName()
    {
        if (unitName != null && !unitName.trim().isEmpty())
        {
            return unitName.trim();
        }
        return timeUnit == null ? "" : timeUnit.getDisplayName();
    }

    /**
     * True when this action can be run on a live timer.
     *
     * <p>Gson bypasses the constructor, so the legacy fallback lives here too:
     * an action persisted without the flag is timed exactly when it carries a
     * positive duration.</p>
     */
    public boolean isTimed()
    {
        if (timed != null)
        {
            return timed;
        }
        return resolveSecondsPerUnit() > 0;
    }

    /** Resolves legacy enum-backed actions even when Gson bypasses the constructor. */
    public long getSecondsPerUnit()
    {
        return isTimed() ? resolveSecondsPerUnit() : 0L;
    }

    public boolean hasSkillMapping(Skill skill)
    {
        return skill != null && getSkillMappings().containsKey(skill);
    }

    public long getXpForSkill(Skill skill)
    {
        if (skill == null)
        {
            return 0L;
        }

        Long override = getSkillMappings().get(skill);
        if (override != null && override > 0)
        {
            return override;
        }

        return Math.max(0L, defaultXpPerUnit);
    }

    /**
     * Converts a completed number of units into per-skill XP.
     *
     * <p>This is the single place that turns "units done" into "XP earned", used
     * both by the live timer as it accrues units and by manual logging of an
     * already-finished session, so the two paths can never disagree.</p>
     *
     * <p>Multiplication saturates rather than overflowing: an absurd unit count
     * clamps to {@link Long#MAX_VALUE} instead of silently wrapping negative.</p>
     */
    public Map<Skill, Long> xpForUnits(long units)
    {
        if (units <= 0)
        {
            return Collections.emptyMap();
        }

        Map<Skill, Long> xp = new EnumMap<>(Skill.class);
        for (Skill skill : getSkillMappings().keySet())
        {
            long rate = getXpForSkill(skill);
            if (rate > 0)
            {
                xp.put(skill, LongMath.saturatedMultiply(rate, units));
            }
        }
        return xp;
    }

    /** Never null, even for actions Gson deserialized without the field. */
    public Map<Skill, Long> getSkillMappings()
    {
        return skillMappings == null ? Collections.emptyMap() : skillMappings;
    }

    /** The stored duration, ignoring whether the action is actually timed. */
    private long resolveSecondsPerUnit()
    {
        if (secondsPerUnit > 0)
        {
            return secondsPerUnit;
        }
        return timeUnit == null ? 0L : timeUnit.getSecondsPerUnit();
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
