package com.gokirlbankedxp;

import com.gokirlbankedxp.service.TimerManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

/**
 * The on-screen banked XP and timer summary.
 *
 * <p>Sizing is handled entirely by RuneLite, so this plugin adds no hotkey or
 * config of its own for it. {@link OverlayPanel} already marks itself resizable,
 * and the client's own "Drag hotkey" (ALT by default, rebindable in RuneLite's
 * settings) is what puts overlays into management mode: holding it and dragging
 * an edge resizes this panel, right-clicking resets it, and {@code OverlayManager}
 * persists whatever size the user lands on. All of that only works if
 * {@link #render(Graphics2D)} hands off to {@code super}, which applies the
 * user's chosen size to the panel — see the note there.</p>
 */
class GokIrlBankedXpOverlay extends OverlayPanel
{
    static final String DISMISS_MILESTONES = "Dismiss milestones";
    /**
     * Default width before the user resizes anything. Wider than RuneLite's
     * 129px standard because skill rows pair a name with a formatted XP total.
     */
    private static final int DEFAULT_WIDTH = 240;

    /** Floor for user resizing, small enough to tuck away but still readable. */
    private static final int MINIMUM_SIZE = 100;

    private final GokIrlBankedXpPlugin plugin;
    private final TimerManager timerManager;

    @Inject
    private GokIrlBankedXpOverlay(GokIrlBankedXpPlugin plugin, TimerManager timerManager)
    {
        this.plugin = plugin;
        this.timerManager = timerManager;
        getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY,
            DISMISS_MILESTONES, "IRL XP"));
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_SCENE);
        panelComponent.setPreferredSize(new Dimension(DEFAULT_WIDTH, 0));
        setMinimumSize(MINIMUM_SIZE);
        // Scales the font with the panel so a shrunken overlay stays legible and
        // a widened one does not look sparse. This is what keeps the overlay
        // looking right across fixed, resizable, and stretched screen modes.
        setDynamicFont(true);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Visibility is purely presentational. The registered plugin still
        // consumes every XP event and advances timers while this returns null.
        panelComponent.getChildren().clear();
        List<String> milestoneLines = plugin.getMilestoneLines();
        if (!milestoneLines.isEmpty())
        {
            panelComponent.getChildren().add(TitleComponent.builder()
                .text("IRL XP milestone").color(Color.ORANGE).build());
            for (String line : milestoneLines)
            {
                panelComponent.getChildren().add(LineComponent.builder().left(line).build());
            }
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Right-click: Dismiss milestones").leftColor(Color.GRAY).build());
        }
        if (!plugin.isOverlayShown())
        {
            return milestoneLines.isEmpty() ? null : super.render(graphics);
        }
        GokIrlBankedXpPlugin.BankedXpSnapshot snapshot = plugin.getCurrentSnapshot();
        if (snapshot != null && snapshot.hasData())
        {
            panelComponent.getChildren().add(
                TitleComponent.builder()
                    .text("IRL XP")
                    .color(graphics.getColor())
                    .build()
            );

            panelComponent.getChildren().add(
                LineComponent.builder()
                    .left("Total")
                    .right(QuantityFormatter.formatNumber(snapshot.getTotalXp()))
                    .build()
            );

            for (GokIrlBankedXpPlugin.BankedSkill entry : snapshot.getSkills())
            {
                LineComponent.LineComponentBuilder builder = LineComponent.builder()
                    .left(entry.getDisplayName())
                    .right(QuantityFormatter.formatNumber(entry.getRemainingXp()));

                // Red for a debt (the figure already carries its minus sign),
                // orange for a positive balance that is nearly spent.
                if (entry.isInDebt())
                {
                    builder.leftColor(Color.RED);
                    builder.rightColor(Color.RED);
                }
                else if (entry.isBelowThreshold())
                {
                    builder.leftColor(Color.ORANGE);
                    builder.rightColor(Color.ORANGE);
                }

                panelComponent.getChildren().add(builder.build());
            }
        }

        List<TimerManager.TimerSnapshot> timers = timerManager.getTimerSnapshots();
        if (!timers.isEmpty())
        {
            panelComponent.getChildren().add(
                TitleComponent.builder()
                    .text("Active Timers")
                    .color(graphics.getColor())
                    .build()
            );

            for (TimerManager.TimerSnapshot timer : timers)
            {
                String label = timer.paused()
                    ? String.format("%s (paused)", timer.actionName())
                    : timer.actionName();
                String rates = timer.formatRates();
                String right = rates.isEmpty()
                    ? timer.formatElapsed()
                    : String.format("%s %s", timer.formatElapsed(), rates);

                panelComponent.getChildren().add(
                    LineComponent.builder()
                        .left(label)
                        .right(right)
                        .build()
                );
            }
        }

        // Must go through super, not panelComponent.render(): OverlayPanel.render()
        // is what copies the overlay's user-chosen preferred size onto the panel
        // (and clears the children afterwards). Rendering the panel directly
        // skipped that, which is why the overlay stayed pinned at its default
        // width no matter how it was dragged or which screen mode was in use.
        return super.render(graphics);
    }
}
