# Pair-wise GSB 本地层序推理系统

一套无需云端账号、无外部数据库的 Java 本地系统，用事件溯源管理地层 context、切割/封闭关系、测年区间、假设分支、冻结快照和人工合并。JDK 内置 HTTP Server 提供网页与 JSON API。

## 构建与运行

```bash
./gradlew --no-daemon assemble
./gradlew --no-daemon test
./gradlew --no-daemon run --args='--port 5204'
```

打开 `http://127.0.0.1:5204`。默认数据目录是 `./data`，也可以加 `--data /path/to/store`。

## 地质关系语义

- `earlier-than A B`：A 早于 B，方向为 `A -> B`。
- `later-than A B`：A 晚于 B，等价于 `B -> A`。
- `cuts A B`：A 切割 B，被切割者 B 先形成，方向为 `B -> A`。
- `cut-by A B`：A 被 B 切割，方向为 `A -> B`。
- `seals A B`：A 封闭 B，被封闭者 B 先形成，方向为 `B -> A`。
- `sealed-by A B`：A 被 B 封闭，方向为 `A -> B`。
- `contemporaneous`：两个离散年代区间存在整数交叠，不参与传递“相等”推导。

同一对 context 可保留多条强弱不同的证据。边的直接证据列表显示所有未撤回且支持该规范方向的证据；撤回只修改指定证据，不删除同端点的其他证据。

## 年代模型

年份使用显式整数坐标：BCE 为负整数，CE 为正整数，不存在零年。输入必须提供 `lowerEra`、`upperEra` 和正整数绝对值，例如 BCE 100 年编码为 `-100`，不依赖“公元前/公元后”的语言默认值。

区间端点支持开放/封闭：内部先归一化为离散整数闭区间。`(-1,1)` 这类跨零开放区间立即报错，因为归一化后上界小于下界或碰到禁止的零年。

推理使用差分约束：

- 测年下界写入 `source -> context.lower`。
- 测年上界写入 `context.upper -> source`。
- `x 早于 y` 写入 `x.upper + 1 <= y.lower`。
- 同期写入两个重叠约束。

Bellman-Ford 正环检测矛盾，传播得到每个 context 的最紧下界/上界。偏序图另外计算可达闭包、同期连通分量和稳定拓扑序；导出中的边标记为 `direct` 或 `transitive`，传递边给出路径。

## 最小矛盾集合

批量提交先在内存试算，任何引用错误、状态机错误或推理矛盾都会导致整个事务拒绝，磁盘状态保持原状。发生矛盾时返回：

- `evidenceIds`：参与不可满足性的关系证据。
- `datingIds`：参与不可满足性的测年证据。
- `constraints` / `cycleConstraints`：正环中的边及来源。

引擎从正环中提取候选来源，再逐个尝试移除；最终集合是包含意义上的最小核心：移除其中任一来源即可打破当前矛盾。

## 业务状态机

- 分支：`open -> merged`；`main` 初始为 `open`，合并后仍继续作为可编辑主线，来源分支转 `merged`。
- 快照：只能从当前可推理通过的分支创建；创建后内容不可变。
- 工作分支：可从父分支当前状态创建，并记录 `baseSnapshotId` 与当时的 `confirmedSnapshotId`。
- 证据：`active -> retracted`；撤回保留记录、原因和时间，不物理删除。
- 后台任务：`pending -> completed`；启动时恢复 pending，单工作线程执行，完成事件按稳定幂等键回放。

发布快照不会阻止同一分支后续编辑，因为已发布快照本身冻结；后续工作形成新的未发布状态。从冻结主线创建的工作分支新增证据不会影响主线。合并时，如果同一证据在分叉后两边被解释成不同关系，API 返回冲突；研究者必须选择 `source` 或 `main` 并提供理由，理由写入 `BRANCH_MERGED` 事件。

## 批量与幂等

`POST /api/branches/{branch}/batch` 的 `operations` 是一次提交边界。服务端先构造试算状态并运行完整推理，只有全部操作和最终约束可行时才写日志。

