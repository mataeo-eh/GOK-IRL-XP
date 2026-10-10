package com.gokirlbankedxp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gokirlbankedxp.service.TestMultipliers;
import com.gokirlbankedxp.ui.CollapsibleSection;
import com.gokirlbankedxp.ui.SkillTrackingGrid;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import org.junit.jupiter.api.Test;

/**
 * Lays the banked XP tab out at the sidebar's real width, inside the same kind
 * of scroll pane RuneLite's PluginPanel wraps it in, and checks what the
 * sections actually measure.
 *
 * <p>The other panel tests never give the panel a size, so they cannot see a
 * layout fault. This one exists because of one: with the balance open, opening
 * "Tracked skills" crushed the skill tiles and the balance list to slivers.</p>
 */
class BankedXpPanelLayoutTest
{
    /** The width the tab gets in the sidebar: PluginPanel's 225px less the shell's 10px padding each side. */
    private static final int TAB_WIDTH = 205;
    /** SkillTrackingGrid's tile height and IrlXpUi.styleList's row height. */
    private static final int TILE_HEIGHT = 34;
    private static final int ROW_HEIGHT = 36;

    @Test
    void openingTrackedSkillsKeepsTilesAndBalanceRowFullHeight() throws Exception
    {
        // A scroll pane only validates inside a window, which needs a display.
        assumeFalse(GraphicsEnvironment.isHeadless());

        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        when(plugin.getTrackableSkills()).thenReturn(Skill.values());
        when(plugin.isOverlayShown()).thenReturn(true);

        AtomicReference<GokIrlBankedXpPanel> reference = new AtomicReference<>();
        AtomicReference<JScrollPane> sidebar = new AtomicReference<>();
        AtomicReference<JFrame> window = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GokIrlBankedXpPanel panel = new GokIrlBankedXpPanel(
                plugin, new TestMultipliers().multiplierManager, new SkillIconManager());
            reference.set(panel);

            // PluginPanel pins its width and takes its height from its contents.
            JPanel fixedWidth = new JPanel(new BorderLayout())
            {
                @Override
                public Dimension getPreferredSize()
                {
                    return new Dimension(TAB_WIDTH, super.getPreferredSize().height);
                }
            };
            fixedWidth.add(panel, BorderLayout.CENTER);
            JPanel north = new JPanel(new BorderLayout());
            north.add(fixedWidth, BorderLayout.NORTH);
            JScrollPane scrollPane = new JScrollPane(north);
            scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sidebar.set(scrollPane);

            // Packed but never shown: enough for layout to run.
            JFrame frame = new JFrame();
            frame.getContentPane().add(scrollPane);
            frame.pack();
            frame.setSize(TAB_WIDTH, 1000);
            frame.validate();
            window.set(frame);
        });

        try
        {
            // One skill in debt, as in the report: a single, fairly wide row.
            reference.get().updateSnapshot(new GokIrlBankedXpPlugin.BankedXpSnapshot(
                -675L, List.of(new GokIrlBankedXpPlugin.BankedSkill(Skill.DEFENCE, "Defence", -675L, 0, false))));
            settle(sidebar.get());

            SwingUtilities.invokeAndWait(() -> {
                for (Component child : reference.get().getComponents())
                {
                    // Tracked skills is the first section that starts closed.
                    if (child instanceof CollapsibleSection && !((CollapsibleSection) child).isOpen())
                    {
                        ((CollapsibleSection) child).setOpen(true);
                        break;
                    }
                }
            });
            settle(sidebar.get());

            SwingUtilities.invokeAndWait(() -> {
                GokIrlBankedXpPanel panel = reference.get();
                assertEquals(panel.getPreferredSize().height, panel.getHeight());

                SkillTrackingGrid grid = find(panel, SkillTrackingGrid.class);
                assertEquals(TILE_HEIGHT, grid.getComponent(0).getHeight());

                JScrollPane balance = (JScrollPane) SwingUtilities.getAncestorOfClass(
                    JScrollPane.class, find(panel, JList.class));
                assertEquals(ROW_HEIGHT, balance.getViewport().getHeight());
                assertFalse(balance.getHorizontalScrollBar().isVisible());
            });
        }
        finally
        {
            SwingUtilities.invokeAndWait(() -> window.get().dispose());
        }
    }

    /**
     * Runs layout until it stops changing. A wrapped note asks for its real
     * height only after a first pass has given it a width, so one pass is not
     * enough; the live client reaches the same state through revalidation.
     */
    private static void settle(JScrollPane sidebar) throws Exception
    {
        for (int pass = 0; pass < 3; pass++)
        {
            SwingUtilities.invokeAndWait(sidebar::validate);
        }
    }

    private static <T> T find(Container container, Class<T> type)
    {
        for (Component child : container.getComponents())
        {
            if (type.isInstance(child))
            {
                return type.cast(child);
            }
            if (child instanceof Container)
            {
                T nested = find((Container) child, type);
                if (nested != null)
                {
                    return nested;
                }
            }
        }
        return null;
    }
}
