# 解析器共享游标重构说明

本次重构为 itu 的三个解析入口（固定格式日期时间 `ITUParser`、token 格式化解析
`ConfigurableDateTimeParser` + `internal/token/*`、duration 解析 `ItuDurationParser`）抽出共享的
内部游标与解析失败模型，统一 consume / peek / checkpoint 与错误位置合并，不改变公开 API、依赖与
线程安全属性。

## 共享层边界

新增/演进的共享层只有两个类，均在 `com.ethlo.time.internal.util`（javadoc 已排除的 internal 包）：

- `ParseCursor`（新增）：统一的输入消费层。
  - 实例 API：`peek()` / `peek(int)` / `consume()` / `consumeIf(char)` / `advance(int)` /
    `expect(Field, char)` / `parse2()` / `parse4()` / `consumeDigits(int)` / `consumeZoneOffset()` /
    `checkpoint()` / `rollback(int)` / `noteFailure(int)` / `mergedErrorIndex(int)`。
    固定格式解析器与 duration 解析器通过实例消费输入。
  - 静态原语：`digitRun(String, int, int)` 与 `parseZoneOffset(String, int)`。token 解析器的
    热内建 token（`FractionsToken`、`ZoneOffsetToken`）直接调用静态原语，避免在 JIT 敏感的
    驱动循环中引入对象层；实例方法 `consumeDigits` / `consumeZoneOffset` 委托同一实现，逻辑只有一份。
  - 打包返回：`digitRun` 返回 `(length << 32) | value`；`parseZoneOffset` 返回
    `(consumed << 48) | (hours << 32) | minutes`，调用方按需解包，热路径不构造 `TimezoneOffset`。
- `ErrorUtil`（演进）：唯一的 `DateTimeParseException` 构造点。三个入口此前的 9 处
  `new DateTimeParseException(...)` 全部收编到这里（`raiseUnexpectedCharacter`、`raiseUnexpectedEndOfText`、
  `raiseMissingGranularity`、`raise`、`raiseInvalidTimezoneOffset`、`raiseUnknownLocalOffsetConvention`、
  `raiseTrailingJunk`、`raiseUnexpectedDateTimeSeparator`、`assertPositionContains`、`assertFractionDigits`）。

各入口接入方式：

- `ITUParser`：整体改为游标驱动（`ParseCursor.of(...)` 取代 `sanityCheckInputParams` 的手工边界检查，
  保留相同异常类型与消息）；可选时区是唯一的候选 token，用 `checkpoint`/`rollback` 实现。
- `ItuDurationParser`：游标驱动；段起点在溢出时用于错误位置，语义与旧的外层 `index` 完全一致。
- `ConfigurableDateTimeParser`：驱动循环保持原样（局部 `pos` + `ParsePosition` 同步）。该循环是
  库中对 JIT 最敏感的代码（源码注释即说明此点），实测改为游标对象后同机吞吐下降 25–40%
  （内联/逃逸分析不稳定），故驱动层不动；token 内部改走共享静态原语。
- `internal/token/*`：`FractionsToken` → `ParseCursor.digitRun`；`ZoneOffsetToken` →
  `ParseCursor.parseZoneOffset`；`SeparatorsToken` → `ErrorUtil.raiseUnexpectedCharacter`；
  `SeparatorToken` 本已委托 `ErrorUtil`。
- `DurationPartsConsumer.error` → `ErrorUtil.raise`。
- `TimezoneOffset.validate` 的抛错点移入冷辅助方法（消息不变），属于顺带的小体积优化。

## 位置单位

所有位置均为**从 0 开始的 UTF-16 code unit 索引**，基于原始输入字符串（含 `offset` 之前的部分），
与 `java.text.ParsePosition` 及 `DateTimeParseException.getErrorIndex()` 一致；输入耗尽时位置可以
等于 `text.length()`。消息中的 "position N" 为 1 基（`index + 1`），与既有行为一致。

