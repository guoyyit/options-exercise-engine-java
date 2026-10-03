# Options Exercise Engine（期权到期自动行权与指派引擎）

面向合约到期日的**自动行权判定、卖方指派、原子结算、崩溃恢复与人工干预**服务。
行情与持仓来自本地文件或内存，不连接真实交易通道。基于 Spring Boot 3 / Java 17。

## 1. 能力总览

- 到期扫描与行权判定：按到期结算价计算实值额，实值额未达到可配置阈值的持仓按**放弃**处理，并写入可审计原因。
- 行权模式：
  - 欧式（EUROPEAN）：仅允许到期日由自动批处理行权；到期前的人工指令会被拒绝。
  - 美式（AMERICAN）：允许在到期前提交人工行权指令，与到期自动处理共享同一套结算与去重逻辑。
- 指派分配：在同一合约的卖方持仓之间分配，支持
  - `proportional` 按持仓比例分配；
  - `random` 基于**固定种子**的加权随机抽样，相同输入产生完全一致结果。
- 结算原子性：单个多头行权及其全部对手方指派在一个事务内“全部生效或全部回滚”，通过 WAL（预写日志）保证。
- 中断恢复：重启后先回滚未提交事务、再重放已提交事务，从最近一致状态继续，已结算持仓不会重复结算。
- 大批量到期：按批次（batch）推进，批次大小与并发度可配置；超时/取消不会把单个合约留在中间状态。
- 人工干预：截止时间（干预窗口）前允许撤销行权指令、调整指派，均校验权限并写入追加式审计日志，与自动处理并发安全。

## 2. 行权规则

对每个到期日的**多头**持仓，读取该合约的到期结算价 `S`，行权价 `K`：

- 看涨期权内在价值：`max(0, S - K)`
- 看跌期权内在价值：`max(0, K - S)`

判定结果：

| 条件 | 动作 | 审计原因 |
| --- | --- | --- |
| 无结算价 | 放弃（保守处理） | `settlement price unavailable, conservative abandon` |
| 内在价值 <= 0（虚值/平值） | 放弃 | `out-of-the-money: intrinsic=...` |
| 0 < 内在价值 < 阈值 | 放弃 | `in-the-money but below threshold: intrinsic=..., threshold=...` |
| 内在价值 >= 阈值 | 行权 | `in-the-money: intrinsic=..., threshold=...` |
| 存在人工行权指令（美式提前/覆盖自动） | 行权 | `manual exercise instruction` |

阈值由 `exercise.engine.exercise-threshold` 配置（默认 0，即任何实值都行权）。

结算资金/标的变动（单位与价格同为整数计价，每张对应一单位标的）：

- 看涨行权：买方付 `S` 收标的（现金 `-S*qty`，标的 `+qty`）；卖方收 `S` 付标的。
- 看跌行权：买方收 `S` 付标的（现金 `+S*qty`，标的 `-qty`）；卖方相反。
- 现金余额不得因结算变为负数；卖方交付导致的标的负余额表示待交割义务，允许出现。

## 3. 指派分配算法

分配发生在**合约级互斥锁**内，针对该合约全部未履行的卖方持仓（持仓量为负）。

### 3.1 按比例 proportional

- 按持仓 ID 排序后，按卖方空头占比分摊 `floor(exercised * short_i / totalShort)`。
- 舍入余量全部并入最后一个卖方，保证分配总量严格等于行权总量；相同输入结果确定。

### 3.2 随机 random（固定种子）

- 随机源为 `new Random(seed)`，其中
  `seed = exercise.engine.random-seed XOR (batchNonce * 0x9E3779B97F4A7C15) XOR contractCode.hashCode()`。
- 逐单位在“尚未被指派的卖方持仓量”上做加权抽样，被抽中后该卖方可用量减一。
- 因为种子、nonce、合约代码与持仓排序全部确定，**相同输入必然得到完全一致的分配**。
- 若卖方总空头量小于被行权总量，直接抛错，不产生任何写入。

一个合约的分配结果随后按“行权总量的连续偏移”切片给该合约内每个多头，每个多头形成独立结算事务。

## 4. 原子结算与恢复语义

### 4.1 事务与 WAL

每个多头行权（或一次指派调整）对应一个事务目录：

```
<stateDir>/transactions/<txId>/
  ref         # 幂等键，如 exercise:<longPositionId> / adjust:<referenceId>
  events.log  # 逐行 JSON 的结算事件
  committed   # 提交标记（文件存在即已提交）
```

提交流程：

1. 在内存中**预校验**全部事件（持仓不会多空反向穿越、现金不为负、指派总量=行权总量）。
2. `beginTransaction` → 追加写 `events.log`（fsync 级别的崩溃安全由“先写日志后改内存”保证）→ 写 `committed`。
3. 提交成功后才把事件应用到内存持仓/账户。

