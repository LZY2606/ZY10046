package com.ethlo.time.internal.util;

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

import java.time.format.DateTimeParseException;

import com.ethlo.time.Field;

/**
 * Shared internal cursor over the input being parsed, plus the allocation-free static primitives the
 * results are built from. The fixed-format parser and the duration parser consume input through cursor
 * instances; the token-based parser's hot tokens consume through the static primitives ({@link #digitRun}
 * and {@link #parseZoneOffset}), which carry the exact same logic. Boundary checks, index advancement and
 * failure positions therefore live in exactly one place.
 * <p>
 * Position unit: all positions are 0-based UTF-16 code-unit indices into the original input string, matching
 * {@link java.text.ParsePosition} and {@link java.time.format.DateTimeParseException#getErrorIndex()}. An
 * end-of-input position may equal {@code text.length()}.
 * <p>
 * Checkpoint/rollback is intended for <b>candidate tokens only</b>: a parser may snapshot the position,
 * attempt an optional construct, and roll back if the construct turns out to be absent. A branch that has
 * been confirmed (a decisive character was consumed) must let its failures propagate rather than rolling
 * back, so a confirmed branch never swallows an error detected further into the input. To support merging,
 * {@link #rollback(int)} deliberately keeps the farthest recorded failure position; see
 * {@link #noteFailure(int)} and {@link #mergedErrorIndex(int)}.
 * <p>
 * Instances are mutable, confined to a single parse and never shared between threads, so the thread-safety
 * properties of the parsers (stateless, safe for concurrent use) are unchanged.
 */
public final class ParseCursor
{
    /**
     * Marker returned by {@link #parseZoneOffset} and {@link #consumeZoneOffset()} when no characters remain
     */
    public static final long ZONE_OFFSET_ABSENT = Long.MIN_VALUE;

    private final String text;
    private final int startOffset;
    private int index;
    private int farthestErrorIndex = -1;

    private ParseCursor(final String text, final int offset)
    {
        this.text = text;
        this.startOffset = offset;
        this.index = offset;
    }

    /**
     * Creates a cursor after validating the arguments, matching the historical sanity checks of the
     * parser entry points.
     *
     * @param text   The input text
     * @param offset The offset to start parsing at
     * @return The cursor
     * @throws NullPointerException      if {@code text} is null
     * @throws IndexOutOfBoundsException if {@code offset} is negative or larger than the input length
     */
    public static ParseCursor of(final String text, final int offset)
    {
        if (text == null)
        {
            throw new NullPointerException("text cannot be null");
        }
        if (text.length() - offset < 0)
        {
            throw new IndexOutOfBoundsException(String.format("offset is %d which is equal to or larger than the input length of %d", offset, text.length()));
        }
        if (offset < 0)
        {
            throw new IndexOutOfBoundsException(String.format("offset cannot be negative, was %d", offset));
        }
        return new ParseCursor(text, offset);
    }

    public String text()
    {
        return text;
    }

    /**
     * @return The offset this cursor was created with
     */
    public int startOffset()
    {
        return startOffset;
    }

    public int index()
    {
        return index;
    }

    public int length()
    {
        return text.length();
    }

    public int remaining()
    {
        return text.length() - index;
    }

    public boolean hasRemaining()
    {
        return index < text.length();
    }

    /**
     * @return The character at the current position. Callers ensure {@link #hasRemaining()}.
     */
    public char peek()
    {
        return text.charAt(index);
    }

    /**
     * @return The character {@code ahead} positions after the current one. Callers ensure it is in bounds.
     */
    public char peek(final int ahead)
    {
        return text.charAt(index + ahead);
    }

    /**
     * @return The character at the current position after advancing past it
     */
    public char consume()
    {
        return text.charAt(index++);
    }

    public void advance(final int count)
    {
        index += count;
    }

    public boolean consumeIf(final char c)
    {
        if (hasRemaining() && text.charAt(index) == c)
        {
            index++;
            return true;
        }
        return false;
    }

    /**
     * @return A marker of the current position, for use with {@link #rollback(int)}
     */
    public int checkpoint()
    {
        return index;
    }

    /**
     * Restores the position to a previously taken {@link #checkpoint()}. Only for abandoning a candidate
     * token; the farthest recorded failure position is intentionally kept so that a retried branch cannot
     * hide an error that was detected further into the input.
     */
    public void rollback(final int checkpoint)
    {
        index = checkpoint;
    }

    /**
     * Records a failure position for later merging. Positions are merged by keeping the farthest one.
     */
    public void noteFailure(final int errorIndex)
    {
        if (errorIndex > farthestErrorIndex)
        {
            farthestErrorIndex = errorIndex;
        }
    }

    /**
     * @param candidate The error position about to be reported
     * @return The farther of {@code candidate} and any position recorded via {@link #noteFailure(int)}
     */
    public int mergedErrorIndex(final int candidate)
    {
        return Math.max(candidate, farthestErrorIndex);
    }

    /**
     * Asserts that the character at the current position is {@code expected}, then consumes it.
     * Shares its failure behaviour with {@link ErrorUtil#assertPositionContains}.
     */
    public void expect(final Field field, final char expected)
    {
        ErrorUtil.assertPositionContains(field, text, index, expected);
        index++;
    }

    /**
     * Consumes exactly two ASCII digits. See {@link LimitedCharArrayIntegerUtil#parse2(String, int)}.
     */
    public int parse2()
    {
        final int value = LimitedCharArrayIntegerUtil.parse2(text, index);
        index += 2;
        return value;
    }

