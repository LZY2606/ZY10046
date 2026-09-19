package com.ethlo.time;

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

import java.text.ParsePosition;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.ethlo.time.token.ConfigurableDateTimeParser;

/**
 * Behaviour guards for the shared parse-cursor refactoring. These pin the observable contract of all
 * three parser entries (fixed RFC-3339, token-based configurable, duration): exception type, error
 * index (0-based UTF-16 code-unit index into the original input) and the core message. Positions were
 * captured from the pre-refactoring implementation and must not drift.
 */
public class ParserBehaviorGuardTest
{
    private static final DateTimeParser YEAR_MONTH = ConfigurableDateTimeParser.of(
            DateTimeTokens.digits(Field.YEAR, 4),
            DateTimeTokens.separators('-'),
            DateTimeTokens.digits(Field.MONTH, 2));

    @AfterEach
    void banner(TestInfo testInfo)
    {
        // Printed to stdout so the guard names are visible even under `mvn -q test`
        System.out.println("GUARD-OK " + testInfo.getDisplayName());
    }

    private static void assertFailure(String input, int expectedErrorIndex, String messagePart, ThrowingParser parser)
    {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(DateTimeParseException.class)
                .satisfies(exc ->
                {
                    final DateTimeParseException parseException = (DateTimeParseException) exc;
                    assertThat(parseException.getErrorIndex()).as("error index for %s", input).isEqualTo(expectedErrorIndex);
                    assertThat(parseException.getMessage()).as("message for %s", input).contains(messagePart);
                });
    }

    private interface ThrowingParser
    {
        void parse(String input);
    }

    @Nested
    @DisplayName("fixed RFC-3339 parser")
    class FixedParser
    {
        @Test
        @DisplayName("fixed: error at input start")
        void errorAtInputStart()
        {
            assertFailure("x017-02-21T15:27:39Z", 0, "Expected character", ITU::parseLenient);
        }

        @Test
        @DisplayName("fixed: error in the middle of a token")
        void errorMidToken()
        {
            assertFailure("2017-0x-21T15:27:39Z", 6, "Expected character", ITU::parseLenient);
        }

        @Test
        @DisplayName("fixed: truncated at end of input")
        void truncatedAtEnd()
        {
            assertFailure("2017-02-21T15:2", 14, "Unexpected end of input", ITU::parseLenient);
        }

        @Test
        @DisplayName("fixed: unpaired surrogate is not a valid token char")
        void unpairedSurrogate()
        {
            assertFailure("2017-02-21T15:27:39\uD800", 19, "Expected character", ITU::parseLenient);
        }

        @Test
        @DisplayName("fixed: non-ASCII unicode digit is rejected")
        void unicodeDigit()
        {
            assertFailure("2017-٠2-21T15:27:39Z", 5, "Expected character", ITU::parseLenient);
            assertFailure("2017-02-21T15:27:39.٥Z", 19, "Must have at least 1 fraction digit", ITU::parseLenient);
        }

        @Test
        @DisplayName("fixed: truncated and malformed zone offset")
        void offsetErrors()
        {
            assertFailure("2017-02-21T15:27:39+", 19, "Invalid timezone offset", ITU::parseLenient);
            assertFailure("2017-02-21T15:27:39+0x:30", 21, "Expected character", ITU::parseLenient);
            assertFailure("2017-02-21T15:27:39+08:3x", 24, "Expected character", ITU::parseLenient);
        }

        @Test
        @DisplayName("fixed: trailing characters after zulu")
        void trailingCharacters()
        {
            assertFailure("2017-02-21T15:27:39Zjunk", 20, "Trailing junk", ITU::parseLenient);
        }

        @Test
        @DisplayName("fixed: zulu parse length is 17 and yields the UTC singleton")
        void zuluParseLength()
        {
            final String input = "abc,2004-11-21T00:00Z1999-11-22T11:22+05:00";
            final ParsePosition position = new ParsePosition(4);
            final DateTime dateTime = ITU.parseLenient(input, ParseConfig.DEFAULT, position);
            assertThat(dateTime.getParseLength()).isEqualTo(17);
            assertThat(position.getIndex()).isEqualTo(21);
            assertThat(dateTime.getOffset()).hasValue(TimezoneOffset.UTC);
        }

        @Test
        @DisplayName("fixed: too many fraction digits")
        void tooManyFractionDigits()
        {
            assertFailure("2017-02-21T15:27:39.1234567890Z", 29, "Maximum supported number of fraction digits", ITU::parseLenient);
        }
    }

    @Nested
    @DisplayName("duration parser")
    class DurationParser
    {
        @Test
        @DisplayName("duration: sign without value")
        void signWithoutValue()
        {
            assertFailure("-", 1, "Expected at least one value and unit", ITU::parseDuration);
            assertFailure("-P", 2, "Expected at least one value and unit", ITU::parseDuration);
        }

