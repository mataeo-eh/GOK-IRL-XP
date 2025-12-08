package com.gokirlbankedxp.model;

public enum TimeUnit
{
    SECONDS(1, "Seconds"),
    MINUTES(60, "Minutes"),
    HOURS(3600, "Hours");

    private final long secondsPerUnit;
    private final String displayName;

    TimeUnit(long secondsPerUnit, String displayName)
    {
        this.secondsPerUnit = secondsPerUnit;
        this.displayName = displayName;
    }

    public long toSeconds(long amount)
    {
        if (amount <= 0)
        {
            return 0L;
        }

        return Math.multiplyExact(amount, secondsPerUnit);
    }

    public long fromSeconds(long seconds)
    {
        if (seconds <= 0)
        {
            return 0L;
        }

        return seconds / secondsPerUnit;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public long getSecondsPerUnit()
    {
        return secondsPerUnit;
    }
}