客户端应传稳定 `idempotencyKey`，例如“业务实体 + 操作类型 + 请求确定性哈希”。重试相同 key 返回首次响应；不同请求体复用同一 key 目前按首次请求处理，调用方应保证键稳定且唯一。

## HTTP API

- `GET /api/state`：分支、快照和规则版本。
- `GET /api/branches/{id}`：当前状态和推理结果。
- `POST /api/branches/{id}/batch`：原子批量操作。
- `POST /api/branches`：创建工作分支。
- `POST /api/branches/{id}/publish`：冻结发布快照。
- `POST /api/branches/{id}/merge`：把非 main 分支合并回 main。
- `GET /api/branches/{id}/audit`：规则版本、完整事务、事件顺序和冻结快照。
- `GET /api/branches/{id}/export`：稳定语义导出和 SHA-256 指纹。
- `POST /api/branches/{id}/jobs` / `GET /api/jobs/{id}`：后台可恢复推理。
- `GET /api/compare?branch=a&branch=b`：比较所有分支结论；不传参数时比较全部。

批量操作支持：

```json
{
  "idempotencyKey": "fieldbook-p12-001",
  "operations": [
    {"op":"addContext", "context":{"id":"C1","label":"Pit fill"}},
    {"op":"addEvidence", "evidence":{"source":"C1","target":"C2","relation":"cut-by","strength":4,"sourceReference":"FB-2","page":"12"}},
    {"op":"addDating", "dating":{"context":"C1","interval":{"lower":100,"upper":150,"lowerEra":"CE","upperEra":"CE","lowerOpen":false,"upperOpen":false},"page":"31"}},
    {"op":"interpretEvidence", "id":"ambiguous-7", "relation":"contemporaneous"},
    {"op":"retract", "id":"weak-evidence", "reason":"photograph disproves contact"}
  ]
}
```

## 持久化格式

数据目录：

- `events.log`：每行一个完整 JSON Object（JSONL）。空行允许，半行或坏行使启动失败，避免把部分提交误认为事实。
- `snapshots/<snapshot-id>.json`：快照不可变冗余文件；若文件缺失，重放事件日志会重建。
- 写入采用临时文件加原子替换：先写 `events.log.tmp`，再替换正式日志；同事务中的快照文件同样原子写入。

当前格式版本为 `pair-wise-gsb-v1`，规则版本为 `rules-v1.0`。事务结构：

```json
{
  "formatVersion": "pair-wise-gsb-v1",
  "ruleVersion": "rules-v1.0",
  "transactionId": "txn-...",
  "idempotencyKey": "client-stable-key",
  "events": [{"type":"EVIDENCE_ADDED", "id":"...", "branchId":"main", "at":1700000000000, "payload":{}}],
  "snapshot": {},
  "response": {"committed": true}
}
```

事件类型包括 `CONTEXT_ADDED`、`EVIDENCE_ADDED`、`DATING_ADDED`、`EVIDENCE_RETRACTED`、`DATING_RETRACTED`、`EVIDENCE_INTERPRETED`、`BRANCH_CREATED`、`BRANCH_MERGED`、`JOB_REQUESTED` 和 `JOB_COMPLETED`。

## 兼容策略

- `pair-wise-gsb-v1` 读取只做向后兼容的附加字段；未知事件类型或不同主版本会拒绝启动。
- 不就地改写历史事件。规则修复应发布新的 `rules-vX.Y`，通过新事件或新快照标记，审计页保留旧规则版本。
- 快照是长期兼容边界；新版本必须能读取旧快照投影，不能依赖可变类的默认字段。
- 导出指纹只包含排序后的语义输入和推理结果，不包含墙钟时间、事务计数器或机器路径；相同证据集在不同机器产生相同摘要。

## 测试

测试由 Gradle `test` 任务启动，无第三方测试框架：

- 跨零年和开闭端点校验。
- 直接/传递边标注与稳定拓扑。
- 环和最小证据集合。
- 批量失败回滚。
- 多条同对端证据的独立撤回。
- 幂等重试与重启回放。
- 工作分支、冻结快照、人工合并理由。
- 稳定导出指纹。
