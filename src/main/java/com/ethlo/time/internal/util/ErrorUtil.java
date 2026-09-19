package com.ethlo.time.internal.util;

/*-
 * #%L
 * Internet Time Utility
 * %%
 * Copyright (C) 2017 - 2024 Morten Haraldsen (ethlo)
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
 * The shared parse-failure model. Every {@link DateTimeParseException} raised by the fixed-format parser,
 * the token-based parser and the duration parser is constructed here, so the position unit (0-based UTF-16
 * code-unit index into the original input, possibly equal to the input length for end-of-input) and the
 * message formats stay consistent across all entries.
 */
public class ErrorUtil
{
    private ErrorUtil()
    {
    }

    public static DateTimeParseException raiseUnexpectedCharacter(String chars, int index, char... expected)
    {
        throw new DateTimeParseException(String.format("Expected character %s at position %d, found %s: %s", Arrays.toString(expected), index + 1, chars.charAt(index), chars), chars, index);
    }

    public static DateTimeParseException raiseUnexpectedEndOfText(final String chars, final int offset)
    {
        throw new DateTimeParseException(String.format("Unexpected end of input: %s", chars), chars, offset);
    }

    public static DateTimeParseException raiseMissingGranularity(Field field, final String chars, final int offset)
    {
        throw new DateTimeParseException(String.format("Unexpected end of input, missing field %s: %s", field.name(), chars), chars, offset);
    }

    /**
     * Generic failure with a pre-defined message. The input text is appended after a colon, matching the
     * long-standing duration parser format.
     */
    public static DateTimeParseException raise(final String errorMessage, final String text, final int index)
    {
        throw new DateTimeParseException(errorMessage + ": " + text, text, index);
    }

    public static DateTimeParseException raiseInvalidTimezoneOffset(final String text, final int index)
    {
        throw new DateTimeParseException(String.format("Invalid timezone offset: %s", text), text, index);
    }

    public static DateTimeParseException raiseUnknownLocalOffsetConvention(final String text, final int index)
    {
        throw new DateTimeParseException("Unknown 'Local Offset Convention' date-time not allowed", text, index);
    }

    public static DateTimeParseException raiseTrailingJunk(final String text, final int lastUsedIndex)
    {
        throw new DateTimeParseException(String.format("Trailing junk data after position %d: %s", lastUsedIndex + 2, text), text, lastUsedIndex + 1);
    }

    public static DateTimeParseException raiseUnexpectedDateTimeSeparator(final String text, final int index, final char[] allowedSeparators)
    {
        final String allowedCharStr = allowedSeparators.length > 1 ? Arrays.toString(allowedSeparators) : Character.toString(allowedSeparators[0]);
        throw new DateTimeParseException(String.format("Expected character %s at position %d, found %s: %s", allowedCharStr, index + 1, text.charAt(index), text), text, index);
    }

    public static void assertPositionContains(Field field, String chars, int index, char expected)
    {
        if (index >= chars.length())
        {
            throw raiseMissingGranularity(field, chars, index);
        }

        if (chars.charAt(index) != expected)
        {
            throw new DateTimeParseException(String.format("Expected character %s at position %d, found %s: %s", expected, index + 1, chars.charAt(index), chars), chars, index);
        }
    }

    public static void assertFractionDigits(String chars, int fractionDigits, int idx)
    {
        if (fractionDigits == 0)
        {
            throw new DateTimeParseException(String.format("Must have at least 1 fraction digit: %s", chars), chars, idx);
        }

        if (fractionDigits > MAX_FRACTION_DIGITS)
        {
            throw new DateTimeParseException(String.format("Maximum supported number of fraction digits in second is 9, got %d: %s", fractionDigits, chars), chars, idx);
        }
    }
}