任何一步在提交前失败：内存零写入，日志事务无 `committed` 标记，重启即丢弃（回滚）。
提交后、内存应用前若进程崩溃：日志已落盘，重启重放即可补齐，不会丢结算。

### 4.2 恢复（`SettlementService.recover`）

启动或每次到期处理前执行：

1. 删除所有**没有** `committed` 标记的事务目录（未完成事务回滚）。
2. 按目录顺序读取所有已提交事务并幂等重放到内存：
   - `exercise:*` 标记持仓已结算并重放持仓/账户变动；
   - `adjust:*` 重放指派调整。
3. 已结算的幂等键集合用于跳过重复结算，因此“自动处理与人工行权并发到达”或“中断后续跑”都不会重复行权/重复指派。

并发控制分层：

- **合约锁**：自动批处理的“查空头 → 分配 → 逐持仓结算”与人工行权共用同一把合约锁。
- **持仓条带锁**：同一持仓幂等键的二次结算被安全短路。

## 5. 大批量、并发、超时与取消

- `exercise.engine.batch-size`：每批处理的决策数。
- `exercise.engine.concurrency`：并行执行批次的固定线程数。
- `exercise.engine.operation-timeout-ms`：单批次超时；超时后置取消标志，尚未开始的批次不再执行。
- 取消是协作式的：批次之间与合约之间检查取消标志。
- 最小原子单元是**单个合约的一次完整行权+指派事务**，因此超时/取消不会把某一合约切成“已扣买方、未扣卖方”的中间状态；已完成的批次保留，再次运行时从最近一致状态继续。

## 6. 人工干预

- 干预窗口：`exercise.engine.intervention-window-ms`，从一次到期处理打开窗口起计时；`0` 表示不限制。
- 权限（`SimplePermissionService`，可替换实现）：
  - `REVOKE_EXERCISE` 撤销人工行权指令；
  - `ADJUST_ASSIGNMENT` 调整指派；
  - `EXERCISE_OVERRIDE` 覆盖式提交行权指令。
- 撤销：窗口内、具备权限、且持仓尚未结算、确有挂起指令时才允许；拒绝同样写审计。
- 调整指派：在两个卖方持仓之间平移已指派数量（总量守恒），走独立的原子调整事务并幂等。
- 审计：所有自动放弃、人工行权、撤销、调整（允许与拒绝）追加写入 `<stateDir>/audit.log`（JSON Lines）。

## 7. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/exercise/instruction/{positionId}?override=false` | 提交人工行权指令（头 `X-Operator`） |
| `POST /api/exercise/manual-exercise/{positionId}?strategy=proportional` | 人工指令立即行权结算 |
| `POST /api/exercise/revoke/{positionId}` | 撤销挂起的行权指令（需权限） |
| `POST /api/exercise/adjust` | 调整指派，body：`{referenceId, from, to}` |
| `POST /api/exercise/expire/{expiryDate}` | 触发某到期日的自动处理 |
| `POST /api/exercise/cancel` | 请求取消当前运行 |
| `GET  /api/exercise/audit?target=...` | 查询审计记录 |

## 8. 本地数据文件

- 持仓 CSV：`id,accountId,contractCode,type(CALL/PUT),style(EUROPEAN/AMERICAN),strike,quantity,expiryDate`
  - `quantity` 为正表示多头，为负表示空头。
- 账户 CSV：`accountId,cash,underlyingUnits`
- 行情 CSV：`contractCode,settlementPrice`

可用 `CsvDataLoader` 加载持仓/账户，`FileMarketDataProvider#load` 加载行情；默认装配的是内存实现 `InMemoryMarketDataProvider`（`@Primary`），便于测试与演示。

## 9. 配置项（`application.properties`）

```properties
exercise.engine.exercise-threshold=0
exercise.engine.batch-size=100
exercise.engine.concurrency=4
exercise.engine.operation-timeout-ms=0
exercise.engine.assignment-strategy=proportional   # proportional | random
exercise.engine.random-seed=20260101
exercise.engine.intervention-window-ms=0
exercise.engine.state-dir=target/engine-state
```

## 10. 测试

`mvn test`，覆盖：

- 虚值放弃、阈值放弃、阈值内行权（`ExerciseDecisionServiceTest`）。
- 按比例分配、随机固定种子可复现（`AssignmentStrategyTest`）。
- 原子结算、提交重放、未提交回滚、校验失败零写入（`SettlementRecoveryTest`）。
- 大批量分批、完整处理与重跑幂等（`ExpirationEngineTest`）。
- 自动与人工行权并发、重复结算短路（`ConcurrentExerciseTest`）。
- 取消后续跑、无中间状态、不重复结算（`CancelRecoveryTest`）。
- 权限校验、撤销、调整指派、窗口关闭、审计（`InterventionTest`）。
- 分批、超时与取消（`BatchExecutorTest`）。
