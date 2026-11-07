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
事务外：问题 query Embedding → ES BM25 → ES kNN → Java RRF、去重和预算筛选
短事务：evidence_snapshot + retrieval_completed
事务外：qwen3.7-plus 回答 → JSON 格式及引用校验
完成事务：assistant 消息 + message_citation + Run(completed) + run_completed + 释放会话占用
```

所有状态写入先锁聊天室，再锁 Run；终态竞争在锁内判断。取消、失败、超时后不能继续保存助手消息或成功事件。任务状态、同一 Run 内递增的事件序号和事件记录在同一事务提交；SSE 只读取已提交的数据。

## 前端接口

| 方法和路径 | 参数 | 返回 |
| --- | --- | --- |
| `GET /api/agentRun/request-key` | 无 | 新 UUID，不缓存 |
| `POST /api/agentRun/add` | 下述 JSON | `runId` |
| `GET /api/agentRun/get` | `id=runId` | 状态、时间、错误、最终消息及引用 |
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

创建任务时锁定聊天室，保存论文 ID、物理 ES 索引、有效索引版本、入库尝试令牌及向量配置。选中论文必须具有可用且与当前 Embedding 模型兼容的索引；最多 50 篇。任务执行期间普通增删资料只影响后续任务；论文实际删除或撤权会在阶段边界及最终写入前阻止继续使用。

BM25 和 kNN 都按用户归属及逐篇快照过滤。kNN 的过滤位于 `knn.filter` 内，保证在近邻检索阶段限定范围，参考 [Elasticsearch 官方过滤示例](https://www.elastic.co/docs/solutions/search/vector/knn/filtered-knn-search)。ES 超时或分片失败不会被当成完整检索结果。

两路各取 20 个候选，以 RRF（常数 60）融合并去重，最多登记 8 条证据，原文总预算 6000 个 cl100k_base 估算 Token。当前只使用有可靠物理页码且 `lowConfidence=false` 的片段，以匹配引用表非空页码约束。没有有效证据时直接保存明确的无法确认说明，不调用回答模型、不生成引用。

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

无需新增表或迁移：使用现有 `agent_run`、`run_event`、`chat_message`、`message_citation` 及其唯一索引。已有数据库必须已执行仓库当前建表脚本。

离线验证命令（PowerShell 中保留参数引号）：

```powershell
mvn -o '-Dspring.profiles.active=test' test
```

测试使用假凭据、本地 HTTP 替身和 Mapper 替身，覆盖真实 SDK 的 query 向量请求、原生 Chat 请求、两路 ES 过滤、RRF、SSE 重连、幂等、取消/完成顺序、截止时间、事务回滚及资料变更消息。真实 MySQL 专项测试按原项目环境变量条件运行；离线通过不代表已完成部署环境下 ES、MySQL 和真实模型的端到端联调。
