package com.gokirlbankedxp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.ParseException;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFormattedTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.NumberFormatter;
import org.junit.jupiter.api.Test;

/**
 * Covers the two ways a typed number used to be misread: a leading prefix being
 * accepted as the whole value ("1,000" as 1), and a failed edit reading back as
 * the previous good value.
 */
class StrictNumberFormatterTest
{
    @Test
    void wholeNumberFieldReadsTheEntireText() throws ParseException
    {
        NumberFormatter formatter = IrlXpUi.positiveLongFormatter();

        assertEquals(1000L, formatter.stringToValue("1,000"));
        assertEquals(1000L, formatter.stringToValue(" 1000 "));
        assertEquals(1_250_000L, formatter.stringToValue("1,250,000"));

        // Trailing text used to be dropped and the prefix accepted.
        assertThrows(ParseException.class, () -> formatter.stringToValue("12abc"));
        assertThrows(ParseException.class, () -> formatter.stringToValue(""));
        assertThrows(ParseException.class, () -> formatter.stringToValue("   "));
        assertThrows(ParseException.class, () -> formatter.stringToValue("abc"));
        // Below the minimum of 1, or not whole, or past the long range.
        assertThrows(ParseException.class, () -> formatter.stringToValue("0"));
        assertThrows(ParseException.class, () -> formatter.stringToValue("-5"));
        assertThrows(ParseException.class, () -> formatter.stringToValue("1.5"));
        assertThrows(ParseException.class, () -> formatter.stringToValue("999999999999999999999"));
    }

    @Test
    void multiplierFieldAcceptsDecimalsAndZeroWithinItsRange() throws ParseException
    {
        NumberFormatter formatter = IrlXpUi.multiplierFormatter(1000.0);

        assertEquals(Double.valueOf(1.5), formatter.stringToValue("1.5"));
        assertEquals(Double.valueOf(0.0), formatter.stringToValue("0"));
        assertEquals(Double.valueOf(2.0), formatter.stringToValue("2"));
        assertEquals(Double.valueOf(1000.0), formatter.stringToValue("1000"));

        assertThrows(ParseException.class, () -> formatter.stringToValue("1,5"));
        assertThrows(ParseException.class, () -> formatter.stringToValue("-1"));
        assertThrows(ParseException.class, () -> formatter.stringToValue("1001"));
        assertThrows(ParseException.class, () -> formatter.stringToValue("abc"));
        assertThrows(ParseException.class, () -> formatter.stringToValue(""));
    }

    /**
     * The trap the rest of the fix exists for: after typing something the field
     * cannot commit, {@code getValue()} still answers with the previous number.
     * Reading through {@link IrlXpUi#readNumber} must report the text as invalid
     * instead of handing that stale value back.
     */
    @Test
    void readNumberReportsInvalidTextInsteadOfTheLastGoodValue() throws Exception
    {
        AtomicReference<JFormattedTextField> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFormattedTextField field = IrlXpUi.numberField(IrlXpUi.positiveLongFormatter());
            field.setValue(60L);
            field.setText("0");
            reference.set(field);
        });

        JFormattedTextField field = reference.get();
        // The committed value is still 60; that is the number that must not be used.
        assertEquals(60L, field.getValue());
        assertNull(IrlXpUi.readNumber(field));

        SwingUtilities.invokeAndWait(() -> field.setText("1,000"));
        assertEquals(1000L, IrlXpUi.readNumber(field).longValue());

        SwingUtilities.invokeAndWait(() -> field.setText(""));
        assertNull(IrlXpUi.readNumber(field));
    }

    /** Invalid text must stay on screen so the user can see what was rejected. */
    @Test
    void numberFieldDoesNotRevertUnparseableTextOnFocusLoss() throws Exception
    {
        AtomicReference<Integer> behaviour = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
            behaviour.set(IrlXpUi.numberField(IrlXpUi.positiveLongFormatter()).getFocusLostBehavior()));

        assertEquals(JFormattedTextField.COMMIT, behaviour.get().intValue());
    }

    @Test
    void parseWholeNumberFollowsTheSameRules()
    {
        assertEquals(OptionalLong.of(1000L), IrlXpUi.parseWholeNumber("1,000"));
        assertEquals(OptionalLong.of(-5L), IrlXpUi.parseWholeNumber("-5"));
        assertEquals(OptionalLong.of(0L), IrlXpUi.parseWholeNumber("0"));

        assertTrue(IrlXpUi.parseWholeNumber(null).isEmpty());
        assertTrue(IrlXpUi.parseWholeNumber("").isEmpty());
        assertTrue(IrlXpUi.parseWholeNumber("12abc").isEmpty());
        assertTrue(IrlXpUi.parseWholeNumber("1.5").isEmpty());
        assertTrue(IrlXpUi.parseWholeNumber("999999999999999999999").isEmpty());
    }
}
