package com.ethlo.time.bench;

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

import com.ethlo.time.DateTimeParser;
import com.ethlo.time.DateTimeTokens;
import com.ethlo.time.Field;
import com.ethlo.time.ITU;
import com.ethlo.time.token.ConfigurableDateTimeParser;

/**
 * Manual same-machine throughput check for the ASCII hot paths. Not a JUnit test; run with:
 * <pre>
 * mvn -q test-compile
 * java -cp target/classes:target/test-classes com.ethlo.time.bench.ThroughputBench
 * </pre>
 * Inputs cycle through pre-generated variants so the JIT cannot constant-fold the parse.
 */
public final class ThroughputBench
{
    private static final int VARIANTS = 4096;
    private static final String[] FIXED_FULL = variants("2017-02-21T15:27:39.123456789+08:30", 20, 9, 1_000_000_000);
    private static final String[] FIXED_ZULU = variants("2017-02-21T15:27:39Z", 17, 2, 60);
    private static final String[] DURATION = variants("P2DT3H4M5.678901234S", 10, 9, 1_000_000_000);
    private static final String[] CONFIGURABLE = variants("2017-02-21T15:27:39.123+08:30", 20, 3, 1000);
    private static final DateTimeParser CONFIGURABLE_PARSER = ConfigurableDateTimeParser.of(
            DateTimeTokens.digits(Field.YEAR, 4), DateTimeTokens.separators('-'),
            DateTimeTokens.digits(Field.MONTH, 2), DateTimeTokens.separators('-'),
            DateTimeTokens.digits(Field.DAY, 2), DateTimeTokens.separators('T'),
            DateTimeTokens.digits(Field.HOUR, 2), DateTimeTokens.separators(':'),
            DateTimeTokens.digits(Field.MINUTE, 2), DateTimeTokens.separators(':'),
            DateTimeTokens.digits(Field.SECOND, 2), DateTimeTokens.separators('.'),
            DateTimeTokens.fractions(), DateTimeTokens.zoneOffset());

    private static final int WARMUP_ROUNDS = 5;
    private static final int MEASURE_ROUNDS = 7;
    private static final int ITERATIONS = 1_000_000;

    private static long sink;

    private ThroughputBench()
    {
    }

    /**
     * Returns copies of {@code template} with {@code width} digits starting at {@code digitPos}
     * replaced by a varying number, keeping every variant valid ASCII input.
     */
    private static String[] variants(String template, int digitPos, int width, int maxExclusive)
    {
        final String[] out = new String[VARIANTS];
        final char[] chars = template.toCharArray();
        for (int i = 0; i < VARIANTS; i++)
        {
            int v = i % maxExclusive;
            for (int d = width - 1; d >= 0; d--)
            {
                chars[digitPos + d] = (char) ('0' + (v % 10));
                v /= 10;
            }
            out[i] = new String(chars);
        }
        return out;
    }

    private interface Case
    {
        long run(String input);
    }

    public static void main(String[] args)
    {
        // Single-scenario mode (fresh JVM per scenario) avoids cross-scenario JIT profile pollution
        final String only = args.length > 0 ? args[0] : "all";
        System.out.println("java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        if ("all".equals(only) || "fixed-full".equals(only))
        {
            bench("fixed-full(fraction+offset)", FIXED_FULL, s -> ITU.parseLenient(s).getNano());
        }
        if ("all".equals(only) || "fixed-zulu".equals(only))
        {
            bench("fixed-zulu(second)", FIXED_ZULU, s -> ITU.parseLenient(s).getSecond());
        }
        if ("all".equals(only) || "duration".equals(only))
        {
            bench("duration", DURATION, s -> ITU.parseDuration(s).getSeconds());
        }
        if ("all".equals(only) || "configurable".equals(only))
        {
            bench("configurable-token", CONFIGURABLE, s -> CONFIGURABLE_PARSER.parse(s).getNano());
        }
        System.out.println("sink=" + sink);
    }

    private static void bench(String name, String[] inputs, Case c)
    {
        for (int i = 0; i < WARMUP_ROUNDS; i++)
        {
            round(inputs, c);
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < MEASURE_ROUNDS; i++)
        {
            best = Math.min(best, round(inputs, c));
        }
        final double opsPerSec = ITERATIONS * 1_000_000_000.0 / best;
        System.out.printf("%-32s %,14.0f ops/sec (best round %d ns)%n", name, opsPerSec, best);
    }

    private static long round(String[] inputs, Case c)
    {
        final long start = System.nanoTime();
        long acc = 0;
        for (int i = 0; i < ITERATIONS; i++)
        {
            acc += c.run(inputs[i & (VARIANTS - 1)]);
        }
        sink += acc;
        return System.nanoTime() - start;
    }
}
