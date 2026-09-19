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

import static com.ethlo.time.internal.fixed.ITUParser.MAX_FRACTION_DIGITS;

import java.time.format.DateTimeParseException;
import java.util.Arrays;

import com.ethlo.time.Field;

/**
 * Shared parse-failure model for the fixed date-time parser, the token-based parser and the
 * duration parser. It is the single place where the public {@link DateTimeParseException}
 * instances and their message formats are built, so the three entry points cannot drift apart.
 *
 * <p>All positions are 0-based char offsets (the unit of
 * {@link DateTimeParseException#getErrorIndex()}); the rendered messages keep the historical
 * 1-based "position" wording.</p>
 */
public final class ParseFailure
{
    private ParseFailure()
    {
    }

    /**
     * Failure for "found character X, expected one of ...", rendering the expected characters
     * as an array, e.g. {@code Expected character [Z, z, +, -] at position 1, found X: ...}.
     */
    public static DateTimeParseException unexpectedCharacter(final String text, final int index, final char... expected)
    {
        return new DateTimeParseException(String.format("Expected character %s at position %d, found %s: %s", Arrays.toString(expected), index + 1, text.charAt(index), text), text, index);
    }

    /**
     * Failure for "found character X, expected character Y", rendering a single expected
     * character (or character description) without array brackets.
     */
    public static DateTimeParseException expectedCharacter(final String text, final int index, final char expected)
    {
        return expectedCharacter(text, index, Character.toString(expected));
    }

    /**
     * Same as {@link #expectedCharacter(String, int, char)} but with a pre-rendered description
     * of the expected character(s).
     */
    public static DateTimeParseException expectedCharacter(final String text, final int index, final String expected)
    {
        return new DateTimeParseException(String.format("Expected character %s at position %d, found %s: %s", expected, index + 1, text.charAt(index), text), text, index);
    }

    public static DateTimeParseException unexpectedEndOfText(final String text, final int index)
    {
        return new DateTimeParseException(String.format("Unexpected end of input: %s", text), text, index);
    }

    public static DateTimeParseException missingField(final Field field, final String text, final int index)
    {
        return new DateTimeParseException(String.format("Unexpected end of input, missing field %s: %s", field.name(), text), text, index);
    }

    /**
     * Failure for a timezone offset introducer (+/-) that is not followed by {@code HH:MM}.
     * Shared by the fixed parser and the zone-offset token.
     */
    public static DateTimeParseException invalidTimezoneOffset(final String text, final int index)
    {
        return new DateTimeParseException(String.format("Invalid timezone offset: %s", text), text, index);
    }

    /**
     * Failure for the disallowed {@code -00:00} offset. Shared by the fixed parser and the
     * zone-offset token.
     */
    public static DateTimeParseException unknownLocalOffsetConvention(final String text, final int index)
    {
        return new DateTimeParseException("Unknown 'Local Offset Convention' date-time not allowed", text, index);
    }

    /**
     * Enforces the fraction-digit constraints shared by the fixed parser and the fractions token.
     */
    public static void assertFractionDigits(final String text, final int fractionDigits, final int index)
    {
        if (fractionDigits == 0)
        {
            throw new DateTimeParseException(String.format("Must have at least 1 fraction digit: %s", text), text, index);
        }

        if (fractionDigits > MAX_FRACTION_DIGITS)
        {
            throw new DateTimeParseException(String.format("Maximum supported number of fraction digits in second is 9, got %d: %s", fractionDigits, text), text, index);
        }
    }

    /**
     * Merges a failure with the farthest position recorded on a {@link Cursor}. The farthest
     * position wins, so a confirmed branch cannot swallow an error observed further ahead by a
     * rolled-back candidate. The exception type, message and parsed data of the original failure
     * are preserved.
     */
    public static DateTimeParseException merge(final DateTimeParseException failure, final int furthestRecorded)
    {
        if (furthestRecorded > failure.getErrorIndex())
        {
            return new DateTimeParseException(failure.getMessage(), failure.getParsedString(), furthestRecorded);
        }
        return failure;
    }
}