## Checkpoint 与错误位置合并语义

- `checkpoint()`/`rollback(int)` 只用于**候选 token**（当前唯一用例：固定格式解析器中秒/小数之后的
  可选时区）。候选不存在时回退位置，解析继续。
- 已确认分支（已消费决定性字符，如 `+`/`Z`）**不得回退**：失败直接向上抛，避免吞掉更远的错误。
- `rollback` 保留 `noteFailure` 记录的最远失败位置；`mergedErrorIndex(candidate)` 取两者较大者，
  即错误位置按"最远优先"合并。
- 以上语义由 `ParseCursorTest` 的 9 个用例固化。

## 行为守卫

新增两个测试类（共 34 个用例，`mvn -q test` 输出中可见 `GUARD-OK <用例名>` 行）：

- `src/test/java/com/ethlo/time/ParserBehaviorGuardTest.java`（25 例）：按三个入口分别固化
  输入开头、token 中间、末尾截断、孤立 surrogate、非 ASCII Unicode 数字、时区 offset、duration
  符号、尾随字符的错误位置与核心消息；另含跨入口同位置一致性（同一非法字符/同一截断在固定格式与
  token 入口报告相同索引）与 parse-format-parse 往返守卫。
- `src/test/java/com/ethlo/time/internal/util/ParseCursorTest.java`（9 例）：consume/peek、
  checkpoint 回退、回退保留最远失败位置、已确认分支错误不被吞、候选时区缺省无位移、offset 参数
   Sanity 检查、Unicode 数字与 surrogate 不被当作数字。

所有用例的位置期望值均取自重构前实现的实测输出，先补守卫、后做重构。

## 重复量统计

统计口径：三个解析入口的 9 个文件中，边界检查（`.length()`）、原始字符读取（`charAt(`）、
位置同步（`setIndex(`）、索引推进（`++`、`+= `）与临时错误构造（`new DateTimeParseException`）
的出现行数。复现命令（仓库根目录）：

```sh
rg -n --no-heading -e '\.length\(\)' -e 'charAt\(' -e 'setIndex\(' -e '\+\+' -e '\+= ' \
   -e 'new DateTimeParseException' \
   src/main/java/com/ethlo/time/internal/fixed/ITUParser.java \
   src/main/java/com/ethlo/time/internal/ItuDurationParser.java \
   src/main/java/com/ethlo/time/internal/DurationPartsConsumer.java \
   src/main/java/com/ethlo/time/internal/token/*.java \
   src/main/java/com/ethlo/time/token/ConfigurableDateTimeParser.java | wc -l
```

结果（同一命令，重构前对 `git show HEAD:<file>` 计数）：

| 文件 | 重构前 | 重构后 |
|---|---:|---:|
| `internal/fixed/ITUParser.java` | 27 | 3 |
| `internal/ItuDurationParser.java` | 7 | 2 |
| `internal/DurationPartsConsumer.java` | 1 | 0 |
| `internal/token/DigitsToken.java` | 1 | 1 |
| `internal/token/FractionsToken.java` | 4 | 1 |
| `internal/token/SeparatorToken.java` | 4 | 4 |
| `internal/token/SeparatorsToken.java` | 5 | 4 |
| `internal/token/ZoneOffsetToken.java` | 7 | 1 |
| `token/ConfigurableDateTimeParser.java` | 11 | 11 |
| **入口文件合计** | **67** | **27** |

**入口文件重复逻辑减少 59.7%（67 → 27，目标 ≥40%）。** 这些逻辑现在唯一地实现在共享层
（`ParseCursor` 24 处、`ErrorUtil` 集中构造）。具体收编的重复块：时区解析（2 份 → 1 份
`parseZoneOffset`）、小数/数字游程扫描（2 份 → 1 份 `digitRun`）、入口参数 Sanity 检查
（2 份 → 1 份 `ParseCursor.of`）、9 处临时异常构造（→ `ErrorUtil`）、固定格式解析器内
`charAt(offset + N)` 的手写下标运算（→ 游标 consume/peek）。

