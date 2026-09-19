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

import static com.ethlo.time.internal.util.ErrorUtil.assertFractionDigits;
import static com.ethlo.time.internal.util.ErrorUtil.raiseUnexpectedCharacter;
import static com.ethlo.time.internal.util.ErrorUtil.raiseUnexpectedEndOfText;

import java.text.ParsePosition;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.ethlo.time.DateTime;
import com.ethlo.time.DateTimeParser;
import com.ethlo.time.Field;
import com.ethlo.time.ParseConfig;
import com.ethlo.time.TimezoneOffset;
import com.ethlo.time.internal.util.ArrayUtils;
import com.ethlo.time.internal.util.ErrorUtil;
import com.ethlo.time.internal.util.LimitedCharArrayIntegerUtil;
import com.ethlo.time.internal.util.ParseCursor;

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

    private static DateTime handleTime(final ParseCursor cursor, final ParseConfig parseConfig, final int year, final int month, final int day, final int hour, final int minute)
    {
        switch (cursor.peek())
        {
            case TIME_SEPARATOR:
                // We have seconds
                cursor.advance(1);
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
                throw raiseUnexpectedCharacter(cursor.text(), cursor.index(), TIME_SEPARATOR, ZULU_UPPER, ZULU_LOWER, PLUS, MINUS);
        }
    }

    private static void assertAllowedDateTimeSeparator(final ParseCursor cursor, final ParseConfig config)
    {
        final char needle = cursor.peek();
        if (!config.isDateTimeSeparator(needle))
        {
            throw ErrorUtil.raiseUnexpectedDateTimeSeparator(cursor.text(), cursor.index(), config.getDateTimeSeparators());
        }
        cursor.consume();
    }

    /**
     * Attempts to read the optional timezone at the cursor. The timezone is a candidate token: if no
     * characters remain the candidate is absent and the position is restored. Once a decisive character
     * is seen the branch is confirmed and any failure propagates.
     */
    private static TimezoneOffset parseTimezone(final ParseCursor cursor, final ParseConfig parseConfig)
    {
        final int checkpoint = cursor.checkpoint();
        final long packed = cursor.consumeZoneOffset();
        if (packed == ParseCursor.ZONE_OFFSET_ABSENT)
        {
            cursor.rollback(checkpoint);
            return null;
        }

        assertNoMoreChars(cursor, parseConfig, cursor.index() - 1);
        // The zulu form must yield the UTC singleton: TimezoneOffset.getRequiredLength() is 1 only for it
        if (ParseCursor.zoneOffsetConsumed(packed) == 1)
        {
            return TimezoneOffset.UTC;
        }
        return TimezoneOffset.ofHoursMinutes(ParseCursor.zoneOffsetHours(packed), ParseCursor.zoneOffsetMinutes(packed));
    }

    private static void assertNoMoreChars(final ParseCursor cursor, final ParseConfig parseConfig, final int lastUsed)
    {
        if (parseConfig.isFailOnTrailingJunk() && cursor.startOffset() == 0)
        {
            if (cursor.length() > lastUsed + 1)
            {
                throw ErrorUtil.raiseTrailingJunk(cursor.text(), lastUsed);
            }
        }
    }

    public static DateTime parseLenient(final String chars, final ParseConfig parseConfig, int offset)
    {
        final ParseCursor cursor = ParseCursor.of(chars, offset);

        // Date portion

        // YEAR
        final int years = cursor.parse4();
        if (!cursor.hasRemaining())
        {
            return new DateTime(Field.YEAR, years, 0, 0, 0, 0, 0, 0, null, 0, cursor.index() - offset);
        }

        // MONTH
        cursor.expect(Field.MONTH, DATE_SEPARATOR);
        final int month = cursor.parse2();
        if (!cursor.hasRemaining())
        {
            return new DateTime(Field.MONTH, years, month, 0, 0, 0, 0, 0, null, 0, cursor.index() - offset);
        }

        // DAY
        cursor.expect(Field.DAY, DATE_SEPARATOR);
        final int days = cursor.parse2();
        if (!cursor.hasRemaining())
        {
            return new DateTime(Field.DAY, years, month, days, 0, 0, 0, 0, null, 0, cursor.index() - offset);
        }

        // HOURS
        assertAllowedDateTimeSeparator(cursor, parseConfig);
        final int hours = cursor.parse2();

        // MINUTES
        cursor.expect(Field.MINUTE, TIME_SEPARATOR);
        final int minutes = cursor.parse2();
        if (!cursor.hasRemaining())
        {
            // Have only minutes
            return new DateTime(Field.MINUTE, years, month, days, hours, minutes, 0, 0, null, 0, cursor.index() - offset);
        }

        // SECONDS or TIMEZONE
        return handleTime(cursor, parseConfig, years, month, days, hours, minutes);
    }

    public static int sanityCheckInputParams(String chars, int offset)
    {
        return ParseCursor.of(chars, offset).remaining();
    }

    private static DateTime handleTimeResolution(final ParseCursor cursor, ParseConfig parseConfig, int year, int month, int day, int hour, int minute)
    {
        if (cursor.remaining() > 2)
        {
            // The seconds are parsed after the timezone, preserving the historical error precedence
            final int secondsIndex = cursor.index();
            final char c = cursor.peek(2);
            if (parseConfig.isFractionSeparator(c))
            {
                return handleFractionalSeconds(cursor, parseConfig, year, month, day, hour, minute);
            }
            else if (c == ZULU_UPPER || c == ZULU_LOWER)
            {
                cursor.advance(3);
                assertNoMoreChars(cursor, parseConfig, cursor.index() - 1);
                final int seconds = LimitedCharArrayIntegerUtil.parse2(cursor.text(), secondsIndex);
                return handleSecondResolution(year, month, day, hour, minute, seconds, TimezoneOffset.UTC);
            }
            else if (c == PLUS || c == MINUS)
            {
                cursor.advance(2);
                final TimezoneOffset timezoneOffset = parseTimezone(cursor, parseConfig);
                final int seconds = LimitedCharArrayIntegerUtil.parse2(cursor.text(), secondsIndex);
                return handleSecondResolution(year, month, day, hour, minute, seconds, timezoneOffset);
            }
            else
            {
                throw raiseUnexpectedCharacter(cursor.text(), cursor.index() + 2, ArrayUtils.merge(parseConfig.getFractionSeparators(), new char[]{ZULU_UPPER, ZULU_LOWER, PLUS, MINUS}));
            }
        }
        else if (cursor.remaining() == 2)
        {
            final int seconds = cursor.parse2();
            return new DateTime(Field.SECOND, year, month, day, hour, minute, seconds, 0, null, 0, cursor.index() - cursor.startOffset());
        }

        throw raiseUnexpectedEndOfText(cursor.text(), cursor.index() - 1);
    }

    private static DateTime handleSecondResolution(int year, int month, int day, int hour, int minute, int seconds, TimezoneOffset timezoneOffset)
    {
        final int charLength = Field.SECOND.getRequiredLength() + (timezoneOffset != null ? timezoneOffset.getRequiredLength() : 0);
        return new DateTime(Field.SECOND, year, month, day, hour, minute, seconds, 0, timezoneOffset, 0, charLength);
    }

    private static DateTime handleFractionalSeconds(ParseCursor cursor, ParseConfig parseConfig, int year, int month, int day, int hour, int minute)
    {
        // The seconds are parsed after the timezone, preserving the historical error precedence
        final int secondsIndex = cursor.index();
        cursor.advance(2);
        cursor.consume(); // The fraction separator, verified by the caller

        final int fractionStart = cursor.index();
        final int fractionValue = cursor.consumeDigits(MAX_FRACTION_DIGITS);
        final int fractionDigits = cursor.index() - fractionStart;
        assertFractionDigits(cursor.text(), fractionDigits, cursor.index() - 1);

        // Scale to nanoseconds
        final int nanos = fractionValue * NANO_SCALE[fractionDigits];

        final TimezoneOffset timezoneOffset = parseTimezone(cursor, parseConfig);
        final int charLength = cursor.index() - cursor.startOffset();
        final int second = LimitedCharArrayIntegerUtil.parse2(cursor.text(), secondsIndex);
        return new DateTime(Field.NANO, year, month, day, hour, minute, second, nanos, timezoneOffset, fractionDigits, charLength);
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
        throw ErrorUtil.raiseMissingGranularity(nextGranularity, chars, field.getRequiredLength());
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
