package com.gokirlbankedxp.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.swing.JButton;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.util.ImageUtil;

/**
 * The sidebar's copy of the in-game skills tab, where each skill tile is a
 * switch for whether IRL XP tracks that skill.
 *
 * <p>The tiles are laid out three across in the same order as the game's own
 * skills tab and carry the game's own skill icons, so a player finds a skill
 * here by where it sits rather than by reading a list. Where the game shows a
 * level, a tile shows its state instead: a green tick while the skill is
 * tracked, and a red cross over a greyed-out tile once it is switched off.</p>
 *
 * <p>This component only draws state and reports clicks. It never decides what
 * a click means: the click is handed to the caller, and the tiles change only
 * when {@link #setUntracked} is called with the plugin's answer. That keeps the
 * grid from ever showing a state the plugin did not actually accept.</p>
 */
public final class SkillTrackingGrid extends JPanel
{
    /**
     * The game's skills tab, read left to right and top to bottom.
     *
     * <p>{@link Skill#values()} is in the game's internal order, which is not the
     * order the tab is drawn in, and the API offers no accessor for the tab
     * layout, so the layout is stated here. A skill that is not listed (one added
     * to the game after this was written) is still shown; it is appended after
     * these rather than dropped.</p>
     */
    private static final List<Skill> IN_GAME_ORDER = Collections.unmodifiableList(Arrays.asList(
        Skill.ATTACK, Skill.HITPOINTS, Skill.MINING,
        Skill.STRENGTH, Skill.AGILITY, Skill.SMITHING,
        Skill.DEFENCE, Skill.HERBLORE, Skill.FISHING,
        Skill.RANGED, Skill.THIEVING, Skill.COOKING,
        Skill.PRAYER, Skill.CRAFTING, Skill.FIREMAKING,
        Skill.MAGIC, Skill.FLETCHING, Skill.WOODCUTTING,
        Skill.RUNECRAFT, Skill.SLAYER, Skill.FARMING,
        Skill.CONSTRUCTION, Skill.HUNTER, Skill.SAILING));

    private static final int COLUMNS = 3;
    private static final int TILE_HEIGHT = 34;
    private static final int TILE_GAP = 3;

    // The stone browns of the in-game skill tiles, and a flatter, darker version
    // for a skill that is switched off.
    private static final Color TILE_ON = new Color(0x4A4236);
    private static final Color TILE_ON_HIGHLIGHT = new Color(0x6A6050);
    private static final Color TILE_OFF = new Color(0x26272A);
    private static final Color TILE_OFF_HIGHLIGHT = new Color(0x34363A);
    private static final Color TILE_EDGE = new Color(0x15130F);
    private static final Color TILE_HOVER = new Color(255, 255, 255, 28);
    private static final Color TICK = new Color(0x3FD45A);
    private static final Color CROSS = new Color(0xE0443E);

    private final Map<Skill, SkillTile> tiles = new EnumMap<>(Skill.class);

    /**
     * @param skills the skills to offer, normally every skill the plugin tracks
     * @param iconManager RuneLite's own source of skill icons, so the artwork is
     *     the game's and nothing has to be bundled with the plugin
     * @param onToggle called with a skill and the state the user is asking for
     *     (true to track it) each time a tile is clicked
     */
    public SkillTrackingGrid(Skill[] skills, SkillIconManager iconManager, BiConsumer<Skill, Boolean> onToggle)
    {
        setLayout(new GridLayout(0, COLUMNS, TILE_GAP, TILE_GAP));
        setOpaque(false);

        for (Skill skill : orderLikeTheGame(skills))
        {
            // getSkillImage(skill) returns the full-size icon used on the game's
            // skills tab. The switched-off version is prepared once here rather
            // than recomputed on every repaint.
            BufferedImage icon = iconManager.getSkillImage(skill);
            BufferedImage fadedIcon = ImageUtil.alphaOffset(ImageUtil.grayscaleImage(icon), 0.45f);

            SkillTile tile = new SkillTile(skill, icon, fadedIcon);
            tile.addActionListener(event -> onToggle.accept(skill, !tile.tracked));
            tiles.put(skill, tile);
            add(tile);
        }
    }

