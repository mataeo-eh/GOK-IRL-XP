package com.gokirlbankedxp.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.text.NumberFormat;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.text.NumberFormatter;

/**
 * Shared visual language for the IRL XP sidebar and editor.
 *
 * <p>The palette intentionally stays close to RuneLite's dark chrome while the
 * warm gold accent makes the plugin feel like a compact progression dashboard.
 * Keeping these choices here prevents the XP and action views from drifting
 * back into two visually unrelated tools.</p>
 */
public final class IrlXpUi
{
    public static final Color BACKGROUND = new Color(0x17191C);
    public static final Color SURFACE = new Color(0x22252A);
    public static final Color SURFACE_RAISED = new Color(0x2A2E34);
    public static final Color INPUT_BACKGROUND = new Color(0x121417);
    public static final Color BORDER = new Color(0x3A3F46);
    public static final Color TEXT = new Color(0xF2F0EA);
    public static final Color MUTED_TEXT = new Color(0xA5A9AF);
    public static final Color ACCENT = new Color(0xD9A441);
    public static final Color ACCENT_DARK = new Color(0x8F6724);
    public static final Color SUCCESS = new Color(0x66B88A);
    public static final Color DANGER = new Color(0xD06B68);
    public static final Color SELECTION = new Color(0x4B3B20);

    private IrlXpUi()
    {
    }

    public static JPanel card(java.awt.LayoutManager layout)
    {
        JPanel panel = new JPanel(layout);
        panel.setBackground(SURFACE);
        panel.setBorder(cardBorder());
        return panel;
    }

    public static Border cardBorder()
    {
        return new CompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(12, 12, 12, 12));
    }

    public static JLabel sectionTitle(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        return label;
    }

    public static JLabel mutedLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED_TEXT);
        label.setFont(label.getFont().deriveFont(11f));
        return label;
    }

    public static void stylePrimaryButton(JButton button)
    {
        styleButton(button, ACCENT, new Color(0x18130B));
        button.setFont(button.getFont().deriveFont(Font.BOLD));
    }

    public static void styleSecondaryButton(JButton button)
    {
        styleButton(button, SURFACE_RAISED, TEXT);
        button.setBorder(new CompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)));
    }

    public static void styleDangerButton(JButton button)
    {
        styleButton(button, new Color(0x46282A), new Color(0xF0B6B3));
        button.setBorder(new CompoundBorder(
            BorderFactory.createLineBorder(new Color(0x714044)),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)));
    }

    private static void styleButton(JButton button, Color background, Color foreground)
    {
        button.setBackground(background);
        button.setForeground(foreground);
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createEmptyBorder(6, 11, 6, 11));
    }

    public static void styleField(JComponent component)
    {
        component.setBackground(INPUT_BACKGROUND);
        component.setForeground(TEXT);
        component.setBorder(new CompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    public static void styleList(JList<?> list, JScrollPane scrollPane)
    {
        list.setBackground(INPUT_BACKGROUND);
        list.setForeground(TEXT);
        list.setSelectionBackground(SELECTION);
        list.setSelectionForeground(TEXT);
        list.setFixedCellHeight(36);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER));
        scrollPane.getViewport().setBackground(INPUT_BACKGROUND);
    }

    /**
     * A muted, multi-line explanatory note.
     *
     * <p>A plain {@link JLabel} cannot wrap without HTML and a hard-coded pixel
     * width, so hints use a non-interactive {@link JTextArea} instead. Wrapped
     * text areas cannot work out their own height until they have been given a
     * width, so callers state how many lines to reserve.</p>
     */
    public static JTextArea wrappingNote(String text, int rows)
    {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setForeground(MUTED_TEXT);
        // JTextArea defaults to a monospaced font; borrow the label font so the
        // note reads as part of the form rather than as sample output.
        area.setFont(new JLabel().getFont().deriveFont(11f));
        area.setRows(rows);
        return area;
    }

    /**
     * Creates the formatter used by every positive-XP input.
     *
     * <p>Invalid intermediate text is allowed so an empty field accepts normal
     * typing. Callers validate the completed value with commitEdit().</p>
     */
    public static NumberFormatter positiveLongFormatter()
    {
        NumberFormat numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        numberFormat.setGroupingUsed(false);
        NumberFormatter formatter = new NumberFormatter(numberFormat);
        formatter.setAllowsInvalid(true);
        formatter.setMinimum(1L);
        formatter.setValueClass(Long.class);
        return formatter;
    }

}
