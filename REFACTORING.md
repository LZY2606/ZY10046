# Shared cursor & parse-failure refactoring

The fixed date-time parser (`ITUParser`), the token-based parser (`ConfigurableDateTimeParser`
and the `com.ethlo.time.internal.token.*` tokens) and the duration parser (`ItuDurationParser`)
used to maintain their own index advancement, boundary checks and error construction. The same
truncation or illegal character could be reported at adjacent but different positions depending
on the entry point, and fixes had to be copied between implementations. This refactoring
extracts a shared internal cursor and a shared parse-failure model so all three parsers consume,
peek, checkpoint and merge error positions the same way.

## Shared layer

Two new internal types (package `com.ethlo.time.internal`, not exported — the OSGi export list
and the public javadoc are unchanged):

- `Cursor` — the read cursor over the input string. It owns the boundary checks and index
  advancement that were previously open-coded per parser: `peek()`/`peek(int)`, `consume()`,
  `advance(int)`, `expect(char, Field)`, `hasRemaining()`/`remaining()`, plus
  `checkpoint()`/`reset(int)` for candidate tokens and `recordFailure(int)`/`furthestFailure()`
  for error-position merging.
- `ParseFailure` — the single place where the public `java.time.format.DateTimeParseException`
  instances and their message formats are built (`unexpectedCharacter`, `expectedCharacter`,
  `unexpectedEndOfText`, `missingField`, `invalidTimezoneOffset`,
  `unknownLocalOffsetConvention`, `assertFractionDigits`), plus `merge(failure,
  furthestRecorded)` which implements farthest-position-wins merging while preserving the
  original exception type, message and parsed data. The former `ErrorUtil` is deleted; all
  call sites (including `LimitedCharArrayIntegerUtil`) now use `ParseFailure`.

### Position units

All positions are **0-based UTF-16 char offsets** into the input string — the same unit as
`String.charAt(int)` and `DateTimeParseException.getErrorIndex()`. When a parse starts from a
non-zero offset (`ParsePosition`/`ITU.parseDuration(text, offset)`), positions remain absolute
offsets into the whole string, not relative to the offset. Rendered messages keep the
historical **1-based** "position N" wording, so no user-visible message changes.

### Checkpoint and failure merging

`checkpoint()`/`reset(int)` exist for **candidate (optional) tokens only** — currently the
zone-offset token, which rolls back and returns `-1` when the input is exhausted. `reset()`
deliberately retains the farthest recorded failure position: once a branch is confirmed (e.g.
a `+`/`-` zone-offset introducer was seen), a failure further ahead is propagated and can never
be swallowed by rolling back to an earlier token boundary. `ConfigurableDateTimeParser` merges
the cursor's farthest recorded failure into any thrown `DateTimeParseException` via
`ParseFailure.merge`. The fixed and duration parsers have no candidate branches, so their
error positions are unaffected (pinned by `ErrorOffsetTest`, which compares against the JDK
parser, and by the new guard tests).

### Boundary of the shared layer

- The cursor carries token-level structure: separators, fixed-width fields, zone offsets,
  optional/candidate tokens.
- Hot digit runs stay **straight-line local code** and re-sync the cursor at the end of the
  run (`ITUParser.parseFractionRun`, `ItuDurationParser.readUntilNonDigit`,
  `FractionsToken.read`). This mirrors the pre-existing `parse2`/`parse4` style in
  `LimitedCharArrayIntegerUtil` and keeps the ASCII hot path at zero cursor allocation
  (verified below).
- Entry-point-specific failure wording stays at the entry points: duration errors keep
  `DurationPartsConsumer.error(...)`, the fixed parser keeps its trailing-junk and
  date-time-separator messages. Public exception types and core messages are unchanged.

## Duplication statistics

Metric: lines matching boundary-check / index-advance idioms
(`charAt(`, `.length()`, `setIndex`, `getIndex`, `++`, `+=`, `offset +`, `index +`, `idx +`,
`pos +`) in the eight parser files (`ITUParser`, `ItuDurationParser`,
`ConfigurableDateTimeParser`, `DigitsToken`, `FractionsToken`, `SeparatorToken`,
`SeparatorsToken`, `ZoneOffsetToken`).

Reproduce (run from the repository root; compare against `git show HEAD:<file>` for the
"before" side):

    grep -cE 'charAt\(|\.length\(\)|setIndex|getIndex|\+\+|\+=|offset \+|index \+|idx \+|pos \+' \
      src/main/java/com/ethlo/time/internal/fixed/ITUParser.java \
      src/main/java/com/ethlo/time/internal/ItuDurationParser.java \
      src/main/java/com/ethlo/time/token/ConfigurableDateTimeParser.java \
      src/main/java/com/ethlo/time/internal/token/{DigitsToken,FractionsToken,SeparatorToken,SeparatorsToken,ZoneOffsetToken}.java

