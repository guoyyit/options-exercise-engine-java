# 合约到期自动行权与指派服务

本项目为期权到期处理提供本地内存实现，不依赖真实交易通道。系统支持到期扫描、实值/虚值判定、欧式与美式行权、按比例或随机指派、结算原子性、中断恢复、分批处理、超时取消、人工干预权限与审计。

## 核心组件

- `ExerciseService`：行权判定、美式行权、到期合约处理、幂等控制。
- `AssignmentAllocator`：指派分配，包含 `ProRataAssignmentAllocator` 与 `RandomAssignmentAllocator`。
- `SettlementService`：整合约批量结算，更新期权持仓、资金与标的资产。
- `ExpirationProcessor`：按批次和并发度处理到期合约，支持超时与取消。
- `ManualInterventionService`：撤销行权、调整指派、权限校验与审计。
- `StateManager`：基于当前快照执行原子状态变更，并在失败时回滚。
- `JsonStateStore`：将完整状态写入临时文件后原子替换为状态文件。

## 行权规则

对于看涨期权，内在价值为 `结算价 - 行权价`；看跌期权为 `行权价 - 结算价`。内在价值小于零时按零处理。

行权阈值由 `exercise.intrinsic-threshold` 配置。系统只对实值持仓行权：

- 阈值为 `0` 时，内在价值必须大于 `0`。
- 阈值大于 `0` 时，内在价值必须大于 `0` 且大于等于阈值。
- 虚值持仓标记为 `ABANDONED`，原因为 `OUT_OF_THE_MONEY`。
- 实值但未达阈值的持仓标记为 `ABANDONED`，原因为 `BELOW_THRESHOLD`。
- 放弃指令写入审计日志，包含合约、账户、结算价和原因。

欧式期权只在到期日自动处理；美式期权可在到期前由人工发起行权，到期日也会参与自动扫描。

同一账户对同一合约不允许重复行权。自动处理使用幂等键 `AUTO:{合约ID}:{账户ID}:{到期日}`；美式行权使用调用方传入的幂等键。已结算或待处理的行权会被识别并跳过。

## 指派算法

每个被行权合约的指派总量等于该合约被行权总量。系统先校验卖方持仓总量，不足时整笔合约失败，不写入中间状态。

### 按比例分配

`PRO_RATA` 按卖方持仓比例分配：

1. 计算每个卖方的理论配额 `行权量 × 卖方持仓 / 卖方总持仓`。
2. 先取整数部分。
3. 剩余数量按最大余数分配；余数相同时按账户 ID 排序，保证结果确定。

### 随机分配

`RANDOM` 使用固定种子的 `Random` 加权选择：

- 卖方账户先按 ID 排序。
- 每次按当前剩余持仓加权选择一个卖方。
- 选择后扣减该卖方剩余可分配数量。

固定种子保证相同输入得到完全一致的结果；不同种子会产生不同随机序列。

## 结算原子性

结算以“整合约所有行权与指派”为一个事务：

- 多头期权持仓扣减，空头期权持仓按指派数量扣减。
- 看涨期权：多头支付行权价现金并获得标的资产；卖方交付标的资产并获得现金。
- 看跌期权：多头交付标的资产并获得行权价现金；卖方获得标的资产并支付现金。
- 资金或标的资产不足时，整笔结算失败，不更新任何持仓。

`StateManager` 先读取当前最新快照，在快照副本上计算新状态，再统一替换内存状态并写入状态文件。任何一步失败都会回滚到提交前快照。

## 中断恢复

默认状态文件为 `target/state.json`，可通过 `exercise.state-file` 配置。

写入时先在同目录创建临时文件，写入完成后通过原子移动替换状态文件：

- 替换前崩溃：状态文件仍是上一个一致快照。
- 替换后崩溃：状态文件是本次一致快照。
- 不存在部分写入的中间文件被当作正式状态。

应用启动时，`StateManager.init()` 加载最近一次一致快照。自动处理依据幂等键和已结算状态继续，不重复结算、不重复指派。

## 分批、并发、超时与取消

到期处理参数可配置：

- `exercise.batch-size`：每批合约数量。
- `exercise.concurrency`：处理线程数。
- `exercise.processing-timeout`：超时时间。

`ExpirationProcessor` 使用固定大小线程池执行批次，并通过 `invokeAll` 应用超时。超时或线程中断时取消未完成任务。每个合约本身是一个原子事务：已完成的合约保持结算结果，未开始的合约保持原状，不会出现合约部分行权或部分指派。

## 人工干预

人工操作必须通过 `Operator` 传入操作人和角色：

- `ADMIN`：拥有所有干预权限。
- `REVOKE_EXERCISE`：可撤销待处理行权。
- `ADJUST_ASSIGNMENT`：可调整待处理指派。

撤销行权会同时取消其待处理指派；已结算行权不可撤销。调整指派时，新卖方必须持有足够的空头持仓。所有成功与失败的干预都会写入审计日志。

干预截止时间为合约到期日 `exercise.cutoff-hour` 对应时刻，时区由 `exercise.zone-id` 配置。

## 配置示例

```properties
exercise.state-file=target/state.json
exercise.intrinsic-threshold=0
exercise.assignment-strategy=PRO_RATA
exercise.random-seed=20261003
exercise.batch-size=100
exercise.concurrency=2
exercise.processing-timeout=PT5M
exercise.cutoff-hour=16
exercise.zone-id=Asia/Shanghai
```

## 本地数据

行情与持仓由本地内存仓库提供：

- `InMemoryContractRepository`
- `InMemoryPositionRepository`
- `InMemoryMarketDataProvider`

应用也可以在启动时将合约、持仓和行情加载到这些仓库。状态变化通过 `JsonStateStore` 持久化到本地 JSON 文件，不依赖真实交易通道。

当状态文件尚未包含数据时，`CsvMarketDataLoader` 会从 `exercise.data-dir`（默认 `data`）读取以下 CSV：

- `contracts.csv`：`id,underlying,optionType,exerciseStyle,strikePrice,expiryDate`
- `positions.csv`：`accountId,instrumentId,quantity`
- `prices.csv`：`contractId,price,valuationDate`

状态文件已包含一致快照时，以状态文件为准，避免恢复后被初始数据覆盖。

## HTTP 接口

- `POST /api/expiration/process?date=YYYY-MM-DD`：处理指定到期日。
- `POST /api/exercises/american`：发起美式行权。
- `POST /api/interventions/revoke`：撤销待处理行权。
- `POST /api/interventions/adjust`：调整待处理指派。
- `GET /api/state`：查看当前完整状态快照。

## 测试覆盖

自动化测试覆盖：

- 虚值放弃与审计。
- 阈值行权与未达阈值放弃。
- 按比例指派。
- 固定种子随机指派的确定性。
- 结算失败时状态不回滚前快照。
- 文件恢复后不重复结算。
- 美式行权、撤销、越权失败与指派调整。
- 大批量分批处理。
- 并发处理同一合约只结算一次。
- 超时取消后不存在中间状态。
