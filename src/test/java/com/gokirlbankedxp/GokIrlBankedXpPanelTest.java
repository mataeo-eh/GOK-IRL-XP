package com.gokirlbankedxp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import org.junit.jupiter.api.Test;

class GokIrlBankedXpPanelTest
{
    @Test
    void parsesPositiveWholeNumberXp()
    {
        assertEquals(1250L, GokIrlBankedXpPanel.parsePositiveXp(" 1250 "));
    }

    @Test
    void rejectsInvalidXpWithoutThrowing()
    {
        assertEquals(0L, GokIrlBankedXpPanel.parsePositiveXp(null));
        assertEquals(0L, GokIrlBankedXpPanel.parsePositiveXp(""));
        assertEquals(0L, GokIrlBankedXpPanel.parsePositiveXp("ten"));
        assertEquals(0L, GokIrlBankedXpPanel.parsePositiveXp("-5"));
        assertEquals(0L, GokIrlBankedXpPanel.parsePositiveXp("999999999999999999999"));
    }

    @Test
    void xpControlIsAnEditableFocusableTextField() throws Exception
    {
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        when(plugin.getTrackableSkills()).thenReturn(new Skill[]{Skill.AGILITY});
        AtomicReference<JTextField> fieldReference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            GokIrlBankedXpPanel panel = new GokIrlBankedXpPanel(plugin);
            fieldReference.set(findTextField(panel));
        });

        JTextField field = fieldReference.get();
        assertNotNull(field);
        assertTrue(field.isEditable());
        assertTrue(field.isEnabled());
        assertTrue(field.isFocusable());
        assertTrue(field.getPreferredSize().height >= 36);
    }

    /**
     * The removal form must be unable to reach a skill with nothing banked.
     *
     * <p>This is the hard guarantee behind "you can never hold a negative
     * balance": rather than validating a subtraction after the fact, the skill
     * drop-down is built from the skills that actually hold XP, so an empty skill
     * is not selectable in the first place.</p>
     */
    @Test
    void removalOffersOnlySkillsThatHaveBankedXp() throws Exception
    {
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        when(plugin.getTrackableSkills()).thenReturn(new Skill[]{Skill.AGILITY, Skill.COOKING});
        when(plugin.getBankedXp(Skill.COOKING)).thenReturn(500L);

        AtomicReference<GokIrlBankedXpPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(new GokIrlBankedXpPanel(plugin)));

        // Only Cooking is banked; Agility has nothing.
        panelReference.get().updateSnapshot(new GokIrlBankedXpPlugin.BankedXpSnapshot(
            500L,
            List.of(new GokIrlBankedXpPlugin.BankedSkill(Skill.COOKING, "Cooking", 500L, 0, false))));
        SwingUtilities.invokeAndWait(() -> { });

        AtomicReference<List<JComboBox<?>>> combos = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> combos.set(findComboBoxes(panelReference.get())));

        // Declaration order: the deposit form is added to the card stack first,
        // so its all-skills selector precedes the removal selector.
        assertEquals(2, combos.get().size(), "expected the deposit and removal selectors");
        JComboBox<?> removeSelector = combos.get().get(1);
        assertEquals(1, removeSelector.getItemCount());
        assertEquals(Skill.COOKING, removeSelector.getItemAt(0));
        assertTrue(removeSelector.isEnabled());
    }

    @Test
    void removalIsInertWhenNothingIsBanked() throws Exception
    {
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        when(plugin.getTrackableSkills()).thenReturn(new Skill[]{Skill.AGILITY});

        AtomicReference<GokIrlBankedXpPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(new GokIrlBankedXpPanel(plugin)));

        panelReference.get().updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot.empty());
        SwingUtilities.invokeAndWait(() -> { });

        AtomicReference<List<JComboBox<?>>> combos = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> combos.set(findComboBoxes(panelReference.get())));

        JComboBox<?> removeSelector = combos.get().get(1);
        assertEquals(0, removeSelector.getItemCount());
        assertFalse(removeSelector.isEnabled());
    }

    private static List<JComboBox<?>> findComboBoxes(Container container)
    {
        List<JComboBox<?>> found = new ArrayList<>();
        for (Component child : container.getComponents())
        {
            if (child instanceof JComboBox)
            {
                found.add((JComboBox<?>) child);
            }
            else if (child instanceof Container)
            {
                found.addAll(findComboBoxes((Container) child));
            }
        }
        return found;
    }

    private static JTextField findTextField(Container container)
    {
        for (Component child : container.getComponents())
        {
            if (child instanceof JTextField)
            {
                return (JTextField) child;
            }
            if (child instanceof Container)
            {
                JTextField nested = findTextField((Container) child);
                if (nested != null)
                {
                    return nested;
                }
            }
        }
        return null;
    }
}
