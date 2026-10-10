package com.gokirlbankedxp.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A sidebar card whose contents can be folded away behind its own header.
 *
 * <p>The sidebar is a single narrow column, so every section that is always
 * fully drawn pushes the ones below it further down the scroll. Each section
 * therefore gets a header that never goes away — a title saying what the section
 * is, a one-line summary of what is inside it, and a Show/Hide button — and a
 * body that is only laid out while the section is open.</p>
 *
 * <p>The summary line is what makes a closed section still useful: it can carry
 * the one fact a player would otherwise have had to open the section to read,
 * such as the banked total or how many skills are switched on.</p>
 *
 * <p>This component owns only the folding. It never looks inside the body, so
 * the panel that built the body keeps every listener and refresh it had.</p>
 */
public final class CollapsibleSection extends JPanel
{
    private final JLabel summary = IrlXpUi.mutedLabel(" ");
    private final JButton toggle = new JButton();
    private final JComponent body;

    /**
     * @param title the heading that names the section, kept short because it
     *     shares one row of a roughly 180px-wide card with the Show/Hide button
     * @param toggleToolTip what opening the section lets the player do
     * @param body the section's contents; hidden, not removed, while closed, so
     *     it keeps its state between openings
     * @param open whether the section starts open
     */
    public CollapsibleSection(String title, String toggleToolTip, JComponent body, boolean open)
    {
        super(new BorderLayout(0, 8));
        this.body = body;

        setBackground(IrlXpUi.SURFACE);
        setBorder(IrlXpUi.cardBorder());

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        JPanel titles = new JPanel(new GridLayout(0, 1, 0, 1));
        titles.setOpaque(false);
        titles.add(IrlXpUi.sectionTitle(title));
        titles.add(summary);
        header.add(titles, BorderLayout.CENTER);

        IrlXpUi.styleSecondaryButton(toggle);
        toggle.setToolTipText(toggleToolTip);
        toggle.addActionListener(event -> setOpen(!isOpen()));
        header.add(toggle, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        add(body, BorderLayout.CENTER);
        setOpen(open);
    }

    /** Whether the body is currently shown. */
    public boolean isOpen()
    {
        return body.isVisible();
    }

    public void setOpen(boolean open)
    {
        body.setVisible(open);
        // The button names what pressing it will do, not the current state.
        toggle.setText(open ? "Hide" : "Show");
        // The section changes height, so the sidebar has to lay itself out again.
        revalidate();
        repaint();
    }

    /** Sets the line under the title, which stays visible while the section is closed. */
    public void setSummary(String text)
    {
        summary.setText(text);
    }

    /** Recolours the summary line, for a section whose summary is its headline figure. */
    public void setSummaryColor(Color color)
    {
        summary.setForeground(color);
    }
}
