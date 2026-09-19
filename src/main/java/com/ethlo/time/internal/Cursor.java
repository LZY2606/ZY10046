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

import java.time.format.DateTimeParseException;

import com.ethlo.time.Field;

/**
 * Shared read cursor for the fixed date-time parser, the token-based parser and the duration
 * parser. It centralises the boundary checks and index advancement that used to be open-coded
 * in each parser.
 *
 * <p>Positions are 0-based UTF-16 char offsets into the input string, i.e. the same unit as
 * {@link String#charAt(int)} and {@link java.time.format.DateTimeParseException#getErrorIndex()}.
 * Messages rendered for users keep the historical 1-based "position" wording.</p>
 *
 * <p>A cursor is created per parse call and never leaves the calling thread, so the sharing
 * of this class does not affect the thread-safety of the parsers.</p>
 *
 * <p><b>Checkpoints and failure merging:</b> {@link #checkpoint()}/{@link #reset(int)} exist for
 * candidate (optional) tokens only. Rolling back never clears the farthest recorded failure
 * position, so a confirmed branch that fails later cannot swallow an error that was observed
 * further ahead. See {@link #recordFailure(int)} and {@link #furthestFailure()}.</p>
 */
public final class Cursor
{
    private final String text;
    private final int start;
    private final int end;
    private int pos;
    private int furthestFailure = -1;

    public Cursor(final String text, final int offset)
    {
        this.text = text;
        this.start = offset;
        this.pos = offset;
        this.end = text.length();
    }

    public String text()
    {
        return text;
    }

    /**
     * @return The offset this cursor started from
     */
    public int startOffset()
    {
        return start;
    }

    /**
     * @return The current 0-based char position
     */
    public int position()
    {
        return pos;
    }

    public int remaining()
    {
        return end - pos;
    }

    public boolean hasRemaining()
    {
        return pos < end;
    }

    /**
     * @return The char at the current position. Callers must ensure {@link #hasRemaining()}.
     */
    public char peek()
    {
        return text.charAt(pos);
    }

    /**
     * @return The char {@code ahead} positions after the current one. Callers must ensure it exists.
     */
    public char peek(final int ahead)
    {
        return text.charAt(pos + ahead);
    }

    /**
     * @return The char at the current position, advancing the position by one
     */
    public char consume()
    {
        return text.charAt(pos++);
    }

    public void advance(final int count)
    {
        pos += count;
    }

    /**
     * Consumes the expected character. This is the shared boundary check previously open-coded
     * as "index vs length, then char comparison" in each parser.
     *
     * @param expected The expected character
     * @param field    The field being read, used for the missing-field error if the input is exhausted
     */
    public void expect(final char expected, final Field field)
    {
        if (pos >= end)
        {
            throw missingFieldFailure(field);
        }
        if (text.charAt(pos) != expected)
        {
            throw unexpectedCharFailure(expected);
        }
        pos++;
    }

    // Cold failure paths, extracted so that expect() stays small enough to always inline
    private DateTimeParseException missingFieldFailure(final Field field)
    {
        recordFailure(pos);
        return ParseFailure.missingField(field, text, pos);
    }

    private DateTimeParseException unexpectedCharFailure(final char expected)
    {
        recordFailure(pos);
        return ParseFailure.expectedCharacter(text, pos, expected);
    }

    /**
     * @return A marker of the current position, for a candidate token that may be rolled back
     */
    public int checkpoint()
    {
        return pos;
    }

    /**
     * Repositions the cursor: either back to a position previously obtained from
     * {@link #checkpoint()} (reserved for candidate tokens), or forward to re-sync after a
     * straight-line digit run that advanced a local index. The farthest recorded failure
     * position is deliberately retained in both cases, so rolling back a candidate cannot
     * hide a farther error.
     */
    public void reset(final int checkpoint)
    {
        this.pos = checkpoint;
    }

    /**
     * Records the position of a failure. Used to merge error positions across candidate
     * branches: the farthest position wins.
     */
    public void recordFailure(final int position)
    {
        if (position > furthestFailure)
        {
            furthestFailure = position;
        }
    }

    /**
     * @return The farthest recorded failure position, or -1 if none was recorded
     */
    public int furthestFailure()
    {
        return furthestFailure;
    }
}
