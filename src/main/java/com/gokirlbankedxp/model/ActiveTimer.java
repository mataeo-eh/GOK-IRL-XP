package com.gokirlbankedxp.model;

import java.util.Collections;
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
        accrueElapsed(nowMillis);
        this.paused = true;
    }

    public void resume(long nowMillis)
    {
        this.paused = false;
        this.lastTickMillis = nowMillis;
    }

    public void alignAwardedUnits(long secondsPerUnit)
    {
        if (secondsPerUnit <= 0)
        {
            this.awardedUnits = 0;
            return;
        }

        this.awardedUnits = elapsedSeconds / secondsPerUnit;
    }

    public Map<Skill, Long> applyTick(long nowMillis, IrlAction action)
    {
        if (action == null || paused)
        {
            lastTickMillis = nowMillis;
            return Collections.emptyMap();
        }

        long deltaSeconds = accrueElapsed(nowMillis);
        if (deltaSeconds == 0)
        {
            return Collections.emptyMap();
        }

        long unitSeconds = action.getSecondsPerUnit();
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
        // The units -> XP conversion lives on the action so that live timers and
        // manually logged sessions can never drift apart.
        return action.xpForUnits(newUnits);
    }

    /**
     * Commits whole elapsed seconds while retaining any millisecond remainder.
     * Advancing the anchor directly to {@code nowMillis} used to discard that
     * remainder on every scheduler tick and caused long-running timers to drift.
     */
    private long accrueElapsed(long nowMillis)
    {
        if (lastTickMillis == 0)
        {
            lastTickMillis = nowMillis;
            return 0L;
        }

        long deltaMillis = Math.max(0L, nowMillis - lastTickMillis);
        long deltaSeconds = deltaMillis / 1000L;
        if (deltaSeconds > 0)
        {
            elapsedSeconds += deltaSeconds;
            lastTickMillis += deltaSeconds * 1000L;
        }
        return deltaSeconds;
    }

    public ActiveTimer copy()
    {
        return new ActiveTimer(id, actionId, elapsedSeconds, paused, lastTickMillis, awardedUnits);
    }
}
