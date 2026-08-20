package com.gokirlbankedxp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;
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
