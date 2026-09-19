package com.ethlo.time.internal.token;

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

import static com.ethlo.time.internal.fixed.ITUParser.MINUS;
import static com.ethlo.time.internal.fixed.ITUParser.PLUS;
import static com.ethlo.time.internal.fixed.ITUParser.TIME_SEPARATOR;
import static com.ethlo.time.internal.fixed.ITUParser.ZULU_LOWER;
import static com.ethlo.time.internal.fixed.ITUParser.ZULU_UPPER;
import static com.ethlo.time.internal.util.LimitedCharArrayIntegerUtil.parse2;

import java.text.ParsePosition;

import com.ethlo.time.Field;
import com.ethlo.time.internal.Cursor;
import com.ethlo.time.internal.ParseFailure;
import com.ethlo.time.token.DateTimeToken;

public class ZoneOffsetToken implements DateTimeToken
{
    @Override
    public int read(final String text, final ParsePosition parsePosition)
    {
        final Cursor cursor = new Cursor(text, parsePosition.getIndex());
        final int value = read(cursor);
        parsePosition.setIndex(cursor.position());
        return value;
    }

    /**
     * Reads the zone offset at the cursor position. The zone offset is a candidate token: if the
     * input is exhausted the cursor is left where it was and {@code -1} is returned. Once a
     * zone-offset introducer (Z/z/+/-) is seen the branch is confirmed and any failure further
     * ahead is propagated, never rolled back.
     */
    public int read(final Cursor cursor)
    {
        final int checkpoint = cursor.checkpoint();
        if (!cursor.hasRemaining())
        {
            cursor.reset(checkpoint);
            return -1;
        }

        final char c = cursor.peek();
        if (c == ZULU_UPPER || c == ZULU_LOWER)
        {
            cursor.consume();
            return 0;
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

        return hours * 3600 + minutes * 60;
    }

    @Override
    public Field getField()
    {
        return Field.ZONE_OFFSET;
    }
}