## 性能（同机 benchmark）

环境（机器相关，结论仅对本机有效）：Apple M5 Max（arm64，18 核），macOS Darwin 25.5.0，
Temurin OpenJDK 24.0.1，单线程。基准类为仓库内
`src/test/java/com/ethlo/time/bench/ThroughputBench.java`（每个场景 4096 个预生成 ASCII 输入变体，
防止 JIT 常量折叠；5 轮预热 + 7 轮计次取最优）。

复现命令（仓库根目录）：

```sh
mvn -q test-compile
java -cp target/classes:target/test-classes com.ethlo.time.bench.ThroughputBench fixed-full
java -cp target/classes:target/test-classes com.ethlo.time.bench.ThroughputBench fixed-zulu
java -cp target/classes:target/test-classes com.ethlo.time.bench.ThroughputBench duration
java -cp target/classes:target/test-classes com.ethlo.time.bench.ThroughputBench configurable
```

对比方法：基线为重构前代码（`git worktree` 检出初始提交 `f9f1a7e`，拷入同一基准类编译），
新旧 JVM 交替运行各 5 次取最优值，消除机器漂移。

原始摘要（ops/sec，越高越好）：

| 场景 | 基线（重构前） | 重构后 | 变化 |
|---|---:|---:|---:|
| fixed-full（小数+offset，固定格式） | 64,774,357 | 67,269,853 | **+3.9%** |
| fixed-zulu（秒级+Z，固定格式） | 104,350,552 | 103,688,275 | −0.6% |
| duration（`P2DT3H4M5.678901234S`） | 31,861,424 | 30,679,630 | −3.7% |
| configurable（token 解析器） | 26,292,265 | 25,490,913 | −3.0% |

四个 ASCII 热路径场景均在 −10% 预算内（最差 duration −3.7%），固定格式主路径反而更快。
上表为每个场景独立 JVM、新旧交替各 5 次取最优值的结果；原始输出每行形如
`fixed-full(fraction+offset)  67,269,853 ops/sec (best round 14866250 ns)`。

分配测量（证明成功路径无新增中间 String/对象分配）：用
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` 测 configurable 场景每 op 分配，
基线与重构后均为 `allocBytes/op=152.0`（`DateTime` + `TimezoneOffset` + `int[]` + `ParsePosition`，
游标对象被逃逸分析标量替换）；`String.format` 仅存在于失败路径。

过程备注：曾尝试让 token 驱动循环与 token 内部直接使用游标实例，同机实测 configurable 场景
下降 25–40%（C2 对内联体积敏感，`consumeZoneOffset` 208 字节处于内联阈值边缘，逃逸分析结果
不稳定），因此回退为"驱动循环不变 + token 内调用静态原语"的最终形态；固定格式与 duration
入口的游标实例实测无回归（见上表）。

## 兼容性

- 公开 API、`pom.xml` 依赖、线程安全属性（解析器无状态、可并发）均未改变；`ParseCursor` 实例
  为单次解析私有，不跨线程共享。
- 未删除任何功能、未缩小任何默认输入；格式化器（`ITUFormatter`/`DurationFormatter`）未改动，
  parse-format-parse 行为由既有测试与新增守卫双重固定。
- 失败时各入口的公开异常类型（`DateTimeParseException` 等）与核心消息保持不变；错误位置与
  JDK 解析器对齐的既有测试（`ErrorOffsetTest`）全部通过。

## 验收

```sh
mvn -q -DskipTests package   # 准备阶段（不计入演示）
mvn -q test                  # 验收：退出码 0，输出中可见 34 行 GUARD-OK <用例名>
```

两条命令均在仓库根目录直接运行，不需要外部服务、环境变量或公网访问。
