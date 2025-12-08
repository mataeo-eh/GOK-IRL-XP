package com.gokirlbankedxp.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import net.runelite.api.Skill;

public class ActiveTimer
{
    private final UUID id;
    private final UUID actionId;
    private long elapsedSeconds;
    private boolean paused;
    private long lastTickMillis;
    private long awardedUnits;

    public ActiveTimer(UUID id, UUID actionId, long elapsedSeconds, boolean paused, long lastTickMillis, long awardedUnits)
    {
        this.id = id == null ? UUID.randomUUID() : id;
        this.actionId = actionId;
        this.elapsedSeconds = Math.max(0, elapsedSeconds);
        this.paused = paused;
        this.lastTickMillis = lastTickMillis;
        this.awardedUnits = Math.max(0, awardedUnits);
    }

    public static ActiveTimer start(UUID actionId, long nowMillis)
    {
        return new ActiveTimer(UUID.randomUUID(), actionId, 0L, false, nowMillis, 0L);
    }

    public UUID getId()
    {
        return id;
    }

    public UUID getActionId()
    {
        return actionId;
    }

    public boolean isPaused()
    {
        return paused;
    }

    public long getElapsedSeconds()
    {
        return elapsedSeconds;
    }

    public long getDisplayElapsedSeconds(long nowMillis)
    {
        if (paused || lastTickMillis == 0)
        {
            return elapsedSeconds;
        }

        long delta = Math.max(0, (nowMillis - lastTickMillis) / 1000);
        return elapsedSeconds + delta;
    }

    public String getFormattedElapsedTime(long nowMillis)
    {
        long seconds = getDisplayElapsedSeconds(nowMillis);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    public void pause(long nowMillis)
    {
        this.paused = true;
        this.lastTickMillis = nowMillis;
    }

    public void resume(long nowMillis)
    {
        this.paused = false;
        this.lastTickMillis = nowMillis;
    }

    public void alignAwardedUnits(TimeUnit timeUnit)
    {
        long unitSeconds = timeUnit.getSecondsPerUnit();
        if (unitSeconds <= 0)
        {
            this.awardedUnits = 0;
            return;
        }

        this.awardedUnits = elapsedSeconds / unitSeconds;
    }

    public Map<Skill, Long> applyTick(long nowMillis, IrlAction action)
    {
        if (action == null || paused)
        {
            lastTickMillis = nowMillis;
            return Collections.emptyMap();
        }

        long deltaSeconds = lastTickMillis == 0
            ? Math.max(0, nowMillis / 1000)
            : Math.max(0, (nowMillis - lastTickMillis) / 1000);
        lastTickMillis = nowMillis;
        if (deltaSeconds == 0)
        {
            return Collections.emptyMap();
        }

        elapsedSeconds += deltaSeconds;
        long unitSeconds = action.getTimeUnit().getSecondsPerUnit();
        if (unitSeconds <= 0)
        {
            return Collections.emptyMap();
        }

        long totalUnits = elapsedSeconds / unitSeconds;
        long newUnits = totalUnits - awardedUnits;
        if (newUnits <= 0)
        {
            return Collections.emptyMap();
        }

        awardedUnits += newUnits;
        Map<Skill, Long> xp = new EnumMap<>(Skill.class);
        for (Map.Entry<Skill, Long> entry : action.getSkillMappings().entrySet())
        {
            Skill skill = entry.getKey();
            long rate = action.getXpForSkill(skill);
            if (rate > 0)
            {
                xp.put(skill, rate * newUnits);
            }
        }
        return xp;
    }

    public ActiveTimer copy()
    {
        return new ActiveTimer(id, actionId, elapsedSeconds, paused, lastTickMillis, awardedUnits);
    }
}
