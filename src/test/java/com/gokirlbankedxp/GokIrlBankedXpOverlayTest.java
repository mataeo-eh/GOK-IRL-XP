package com.gokirlbankedxp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gokirlbankedxp.service.TimerManager;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises actual rendering so hidden mode cannot leak timer or balance rows. */
class GokIrlBankedXpOverlayTest
{
    @Test
    void hiddenOverlayRendersOnlyPendingMilestonesAndDisappearsAfterDismissal() throws Exception
    {
        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        TimerManager timers = mock(TimerManager.class);
        Constructor<GokIrlBankedXpOverlay> constructor = GokIrlBankedXpOverlay.class
            .getDeclaredConstructor(GokIrlBankedXpPlugin.class, TimerManager.class);
        constructor.setAccessible(true);
        GokIrlBankedXpOverlay overlay = constructor.newInstance(plugin, timers);
        Graphics2D graphics = new BufferedImage(500, 500, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            when(plugin.getMilestoneLines()).thenReturn(Collections.emptyList());
            assertNull(overlay.render(graphics));
            when(plugin.getMilestoneLines()).thenReturn(List.of("Mining: level 51 reached", "2 further levels banked"));
            assertNotNull(overlay.render(graphics));
            when(plugin.getMilestoneLines()).thenReturn(Collections.emptyList());
            assertNull(overlay.render(graphics));
            verify(plugin, never()).getCurrentSnapshot();
            verify(timers, never()).getTimerSnapshots();
            assertTrue(overlay.getMenuEntries().stream().anyMatch(entry ->
                GokIrlBankedXpOverlay.DISMISS_MILESTONES.equals(entry.getOption())));
            // Showing the regular display restores rendering without rebuilding the overlay.
            when(plugin.isOverlayShown()).thenReturn(true);
            when(plugin.getCurrentSnapshot()).thenReturn(GokIrlBankedXpPlugin.BankedXpSnapshot.empty());
            when(timers.getTimerSnapshots()).thenReturn(Collections.emptyList());
            overlay.render(graphics);
            verify(plugin).getCurrentSnapshot();
            verify(timers).getTimerSnapshots();
        }
        finally
        {
            graphics.dispose();
        }
    }
}
