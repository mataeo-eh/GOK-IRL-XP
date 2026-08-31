package com.gokirlbankedxp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.runelite.api.Skill;
import org.junit.jupiter.api.Test;

/** Covers the learned XP-drop rate that drives the "about to run out" warning. */
class DepletionForecasterTest
{
    @Test
    void forecastIsTheSumOfTheLastDropsOnceTheWindowIsFull()
    {
        DepletionForecaster forecaster = new DepletionForecaster();

        // A steady skill: five identical drops, as woodcutting or fishing gives.
        for (int i = 0; i < 5; i++)
        {
            forecaster.recordDrop(Skill.WOODCUTTING, 100L, 5);
        }

        assertEquals(500L, forecaster.forecast(Skill.WOODCUTTING, 5));
        assertEquals(100L, forecaster.averageDrop(Skill.WOODCUTTING));
    }

    @Test
    void onlyTheMostRecentDropsCount()
    {
        DepletionForecaster forecaster = new DepletionForecaster();

        // Old, cheap drops must fall out of the window when the player switches
        // to something more expensive, or the warning would fire far too late.
        for (int i = 0; i < 5; i++)
        {
            forecaster.recordDrop(Skill.WOODCUTTING, 25L, 3);
        }
        for (int i = 0; i < 3; i++)
        {
            forecaster.recordDrop(Skill.WOODCUTTING, 175L, 3);
        }

        assertEquals(3, forecaster.sampleCount(Skill.WOODCUTTING));
        assertEquals(525L, forecaster.forecast(Skill.WOODCUTTING, 3));
    }

    @Test
    void aPartialWindowScalesTheAverageInsteadOfUnderReporting()
    {
        DepletionForecaster forecaster = new DepletionForecaster();

        // Only two of the five samples exist yet. Summing them would predict 200
        // and warn far too late; scaling the mean predicts a full five actions.
        forecaster.recordDrop(Skill.AGILITY, 100L, 5);
        forecaster.recordDrop(Skill.AGILITY, 100L, 5);

        assertEquals(500L, forecaster.forecast(Skill.AGILITY, 5));
    }

    @Test
    void variableDropsAveragedRatherThanTakenFromTheLastOne()
    {
        DepletionForecaster forecaster = new DepletionForecaster();

        // Combat: every hit is worth a different amount, so a single sample would
        // swing the prediction wildly. The mean smooths that out.
        forecaster.recordDrop(Skill.ATTACK, 40L, 4);
        forecaster.recordDrop(Skill.ATTACK, 120L, 4);
        forecaster.recordDrop(Skill.ATTACK, 60L, 4);
        forecaster.recordDrop(Skill.ATTACK, 100L, 4);

        assertEquals(80L, forecaster.averageDrop(Skill.ATTACK));
        assertEquals(320L, forecaster.forecast(Skill.ATTACK, 4));
    }

    @Test
    void skillsDoNotContaminateEachOther()
    {
        DepletionForecaster forecaster = new DepletionForecaster();

        forecaster.recordDrop(Skill.MINING, 60L, 5);
        forecaster.recordDrop(Skill.COOKING, 300L, 5);

        assertEquals(60L, forecaster.averageDrop(Skill.MINING));
        assertEquals(300L, forecaster.averageDrop(Skill.COOKING));
    }

    @Test
    void unknownOrEmptySkillsPredictNothing()
    {
        DepletionForecaster forecaster = new DepletionForecaster();

        // Zero means "no prediction available", which the plugin reads as
        // "do not warn" rather than as "nothing will be consumed".
        assertEquals(0L, forecaster.forecast(Skill.SLAYER, 5));
        assertEquals(0L, forecaster.averageDrop(Skill.SLAYER));
        assertEquals(0, forecaster.sampleCount(Skill.SLAYER));
        assertEquals(0L, forecaster.forecast(null, 5));
    }

    @Test
    void aDisabledWindowRecordsNothingAndPredictsNothing()
    {
        DepletionForecaster forecaster = new DepletionForecaster();

        forecaster.recordDrop(Skill.FISHING, 90L, 0);

        assertEquals(0, forecaster.sampleCount(Skill.FISHING));
        assertEquals(0L, forecaster.forecast(Skill.FISHING, 0));
    }

    @Test
    void nonPositiveDropsAreIgnored()
    {
        DepletionForecaster forecaster = new DepletionForecaster();

        forecaster.recordDrop(Skill.FIREMAKING, 0L, 5);
        forecaster.recordDrop(Skill.FIREMAKING, -50L, 5);

        assertEquals(0, forecaster.sampleCount(Skill.FIREMAKING));
    }

    @Test
    void clearForgetsEveryLearnedRate()
    {
        DepletionForecaster forecaster = new DepletionForecaster();
        forecaster.recordDrop(Skill.HUNTER, 200L, 5);

        forecaster.clear();

        assertEquals(0, forecaster.sampleCount(Skill.HUNTER));
        assertEquals(0L, forecaster.forecast(Skill.HUNTER, 5));
    }
}