| File | Before | After |
|---|---|---|
| `internal/fixed/ITUParser.java` | 47 | 15 |
| `internal/ItuDurationParser.java` | 9 | 6 |
| `token/ConfigurableDateTimeParser.java` | 15 | 8 |
| `internal/token/DigitsToken.java` | 3 | 3 |
| `internal/token/FractionsToken.java` | 5 | 5 |
| `internal/token/SeparatorToken.java` | 5 | 3 |
| `internal/token/SeparatorsToken.java` | 6 | 3 |
| `internal/token/ZoneOffsetToken.java` | 9 | 2 |
| **Total** | **99** | **45** |

**Reduction: 99 → 45 = −54.5%** (target: ≥ 40%). The shared layer itself contains 10 such
lines (`Cursor` 8, `ParseFailure` 2), which replace the removed copies.

## Behaviour guards

New tests (run as part of `mvn test`; their names are printed with a `[behavior-guard]`
prefix even in quiet output via `GuardTestNameListener`):

- `com.ethlo.time.SharedParserBehaviorGuardTest` (27 cases) — pins exception type, core
  message and 0-based error index for all three entry points: input start, mid-token,
  end-of-input truncation, surrogate pairs (as digit, separator and unit), non-ASCII
  (Unicode) digits, non-zero parse offsets, duration sign handling, trailing junk, candidate
  zone-offset error positions, and parse→format→parse round trips.
- `com.ethlo.time.internal.CursorTest` (9 cases) — cursor consume/peek/advance, `expect`
  boundary checks, checkpoint/reset, and the failure-merging contract (rollback retains the
  farthest recorded failure; `ParseFailure.merge` prefers the farthest position and preserves
  type/message/parsed data).

## Benchmark (same machine, before vs after)

Environment: Apple M5 Max (arm64), macOS 15 (Darwin 25.5.0), OpenJDK Temurin 24.0.1+9
(`java` on PATH), Apache Maven 3.9.16. The machine had background load (load average ≈ 11),
so all numbers are **medians of 5 interleaved before/after runs**, each scenario in its own
JVM to avoid profile pollution. Results are machine-dependent and only valid as a same-machine
A/B comparison.

Reproduce:

    # build both revisions (before = git stash / HEAD, after = working tree), then per scenario:
    mvn -q -DskipTests package && mvn -q test-compile
    java -cp target/classes:target/test-classes com.ethlo.time.bench.ParseBenchmark <full|zulu|minute|duration>

The benchmark (`src/test/java/com/ethlo/time/bench/ParseBenchmark.java`) is plain Java — no
new dependencies — and consumes results through a sink so the JIT cannot eliminate the parses.

| Scenario (ASCII hot path) | Before (ops/s) | After (ops/s) | Delta |
|---|---|---|---|
| `parseDateTime` full nano + offset | 33,947,228 | 34,103,746 | **+0.5%** |
| `parseDateTime` second + Z | 90,878,798 | 99,919,096 | **+9.9%** |
| `parseLenient` minute | 75,345,175 | 74,109,070 | **−1.6%** |
| `parseDuration` | 30,317,589 | 28,727,595 | **−5.2%** |

All deltas are within the allowed −10% ASCII hot-path budget. Raw per-round output of the
final session is kept in this directory's build log (`ab_final2` session); the two
`second-zulu` "before" outliers above 4×10⁸ ops/s are JIT dead-code-elimination artifacts and
are absorbed by the median.

Allocation per successful parse (measured with
`com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes`, 5M iterations after 2M
warmup): second+Z 96.0 B → 96.0 B (unchanged); full nano + offset 191.6 B → 136.0 B
(improved — the cursor is fully scalar-replaced after `Cursor.expect` was kept below the
JIT inline threshold). No intermediate `String` allocations are introduced on success paths:
the shared layer allocates at most one small `Cursor` object per parse, which escape analysis
eliminates on the hot paths.

## Invariants

- Public API, exported packages (`com.ethlo.time`, `com.ethlo.time.token`) and dependencies
  are unchanged; `ErrorUtil` was package-internal and is replaced by `ParseFailure`.
- Public exception types and core messages per entry point are unchanged (pinned by the
  guard tests and the pre-existing `ErrorOffsetTest` JDK comparison).
- Thread-safety is unchanged: parsers remain stateless; a `Cursor` is a per-call local and
  never leaves the calling thread.
- Formatter output and parse→format→parse behaviour are unchanged (pinned by
  `SharedParserBehaviorGuardTest.parseFormatParseRoundTrip` and the existing formatter tests).
