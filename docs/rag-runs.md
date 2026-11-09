# 固定 RAG 问答与进度事件

本模块沿用现有登录 Session、MyBatis-Plus 雪花 ID 和上海时间。接口前缀为 `/api`，返回项目统一的 `BaseResponse`；Long ID 以字符串返回，前端不要转成 JavaScript Number。

## 配置与执行

`application.yml` 默认开启 `rag.enabled`，回答模型为 `qwen3.7-plus`。`ragChatModel` 沿用 `spring.ai.dashscope.base-url`、`api-key` 和 `workspace-id`，通过原生 DashScope ChatModel 调用，不拼接 `/compatible-mode`。`spring.ai.dashscope.chat.enabled=false` 只关闭 SDK 自动配置，避免与显式创建的客户端重复。

论文入库继续使用 `paperEmbeddingModel` 的 `document` 模式；问题向量使用独立的 `ragQueryEmbeddingModel`，默认 `query` 模式。当前 M6.1 SDK 忽略单次 EmbeddingRequest 的 textType 覆盖，不能通过临时修改共享模型的配置解决。两个客户端沿用相同 Embedding 模型和连接设置。

当前 SDK 的 `getMaxTokens()` 恒返回 null，`BudgetedDashScopeChatModel` 在原生请求边界补齐任务的 `max_tokens`；`getCompletionTokens()` 恒返回 0，回答用量读取原生 `getGenerationTokens()`。本地 HTTP 测试直接检查请求和返回用量，后续升级 SDK 时应重新验证这些兼容处理。

