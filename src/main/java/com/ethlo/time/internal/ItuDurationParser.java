package com.ethlo.time.internal;

/*-
 * #%L
 * Internet Time Utility
 * %%
 * Copyright (C) 2017 - 2025 Morten Haraldsen @ethlo
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static com.ethlo.time.internal.DurationPartsConsumer.error;
import static com.ethlo.time.internal.fixed.ITUParser.RADIX;
import static com.ethlo.time.internal.fixed.ITUParser.sanityCheckInputParams;

import com.ethlo.time.Duration;

/**
 * <b>Rationale Against Supporting Years and Months</b>
 * <p>
 * Supporting years (Y) and months (M) in duration calculations is problematic due to:
 * <p>
 * Variable Lengths: Months have different lengths (28-31 days), and years may be leap or non-leap (365 vs. 366 days). This makes durations ambiguous unless referenced to a specific start date.
 * <p>
 * Context-Dependent Interpretation: P1M could mean 28, 29, 30, or 31 days depending on the month in which it is applied.
 * <p>
 * Difficult Arithmetic: Operations like addition and comparison require anchoring to a specific date, making them non-trivial in purely arithmetic computations.
 * <p>
 * Consistency Issues: Excluding years and months ensures durations are always exact and unambiguous, aligning with precise time-based measurements.
 */
public class ItuDurationParser
{
    public static final int NANOS_IN_SECOND = 1_000_000_000;
    public static final char SEP_T = 'T';
    public static final char UNIT_WEEK = 'W';
    public static final char UNIT_DAY = 'D';
    public static final char UNIT_HOUR = 'H';
    public static final char UNIT_MINUTE = 'M';
    public static final char UNIT_SECOND = 'S';
    public static final char DOT = '.';
    public static final char DIGIT_ZERO = '0';
    public static final char DIGIT_NINE = '9';
    public static final char MINUS = '-';
    public static final char UNIT_UNDEFINED = '\0';

    public static Duration parse(final String chars)
    {
        return parse(chars, 0);
    }

    public static Duration parse(final String text, final int offset)
    {
        final int availableLength = sanityCheckInputParams(text, offset);
        if (availableLength == 0)
        {
            error("Duration cannot be empty", text, text.length() - 1);
        }

        final Cursor cursor = new Cursor(text, offset);
        boolean negative = false;

        // Check for a leading negative sign
        if (cursor.peek() == MINUS)
        {
            negative = true;
            cursor.consume();
        }

        final DurationPartsConsumer handler = new DurationPartsConsumer(cursor.position(), negative);
        int segmentStart = cursor.position();
        try
        {
            while (cursor.hasRemaining())
            {
                segmentStart = cursor.position();
                readUntilNonDigit(cursor, handler);
            }

            handler.validate(text, cursor.position());
        }
        catch (ArithmeticException exc)
        {
            // NOTE: The overflow checks below use Math.addExact/multiplyExact, which signal with an
            // ArithmeticException. Callers are documented to get a DateTimeParseException, so translate it.
            error("Duration is too large to be represented", text, Math.min(segmentStart, text.length() - 1));
        }

        return handler.getResult();
    }

    private static void readUntilNonDigit(final Cursor cursor, final DurationPartsConsumer consumer)
    {
        final String text = cursor.text();
        long value = 0;
        // Hot digit run: straight-line local index, same shape as the fixed parser's digit loops.
        // The cursor is re-synced when the run ends.
        int index = cursor.position();
        final int startIndex = index;
        final int length = text.length();
        for (; index < length; index++)
        {
            final char c = text.charAt(index);
            if (c >= DIGIT_ZERO && c <= DIGIT_NINE)
            {
                final int digit = c - DIGIT_ZERO;
                value = Math.addExact(Math.multiplyExact(value, RADIX), digit);
            }
            else
            {
                consumer.accept(text, index, index - startIndex, c, value);
                cursor.reset(index + 1);
                return;
            }
        }

        // If we never hit any non-digit
        if (index - startIndex > 0)
        {
            consumer.accept(text, index, index - startIndex, UNIT_UNDEFINED, value);
        }
        cursor.reset(index);
    }
}