        @Test
        @DisplayName("duration: negative duration parses with sign absorbed")
        void negativeDuration()
        {
            final Duration duration = ITU.parseDuration("-PT2.5S");
            assertThat(duration.getSeconds()).isEqualTo(-3);
            assertThat(duration.getNanos()).isEqualTo(500_000_000);
        }

        @Test
        @DisplayName("duration: empty designator")
        void emptyDesignator()
        {
            assertFailure("P", 1, "Expected at least one value and unit", ITU::parseDuration);
            assertFailure("PT", 2, "Expected at least one value and unit after the 'T'", ITU::parseDuration);
        }

        @Test
        @DisplayName("duration: invalid unit at token end")
        void invalidUnit()
        {
            assertFailure("P1X", 2, "Invalid unit: X", ITU::parseDuration);
        }

        @Test
        @DisplayName("duration: trailing characters are consumed as invalid unit")
        void trailingCharacters()
        {
            assertFailure("PT5S junk", 4, "Invalid unit", ITU::parseDuration);
            assertFailure("P1DT2H3M4S5", 11, "No unit defined for value 5", ITU::parseDuration);
        }

        @Test
        @DisplayName("duration: misplaced sign inside value")
        void misplacedSign()
        {
            assertFailure("PT-5S", 2, "Invalid unit: -", ITU::parseDuration);
        }

        @Test
        @DisplayName("duration: non-ASCII unicode digit is rejected")
        void unicodeDigit()
        {
            assertFailure("P٣D", 1, "Invalid unit", ITU::parseDuration);
        }

        @Test
        @DisplayName("duration: unpaired surrogate is not a valid unit")
        void unpairedSurrogate()
        {
            assertFailure("P1D\uD800", 3, "Invalid unit", ITU::parseDuration);
        }
    }

    @Nested
    @DisplayName("configurable token parser")
    class TokenParser
    {
        @Test
        @DisplayName("token: error at input start")
        void errorAtInputStart()
        {
            assertFailure("x017-02", 0, "Expected character", s -> YEAR_MONTH.parse(s, new ParsePosition(0)));
        }

        @Test
        @DisplayName("token: error in the middle of a token")
        void errorMidToken()
        {
            assertFailure("2017-0x", 6, "Expected character", s -> YEAR_MONTH.parse(s, new ParsePosition(0)));
        }

        @Test
        @DisplayName("token: truncated at end of input")
        void truncatedAtEnd()
        {
            assertFailure("2017-", 5, "Unexpected end of input", s -> YEAR_MONTH.parse(s, new ParsePosition(0)));
        }

        @Test
        @DisplayName("token: non-ASCII unicode digit is rejected")
        void unicodeDigit()
        {
            assertFailure("2017-٠2", 5, "Expected character", s -> YEAR_MONTH.parse(s, new ParsePosition(0)));
        }

        @Test
        @DisplayName("token: separator mismatch")
        void separatorMismatch()
        {
            assertFailure("2017x02", 4, "Expected character", s -> YEAR_MONTH.parse(s, new ParsePosition(0)));
        }
    }

    @Nested
    @DisplayName("cross-entry consistency")
    class CrossEntry
    {
        @Test
        @DisplayName("same illegal char reports the same position in fixed and token parsers")
        void samePositionAcrossEntries()
        {
            final DateTimeParseException fixed = catchFailure(() -> ITU.parseLenient("2017-0x-21T15:27:39Z"));
            final DateTimeParseException token = catchFailure(() -> YEAR_MONTH.parse("2017-0x", new ParsePosition(0)));
            assertThat(token.getErrorIndex()).isEqualTo(fixed.getErrorIndex());
        }

        @Test
        @DisplayName("truncation reports the same position in fixed and token parsers")
        void sameTruncationPositionAcrossEntries()
        {
            final DateTimeParseException fixed = catchFailure(() -> ITU.parseLenient("2017-"));
            final DateTimeParseException token = catchFailure(() -> YEAR_MONTH.parse("2017-", new ParsePosition(0)));
            assertThat(token.getErrorIndex()).isEqualTo(fixed.getErrorIndex());
        }

        private DateTimeParseException catchFailure(Runnable parser)
        {
            try
            {
                parser.run();
            }
            catch (DateTimeParseException exc)
            {
                return exc;
            }
            throw new AssertionError("Expected DateTimeParseException");
        }
    }

    @Nested
    @DisplayName("format round-trip")
    class FormatRoundTrip
    {
        @Test
        @DisplayName("parse-format-parse keeps the instant and the text")
        void parseFormatParse()
        {
            final String text = "2017-02-21T15:27:39.123456789+08:30";
            final OffsetDateTime parsed = ITU.parseDateTime(text);
            final String formatted = ITU.format(parsed, 9);
            assertThat(formatted).isEqualTo(text);
            assertThat(ITU.parseDateTime(formatted)).isEqualTo(parsed);
        }
    }
}
