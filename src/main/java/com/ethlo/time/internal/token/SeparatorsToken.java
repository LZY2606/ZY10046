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

import java.text.ParsePosition;
import java.util.Arrays;

import com.ethlo.time.internal.Cursor;
import com.ethlo.time.internal.ParseFailure;
import com.ethlo.time.token.DateTimeToken;

public class SeparatorsToken implements DateTimeToken
{
    private final char[] separators;

    public SeparatorsToken(char... separators)
    {
        this.separators = separators;
    }

    @Override
    public int read(final String text, final ParsePosition parsePosition)
    {
        final Cursor cursor = new Cursor(text, parsePosition.getIndex());
        read(cursor);
        parsePosition.setIndex(cursor.position());
        return 1;
    }

    /**
     * Asserts that one of the separators is at the cursor position and consumes it.
     */
    public void read(final Cursor cursor)
    {
        if (!cursor.hasRemaining())
        {
            throw ParseFailure.unexpectedEndOfText(cursor.text(), cursor.text().length());
        }

        final char c = cursor.peek();
        for (char sep : separators)
        {
            if (c == sep)
            {
                cursor.consume();
                return;
            }
        }
        throw ParseFailure.unexpectedCharacter(cursor.text(), cursor.position(), separators);
    }

    @Override
    public String toString()
    {
        return "separators: " + Arrays.toString(separators);
    }
}
