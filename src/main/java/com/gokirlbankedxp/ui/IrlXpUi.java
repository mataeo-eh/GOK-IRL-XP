package com.gokirlbankedxp.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.util.Locale;
import java.util.OptionalLong;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.text.NumberFormatter;
import net.runelite.api.Skill;

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
     * typing. The value is judged when it is read back through
     * {@link #readNumber}, and only text that parses <em>completely</em> counts:
     * see {@link StrictNumberFormatter} for why "1,000" and "12abc" must not be
     * quietly read as 1 and 12.</p>
     *
     * <p>The field displays plain digits, but the parser accepts thousands
     * separators, so a user may type either {@code 1000} or {@code 1,000}.</p>
     */
    public static NumberFormatter positiveLongFormatter()
    {
        NumberFormat display = NumberFormat.getIntegerInstance(Locale.US);
        display.setGroupingUsed(false);
        NumberFormat parser = NumberFormat.getIntegerInstance(Locale.US);
        parser.setGroupingUsed(true);
        StrictNumberFormatter formatter = new StrictNumberFormatter(display, parser);
        formatter.setMinimum(1L);
        formatter.setValueClass(Long.class);
        return formatter;
    }

    /**
     * Creates the formatter used by every level-multiplier input.
     *
     * <p>Unlike {@link #positiveLongFormatter()} this accepts decimals and
     * accepts zero, because both are meaningful here: {@code 1.5} is "half again
     * as much XP" and {@code 0} is "this skill earns nothing at this level". Only
     * negatives are impossible, since XP already banked must never be reduced by
     * banking more. The ceiling is a typo guard, not a game rule.</p>
     *
     * <p>Grouping is off for parsing too: multipliers never reach a thousand,
     * and a stray comma in a decimal is far more likely a typo than a separator.</p>
     *
     * @param maximum the largest multiplier the editor will accept
     */
    public static NumberFormatter multiplierFormatter(double maximum)
    {
        NumberFormat display = NumberFormat.getNumberInstance(Locale.US);
        display.setGroupingUsed(false);
        display.setMinimumFractionDigits(0);
        // Two places is enough to express the multipliers people actually pick
        // (1.5x, 2.25x) without inviting values that round to nothing in use.
        display.setMaximumFractionDigits(2);
        NumberFormat parser = NumberFormat.getNumberInstance(Locale.US);
        parser.setGroupingUsed(false);
        StrictNumberFormatter formatter = new StrictNumberFormatter(display, parser);
        formatter.setMinimum(0.0);
        formatter.setMaximum(maximum);
        formatter.setValueClass(Double.class);
        return formatter;
    }

    /**
     * Builds a numeric field that never silently rewrites what the user typed.
     *
     * <p>{@link JFormattedTextField}'s default focus-lost policy is
     * {@code COMMIT_OR_REVERT}: when the text does not parse, the field snaps
     * back to its last good value the moment focus leaves it. A user who typed
     * {@code 0} into "seconds per unit" and clicked Save watched the field
     * revert to {@code 60} and the action save with 60 — or did not watch, and
     * only found out later. With {@code COMMIT}, unparseable text stays on
     * screen, and {@link #readNumber} reports it as invalid so the form shows an
     * error instead.</p>
     */
    public static JFormattedTextField numberField(NumberFormatter formatter)
    {
        JFormattedTextField field = new JFormattedTextField(formatter);
        field.setFocusLostBehavior(JFormattedTextField.COMMIT);
        return field;
    }

    /**
     * Reads the number currently shown in a formatted field.
     *
     * <p>Deliberately reads the <em>text</em> through the field's own formatter
     * rather than {@link JFormattedTextField#getValue()}. The value is only
     * updated by a successful commit, so after the user types something invalid
     * it still holds the previous good number — exactly the figure that must
     * not be acted on. Reading the text has no side effects either, so this is
     * safe to call from a document listener while the user is typing.</p>
     *
     * @return the number on screen, or null when the text is blank, not a
     *     number, or outside the formatter's range
     */
    public static Number readNumber(JFormattedTextField field)
    {
        JFormattedTextField.AbstractFormatter formatter = field.getFormatter();
        if (formatter == null)
        {
            return null;
        }

        try
        {
            Object value = formatter.stringToValue(field.getText());
            return value instanceof Number ? (Number) value : null;
        }
        catch (ParseException ex)
        {
            return null;
        }
    }

    /**
     * Parses a whole number typed into a plain text field, such as the sidebar's
     * XP amount box.
     *
     * <p>Uses the same rules as the formatted fields: the whole string must be a
     * number, thousands separators are accepted ({@code 1,000}), and anything
     * that does not fit in a {@code long} is rejected rather than clamped.</p>
     *
     * @return the number, or empty when the text is not one
     */
    public static OptionalLong parseWholeNumber(String text)
    {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty())
        {
            return OptionalLong.empty();
        }

        NumberFormat parser = NumberFormat.getIntegerInstance(Locale.US);
        parser.setGroupingUsed(true);
        ParsePosition position = new ParsePosition(0);
        Number parsed = parser.parse(trimmed, position);
        // DecimalFormat only returns a Long when the value is integral and fits;
        // anything else came back as a Double and is not a usable whole number.
        if (!(parsed instanceof Long) || position.getIndex() != trimmed.length())
        {
            return OptionalLong.empty();
        }
        return OptionalLong.of(parsed.longValue());
    }

    /** Renders a multiplier the way the editor and the sidebar both show it. */
    public static String formatMultiplier(double multiplier)
    {
        return String.format(Locale.US, "%.2fx", multiplier);
    }

    /**
     * A renderer that shows a {@link Skill} by its in-game display name.
     *
     * <p>{@code Skill} does not override {@code toString()}, so the default
     * renderer shows the raw enum constant ("RUNECRAFT") rather than the name the
     * game uses ("Runecraft"). Every skill drop-down in this plugin needs the
     * same fix, so it lives here rather than being re-implemented per panel.</p>
     */
    public static ListCellRenderer<Object> skillNameRenderer()
    {
        return new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
            {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Skill)
                {
                    setText(((Skill) value).getName());
                }
                return this;
            }
        };
    }
}
