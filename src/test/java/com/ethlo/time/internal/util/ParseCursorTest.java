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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;


/**
 * Guards for the shared cursor contract: consume/peek/checkpoint and the error-position merge rules.
 * Checkpoint rollback exists for candidate tokens only; a confirmed branch must never roll back and
 * thereby swallow an error detected further into the input.
 */
public class ParseCursorTest
{
    @AfterEach
    void banner(TestInfo testInfo)
    {
        // Printed to stdout so the guard names are visible even under `mvn -q test`
        System.out.println("GUARD-OK " + testInfo.getDisplayName());
    }

    @Test
    @DisplayName("cursor: consume and peek advance predictably")
    void consumeAndPeek()
    {
        final ParseCursor cursor = ParseCursor.of("2017-02", 0);
        assertThat(cursor.peek()).isEqualTo('2');
        assertThat(cursor.peek(1)).isEqualTo('0');
        assertThat(cursor.consume()).isEqualTo('2');
        assertThat(cursor.index()).isEqualTo(1);
        assertThat(cursor.remaining()).isEqualTo(6);
        cursor.advance(3);
        assertThat(cursor.peek()).isEqualTo('-');
        assertThat(cursor.consumeIf('-')).isTrue();
        assertThat(cursor.consumeIf('x')).isFalse();
        assertThat(cursor.index()).isEqualTo(5);
    }

    @Test
    @DisplayName("cursor: checkpoint rollback restores the position for a candidate token")
    void rollbackRestoresPosition()
    {
        final ParseCursor cursor = ParseCursor.of("PT1S", 0);
        cursor.advance(2);
        final int checkpoint = cursor.checkpoint();
        cursor.advance(2);
        assertThat(cursor.index()).isEqualTo(4);
        cursor.rollback(checkpoint);
        assertThat(cursor.index()).isEqualTo(2);
        assertThat(cursor.peek()).isEqualTo('1');
    }

    @Test
    @DisplayName("cursor: rollback keeps the farthest recorded failure position")
    void rollbackKeepsFarthestFailure()
    {
        final ParseCursor cursor = ParseCursor.of("2017-02-21T15:27:39+", 0);
        final int checkpoint = cursor.checkpoint();
        cursor.advance(19);
        cursor.noteFailure(19);
        cursor.rollback(checkpoint);

        // The candidate branch was abandoned, but its failure position survives the rollback
        assertThat(cursor.mergedErrorIndex(0)).isEqualTo(19);
        assertThat(cursor.mergedErrorIndex(25)).isEqualTo(25);
    }

    @Test
    @DisplayName("cursor: confirmed branch failure propagates instead of being swallowed")
    void confirmedBranchFailurePropagates()
    {
        // A '+' is a decisive character: the timezone branch is confirmed, so the failure at the
        // truncated offset must propagate to the caller rather than being rolled back
        final ParseCursor cursor = ParseCursor.of("2017-02-21T15:27:39+", 0);
        cursor.advance(19);
        final int checkpoint = cursor.checkpoint();
        assertThatThrownBy(cursor::consumeZoneOffset)
                .isInstanceOf(DateTimeParseException.class)
                .satisfies(exc -> assertThat(((DateTimeParseException) exc).getErrorIndex()).isEqualTo(19));
        // No rollback happened: the caller still sees the confirmed branch position
        assertThat(cursor.index()).isEqualTo(checkpoint);
        assertThat(cursor.mergedErrorIndex(0)).isEqualTo(19);
    }

    @Test
    @DisplayName("cursor: absent candidate zone offset leaves the position untouched")
    void absentZoneOffsetIsANoOp()
    {
        final ParseCursor cursor = ParseCursor.of("2017-02-21T15:27:39", 0);
        cursor.advance(19);
        final int checkpoint = cursor.checkpoint();
        assertThat(cursor.consumeZoneOffset()).isEqualTo(ParseCursor.ZONE_OFFSET_ABSENT);
        cursor.rollback(checkpoint);
        assertThat(cursor.index()).isEqualTo(19);
    }

    @Test
    @DisplayName("cursor: zone offset is consumed with the shared position unit")
    void zoneOffsetConsumption()
    {
        final ParseCursor cursor = ParseCursor.of("2017-02-21T15:27:39+08:30", 0);
        cursor.advance(19);
        final long packed = cursor.consumeZoneOffset();
        assertThat(ParseCursor.zoneOffsetHours(packed) * 3600 + ParseCursor.zoneOffsetMinutes(packed) * 60).isEqualTo(8 * 3600 + 30 * 60);
        assertThat(cursor.index()).isEqualTo(25);
        assertThat(cursor.hasRemaining()).isFalse();
    }

    @Test
    @DisplayName("cursor: digit runs are consumed with an accumulation cap")
    void consumeDigitsCap()
    {
        final ParseCursor cursor = ParseCursor.of("123456789012", 0);
        final int value = cursor.consumeDigits(9);
        assertThat(value).isEqualTo(123456789);
        assertThat(cursor.index()).isEqualTo(12);
    }

    @Test
    @DisplayName("cursor: unicode digits and surrogates are not consumed as digits")
    void nonAsciiDigitsAreNotConsumed()
    {
        final ParseCursor unicode = ParseCursor.of("12٣4", 0);
        assertThat(unicode.consumeDigits(9)).isEqualTo(12);
        assertThat(unicode.index()).isEqualTo(2);

        final ParseCursor surrogate = ParseCursor.of("12\uD800\uDC004", 0);
        assertThat(surrogate.consumeDigits(9)).isEqualTo(12);
        assertThat(surrogate.index()).isEqualTo(2);
    }

    @Test
    @DisplayName("cursor: offset sanity checks match the historical contract")
    void offsetSanityChecks()
    {
        assertThatThrownBy(() -> ParseCursor.of(null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("text cannot be null");
        assertThatThrownBy(() -> ParseCursor.of("abc", 4))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage("offset is 4 which is equal to or larger than the input length of 3");
        assertThatThrownBy(() -> ParseCursor.of("abc", -1))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage("offset cannot be negative, was -1");
        assertThat(ParseCursor.of("abc", 3).hasRemaining()).isFalse();
    }
}
