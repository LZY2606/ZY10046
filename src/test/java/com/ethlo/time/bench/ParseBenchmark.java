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

import com.ethlo.time.ITU;

/**
 * Plain-Java throughput benchmark for the ASCII hot path. No JMH dependency so that the
 * project dependency set stays unchanged. Each scenario should be run in its own JVM to
 * avoid profile pollution between input shapes:
 *
 *   mvn -q -DskipTests package && mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.ethlo.time.bench.ParseBenchmark full
 *   java -cp target/classes:target/test-classes com.ethlo.time.bench.ParseBenchmark zulu
 *   java -cp target/classes:target/test-classes com.ethlo.time.bench.ParseBenchmark minute
 *   java -cp target/classes:target/test-classes com.ethlo.time.bench.ParseBenchmark duration
 */
public final class ParseBenchmark
{
    private static final String FULL = "2023-01-01T23:38:34.987654321+06:00";
    private static final String SECOND_ZULU = "2023-01-01T23:38:34Z";
    private static final String MINUTE = "2023-01-01T23:38";
    private static final String DURATION = "P2W3DT4H5M6.123456789S";

    /**
     * Sink that keeps the JIT from eliminating the parses
     */
    private static long sink;

    private ParseBenchmark()
    {
    }

    public static void main(String[] args)
    {
        final String scenario = args.length > 0 ? args[0] : "all";
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        if ("all".equals(scenario) || "full".equals(scenario))
        {
            bench("parseDateTime/full-nano-offset", () -> sink += ITU.parseDateTime(FULL).getNano());
        }
        if ("all".equals(scenario) || "zulu".equals(scenario))
        {
            bench("parseDateTime/second-zulu", () -> sink += ITU.parseDateTime(SECOND_ZULU).getNano());
        }
        if ("all".equals(scenario) || "minute".equals(scenario))
        {
            bench("parseLenient/minute", () -> sink += ITU.parseLenient(MINUTE).hashCode());
        }
        if ("all".equals(scenario) || "duration".equals(scenario))
        {
            bench("parseDuration/ascii", () -> sink += ITU.parseDuration(DURATION).getSeconds());
        }
        System.out.println("sink=" + sink);
    }

    private static void bench(final String name, final Runnable runnable)
    {
        // Warmup
        for (int i = 0; i < 500_000; i++)
        {
            runnable.run();
        }
        double best = 0;
        double worst = Double.MAX_VALUE;
        for (int round = 0; round < 7; round++)
        {
            final int iterations = 3_000_000;
            final long start = System.nanoTime();
            for (int i = 0; i < iterations; i++)
            {
                runnable.run();
            }
            final long elapsed = System.nanoTime() - start;
            final double opsPerSec = iterations * 1_000_000_000.0 / elapsed;
            best = Math.max(best, opsPerSec);
            worst = Math.min(worst, opsPerSec);
            System.out.printf("%-32s round=%d ops/sec=%,.0f%n", name, round, opsPerSec);
        }
        System.out.printf("%-32s BEST  ops/sec=%,.0f  WORST ops/sec=%,.0f%n", name, best, worst);
    }
}
