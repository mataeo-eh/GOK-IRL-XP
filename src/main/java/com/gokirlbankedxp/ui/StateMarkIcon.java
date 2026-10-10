package com.gokirlbankedxp.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * The plugin's one on/off mark: a green tick for on, a red cross for off.
 *
 * <p>Both the skill tiles and the overlay switch say "this is on" and "this is
 * off" with the same mark, so it is drawn in one place. It is drawn as strokes
 * rather than text so it does not depend on the client font having tick and
 * cross glyphs.</p>
 *
 * <p>Usable two ways: as an {@link Icon} on a button or label, or through
 * {@link #paintMark} from a component that paints itself by hand.</p>
 */
public final class StateMarkIcon implements Icon
{
    public static final Color ON_COLOR = new Color(0x3FD45A);
    public static final Color OFF_COLOR = new Color(0xE0443E);

    private static final float STROKE_WIDTH = 2.2f;

    private final int size;
    private final boolean on;

    /**
     * @param size the width and height of the mark, in pixels
     * @param on true for the green tick, false for the red cross
     */
    public StateMarkIcon(int size, boolean on)
    {
        this.size = size;
        this.on = on;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y)
    {
        Graphics2D g = (Graphics2D) graphics.create();
        try
        {
            paintMark(g, x, y, size, on);
        }
        finally
        {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth()
    {
        return size;
    }

    @Override
    public int getIconHeight()
    {
        return size;
    }

    /**
     * Draws the mark inside the square whose top-left corner is {@code (x, y)}.
     *
     * <p>Changes the colour, stroke and antialiasing of {@code g}, so pass a
     * graphics context that is about to be disposed.</p>
     */
    public static void paintMark(Graphics2D g, int x, int y, int size, boolean on)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (on)
        {
            // A tick: a short stroke down to the low point, then a long one up.
            int lowX = x + Math.round(size * 0.4f);
            int lowY = y + size - 1;
            g.setColor(ON_COLOR);
            g.drawLine(x, y + size / 2, lowX, lowY);
            g.drawLine(lowX, lowY, x + size, y);
        }
        else
        {
            g.setColor(OFF_COLOR);
            g.drawLine(x + 1, y + 1, x + size - 1, y + size - 1);
            g.drawLine(x + size - 1, y + 1, x + 1, y + size - 1);
        }
    }
}
