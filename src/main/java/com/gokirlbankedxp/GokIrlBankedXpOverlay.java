package com.gokirlbankedxp;

import com.gokirlbankedxp.service.TimerManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

class GokIrlBankedXpOverlay extends OverlayPanel
{
    private final GokIrlBankedXpPlugin plugin;
    private final TimerManager timerManager;

    @Inject
    private GokIrlBankedXpOverlay(GokIrlBankedXpPlugin plugin, TimerManager timerManager)
    {
        this.plugin = plugin;
        this.timerManager = timerManager;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_SCENE);
        panelComponent.setPreferredSize(new Dimension(240, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();

        GokIrlBankedXpPlugin.BankedXpSnapshot snapshot = plugin.getCurrentSnapshot();
        if (snapshot != null && snapshot.hasData())
        {
            panelComponent.getChildren().add(
                TitleComponent.builder()
                    .text("IRL Banked XP")
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

                if (entry.isBelowThreshold())
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

        return panelComponent.render(graphics);
    }
}
