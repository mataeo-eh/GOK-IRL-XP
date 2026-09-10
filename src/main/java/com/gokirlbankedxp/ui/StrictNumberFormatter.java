package com.gokirlbankedxp.ui;

import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import javax.swing.text.NumberFormatter;

/**
 * A {@link NumberFormatter} that only accepts text it can read <em>in full</em>.
 *
 * <p>The stock formatter delegates to {@link java.text.Format#parseObject(String)},
 * which stops at the first character it cannot read and returns whatever it
 * parsed up to that point. In a plugin that turns typed numbers into banked XP
 * that is a data-integrity problem, not a nicety:</p>
 * <ul>
 *   <li>With grouping switched off, {@code "1,000"} parsed as {@code 1}. A user
 *       who typed a thousand XP per unit silently got one.</li>
 *   <li>{@code "12abc"} parsed as {@code 12}, hiding the typo.</li>
 * </ul>
 *
 * <p>This formatter separates the two jobs the stock one conflates. The
 * <em>display</em> format (passed to the superclass) turns a value into text
 * the same way it always did, so fields still show {@code 1000} rather than
 * {@code 1,000}. The <em>parser</em> is a second format used only for reading,
 * and it must consume every character of the trimmed text or the read fails.
 * Giving the parser grouping lets {@code "1,000"} read as a thousand, which is
 * what anyone typing it means.</p>
 *
 * <p>Whole-number fields additionally refuse anything the parser had to widen
 * to a {@code double}: a value past the {@code long} range would otherwise
 * saturate silently instead of being reported as invalid.</p>
 *
 * <p>The min/max check is the same one {@link javax.swing.text.InternationalFormatter}
 * applies, re-implemented here because that class performs it inside its own
 * {@code stringToValue}, which this one replaces.</p>
 */
public class StrictNumberFormatter extends NumberFormatter
{
    private final NumberFormat parser;

    /**
     * @param display how a value is rendered into the field
     * @param parser how the field's text is read back; must consume the whole
     *     string for the read to succeed
     */
    public StrictNumberFormatter(NumberFormat display, NumberFormat parser)
    {
        super(display);
        this.parser = parser;
        // Half-typed text has to be allowed or the user cannot clear the field
        // to type a new number. Validity is judged when the value is read.
        setAllowsInvalid(true);
    }

    @Override
    public Object stringToValue(String text) throws ParseException
    {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty())
        {
            throw new ParseException("No number entered", 0);
        }

        ParsePosition position = new ParsePosition(0);
        Number parsed = parser.parse(trimmed, position);
        if (parsed == null || position.getIndex() != trimmed.length())
        {
            // Either nothing parsed, or something was left over ("12abc").
            throw new ParseException("Not a whole number", Math.max(0, position.getErrorIndex()));
        }

        Object value = toValueClass(parsed);
        checkRange(value);
        return value;
    }

    /**
     * Converts what the parser returned into the field's declared value class.
     *
     * <p>{@link java.text.DecimalFormat} returns a {@link Long} when the number
     * is integral and fits, and a {@link Double} otherwise. For a {@code Long}
     * field a {@code Double} therefore means "too big" or "had a fraction", both
     * of which are rejected rather than rounded.</p>
     */
    private Object toValueClass(Number parsed) throws ParseException
    {
        Class<?> valueClass = getValueClass();
        if (valueClass == Long.class)
        {
            if (!(parsed instanceof Long))
            {
                throw new ParseException("Not a whole number in range", 0);
            }
            return parsed;
        }
        if (valueClass == Double.class)
        {
            return parsed.doubleValue();
        }
        // No value class declared: hand back whatever the parser produced.
        return parsed;
    }

    /** The same min/max rule InternationalFormatter enforces, for the replaced read path. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void checkRange(Object value) throws ParseException
    {
        Comparable minimum = getMinimum();
        Comparable maximum = getMaximum();
        try
        {
            if (minimum != null && minimum.compareTo(value) > 0)
            {
                throw new ParseException("Value below minimum", 0);
            }
            if (maximum != null && maximum.compareTo(value) < 0)
            {
                throw new ParseException("Value above maximum", 0);
            }
        }
        catch (ClassCastException ex)
        {
            throw new ParseException("Value is not comparable to the field's range", 0);
        }
    }
}
