# Pair-wise GSB

本地运行的考古层序推理工作台。系统把 context、地层/切割/封闭关系、测年区间、来源页码和假设分支合成为可解释的偏序模型；不依赖云端账号或外部数据库。

## 安装与运行

```bash
./gradlew --no-daemon assemble
./gradlew --no-daemon test
./gradlew --no-daemon run --args='--port 5204'
```

浏览器打开 <http://127.0.0.1:5204>。默认数据目录是 `./data`，也可指定：

```bash
./gradlew --no-daemon run --args='--port 5204 --data ./data'
```

## 建模规则

- Context 是最小地层单位，可表示层、切割单位、封闭单位等。
- 关系只有 `earlier-than` 与 `contemporaneous`；每条关系都有独立证据 ID、来源、页码、强弱和备注。
- 同一对 context 可以有多条证据；撤回按证据 ID 分支内生效，不删除其他证据或原事件。
- `contemporaneous` 用并查集折叠成同期组件；组件之间的 `earlier-than` 构成 DAG。
- 直接边标为 `direct`，由 DAG 可达性得到的边标为 `transitive`，并给出按证据 ID 稳定排序的支撑路径。
- 年使用有符号天文整数：`1 CE = 1`，`1 BCE = 0`，`2 BCE = -1`。页面的 BCE/CE 选择只负责转换，绝不按语言猜测。
- 开区间端点先归一化为整数闭区间：开下界 `(x` 等价于 `[x+1`，开上界 `x)` 等价于 `x-1]`。
- 严格早于关系使用整数年约束 `max(A) + 1 <= min(B)`；所有关系和测年界限进入差值约束，计算各同期组件允许的最紧年代区间。
- 矛盾分为 `topological-cycle` 与 `empty-interval`；响应给出经过删除敏感性筛选的最小参与证据集合，而不是只返回“图非法”。
- 规则版本固定在分析结果中：`gsb-rules-2026-09-21`。

## 分支、快照和合并

- `main` 是初始工作主线；可从父分支或已确认快照创建任意工作分支。
- 新增证据只写入选择的工作分支；分支复制当时可见的 context、证据与撤回集合。
- 快照只能在分析可行时冻结；快照保留事件序号和输入摘要，后续事件不会改变其视图。
- 合并前用预览找出同一 `evidenceKey` 的不同解释。冲突必须提供人工 `source`/`target` 选择和理由；审计事件会记录选择、撤回和合并原因。
- `/api/compare` 比较多个分支当前共同成立的组件关系和共同区间交集。

## HTTP API

- `POST /api/batch`：一次事务提交多个命令。
- `GET /api/state`：分支、快照和后台任务总览。
- `GET /api/branches/{id}`：当前分支的完整分析、证据、矛盾和任务状态。
- `GET /api/snapshots/{id}`：冻结快照视图。
- `POST /api/merge-preview`：预览合并冲突。
- `POST /api/compare`：跨分支共同结论。
- `GET /api/export?branch=main`：稳定导出和 SHA-256 摘要。
- `GET /api/events?fromSeq=1`：展开事件顺序、载荷和批次序号。
- `POST /api/wait-jobs`：测试或脚本等待当前后台分析完成。

批处理示例：

```json
{
  "idempotencyKey": "field-book-page-42-upload-1",
  "commands": [
    {"type":"create-context","branchId":"main","id":"L3","label":"Layer 3","source":"Field book"},
    {
      "type":"add-relation",
      "branchId":"main",
      "id":"rel-cut-7",
      "evidenceKey":"page-42-cut",
      "fromContextId":"C7",
      "toContextId":"L3",
      "relation":"earlier-than",
      "source":"Field book",
      "pages":"42",
      "strength":"strong"
    }
  ]
}
```

重复提交同一个 `idempotencyKey` 会返回首次响应，不重复应用命令、任务或事件。客户端应使用稳定业务键（来源、页码、上传批次）而不是每次随机重试键。

## 事务和后台计算

- 命令先在内存候选状态上按顺序验证；任一命令失败，整批不写文件、不入队、不改状态。
- 成功批处理原子发布为一个 JSON 文件，文件内包含所有领域事件、每分支一个 `job-enqueued` 事件和操作记录。
- 后台分析完成后以独立的系统批处理追加 `job-finished`；任务 ID 由输入批处理序号和分支 ID 确定，完成事件按同一系统幂等键发布。
- 启动时重放全部事件；`queued`/`running` 任务恢复为 `queued`。同一任务 ID 已有完成结果时不会再次记入。

## 持久化格式

数据保存在 `data/`，每个成功事务是一个 UTF-8 JSON 文件：

```text
000000000001-batch-1-ab12cd34.json
000000000002-job-result-2-ef56ab78.json
```

顶层字段：

- `formatVersion`：当前为 `1`。
- `seq`：从 1 开始的无空号事务序号。
- `batchId`：稳定文件名的一部分。
- `createdAt`：Unix 毫秒时间戳。
- `idempotencyKey`：客户端键或系统任务键。
- `events`：不可变事件数组；每个事件含 `eventId`、`type`、`at`、`payload`。

事件类型包括 `context-created`、`dating-added`、`relation-added`、`dating-retracted`、`relation-retracted`、`branch-created`、`snapshot-created`、`branch-merged`、`branch-status-changed`、`job-enqueued`、`job-finished` 和 `operation-recorded`。

写入采用临时文件加原子改名；文件名以 12 位零填充序号开头。不要手工重排或复用序号。备份时复制整个 `data/` 目录即可。

## 兼容策略

- v1 文件会被当前及后续兼容版本直接读取；未知事件类型将拒绝启动，避免静默丢弃审计信息。
- v2 及以后只允许向前兼容读取旧文件，并在迁移文档中说明字段新增、默认值和重放规则。
- 已发布事件类型和字段不会在同一 `formatVersion` 内删除或改变语义；规则变化会发布新的 `ruleVersion` 并保留分析结果版本字段。
- 稳定导出先按 context/evidence ID 排序，再生成规范 JSON；导出摘要不依赖内存哈希序或机器本地时间，因此同一证据集跨机器摘要一致。

## 测试

`./gradlew --no-daemon test` 覆盖：

- 可行拓扑、直接/传递边和年代区间传播。
- 拓扑环和空区间的最小证据集合。
- 开闭端点、BCE/CE 跨零和无公元零假设。
- 同对 context 多证据与单条撤回。
- 批处理原子性、稳定幂等重放。
- 快照冻结、分支解释冲突、人工合并理由。
- 重启事件恢复、任务恢复与稳定导出摘要。
