package com.gokirlbankedxp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.gokirlbankedxp.service.TestMultipliers;
import com.gokirlbankedxp.ui.CollapsibleSection;
import com.gokirlbankedxp.ui.SkillTrackingGrid;
import com.gokirlbankedxp.ui.StateMarkIcon;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import org.junit.jupiter.api.Test;

class GokIrlBankedXpPanelTest
{
    @Test
    void constructionDoesNotReadAnUninjectedPlugin() throws Exception
    {
        // Reproduce Guice's construction phase with a real plugin whose fields
        // have not been injected. A mock hides premature service/config reads.
        GokIrlBankedXpPlugin plugin = new GokIrlBankedXpPlugin();
        AtomicReference<GokIrlBankedXpPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reference.set(new GokIrlBankedXpPanel(
            plugin, new TestMultipliers().multiplierManager, new SkillIconManager())));
        SwingUtilities.invokeAndWait(() -> {
            assertNotNull(reference.get());
            assertEquals(0, findComboBoxes(reference.get()).get(0).getItemCount());
        });
    }

    @Test
    void overlayToggleEditsVisibilityAndFollowsExternalSettings() throws Exception
    {
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        when(plugin.getTrackableSkills()).thenReturn(new Skill[]{Skill.MINING});
        when(plugin.isOverlayShown()).thenReturn(true);
        AtomicReference<GokIrlBankedXpPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reference.set(new GokIrlBankedXpPanel(
            plugin, new TestMultipliers().multiplierManager, new SkillIconManager())));
        // Startup publishes the first snapshot after injection and profile load.
        reference.get().updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot.empty());
        java.lang.reflect.Field field = GokIrlBankedXpPanel.class.getDeclaredField("overlayToggle");
        field.setAccessible(true);
        JButton toggle = (JButton) field.get(reference.get());
        SwingUtilities.invokeAndWait(() -> {
            // Showing: the words offer to hide it, and the mark is the green tick.
            assertEquals("Hide on-screen overlay", toggle.getText());
            assertEquals(StateMarkIcon.ON_COLOR, markColor(toggle));
            toggle.doClick();
            // Hidden: the words offer to show it, and the mark is the red cross.
            assertEquals("Show on-screen overlay", toggle.getText());
            assertEquals(StateMarkIcon.OFF_COLOR, markColor(toggle));
        });
        verify(plugin).setOverlayShown(false);
        // An external configuration/profile refresh updates the UI without writing back.
        reference.get().updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot.empty());
        SwingUtilities.invokeAndWait(() -> assertEquals("Hide on-screen overlay", toggle.getText()));
        verify(plugin, times(1)).setOverlayShown(false);
        verify(plugin, times(0)).setOverlayShown(true);
    }

    /** Every section has a header that stays put and a body its button folds away. */
    @Test
    void everySectionCollapsesAndOnlyTheSetOnceOnesStartClosed() throws Exception
    {
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        when(plugin.getTrackableSkills()).thenReturn(new Skill[]{Skill.MINING});
        AtomicReference<GokIrlBankedXpPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reference.set(new GokIrlBankedXpPanel(
            plugin, new TestMultipliers().multiplierManager, new SkillIconManager())));

        SwingUtilities.invokeAndWait(() -> {
            List<CollapsibleSection> sections = new ArrayList<>();
            for (Component child : reference.get().getComponents())
            {
                if (child instanceof CollapsibleSection)
                {
                    sections.add((CollapsibleSection) child);
                }
            }
            // Bank XP, Balance, Tracked skills, More settings.
            assertEquals(4, sections.size());
            assertTrue(sections.get(0).isOpen());
            assertTrue(sections.get(1).isOpen());
            assertFalse(sections.get(2).isOpen());
            assertFalse(sections.get(3).isOpen());

            for (CollapsibleSection section : sections)
            {
                boolean wasOpen = section.isOpen();
                int heightBefore = section.getPreferredSize().height;
                JButton button = findButton(section, wasOpen ? "Hide" : "Show");
                assertNotNull(button);
                button.doClick();
                assertEquals(!wasOpen, section.isOpen());
                // An open section is taller than the same section closed.
                assertEquals(wasOpen, section.getPreferredSize().height < heightBefore);
            }
        });
    }

    /** The colour the toggle's state mark paints in, read from a rendering of the icon. */
    private static java.awt.Color markColor(JButton toggle)
    {
        javax.swing.Icon icon = toggle.getIcon();
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            icon.getIconWidth() + 2, icon.getIconHeight() + 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        icon.paintIcon(toggle, g, 1, 1);
        g.dispose();
        // Edge pixels are blended by antialiasing; a fully opaque pixel is in
        // the body of a stroke and carries the mark's exact colour.
        for (int y = 0; y < image.getHeight(); y++)
        {
            for (int x = 0; x < image.getWidth(); x++)
            {
                java.awt.Color pixel = new java.awt.Color(image.getRGB(x, y), true);
                if (pixel.getAlpha() == 255)
                {
                    return pixel;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Container container, String text)
    {
        for (Component child : container.getComponents())
        {
            if (child instanceof JButton && text.equals(((JButton) child).getText()))
            {
                return (JButton) child;
            }
            if (child instanceof Container)
            {
                JButton nested = findButton((Container) child, text);
                if (nested != null)
                {
                    return nested;
                }
            }
        }
        return null;
    }
    @Test
    void parsesPositiveWholeNumberXp()
    {
        assertEquals(1250L, GokIrlBankedXpPanel.parsePositiveXp(" 1250 "));
    }

    /** People type XP the way the game prints it; the separator must not be a rejection. */
    @Test
    void acceptsThousandsSeparators()
    {
        assertEquals(1250L, GokIrlBankedXpPanel.parsePositiveXp("1,250"));
        assertEquals(1_000_000L, GokIrlBankedXpPanel.parsePositiveXp("1,000,000"));
    }

    /** A number followed by junk is not that number; it is a typo to report. */
    @Test
    void rejectsTrailingText()
    {
        assertEquals(0L, GokIrlBankedXpPanel.parsePositiveXp("1250xp"));
        assertEquals(0L, GokIrlBankedXpPanel.parsePositiveXp("12.5"));
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
            GokIrlBankedXpPanel panel = new GokIrlBankedXpPanel(plugin, new TestMultipliers().multiplierManager, new SkillIconManager());
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
        SwingUtilities.invokeAndWait(() -> panelReference.set(new GokIrlBankedXpPanel(plugin, new TestMultipliers().multiplierManager, new SkillIconManager())));

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

    /** A skill in debt has nothing to take back, so the removal form must not offer it. */
    @Test
    void removalDoesNotOfferASkillThatIsInDebt() throws Exception
    {
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        when(plugin.getTrackableSkills()).thenReturn(new Skill[]{Skill.COOKING, Skill.WOODCUTTING});
        when(plugin.getBankedXp(Skill.COOKING)).thenReturn(500L);
        when(plugin.getBankedXp(Skill.WOODCUTTING)).thenReturn(-5_000L);

        AtomicReference<GokIrlBankedXpPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(new GokIrlBankedXpPanel(plugin, new TestMultipliers().multiplierManager, new SkillIconManager())));

        // Cooking is banked; Woodcutting is owed after a quest reward.
        panelReference.get().updateSnapshot(new GokIrlBankedXpPlugin.BankedXpSnapshot(
            -4_500L,
            List.of(
                new GokIrlBankedXpPlugin.BankedSkill(Skill.COOKING, "Cooking", 500L, 0, false),
                new GokIrlBankedXpPlugin.BankedSkill(Skill.WOODCUTTING, "Woodcutting", -5_000L, 0, false))));
        SwingUtilities.invokeAndWait(() -> { });

        AtomicReference<List<JComboBox<?>>> combos = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> combos.set(findComboBoxes(panelReference.get())));

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
        SwingUtilities.invokeAndWait(() -> panelReference.set(new GokIrlBankedXpPanel(plugin, new TestMultipliers().multiplierManager, new SkillIconManager())));

        panelReference.get().updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot.empty());
        SwingUtilities.invokeAndWait(() -> { });

        AtomicReference<List<JComboBox<?>>> combos = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> combos.set(findComboBoxes(panelReference.get())));

        JComboBox<?> removeSelector = combos.get().get(1);
        assertEquals(0, removeSelector.getItemCount());
        assertFalse(removeSelector.isEnabled());
    }

    /** A switched-off skill cannot be banked into, so the deposit form must not offer it. */
    @Test
    void depositOffersOnlyTrackedSkillsAndFollowsTheGrid() throws Exception
    {
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        when(plugin.getTrackableSkills()).thenReturn(new Skill[]{Skill.AGILITY, Skill.COOKING});
        when(plugin.getUntrackedSkills()).thenReturn(Set.of(Skill.AGILITY));

        AtomicReference<GokIrlBankedXpPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(new GokIrlBankedXpPanel(plugin, new TestMultipliers().multiplierManager, new SkillIconManager())));
        // Saved tracking choices are applied by startup's first snapshot.
        panelReference.get().updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot.empty());

        AtomicReference<JComboBox<?>> deposit = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> deposit.set(findComboBoxes(panelReference.get()).get(0)));
        assertEquals(1, deposit.get().getItemCount());
        assertEquals(Skill.COOKING, deposit.get().getItemAt(0));

        // Turned back on in the grid: the next snapshot brings the skill back.
        when(plugin.getUntrackedSkills()).thenReturn(Set.of());
        panelReference.get().updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot.empty());
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(2, deposit.get().getItemCount());
    }

    /** The grid starts collapsed, and a tile click asks the plugin for the opposite state. */
    @Test
    void trackedSkillsSectionStartsClosedAndTilesToggleTracking() throws Exception
    {
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        when(plugin.getTrackableSkills()).thenReturn(new Skill[]{Skill.AGILITY, Skill.COOKING});
        when(plugin.getUntrackedSkills()).thenReturn(Set.of(Skill.COOKING));

        AtomicReference<GokIrlBankedXpPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(new GokIrlBankedXpPanel(plugin, new TestMultipliers().multiplierManager, new SkillIconManager())));
        panelReference.get().updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot.empty());

        SwingUtilities.invokeAndWait(() -> {
            SkillTrackingGrid grid = findGrid(panelReference.get());
            assertNotNull(grid);
            assertFalse(grid.getParent().isVisible(), "the section must start collapsed");
            assertEquals(2, grid.getSkillCount());

            // Tiles are in the game's skills-tab order: Agility before Cooking.
            ((JButton) grid.getComponent(0)).doClick();
            ((JButton) grid.getComponent(1)).doClick();
        });

        verify(plugin).setSkillTracked(Skill.AGILITY, false);
        verify(plugin).setSkillTracked(Skill.COOKING, true);
    }

    private static SkillTrackingGrid findGrid(Container container)
    {
        for (Component child : container.getComponents())
        {
            if (child instanceof SkillTrackingGrid)
            {
                return (SkillTrackingGrid) child;
            }
            if (child instanceof Container)
            {
                SkillTrackingGrid nested = findGrid((Container) child);
                if (nested != null)
                {
                    return nested;
                }
            }
        }
        return null;
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
