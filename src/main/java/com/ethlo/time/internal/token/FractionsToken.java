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

import static com.ethlo.time.internal.ParseFailure.assertFractionDigits;
import static com.ethlo.time.internal.fixed.ITUParser.MAX_FRACTION_DIGITS;
import static com.ethlo.time.internal.util.LimitedCharArrayIntegerUtil.DIGIT_9;
import static com.ethlo.time.internal.util.LimitedCharArrayIntegerUtil.ZERO;

import java.text.ParsePosition;

import com.ethlo.time.Field;
import com.ethlo.time.internal.Cursor;
import com.ethlo.time.token.DateTimeToken;

public class FractionsToken implements DateTimeToken
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
     * Reads the fraction digits at the cursor position, advancing the cursor past them.
     */
    public int read(final Cursor cursor)
    {
        // Hot digit run: straight-line local index, re-syncing the cursor when the run ends
        final String text = cursor.text();
        final int startIndex = cursor.position();
        int idx = startIndex;
        final int length = text.length();
        int value = 0;
        while (idx < length)
        {
            final char c = text.charAt(idx);
            if (c < ZERO || c > DIGIT_9)
            {
                break;
            }
            if (idx - startIndex < MAX_FRACTION_DIGITS)
            {
                // Beyond the maximum the value is rejected below, so avoid overflowing the accumulator
                value = value * 10 + (c - ZERO);
            }
            idx++;
        }
        cursor.reset(idx);

        // NOTE: The fixed-format parser has always enforced this. Without it here the accumulator silently
        // overflowed and the resulting nano value exceeded a second.
        assertFractionDigits(text, idx - startIndex, Math.max(startIndex, idx - 1));

        return value;
    }

    @Override
    public Field getField()
    {
        return Field.NANO;
    }
}
