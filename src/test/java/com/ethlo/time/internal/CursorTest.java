package com.ethlo.time.internal;

/*-
 * #%L
 * Internet Time Utility
 * %%
 * Copyright (C) 2017 - 2026 Morten Haraldsen @ethlo
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Test;

import com.ethlo.time.Field;

/**
 * Unit tests for the shared cursor and its failure-merging contract.
 */
public class CursorTest
{
    @Test
    void consumePeekAndAdvance()
    {
        final Cursor cursor = new Cursor("abcd", 1);
        assertThat(cursor.position()).isEqualTo(1);
        assertThat(cursor.startOffset()).isEqualTo(1);
        assertThat(cursor.remaining()).isEqualTo(3);
        assertThat(cursor.peek()).isEqualTo('b');
        assertThat(cursor.peek(1)).isEqualTo('c');
        assertThat(cursor.consume()).isEqualTo('b');
        assertThat(cursor.position()).isEqualTo(2);
        cursor.advance(2);
        assertThat(cursor.hasRemaining()).isFalse();
    }

    @Test
    void expectConsumesMatchingCharacter()
    {
        final Cursor cursor = new Cursor("a-b", 0);
        cursor.consume();
        cursor.expect('-', Field.MONTH);
        assertThat(cursor.position()).isEqualTo(2);
    }

    @Test
    void expectFailsOnMismatchWithCharPosition()
    {
        final Cursor cursor = new Cursor("axb", 0);
        cursor.consume();
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> cursor.expect('-', Field.MONTH));
        assertThat(exc.getErrorIndex()).isEqualTo(1);
        assertThat(exc.getMessage()).startsWith("Expected character - at position 2, found x");
    }

    @Test
    void expectFailsOnExhaustedInputWithMissingField()
    {
        final Cursor cursor = new Cursor("ab", 2);
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> cursor.expect('-', Field.MONTH));
        assertThat(exc.getErrorIndex()).isEqualTo(2);
        assertThat(exc.getMessage()).startsWith("Unexpected end of input, missing field MONTH");
    }

    @Test
    void checkpointResetRestoresPosition()
    {
        final Cursor cursor = new Cursor("abcdef", 0);
        final int checkpoint = cursor.checkpoint();
        cursor.advance(3);
        cursor.reset(checkpoint);
        assertThat(cursor.position()).isEqualTo(0);
    }

    @Test
    void resetRetainsFurthestRecordedFailure()
    {
        // Simulates a candidate token that advances, fails far ahead and is rolled back:
        // the farther failure position must survive the rollback
        final Cursor cursor = new Cursor("0123456789", 0);
        final int checkpoint = cursor.checkpoint();
        cursor.advance(8);
        cursor.recordFailure(8);
        cursor.reset(checkpoint);
        assertThat(cursor.position()).isEqualTo(0);
        assertThat(cursor.furthestFailure()).isEqualTo(8);
    }

    @Test
    void mergePrefersFarthestFailurePosition()
    {
        final DateTimeParseException failure = ParseFailure.unexpectedEndOfText("abcdef", 3);
        final DateTimeParseException merged = ParseFailure.merge(failure, 8);
        assertThat(merged.getErrorIndex()).isEqualTo(8);
        // Type, message and parsed data are preserved
        assertThat(merged.getMessage()).isEqualTo(failure.getMessage());
        assertThat(merged.getParsedString()).isEqualTo(failure.getParsedString());
    }

    @Test
    void mergeKeepsOriginalFailureWhenItIsFarthest()
    {
        final DateTimeParseException failure = ParseFailure.unexpectedEndOfText("abcdef", 5);
        assertThat(ParseFailure.merge(failure, 3)).isSameAs(failure);
        assertThat(ParseFailure.merge(failure, -1)).isSameAs(failure);
    }

    @Test
    void expectRecordsFailurePositionOnCursor()
    {
        final Cursor cursor = new Cursor("ax", 0);
        cursor.consume();
        assertThrows(DateTimeParseException.class, () -> cursor.expect('-', Field.DAY));
        assertThat(cursor.furthestFailure()).isEqualTo(1);
    }
}
