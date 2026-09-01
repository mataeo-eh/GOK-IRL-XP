package com.gokirlbankedxp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gokirlbankedxp.model.XpMultiplierTier;
import com.gokirlbankedxp.service.TestMultipliers;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import org.junit.jupiter.api.Test;

/**
 * Builds the multipliers sidebar tab and its editor for real, on the EDT.
 *
 * <p>Swing layout mistakes — a constraint reused for two cells, a component added
 * to a container it cannot size — only surface when a component is actually
 * constructed and laid out. Compiling proves nothing about any of that, and this
 * plugin's UI is only otherwise exercised by launching the game client.</p>
 */
class XpMultipliersPanelTest
{
    @Test
    void theTabBuildsAndSummarisesEachSkillsLadder() throws Exception
    {
        TestMultipliers fixture = new TestMultipliers()
            .withTier(Skill.WOODCUTTING, 50, 1.5)
            .withTier(Skill.WOODCUTTING, 70, 2.0)
            .atLevel(Skill.WOODCUTTING, 75);

        AtomicReference<XpMultipliersPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(
            new XpMultipliersPanel(fixture.multiplierManager, fixture.levelTracker)));

        // The panel populates its models through invokeLater, so let the queued
        // refresh run before inspecting them.
        SwingUtilities.invokeAndWait(() -> { });

        assertNotNull(panelReference.get());

        AtomicReference<List<String>> notes = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> notes.set(textOf(panelReference.get())));

        // Whichever skill happens to be selected, the tab must describe the
        // ladder it is showing rather than leaving the summary blank.
        assertTrue(notes.get().stream().anyMatch(text -> !text.isBlank()),
            "expected the tab to describe the selected skill");
    }

    /**
     * The count spinner is the single control that decides how many thresholds a
     * skill has, so it must open showing exactly the ladder that was saved.
     */
    @Test
    void theEditorOpensWithTheSavedNumberOfThresholds() throws Exception
    {
        List<XpMultiplierTier> saved = List.of(
            new XpMultiplierTier(50, 1.5),
            new XpMultiplierTier(70, 2.0),
            new XpMultiplierTier(90, 3.0));

        AtomicReference<XpMultiplierEditorDialog> dialogReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> dialogReference.set(
            new XpMultiplierEditorDialog(null, Skill.MINING, saved, 75, true)));

        AtomicReference<List<JSpinner>> spinners = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> spinners.set(spinnersOf(dialogReference.get())));

        // One count spinner, plus one level spinner per threshold row.
        assertEquals(4, spinners.get().size());
        assertEquals(3, spinners.get().get(0).getValue(), "the count spinner should show the saved size");
        assertEquals(50, spinners.get().get(1).getValue());
        assertEquals(70, spinners.get().get(2).getValue());
        assertEquals(90, spinners.get().get(3).getValue());

        SwingUtilities.invokeAndWait(() -> dialogReference.get().dispose());
    }

    /**
     * Changing the count is the only way rows appear or disappear, and it has to
     * work in both directions without leaving orphaned rows behind.
     */
    @Test
    void theCountSpinnerAddsAndRemovesThresholdRows() throws Exception
    {
        AtomicReference<XpMultiplierEditorDialog> dialogReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> dialogReference.set(
            new XpMultiplierEditorDialog(null, Skill.COOKING, List.of(), 1, false)));

        AtomicReference<List<JSpinner>> spinners = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> spinners.set(spinnersOf(dialogReference.get())));
        // An empty ladder starts with the count spinner alone.
        assertEquals(1, spinners.get().size());

        SwingUtilities.invokeAndWait(() -> spinnersOf(dialogReference.get()).get(0).setValue(2));
        SwingUtilities.invokeAndWait(() -> spinners.set(spinnersOf(dialogReference.get())));
        assertEquals(3, spinners.get().size(), "two rows should have appeared");

        SwingUtilities.invokeAndWait(() -> spinnersOf(dialogReference.get()).get(0).setValue(0));
        SwingUtilities.invokeAndWait(() -> spinners.set(spinnersOf(dialogReference.get())));
        assertEquals(1, spinners.get().size(), "both rows should have gone again");

        SwingUtilities.invokeAndWait(() -> dialogReference.get().dispose());
    }

    private static List<JSpinner> spinnersOf(Container root)
    {
        List<JSpinner> found = new ArrayList<>();
        collectSpinners(root, found);
        return found;
    }

    private static void collectSpinners(Container container, List<JSpinner> found)
    {
        for (Component child : container.getComponents())
        {
            if (child instanceof JSpinner)
            {
                found.add((JSpinner) child);
            }
            else if (child instanceof Container)
            {
                collectSpinners((Container) child, found);
            }
        }
    }

    private static List<String> textOf(Container container)
    {
        List<String> found = new ArrayList<>();
        for (Component child : container.getComponents())
        {
            if (child instanceof JTextArea)
            {
                found.add(((JTextArea) child).getText());
            }
            else if (child instanceof Container)
            {
                found.addAll(textOf((Container) child));
            }
        }
        return found;
    }
}
