package com.ethlo.time;

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

import java.text.ParsePosition;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Test;

import com.ethlo.time.token.ConfigurableDateTimeParserTest;

/**
 * Behaviour guards for the shared cursor/failure refactoring. These pin the observable
 * behaviour (exception type, core message, 0-based error index) of all three parse entry
 * points: the fixed date-time parser, the token-based parser and the duration parser.
 */
public class SharedParserBehaviorGuardTest
{
    private final DateTimeParser tokenParser = DateTimeParsers.of(
            DateTimeTokens.digits(Field.YEAR, 4),
            DateTimeTokens.separators('-'),
            DateTimeTokens.digits(Field.MONTH, 2),
            DateTimeTokens.separators('-'),
            DateTimeTokens.digits(Field.DAY, 2),
            DateTimeTokens.separators('T', 't'),
            DateTimeTokens.digits(Field.HOUR, 2),
            DateTimeTokens.separators(':'),
            DateTimeTokens.digits(Field.MINUTE, 2),
            DateTimeTokens.separators(':'),
            DateTimeTokens.digits(Field.SECOND, 2),
            DateTimeTokens.separators('.'),
            DateTimeTokens.fractions(),
            DateTimeTokens.zoneOffset()
    );

    // --- Error at the very start of the input ---

    @Test
    void fixedParserErrorAtInputStart()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDateTime("X012-11-11T12:22:11Z"));
        assertThat(exc.getErrorIndex()).isEqualTo(0);
        assertThat(exc.getMessage()).startsWith("Expected character [0, 1, 2, 3, 4, 5, 6, 7, 8, 9] at position 1, found X");
    }

    @Test
    void tokenParserErrorAtInputStart()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> tokenParser.parse("X023-01-01T23:38:34.9Z", new ParsePosition(0)));
        assertThat(exc.getErrorIndex()).isEqualTo(0);
    }

    @Test
    void durationParserErrorAtInputStart()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDuration("XPT1S"));
        assertThat(exc.getErrorIndex()).isEqualTo(0);
        assertThat(exc.getMessage()).startsWith("Duration must start with 'P'");
    }

    // --- Error in the middle of a token ---

    @Test
    void fixedParserErrorMidToken()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDateTime("2012-1X-11T12:22:11Z"));
        assertThat(exc.getErrorIndex()).isEqualTo(6);
    }

    @Test
    void tokenParserErrorMidToken()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> tokenParser.parse("2023-0X-01T23:38:34.9Z", new ParsePosition(0)));
        assertThat(exc.getErrorIndex()).isEqualTo(6);
    }

    @Test
    void durationParserErrorMidToken()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDuration("PT1X2S"));
        assertThat(exc.getMessage()).startsWith("Invalid unit: X");
    }

    // --- Truncation at the end of the input ---

    @Test
    void fixedParserTruncatedAtEnd()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDateTime("2012-11-11T12:2"));
        assertThat(exc.getMessage()).startsWith("Unexpected end of input");
    }

    @Test
    void tokenParserTruncatedAtEnd()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> tokenParser.parse("2023-01-01T23:3", new ParsePosition(0)));
        assertThat(exc.getMessage()).startsWith("Unexpected end of input");
    }

    @Test
    void durationParserTruncatedAtEnd()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDuration("PT1"));
        assertThat(exc.getMessage()).startsWith("No unit defined for value 1");
    }

    // --- Surrogate pairs must not be mistaken for digits, separators or units ---

    @Test
    void fixedParserRejectsSurrogatePair()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDateTime("2012-11-11T12:22:11.\uD83D\uDE00"));
        assertThat(exc.getMessage()).startsWith("Must have at least 1 fraction digit");
    }

    @Test
    void fixedParserRejectsSurrogateAsDateTimeSeparator()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDateTime("2012-11-11\uD83D\uDE0012:22:11Z"));
        assertThat(exc.getErrorIndex()).isEqualTo(10);
    }

    @Test
    void tokenParserRejectsSurrogatePair()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> tokenParser.parse("2023-01-01T23:38:34.\uD83D\uDE00", new ParsePosition(0)));
        assertThat(exc.getMessage()).startsWith("Must have at least 1 fraction digit");
    }

    @Test
    void durationParserRejectsSurrogateUnit()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDuration("PT1\uD83D\uDE00"));
        assertThat(exc.getMessage()).startsWith("Invalid unit: \uD83D");
    }

    // --- Non-ASCII (Unicode) digits are not accepted ---

    @Test
    void fixedParserRejectsUnicodeDigits()
    {
        // Fullwidth digits
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDateTime("\uFF12\uFF10\uFF11\uFF12-11-11T12:22:11Z"));
        assertThat(exc.getErrorIndex()).isEqualTo(0);
    }

    @Test
    void tokenParserRejectsUnicodeDigits()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> tokenParser.parse("2023-01-01T23:38:34.\uFF19Z", new ParsePosition(0)));
        assertThat(exc.getMessage()).startsWith("Must have at least 1 fraction digit");
    }

    @Test
    void durationParserRejectsUnicodeDigits()
    {
        // Arabic-Indic digit is not a valid duration value character
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDuration("PT\u0661S"));
        assertThat(exc.getMessage()).startsWith("Invalid unit: \u0661");
    }

    // --- Parsing from a non-zero offset ---

    @Test
    void fixedParserHonoursOffset()
    {
        final ParsePosition position = new ParsePosition(2);
        final DateTime result = ITU.parseLenient("xx2023-01-01T23:38", ParseConfig.DEFAULT, position);
        assertThat(result.toString()).isEqualTo("2023-01-01T23:38");
        assertThat(position.getIndex()).isEqualTo(18);
    }

    @Test
    void fixedParserReportsAbsoluteErrorIndexWithOffset()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class,
                () -> ITU.parseLenient("xx2023-0X-01T23:38", ParseConfig.DEFAULT, new ParsePosition(2)));
        assertThat(exc.getErrorIndex()).isEqualTo(8);
    }

    @Test
    void durationParserHonoursOffset()
    {
        assertThat(ITU.parseDuration("xxPT1S", 2)).isEqualTo(Duration.of(1, 0));
    }

    // --- Duration sign handling ---

    @Test
    void durationParserHandlesLeadingSign()
    {
        final Duration result = ITU.parseDuration("-PT7.5S");
        assertThat(result.getSeconds()).isEqualTo(-8);
        assertThat(result.getNanos()).isEqualTo(500_000_000);
    }

    @Test
    void durationParserRejectsDoubleSign()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDuration("--PT1S"));
        assertThat(exc.getMessage()).startsWith("Duration must start with 'P'");
    }

    // --- Trailing characters ---

    @Test
    void fixedParserRejectsTrailingJunkByDefault()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseLenient("2017-02-21T15:27:39Zjunk", ParseConfig.DEFAULT));
        assertThat(exc.getMessage()).startsWith("Trailing junk data after position 21");
    }

    @Test
    void fixedParserAllowsTrailingJunkWhenConfigured()
    {
        final ParseConfig config = ParseConfig.DEFAULT.withFailOnTrailingJunk(false);
        assertThat(ITU.parseLenient("2017-02-21T15:27:39Zjunk", config).toString()).isEqualTo("2017-02-21T15:27:39Z");
    }

    @Test
    void durationParserRejectsTrailingJunk()
    {
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> ITU.parseDuration("PT1SX"));
        assertThat(exc.getMessage()).startsWith("Invalid unit: X");
    }

    // --- Candidate token rollback must not swallow a farther error ---

    @Test
    void tokenParserReportsFartherErrorFromConfirmedZoneOffsetBranch()
    {
        // The '+' commits to the zone-offset branch; the malformed offset hour must be
        // reported at its own position, not collapsed to an earlier token boundary
        final DateTimeParseException exc = assertThrows(DateTimeParseException.class, () -> tokenParser.parse("2023-01-01T23:38:34.987+0X:00", new ParsePosition(0)));
        assertThat(exc.getErrorIndex()).isEqualTo(25);
    }

    // --- Format / parse-format-parse round trip stays stable ---

    @Test
    void parseFormatParseRoundTrip()
    {
        final String input = "2023-01-01T23:38:34.987654321+06:00";
        final OffsetDateTime parsed = ITU.parseDateTime(input);
        final String formatted = ITU.format(parsed, 9);
        assertThat(formatted).isEqualTo(input);
        assertThat(ITU.parseDateTime(formatted)).isEqualTo(parsed);
    }

    @Test
    void tokenParserMatchesFixedParserOutput()
    {
        final String input = "2023-01-01T23:38:34.987654321Z";
        final DateTime fixed = ITU.parseLenient(input);
        final DateTime token = tokenParser.parse(input, new ParsePosition(0));
        assertThat(token).isEqualTo(fixed);
        assertThat(token.toString()).isEqualTo(input);
    }
}
