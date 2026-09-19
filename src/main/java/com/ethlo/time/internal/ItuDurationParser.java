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

import static com.ethlo.time.internal.fixed.ITUParser.RADIX;

import com.ethlo.time.Duration;
import com.ethlo.time.internal.util.ErrorUtil;
import com.ethlo.time.internal.util.ParseCursor;

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
        final ParseCursor cursor = ParseCursor.of(text, offset);
        if (!cursor.hasRemaining())
        {
            ErrorUtil.raise("Duration cannot be empty", text, text.length() - 1);
        }

        // Check for a leading negative sign
        final boolean negative = cursor.consumeIf(MINUS);

        final DurationPartsConsumer handler = new DurationPartsConsumer(cursor.index(), negative);
        // The start of the current segment, for overflow error reporting
        int segmentStart = cursor.index();
        try
        {
            while (cursor.hasRemaining())
            {
                segmentStart = cursor.index();
                readUntilNonDigit(cursor, handler);
            }

            handler.validate(text, cursor.index());
        }
        catch (ArithmeticException exc)
        {
            // NOTE: The overflow checks below use Math.addExact/multiplyExact, which signal with an
            // ArithmeticException. Callers are documented to get a DateTimeParseException, so translate it.
            ErrorUtil.raise("Duration is too large to be represented", text, Math.min(segmentStart, text.length() - 1));
        }

        return handler.getResult();
    }

    private static void readUntilNonDigit(final ParseCursor cursor, final DurationPartsConsumer consumer)
    {
        final String text = cursor.text();
        long value = 0;
        final int startIndex = cursor.index();
        while (cursor.hasRemaining())
        {
            final char c = cursor.peek();
            if (c >= DIGIT_ZERO && c <= DIGIT_NINE)
            {
                value = Math.addExact(Math.multiplyExact(value, RADIX), c - DIGIT_ZERO);
                cursor.advance(1);
            }
            else
            {
                consumer.accept(text, cursor.index(), cursor.index() - startIndex, c, value);
                cursor.advance(1);
                return;
            }
        }

        // If we never hit any non-digit
        if (cursor.index() - startIndex > 0)
        {
            consumer.accept(text, cursor.index(), cursor.index() - startIndex, UNIT_UNDEFINED, value);
        }
    }
}