    /**
     * Redraws every tile from the plugin's current answer.
     *
     * @param untracked the skills that are switched off; every other tile shows as on
     */
    public void setUntracked(Set<Skill> untracked)
    {
        tiles.forEach((skill, tile) -> tile.setTracked(!untracked.contains(skill)));
    }

    /** How many tiles the grid holds, for the "N of M on" summary beside it. */
    public int getSkillCount()
    {
        return tiles.size();
    }

    /** The offered skills in skills-tab order, with any the tab layout does not list at the end. */
    private static List<Skill> orderLikeTheGame(Skill[] skills)
    {
        List<Skill> offered = Arrays.asList(skills);
        List<Skill> ordered = new ArrayList<>();
        for (Skill skill : IN_GAME_ORDER)
        {
            if (offered.contains(skill))
            {
                ordered.add(skill);
            }
        }
        for (Skill skill : offered)
        {
            if (!ordered.contains(skill))
            {
                ordered.add(skill);
            }
        }
        return ordered;
    }

    /**
     * One skill's tile. A {@link JButton} underneath, so it is reachable by
     * keyboard and reports clicks the standard way, but painted entirely by
     * hand: the look-and-feel's button chrome would not read as a game tile.
     */
    private static final class SkillTile extends JButton
    {
        private final Skill skill;
        private final BufferedImage icon;
        private final BufferedImage fadedIcon;
        private boolean tracked = true;

        SkillTile(Skill skill, BufferedImage icon, BufferedImage fadedIcon)
        {
            this.skill = skill;
            this.icon = icon;
            this.fadedIcon = fadedIcon;

            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            // Width comes from the grid, which shares the sidebar evenly; only
            // the height is fixed.
            setPreferredSize(new Dimension(1, TILE_HEIGHT));
            refreshToolTip();
        }

        void setTracked(boolean tracked)
        {
            if (this.tracked == tracked)
            {
                return;
            }
            this.tracked = tracked;
            refreshToolTip();
            repaint();
        }

        private void refreshToolTip()
        {
            setToolTipText(tracked
                ? skill.getName() + ": tracked. Click to stop tracking."
                : skill.getName() + ": not tracked. Its banked XP is kept. Click to track again.");
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            Graphics2D g = (Graphics2D) graphics.create();
            try
            {
                int width = getWidth();
                int height = getHeight();

                // The tile itself: a dark edge, a flat face, and a one-pixel
                // highlight along the top and left to give the bevelled look of
                // the game's tiles.
                g.setColor(TILE_EDGE);
                g.fillRect(0, 0, width, height);
                g.setColor(tracked ? TILE_ON : TILE_OFF);
                g.fillRect(1, 1, width - 2, height - 2);
                g.setColor(tracked ? TILE_ON_HIGHLIGHT : TILE_OFF_HIGHLIGHT);
                g.drawLine(1, 1, width - 2, 1);
                g.drawLine(1, 1, 1, height - 2);

                if (getModel().isRollover() || isFocusOwner())
                {
                    g.setColor(TILE_HOVER);
                    g.fillRect(1, 1, width - 2, height - 2);
                }

                // Icon on the left, where the game puts it.
                BufferedImage image = tracked ? icon : fadedIcon;
                int iconX = 5;
                int iconY = (height - image.getHeight()) / 2;
                g.drawImage(image, iconX, iconY, null);

                // State on the right, where the game puts the level. Drawn as
                // strokes rather than text so it does not depend on the client
                // font having tick and cross glyphs.
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int markSize = 10;
                int markX = width - markSize - 6;
                int markY = (height - markSize) / 2;
                if (tracked)
                {
                    g.setColor(TICK);
                    g.drawLine(markX, markY + 5, markX + 4, markY + markSize - 1);
                    g.drawLine(markX + 4, markY + markSize - 1, markX + markSize, markY);
                }
                else
                {
                    g.setColor(CROSS);
                    g.drawLine(markX + 1, markY + 1, markX + markSize - 1, markY + markSize - 1);
                    g.drawLine(markX + markSize - 1, markY + 1, markX + 1, markY + markSize - 1);
                }
            }
            finally
            {
                g.dispose();
            }
        }
    }
}