    /**
     * Consumes exactly four ASCII digits. See {@link LimitedCharArrayIntegerUtil#parse4(String, int)}.
     */
    public int parse4()
    {
        final int value = LimitedCharArrayIntegerUtil.parse4(text, index);
        index += 4;
        return value;
    }

    /**
     * Consumes a run of ASCII digits at the cursor, see {@link #digitRun(String, int, int)}.
     *
     * @return The accumulated value of the first {@code maxAccumulated} digits
     */
    public int consumeDigits(final int maxAccumulated)
    {
        final long run = digitRun(text, index, maxAccumulated);
        index += digitRunLength(run);
        return digitRunValue(run);
    }

    /**
     * Reads a run of ASCII digits starting at {@code start}. Up to {@code maxAccumulated} leading digits
     * are accumulated into the value; the full run is always measured, so the caller can reject over-long
     * runs using {@link #digitRunLength(long)}.
     *
     * @return The run, packed as {@code (length << 32) | value}; unpack with {@link #digitRunLength(long)}
     * and {@link #digitRunValue(long)}
     */
    public static long digitRun(final String input, final int start, final int maxAccumulated)
    {
        final int end = input.length();
        int idx = start;
        int consumed = 0;
        int value = 0;
        while (idx < end)
        {
            final int digit = input.charAt(idx) - LimitedCharArrayIntegerUtil.ZERO;
            if (digit < 0 || digit > 9)
            {
                break;
            }
            consumed++;
            if (consumed <= maxAccumulated)
            {
                // Beyond the maximum the caller rejects the value, so avoid overflowing the accumulator
                value = value * 10 + digit;
            }
            idx++;
        }
        return ((long) consumed << 32) | (value & 0xFFFFFFFFL);
    }

    /**
     * @param packed The value returned by {@link #digitRun(String, int, int)}
     * @return The number of digits in the run
     */
    public static int digitRunLength(final long packed)
    {
        return (int) (packed >>> 32);
    }

    /**
     * @param packed The value returned by {@link #digitRun(String, int, int)}
     * @return The accumulated value of the run
     */
    public static int digitRunValue(final long packed)
    {
        return (int) packed;
    }

    /**
     * Consumes a zone offset ({@code Z}, {@code z} or {@code +/-HH:MM}) at the cursor, advancing past it.
     *
     * @return The packed offset, or {@link #ZONE_OFFSET_ABSENT} if no characters remain (the candidate
     * token is absent, in which case the position is left untouched)
     */
    public long consumeZoneOffset()
    {
        try
        {
            final long packed = parseZoneOffset(text, index);
            if (packed != ZONE_OFFSET_ABSENT)
            {
                index += zoneOffsetConsumed(packed);
            }
            return packed;
        }
        catch (DateTimeParseException exc)
        {
            noteFailure(exc.getErrorIndex());
            throw exc;
        }
    }

    /**
     * Reads a zone offset ({@code Z}, {@code z} or {@code +/-HH:MM}) at {@code start}. The result packs
     * the consumed length and the raw hour/minute parts, so callers do not pay for constructing and
     * validating a {@code TimezoneOffset} on the hot path; they unpack with {@link #zoneOffsetConsumed(long)},
     * {@link #zoneOffsetHours(long)} and {@link #zoneOffsetMinutes(long)} and build whatever representation
     * they need.
     *
     * @return The packed offset, or {@link #ZONE_OFFSET_ABSENT} if no characters remain at {@code start}
     */
    public static long parseZoneOffset(final String text, final int start)
    {
        if (start >= text.length())
        {
            return ZONE_OFFSET_ABSENT;
        }

        final char c = text.charAt(start);
        if (c == 'Z' || c == 'z')
        {
            return packZoneOffset(1, 0, 0);
        }

        if (c != '+' && c != '-')
        {
            throw ErrorUtil.raiseUnexpectedCharacter(text, start, 'Z', 'z', '+', '-');
        }

        if (text.length() - start < 6)
        {
            throw ErrorUtil.raiseInvalidTimezoneOffset(text, start);
        }

        ErrorUtil.assertPositionContains(Field.ZONE_OFFSET, text, start + 3, ':');

        int hours = LimitedCharArrayIntegerUtil.parse2(text, start + 1);
        int minutes = LimitedCharArrayIntegerUtil.parse2(text, start + 4);
        if (c == '-')
        {
            hours = -hours;
            minutes = -minutes;

            if (hours == 0 && minutes == 0)
            {
                throw ErrorUtil.raiseUnknownLocalOffsetConvention(text, start);
            }
        }

        return packZoneOffset(6, hours, minutes);
    }

    private static long packZoneOffset(final int consumed, final int hours, final int minutes)
    {
        return ((long) consumed << 48) | ((long) (hours & 0xFFFF) << 32) | (minutes & 0xFFFFFFFFL);
    }

    /**
     * @param packed The value returned by {@link #parseZoneOffset(String, int)}
     * @return The number of characters the offset occupies
     */
    public static int zoneOffsetConsumed(final long packed)
    {
        return (int) (packed >>> 48);
    }

    /**
     * @param packed The value returned by {@link #parseZoneOffset(String, int)}
     * @return The hour part of the offset
     */
    public static int zoneOffsetHours(final long packed)
    {
        return (short) (packed >>> 32);
    }

    /**
     * @param packed The value returned by {@link #parseZoneOffset(String, int)}
     * @return The minute part of the offset
     */
    public static int zoneOffsetMinutes(final long packed)
    {
        return (int) packed;
    }
}
