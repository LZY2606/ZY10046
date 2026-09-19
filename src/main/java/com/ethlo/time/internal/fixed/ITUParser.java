package com.ethlo.time.internal.fixed;

/*-
 * #%L
 * Internet Time Utility
 * %%
 * Copyright (C) 2017 Morten Haraldsen (ethlo)
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

import static com.ethlo.time.internal.ParseFailure.assertFractionDigits;
import static com.ethlo.time.internal.util.LimitedCharArrayIntegerUtil.ZERO;
import static com.ethlo.time.internal.util.LimitedCharArrayIntegerUtil.parse2;
import static com.ethlo.time.internal.util.LimitedCharArrayIntegerUtil.parse4;

import java.text.ParsePosition;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

import com.ethlo.time.DateTime;
import com.ethlo.time.DateTimeParser;
import com.ethlo.time.Field;
import com.ethlo.time.ParseConfig;
import com.ethlo.time.TimezoneOffset;
import com.ethlo.time.internal.Cursor;
import com.ethlo.time.internal.ParseFailure;
import com.ethlo.time.internal.util.ArrayUtils;

public class ITUParser implements DateTimeParser
{
    /**
     * Default date field seperator
     */
    public static final char DATE_SEPARATOR = '-';
    /**
     * Default time field seperator
     */
    public static final char TIME_SEPARATOR = ':';
    /**
     * Default date/time seperator
     */
    public static final char SEPARATOR_UPPER = 'T';
    /**
     * Default date/time seperator lower-case
     */
    public static final char SEPARATOR_LOWER = 't';
    /**
     * Alternative date/time seperator
     */
    public static final char SEPARATOR_SPACE = ' ';
    public static final char PLUS = '+';
    public static final char MINUS = '-';
    public static final char FRACTION_SEPARATOR = '.';
    public static final char ZULU_UPPER = 'Z';
    public static final char ZULU_LOWER = 'z';
    public static final int MAX_FRACTION_DIGITS = 9;
    public static final int RADIX = 10;
    public static final int DIGITS_IN_NANO = 9;
    private static final int[] NANO_SCALE = {1_000_000_000, 100_000_000, 10_000_000, 1_000_000, 100_000, 10_000, 1_000, 100, 10, 1};
    private static final DateTimeParser instance = new ITUParser();

    private ITUParser()
    {

    }

    private static DateTime handleTime(final Cursor cursor, final ParseConfig parseConfig, final int year, final int month, final int day, final int hour, final int minute)
    {
        switch (cursor.peek())
        {
            case TIME_SEPARATOR:
                // We have seconds
                return handleTimeResolution(cursor, parseConfig, year, month, day, hour, minute);

            // We look for time-zone information
            case PLUS:
            case MINUS:
            case ZULU_UPPER:
            case ZULU_LOWER:
                final TimezoneOffset zoneOffset = parseTimezone(cursor, parseConfig);
                final int charLength = Field.MINUTE.getRequiredLength() + (zoneOffset != null ? zoneOffset.getRequiredLength() : 0);
                return new DateTime(Field.MINUTE, year, month, day, hour, minute, 0, 0, zoneOffset, 0, charLength);

            default:
                throw ParseFailure.unexpectedCharacter(cursor.text(), cursor.position(), TIME_SEPARATOR, ZULU_UPPER, ZULU_LOWER, PLUS, MINUS);
        }
    }

    private static void assertAllowedDateTimeSeparator(final Cursor cursor, final ParseConfig config)
    {
        final char needle = cursor.peek();
        if (!config.isDateTimeSeparator(needle))
        {
            final String allowedCharStr = config.getDateTimeSeparators().length > 1 ? Arrays.toString(config.getDateTimeSeparators()) : Character.toString(config.getDateTimeSeparators()[0]);
            throw ParseFailure.expectedCharacter(cursor.text(), cursor.position(), allowedCharStr);
        }
        cursor.consume();
    }

    private static TimezoneOffset parseTimezone(final Cursor cursor, final ParseConfig parseConfig)
    {
        if (!cursor.hasRemaining())
        {
            return null;
        }

        final char c = cursor.peek();
        if (c == ZULU_UPPER || c == ZULU_LOWER)
        {
            cursor.consume();
            assertNoMoreChars(cursor, parseConfig);
            return TimezoneOffset.UTC;
        }

        if (c != PLUS && c != MINUS)
        {
            throw ParseFailure.unexpectedCharacter(cursor.text(), cursor.position(), ZULU_UPPER, ZULU_LOWER, PLUS, MINUS);
        }

        if (cursor.remaining() < 6)
        {
            throw ParseFailure.invalidTimezoneOffset(cursor.text(), cursor.position());
        }

        final char sign = cursor.consume();
        int hours = parse2(cursor.text(), cursor.position());
        cursor.advance(2);
        cursor.expect(TIME_SEPARATOR, Field.ZONE_OFFSET);
        int minutes = parse2(cursor.text(), cursor.position());
        cursor.advance(2);
        if (sign == MINUS)
        {
            hours = -hours;
            minutes = -minutes;

            if (hours == 0 && minutes == 0)
            {
                throw ParseFailure.unknownLocalOffsetConvention(cursor.text(), cursor.position() - 6);
            }
        }

        assertNoMoreChars(cursor, parseConfig);
        return TimezoneOffset.ofHoursMinutes(hours, minutes);
    }

    private static void assertNoMoreChars(final Cursor cursor, final ParseConfig parseConfig)
    {
        if (parseConfig.isFailOnTrailingJunk() && cursor.startOffset() == 0 && cursor.hasRemaining())
        {
            throw new DateTimeParseException(String.format("Trailing junk data after position %d: %s", cursor.position() + 1, cursor.text()), cursor.text(), cursor.position());
        }
    }

    public static DateTime parseLenient(final String chars, final ParseConfig parseConfig, int offset)
    {
        final int availableLength = sanityCheckInputParams(chars, offset);
        final Cursor cursor = new Cursor(chars, offset);

        // Date portion

        // YEAR
        final int years = parse4(chars, cursor.position());
        cursor.advance(4);
        if (4 == availableLength)
        {
            return new DateTime(Field.YEAR, years, 0, 0, 0, 0, 0, 0, null, 0, availableLength);
        }

        // MONTH
        cursor.expect(DATE_SEPARATOR, Field.MONTH);
        final int month = parse2(chars, cursor.position());
        cursor.advance(2);
        if (7 == availableLength)
        {
            return new DateTime(Field.MONTH, years, month, 0, 0, 0, 0, 0, null, 0, availableLength);
        }

        // DAY
        cursor.expect(DATE_SEPARATOR, Field.DAY);
        final int days = parse2(chars, cursor.position());
        cursor.advance(2);
        if (10 == availableLength)
        {
            return new DateTime(Field.DAY, years, month, days, 0, 0, 0, 0, null, 0, availableLength);
        }

        // HOURS
        assertAllowedDateTimeSeparator(cursor, parseConfig);
        final int hours = parse2(chars, cursor.position());
        cursor.advance(2);

        // MINUTES
        cursor.expect(TIME_SEPARATOR, Field.MINUTE);
        final int minutes = parse2(chars, cursor.position());
        cursor.advance(2);
        if (availableLength == 16)
        {
            // Have only minutes
            return new DateTime(Field.MINUTE, years, month, days, hours, minutes, 0, 0, null, 0, 16);
        }

        // SECONDS or TIMEZONE
        return handleTime(cursor, parseConfig, years, month, days, hours, minutes);
    }

    public static int sanityCheckInputParams(String chars, int offset)
    {
        if (chars == null)
        {
            throw new NullPointerException("text cannot be null");
        }

        final int availableLength = chars.length() - offset;

        if (availableLength < 0)
        {
            throw new IndexOutOfBoundsException(String.format("offset is %d which is equal to or larger than the input length of %d", offset, chars.length()));
        }

        if (offset < 0)
        {
            throw new IndexOutOfBoundsException(String.format("offset cannot be negative, was %d", offset));
        }
        return availableLength;
    }

    private static DateTime handleTimeResolution(final Cursor cursor, final ParseConfig parseConfig, final int year, final int month, final int day, final int hour, final int minute)
    {
        // The cursor is positioned at the seconds separator
        if (cursor.remaining() > 3)
        {
            final char c = cursor.peek(3);
            if (parseConfig.isFractionSeparator(c))
            {
                return handleFractionalSeconds(cursor, parseConfig, year, month, day, hour, minute);
            }
            else if (c == ZULU_UPPER || c == ZULU_LOWER)
            {
                cursor.advance(4);
                assertNoMoreChars(cursor, parseConfig);
                return handleSecondResolution(cursor, year, month, day, hour, minute, TimezoneOffset.UTC);
            }
            else if (c == PLUS || c == MINUS)
            {
                cursor.advance(3);
                final TimezoneOffset timezoneOffset = parseTimezone(cursor, parseConfig);
                return handleSecondResolution(cursor, year, month, day, hour, minute, timezoneOffset);
            }
            else
            {
                throw ParseFailure.unexpectedCharacter(cursor.text(), cursor.position() + 3, ArrayUtils.merge(parseConfig.getFractionSeparators(), new char[]{ZULU_UPPER, ZULU_LOWER, PLUS, MINUS}));
            }
        }
        else if (cursor.remaining() == 3)
        {
            final int seconds = parse2(cursor.text(), cursor.position() + 1);
            return new DateTime(Field.SECOND, year, month, day, hour, minute, seconds, 0, null, 0, cursor.remaining() + 16);
        }

        throw ParseFailure.unexpectedEndOfText(cursor.text(), cursor.position());
    }

    private static DateTime handleSecondResolution(final Cursor cursor, final int year, final int month, final int day, final int hour, final int minute, final TimezoneOffset timezoneOffset)
    {
        final int seconds = parse2(cursor.text(), cursor.startOffset() + 17);
        final int charLength = Field.SECOND.getRequiredLength() + (timezoneOffset != null ? timezoneOffset.getRequiredLength() : 0);
        return new DateTime(Field.SECOND, year, month, day, hour, minute, seconds, 0, timezoneOffset, 0, charLength);
    }

    private static DateTime handleFractionalSeconds(final Cursor cursor, final ParseConfig parseConfig, final int year, final int month, final int day, final int hour, final int minute)
    {
        final String chars = cursor.text();
        final int fracStart = cursor.position() + 4;
        // Hot digit run: straight-line code on primitives only, so no cursor escapes this method
        final long run = parseFractionRun(chars, fracStart);
        final int idx = (int) (run >>> 32);
        final int nanosRaw = (int) run;
        final int fractionDigits = idx - fracStart;
        cursor.reset(idx);
        assertFractionDigits(chars, fractionDigits, idx - 1);

        // Scale to nanoseconds
        final int nanos = nanosRaw * NANO_SCALE[fractionDigits];

        final TimezoneOffset timezoneOffset = parseTimezone(cursor, parseConfig);
        final int charLength = cursor.position() - cursor.startOffset();
        final int second = parse2(chars, cursor.startOffset() + 17);
        return new DateTime(Field.NANO, year, month, day, hour, minute, second, nanos, timezoneOffset, fractionDigits, charLength);
    }

    /**
     * Scans the fraction digits starting at {@code idx}. Returns the end index of the digit run
     * in the high 32 bits and the accumulated value of the first up to 9 digits in the low 32 bits.
     */
    private static long parseFractionRun(final String chars, final int idx)
    {
        final int length = chars.length();
        int fractionDigits = 0;
        int nanos = 0;
        int i = idx;

        // Fast path for the common 3/6/9 digit cases: consume digits three at a time in straight-line code
        while (fractionDigits < MAX_FRACTION_DIGITS && i + 3 <= length)
        {
            final int d0 = chars.charAt(i) - ZERO;
            final int d1 = chars.charAt(i + 1) - ZERO;
            final int d2 = chars.charAt(i + 2) - ZERO;
            if ((d0 | d1 | d2) < 0 || d0 > 9 || d1 > 9 || d2 > 9)
            {
                break;
            }
            nanos = nanos * 1000 + d0 * 100 + d1 * 10 + d2;
            fractionDigits += 3;
            i += 3;
        }

        // Remainder: one digit at a time
        while (i < length)
        {
            final int d = chars.charAt(i) - ZERO;
            if (d < 0 || d > 9)
            {
                break;
            }
            fractionDigits++;
            if (fractionDigits <= MAX_FRACTION_DIGITS)
            {
                // Beyond the maximum the value is rejected below, so avoid overflowing the accumulator
                nanos = nanos * RADIX + d;
            }
            i++;
        }
        return ((long) i << 32) | (nanos & 0xFFFFFFFFL);
    }

    public static OffsetDateTime parseDateTime(final String chars, int offset)
    {
        final DateTime dateTime = parseLenient(chars, ParseConfig.DEFAULT, offset);
        if (dateTime.includesGranularity(Field.SECOND))
        {
            return dateTime.toOffsetDatetime();
        }
        final Field field = dateTime.getMostGranularField();
        final Field nextGranularity = Field.values()[field.ordinal() + 1];
        throw new DateTimeParseException(String.format("Unexpected end of input, missing field %s: %s", nextGranularity, chars), chars, field.getRequiredLength());
    }

    public static DateTime parseLenient(String text, ParseConfig parseConfig, ParsePosition position)
    {
        try
        {
            int offset = position.getIndex();
            final DateTime result = ITUParser.parseLenient(text, parseConfig, position.getIndex());
            position.setIndex(offset + result.getParseLength());
            return result;
        }
        catch (DateTimeParseException exc)
        {
            position.setErrorIndex(exc.getErrorIndex());
            position.setIndex(position.getErrorIndex());
            throw exc;
        }
    }

    public static DateTimeParser getInstance()
    {
        return instance;
    }

    @Override
    public DateTime parse(final String text, final ParsePosition parsePosition)
    {
        return parseLenient(text, ParseConfig.DEFAULT, parsePosition);
    }

    @Override
    public DateTime parse(final String text)
    {
        return parseLenient(text, ParseConfig.DEFAULT, 0);
    }
}