`qwen3.7-plus`（含日期版本）即使只输入文本，也必须调用 `multimodal-generation` 端点。M6.1 默认使用文本端点，会返回 `400 InvalidParameter: url error, please check url`。上述适配器根据任务实际模型设置原生请求的 `multiModel` 标志，并将字符串消息转换为 `[{"text":"..."}]`；`qwen-plus` 等文本模型继续使用文本端点。切换其他模型时应核对其端点要求，不能只修改 base-url。参见[百炼错误码说明](https://help.aliyun.com/zh/model-studio/error-code/)。

默认参数：每实例同时执行 4 个 Run，单次外部请求最长 60 秒，运行总预算 180 秒，排队最长 300 秒；每秒扫描数据库队列。模型调用与检索都在事务外执行。

关闭 `rag.enabled` 会拒绝新任务，但已有任务仍会回收到终态；`rag.scheduler.enabled=false` 则完全关闭本实例的问答调度，主要用于离线测试。至少一个服务实例必须启用调度。

流程：

```text
创建事务：Run(pending) + user 消息 + 会话 active_run_id
领取事务：Run(running) + start_time/deadline + run_started
短事务：retrieval_started
事务外：问题 query Embedding → ES BM25 → ES kNN → Java RRF、去重 → 保留完整候选 → 预算筛选
短事务：retrieval_snapshot + evidence_snapshot + retrieval_completed
事务外：qwen3.7-plus 回答 → JSON 格式及引用校验
完成事务：assistant 消息 + message_citation + Run(completed) + run_completed + 释放会话占用
```

所有状态写入先锁聊天室，再锁 Run；终态竞争在锁内判断。取消、失败、超时后不能继续保存助手消息或成功事件。任务状态、同一 Run 内递增的事件序号和事件记录在同一事务提交；SSE 只读取已提交的数据。

## 业务阶段与数据库升级

`agent_run.execution_phase` 记录业务阶段；`status` 记录运行生命周期，`event_seq` 只用于事件排序和 SSE 补发。增加技能或工具进度事件不会改变业务阶段，也不会阻止原有流程继续执行。

| 方法 | 允许的原阶段 | 提交后的阶段 |
| --- | --- | --- |
| `create` | 新任务 | `queued` |
| `start` | `status=pending` | `ready` |
| `retrievalStarted` | `ready`、`evidence_ready`、`generating` | `retrieving` |
| `retrieved` | `retrieving` | `evidence_ready` |
| `answerGenerationStarted` | `evidence_ready`、`generating` | `generating` |
| `modelCallStarted` | 任意阶段，要求 `status=running` 且未超时 | 保留业务阶段，累计一次调用尝试 |
| `complete` | `evidence_ready`、`generating` | 保留最后阶段，`status=completed` |

阶段检查在 Run 行锁内执行。检索开始的阶段更新与开始事件一起提交；检索完成的两份快照、阶段更新和完成事件一起提交。可在上一轮证据已就绪或模型已调用后开始下一轮；检索进行中重复开始、未开始就提交结果、未准备证据就完成仍会被拒绝。模型再次调用仍累计 `step_count`，此处不新增自动修复或重试。

进入回答生成阶段和完成回答前均检查检索、证据快照：SQL null 或 JSON null 不表示有效空结果，JSON 空数组 `[]` 才表示检索完成但没有结果。公共模型调用检查不要求已有证据，因此 Agent 首轮选择工具时也可以调用。空结果允许跳过模型，从 `evidence_ready` 直接保存无法确认的回答。权限、截止时间和引用校验继续生效。失败、取消和超时保留最后业务阶段，便于定位停止位置。

已有数据库需要在启动新代码前手动执行 `sql/migrations/20261109_add_run_execution_phase.sql`，仅执行一次。升级顺序：停止所有实例的 Run 写入，完整执行加列和历史回填，再启动新版本。新数据库使用更新后的 `sql/create_table.sql`，不重复执行增量脚本。

历史回填按模型轮数、两份快照、检索开始事件类型和开始时间确定最后可确认的阶段，不读取事件序号，也不改变历史状态、事件或更新时间。历史事件已清理时无法精确还原的阶段按剩余记录回填。升级不会恢复旧 `running` 任务，仍由原有截止时间回收流程结束它们。

本次阶段解耦保留固定 RAG 单次检索流程；Agent 多轮工具执行及证据合并另行接入。

## 按模型调用累计用量

`RunChatCaller` 在 `RagCalls` 的限时操作内、真正调用聊天模型之前调用 `RunStore.modelCallStarted()`。它在短事务中锁定最新 Run，检查运行状态、截止时间和当前资料权限，并累计一次 `step_count`，不要求证据、不修改业务阶段。`answerGenerationStarted()` 仅检查证据和切换生成阶段，不再计数。每次通过检查的调用尝试计一次，模型请求失败也不回退次数；未通过检查则不发起请求。

`rag.max-model-calls` 默认 8，允许范围 1 至 100。创建 Run 时在现有 `model_config` 中记录 `maxModelCalls` 预算快照，已有 Run 缺少该项时使用当前配置。达到上限后，下一次调用检查会将 Run 结束为 `failed / MODEL_CALL_LIMIT`，释放会话占用并返回 false。取消或超时也阻止后续模型调用；调用入口抛出的中断异常不会覆盖已保存终态。修复或显式重试也必须通过同一入口，此处不增加自动重试。该次数上限独立于 Token 用量统计，不是总 Token 预算。

`RunChatCaller.call(run, prompt)` 是固定 RAG 和后续 Agent 共用的聊天调用入口。通过 `RagCalls` 获得响应后，先读取 usage 并调用 `RunStore.addModelUsage()`，再将响应交给回答解析和引用校验。因此回答格式错误、引用失败或最终消息写入失败，不会回滚已经记录的模型用量。DashScope M6.1 的输出 Token 继续读取原生 `getGenerationTokens()`。

`addModelUsage()` 使用独立短事务，沿用会话、Run 的锁顺序，再通过专用 SQL 对现有 `input_tokens`、`output_tokens` 原子累加，检查负数和 Java long 溢出。它不保存传入执行器的旧 Run 对象，不修改任务状态。响应已取得但任务刚好被取消或超时时，允许仅补记消耗。底层响应若已被超时或中断丢弃，则无法补记其未知用量。

`Answer` 只包含正文和引用 ID；`complete()` 不再设置 Token。此次没有新增表、字段或 callId。累计接口非幂等，同一次响应只能提交一次；数据库报错或提交结果不确定时直接传播异常，不自动重试记账或模型调用。模型调用和记账入口应在业务事务之外使用。

数据库保存的是上游已经报告并成功记录的累计值；缺失 usage 不代表真实消耗为零，此版本不记录用量完整性，也不补造历史用量。Embedding 用量不计入聊天模型这两个累计字段。后续 Agent 每轮聊天调用均应经过此入口，共用调用次数检查与计数；任务方法、工具和上下文预算由执行流程进一步管理。

## 前端接口

| 方法和路径 | 参数 | 返回 |
| --- | --- | --- |
| `GET /api/agentRun/request-key` | 无 | 新 UUID，不缓存 |
| `POST /api/agentRun/add` | 下述 JSON | `runId` |
| `GET /api/agentRun/get` | `id=runId` | 状态、时间、错误、最终消息及引用 |
| `GET /api/agentRun/evaluation` | `id=runId` | 评测采集数据，使用 `snake_case` 字段名 |
| `POST /api/agentRun/cancel` | `{"id":"runId"}` | 最终状态字符串 |
| `GET /api/agentRun/events` | `id=runId&afterEventSeq=0` | SSE；也支持 `Last-Event-ID` |
| `GET /api/runEvent/list` | `runId`、`afterEventSeq=0` | 按序返回最多 100 个事件 |
| `GET /api/chatMessage/list/page` | `sessionId`、`pageNum=1`、`pageSize=20` | 消息倒序分页，包含资料变更消息 |
| `GET /api/messageCitation/list` | `messageId` | 所属消息的引用快照 |

```json
{
  "sessionId": "1234567890123456789",
  "requestKey": "ec9f7902-161b-43a1-951e-33b07e414722",
  "question": "这些论文采用了哪些检索方法？"
}
```

`retryOfRunId` 可选，主动重新执行已结束任务时传旧任务 ID，并使用新 `requestKey`。前端也可以直接生成 UUID；后端签发的键不是身份凭证，所有接口都必须登录。

同一会话、同一键、相同问题和重试关联返回原 `runId`，即使原任务已经结束，也不会再次执行。相同键提交不同内容、会话已有其他活动任务时，由 `GlobalExceptionHandler` 返回业务码 `40900`。业务异常沿用项目统一处理方式，HTTP 状态为 200，前端根据响应体的 `code` 判断结果。问题不超过 16000 个 UTF-8 字节，键为 16～64 位字母、数字、下划线或连字符。

## 评测数据查询

`GET /api/agentRun/evaluation?id=<runId>` 需要现有登录 Session，返回统一的 `BaseResponse`，并设置 `Cache-Control: no-store`。服务端只按当前登录用户校验任务及会话归属，不接受客户端 `userId` 作为授权依据；不属于当前用户、已删除或不存在的任务按现有规则返回业务错误。接口只读取已保存的数据，不触发检索、生成或模型裁判。

成功响应示例（示意数据）：

```json
{
  "code": 0,
  "data": {
    "run_id": "1234567890123456789",
    "status": "completed",
    "question": "论文采用了什么方法？",
    "retrieved_chunk_ids": ["chunk_A", "chunk_B", "chunk_C"],
    "final_evidence_chunk_ids": ["chunk_A", "chunk_B"],
    "final_evidence": [
      {"evidence_id": "E1", "paper_id": "21", "chunk_id": "chunk_A", "text": "实际进入模型的原文 A"},
      {"evidence_id": "E2", "paper_id": "21", "chunk_id": "chunk_B", "text": "实际进入模型的原文 B"}
    ],
    "answer": "论文采用了该方法。[E1]",
    "citations": [
      {"evidence_id": "E1", "paper_id": "21", "chunk_id": "chunk_A", "page_start": 5, "page_end": 5}
    ],
    "latency_ms": 18000,
    "input_tokens": 1200,
    "output_tokens": 310,
    "error_code": null,
    "error_message": null
  },
  "message": "ok"
}
```

- `retrieved_chunk_ids` 返回整个 Run 已成功保存的检索候选去重并集，按首次出现顺序保存；`final_evidence` 来自完整的最终证据快照，其中 `text` 对应 `quotedText`。`citations` 只包含助手实际引用的条目，不能用全部最终证据代替。
- SQL `NULL` 快照导出为 null，不从其他字段补造；已采集空数组导出为 `[]`。损坏、类型不符或重复 ID 的候选快照作为数据异常处理，不静默变成空检索。
- `latency_ms` 是终态任务的 `end_time - start_time`，含检索、模型及工具执行，不含排队和脚本轮询；未进入终态、尚未开始执行或起止时间不完整时为 null。
- 已完成任务返回已发布的答案及引用；未完成、失败、超时或取消的任务返回 `answer: null`、`citations: null`，但保留已经提交的检索和最终证据快照以及错误信息。
- `input_tokens`、`output_tokens` 仅在 completed 时返回数据库中的已记录累计值。其他状态仍返回 null：虽然失败任务现在能保留已取得的用量，但缺少完整性标记时不能将部分消耗作为完整总量。缺失 usage 的消耗无法由累计值推断；无证据且没有调用模型的 completed 任务可返回 0。
- 所有 ID 是字符串，耗时和 Token 是 JSON 数字，适合直接写入评测预测记录。题集 ID（如 A09）由采集脚本关联；`judge_*` 字段由后续评分阶段补充，接口不生成评分。内部模型配置、论文权限快照和文件 URL 不对外导出。

## 事件与取消

事件类型：`run_started`、`retrieval_started`、`retrieval_completed`、`run_completed`、`run_failed`、`run_cancelled`、`run_timeout`。一个任务可能在开始执行前就取消或排队超时，因此不保证首个事件一定是 `run_started`。

```text
id:3
event:retrieval_completed
data:{"runId":"123","eventId":"3","type":"retrieval_completed","timestamp":"2026-11-06T10:00:00","payload":{"evidenceCount":5}}
```

完成事件的 `payload` 包含 `status`、`messageId`、`content`、`citations`；失败终态携带 `errorCode` 和安全的错误摘要，不透传模型响应体或凭据。第一阶段不发送未校验的 `answer_delta`。

前端使用 `addEventListener("retrieval_completed", ...)` 等监听命名事件，不能只依赖 `onmessage`。收到任一终态事件后关闭 EventSource。断网、刷新、关闭页面仅断开订阅，后台继续运行；显式点击“停止生成”才调用取消接口。

自动重连时 `Last-Event-ID` 优先于 URL 中的游标。手动重建连接可传最后接收的 `afterEventSeq`。前端按 `(runId,eventId)` 去重，不能因为 SSE 断线重复创建问题。心跳每 15 秒发送一次；订阅每 5 分钟结束一次，由 EventSource 自动重连，任务不受影响。

事件缺失时返回无序号的 `run_snapshot` 事件并关闭连接，前端应用快照并查询任务最终状态。当前未启用事件清理；未来添加保留期任务时可沿用此行为。Nginx 应关闭 SSE 响应缓冲，读超时大于心跳间隔。

## 检索范围和引用

检索入口为 `RagRetriever.retrieve(RetrievalRequest request, RetrievalContext context)`，不再接收 `AgentRun`。执行层负责读取持久化快照、构造后端上下文，检索器只处理本次查询和受限资料范围。

- `RetrievalRequest(query, requestedPaperIds, topK)`：query 非空且不超过 16000 个 UTF-8 字节；topK 为每路候选数量，构造时限制 1 至 100，执行时还必须不超过 `rag.retrieval-top-k`。该配置同时是固定 RAG 的默认值和 Agent 的上限；超限请求在调用外部服务前报错，不静默截断。融合后候选可能多于 topK。requestedPaperIds 为 null 时使用完整上下文范围；显式空列表、重复 ID、无效 ID 和范围外 ID 均拒绝，不静默删掉越界项。
- `RetrievalContext(userId, allowedScopes, deadline)`：仅由后端根据当前 Run 构建，不作为 HTTP 或模型工具的直接输入。范围列表不可变，保留 Run 固定的索引、版本、尝试令牌和向量配置，范围总数受 `rag.max-papers` 限制。请求只能从该范围选择子集。
- Embedding、BM25、kNN 统一使用 request.query；BM25 与 kNN 统一使用解析出的子集和上下文 userId。访问前重新检查论文当前归属，`RunStore` 在保存证据和发布答案前再次验权；普通会话资料调整不会替换启动时快照，删除或撤权则阻止继续访问或发布结果。
- 外部请求使用 `RagCalls.call(deadline, operation)`，继续受单次请求和整个 Run 截止时间约束。旧的 `call(AgentRun, operation)` 委托给这个入口，兼容聊天模型调用。

固定 RAG 在 `RunProcessor` 中这样调用：

```java
var request = new RetrievalRequest(run.getUserQuery(), null, properties.getRetrievalTopK());
var context = new AgentRunContext(run.getId(),
        new RetrievalContext(run.getUserId(), json.scopes(run.getPapersSnapshot()), run.getDeadline()));
var candidates = retriever.retrieve(request, context.getRetrievalContext());
var retrieval = context.getEvidenceRegistry().registerRetrieval(candidates);
```

后续 Agent 可复用该上下文，通过 `new RetrievalRequest("实验中的消融设置和结果", List.of(paperId), 10)` 发起补充查询；paperId 必须属于上下文范围，topK 不得超过配置上限。每次检索均不修改 Run 原始问题。

### Run 级证据登记

`AgentRunContext` 每个 Run 创建一次，包含不可替换的 `RetrievalContext` 和 `EvidenceRegistry`，不作为 Spring 单例。后续各工具共享这个运行上下文，不能每次工具调用都重建注册器。

`RagRetriever` 返回 `RetrievalCandidates`：本轮完整的有序候选 Chunk ID 和预算筛选后未编号的 `EvidenceCandidate`。执行层调用 `context.getEvidenceRegistry().registerRetrieval()`，先登记原文，再累计候选 ID，得到 `RetrievalResult`：其中 `retrievedChunkIds` 是整个 Run 的候选并集，`evidence` 仅为本轮登记的证据子集，再将其交给 `RunStore.retrieved()` 和回答生成器。现有检索候选快照、证据快照和引用落库结构保持兼容。

注册器以论文 ID、ES 索引、索引版本、尝试令牌和 Chunk ID 识别来源，同时核对候选所属的完整后端快照。首次登记分配 `E1`、`E2` 等 ID；相同来源、原文和页码复用旧 ID；同一来源原文或定位不同则报错，不能覆盖已登记证据。整批校验通过后才分配编号，本批返回结果也去重。不同 Run 各自从 E1 开始。注册器只递增编号，不提供删除或重置操作。

例如第一次检索 A、B 得到 E1、E2；第二次检索 B、C 返回 E2、E3，注册器全集为 E1、E2、E3。`snapshot()` 返回不可修改的全集快照，`size()` 返回已登记数量；全集不自动等同于最终回答实际使用的证据。

`EvidenceRegistry` 内部分别保存候选 ID 集合和已登记的原文证据。调用方通过 `context.getEvidenceRegistry()` 直接登记检索结果或读取候选集合；`AgentRunContext` 仅持有注册器，不提供同名委托方法。注册器的 `getRetrievedChunkIds()` 返回不可修改的全 Run 候选快照。例如第一轮 `[A,B,C]`、第二轮 `[B,D]`，累计为 `[A,B,C,D]`；本轮没有证据、只有候选时仍累计候选，本轮候选为空时保留已有集合。不用证据反推候选，不把所有候选都作为可引用证据。

`RunStore.retrieved()` 在行锁内把传入候选与数据库已有数组再次合并，防止只传本轮候选或较旧的累计快照时覆盖前面的记录。每轮按 `retrievalStarted → retrieve → registerRetrieval → retrieved` 执行，已保存的轮次在后续失败、取消或超时后仍保留；终态或截止时间之后到达的新结果不写入。`retrieval_completed.payload.candidateCount` 为累计不重复候选数，`evidenceCount` 为本轮证据数。

当前固定 RAG 仍只检索一次，候选顺序与数量保持原单轮语义。后续 Agent 执行器可复用上述串行流程，最终模型输入选择另行接入；`evidence_snapshot` 保存本轮选择的证据，不自动保存注册器全集。章节和页面工具后续需要提供稳定来源定位后复用注册器。编号和内存候选集合不支持重启后恢复同一个 Run，不新增数据库字段或表。

创建任务时锁定聊天室，保存论文 ID、物理 ES 索引、有效索引版本、入库尝试令牌及向量配置。选中论文必须具有可用且与当前 Embedding 模型兼容的索引；最多 50 篇。任务执行期间普通增删资料只影响后续任务；论文实际删除或撤权会在阶段边界及最终写入前阻止继续使用。

BM25 和 kNN 都按用户归属及逐篇快照过滤。kNN 的过滤位于 `knn.filter` 内，保证在近邻检索阶段限定范围，参考 [Elasticsearch 官方过滤示例](https://www.elastic.co/docs/solutions/search/vector/knn/filtered-knn-search)。ES 超时或分片失败不会被当成完整检索结果。

两路各取 20 个候选，以 RRF（常数 60）融合并去重，最多登记 8 条证据，原文总预算 6000 个 cl100k_base 估算 Token。当前只使用有可靠物理页码且 `lowConfidence=false` 的片段，以匹配引用表非空页码约束。没有有效证据时直接保存明确的无法确认说明，不调用回答模型、不生成引用。

`agent_run.retrieval_snapshot` 保存全 Run 各轮范围、版本及来源校验通过的 RRF 候选 Chunk ID 去重并集，采集位置在证据 Token、最大证据数量和相同原文筛选之前。其长度用于候选数量统计，与 Gold 的交集用于全部候选 Gold 覆盖率。单轮数组仍保持 RRF 排名；多轮按首次出现顺序追加，不构成跨轮相关度排名，不能无说明地将其前5/10条和固定 RAG 的排名指标直接比较。`evidence_snapshot` 保存本轮选用的证据，两份快照与 `retrieval_completed` 在同一短事务提交。SQL `NULL` 表示未采集或未提交，JSON `[]` 表示已保存至少一轮检索但累计没有候选；后续空轮次不清空历史集合。取消或超时后的新结果不得迟到写入；后续模型失败不会清空已提交的快照，历史任务不补填。

模型只输出 `answer` 和 `evidenceIds`。服务端检查 ID 属于本次证据、正文 `[E1]` 标记与结构化 ID 一致，随后补齐论文、页码、文件 URL 和检索原文快照。无效引用直接使任务失败，不发布原始模型正文。历史仅纳入最多 6 条有长度预算的用户问题以理解追问，旧助手答案不作为新证据。

这是来源及格式校验，不是对每个论断的自动语义证明。`quoted_text` 保存当前解析器提取的片段正文，解析质量仍受 PDF 提取结果影响。当前实现没有自动修复回答、自动重试模型或断点续跑。

## 聊天室资料变更

沿用 `/api/sessionPaper/add` 和 `/api/sessionPaper/update`。新增、恢复、移出发生实际集合变化时，同事务更新关联、增加 `scope_version`、更新最近活动时间，并插入：

```text
role = system
message_type = scope_change
run_id = null
content = 已添加资料：《论文标题》 / 已移除资料：《论文标题》
extra_data = beforePaperIds、afterPaperIds、scopeVersion、paperId、action
```

重复添加、移除或恢复不增加消息或范围版本。系统消息插入失败会回滚资料关联与版本更新。

## 运行恢复与测试

`pending` 来自数据库队列，提交成功后即使进程退出仍可被下一实例领取。领取后没有自动重试；崩溃遗留的 `running` 任务在持久化的 `deadline` 到达后由扫描器标记 `timeout` 并释放会话占用，避免新实例误杀其他实例正在执行的任务。取消尽可能中止底层请求；不支持中止的调用可以继续消耗上游资源，但其结果不能写入任务。

使用现有 `agent_run`、`run_event`、`chat_message`、`message_citation` 及其唯一索引。已有数据库的 `agent_run` 必须包含新增的 `retrieval_snapshot JSON NULL` 字段；新建数据库使用仓库当前建表脚本。评测数据由独立的 `/agentRun/evaluation` 接口读取，现有前端详情及 SSE 接口返回结构保持不变。

离线验证命令（PowerShell 中保留参数引号）：

```powershell
mvn -o '-Dspring.profiles.active=test' test
```

测试使用假凭据、本地 HTTP 替身和 Mapper 替身，覆盖真实 SDK 的 query 向量请求、原生 Chat 请求、两路 ES 过滤、RRF、SSE 重连、幂等、取消/完成顺序、截止时间、事务回滚及资料变更消息。真实 MySQL 专项测试按原项目环境变量条件运行；离线通过不代表已完成部署环境下 ES、MySQL 和真实模型的端到端联调。
