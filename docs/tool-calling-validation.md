# 第二、三批：模型工具调用验证与执行层

## 第二、三批交付范围

第四批已通过生产 `AgentExecutor / AgentHarness` 接入串行多轮循环，入口、证据与预算策略见[最小 Agent 循环](./agent-loop.md)。本文保留第二、三批的协议验证记录；Probe 始终只存在于测试目录，不是当前生产入口。

第二批提供 `agent/model/ToolCallModelAdapter`、测试辅助类 `ToolCallProbe` 和两个只读论文工具。`ToolCallProbe` 位于 `src/test/java/com/ecnu/agentpapermate/agent/model`，由测试显式创建，不注册 Spring Bean，不进入生产构建。第三批将工具路由、执行与结果协议收敛到 `agent/tool/ToolRegistry`、`ToolExecutor` 和 `ToolResult`。第二、三批交付时未注册 agent 执行器；第四批已在保留固定 RAG 的基础上接入。

`ToolCallProbe.verify(context)` 接受后端已领取 Run 的共享上下文，最多发起两次模型请求：

```text
首轮模型请求（带两个工具定义）
  → 检查返回1至2个工具调用，整批校验名称、类型、ID及重复ID
  → 串行逐个执行：状态/权限检查 → tool_started → 参数校验及只读操作
  → 再次检查状态/权限 → tool_completed 或 tool_failed
  → 原 AssistantMessage + 各调用对应的 ToolResponseMessage
  → 第二轮模型请求（不提供工具）
  → 返回协议验证结果，不发布助手消息或引用
```

返回的 `VerificationResult` 保留首轮响应、回填后的响应和工具结果，可核对工具调用 ID、工具名、响应文本及用量。第二轮仍返回工具调用、没有文本、首轮没有工具调用或超过本批数量限制，均明确抛出异常，不声称验证通过。参数无效返回 `status=error / INVALID_TOOL_ARGUMENTS / retryable=false`，不向模型返回异常文本；服务故障、权限失效、取消和超时直接中止验证。没有自动重试。

## 框架与业务边界决策

参考 Wolf-eq-app 的 ToolCallAgent / DateManus，但只复用工具协议和显式回填思路。当前项目继续使用 Spring AI M6、Alibaba M6.1，不引入参考项目的 M7 或 MCP 依赖。

| 负责方 | 本批职责 |
| --- | --- |
| DashScope SDK | 请求/响应转换，传递工具 Schema，解析 AssistantMessage 中的工具调用及 ToolResponseMessage |
| ToolCallModelAdapter | 使用本次 Run 的模型配置；设置 `proxyToolCalls=true`；只发送原生工具定义，不注册可执行回调 |
| RunChatCaller | 每次模型请求前执行 `modelCallStarted`，响应后登记已知 Token；复用超时、取消和无重试策略 |
| ToolCallProbe | 有界验证编排，串行调用 ToolExecutor，正确回填结果 ID；不再自行执行工具或登记进度 |
| ToolRegistry | 将 Schema 和后端处理函数一起注册；重复名称初始化失败；按后端白名单筛选模型可见定义 |
| ToolExecutor | 唯一工具执行主体，负责白名单、参数对象及长度边界、限时等待、调用 ID 去重、预算和进度 |
| PaperTools | 两个工具的参数约束、真实归属及 Run 范围检查、检索和证据登记 |
| RunStore | 在短事务中仲裁存活状态、权限和截止时间，累计工具次数及写进度事件；不修改模型计数规则 |

与参考代码使用 ToolCallingManager 不同，本项目没有向 SDK 注册 FunctionCallback，也没有工具 Advisor。只有 ToolExecutor 调用注册的处理函数；PaperTools 不再提供 switch 路由入口。工具历史和结果列表是 verify 方法的局部变量，不保存在 Spring 单例字段中。

对本地 M6.1 字节码的检查确认：proxyToolCalls 为 true 时跳过 SDK 内部 handleToolCalls/递归模型调用；没有注册函数回调时，直接提供的原生 tools 定义会被保留。现有 BudgetedDashScopeChatModel 在补齐输出预算和 qwen3.7-plus 端点时，保留 tools、toolChoice、toolCallId 和 toolCalls 字段。

用户授权测试后，已运行真实 M6.1 SDK 对本地 HTTP 服务的工具往返测试：首轮携带两个工具 Schema，读取两个工具调用，工具各执行一次；第二轮携带原始 AssistantMessage 和匹配的 ToolResponse，工具 ID 保持一致，模型计数和用量各登记两次。另验证了重复调用 ID 和取消阻止后续执行。测试使用本地模型替身及模拟数据层，没有调用收费模型或业务数据库；这些测试不代表云端模型选工具质量或 MySQL 并发集成测试已经完成。

## 两个最小只读工具

| 工具 | 参数与行为 |
| --- | --- |
| get_paper_metadata | `paperId` 为正整数字符串，必须属于 Run 快照，并核对当前归属。只返回标题、作者、主题、页数和快照索引版本；缺失值保留 null，不生成证据 ID |
| search_paper_chunks | `query` 必填，复用16000 UTF-8字节限制；`requestedPaperIds` 省略时使用完整 Run 范围，显式空值、空数组、重复或越界 ID 拒绝；`topK` 省略时用配置，不能超过 `rag.retrieval-top-k` |

两个工具均拒绝未知参数，模型不能传入 userId、索引名称、版本、deadline 或权限字段。检索仍复用 `retrievalStarted → RagRetriever → EvidenceRegistry.registerRetrieval → retrieved`，复用同一上下文的证据编号与候选并集，不重建注册器。metadata 不替换 Run 的索引快照；作者、主题、页数来自访问时的元数据，未承诺创建 Run 时的元数据历史版本。

`executionAllowed` 只检查存活状态，不推进业务阶段、不累计次数。`toolCallStarted` 累计一次工具尝试并写入 `tool_started`；`toolCallFinished` 写入 `tool_completed` 或 `tool_failed`，不记录参数、结果正文和异常详情。终态后不追加迟到工具事件。事件新增不会改变 execution_phase 的判定，也不改变 retrieval_snapshot 和 evidence_snapshot 的语义。

## 后端调用约定与剩余工作

调用者必须独立领取一个开发用 Run，在 start 事务提交后创建一次 AgentRunContext，再调用一次 Probe；不能与 Scheduler 对同一 Run 同时执行，也不能把生产固定 RAG 的上下文拿来旁路验证。Probe 不负责领取和重放任务。

Probe 返回的是原始模型响应，不是已通过 AnswerValidator 的最终答案；调用者必须自行收尾并释放 Run 占用，不能直接传入 `RunStore.complete`。协议验证结束后可使用 `RunStore.cancel` 结束开发 Run；异常可按现有 Dispatcher 规则调用 fail，或由截止时间回收。当前未新增可从浏览器触发的入口。

## 第三批执行层约定

`ToolRegistry` 将 ToolDefinition 与后端处理函数一同注册，不依赖反射或模型提供的实现名称。`definitions(allowedTools)` 只返回系统注册集合和后端白名单的交集；Executor 再次检查同一白名单。Schema 对应的类型、必填字段、未知字段、topK 和论文范围由注册处理函数明确校验，不引入通用 JSON Schema 引擎。

工具结果固定为 `status / code / retryable / data / evidence`。元数据缺失字段仍为 null，证据集合为空；检索工具把候选累计集合放在 data，当前选择的可引用证据单独放在 evidence。非法参数返回 INVALID_TOOL_ARGUMENTS；未知或禁止工具返回 TOOL_NOT_ALLOWED；已经使用的调用 ID 返回 DUPLICATE_TOOL_CALL。上述结果不包含异常文本，均不自动重试。权限失效、执行故障和超时向运行入口传播，避免在不完整的检索阶段继续执行后续模型轮次。

新增 `rag.max-tool-calls=12`、`rag.tool-timeout-seconds=20`；允许范围分别为1至100次、1至300秒。新 Run 将 `maxToolCalls` 和 `toolTimeoutSeconds` 写入 model_config，不受运行期间配置变更影响；旧 Run 缺少配置时回退当前默认值。原有 maxModelCalls 保持独立。单工具等待还受整个 Run 截止时间和外部调用等待上限约束。

整个 Run 的截止时间单独保存在 `AgentRunContext.deadline`；ToolExecutor、RunChatCaller 直接使用该字段，PaperTools 将其作为独立参数传给 RagRetriever。RetrievalContext 只保存用户和论文范围，不再承载 Run 的时间预算。

`RunStore.toolCallStarted` 在原有会话、Run 锁内检查工具调用 ID、预算和权限，原子累计 tool_call_count 并写入 tool_started。达到上限后的下一次尝试会提交 failed / TOOL_CALL_LIMIT 并释放会话。未知或禁止工具、重复调用 ID 不消耗次数；已被允许的工具因参数无效失败仍计一次尝试。

调用 ID 的去重依据为当前 Run 已提交的 tool_started 事件，因此不依赖单例内存集合。Run 行锁阻止同一 ID 被同时领取；同一 ID 换工具名也不能再次执行。完成事件必须匹配已登记的 ID 和工具名，重复完成不追加事件。此处不缓存或重放旧工具结果，不支持重启后恢复执行；未使用结果的调用也不会自动重试。当前不清理活动 Run 的事件；未来如增加事件清理，必须保留调用去重依据或迁移到独立调用表。

工具由调用方串行执行，不允许同时修改同一上下文的 EvidenceRegistry；ToolExecutor 不承担并行工具调度。单工具超时后停止整个 Probe 或 AgentHarness，丢弃迟到返回值，不带着未完成检索阶段继续循环。第四批 AgentHarness 已接管循环停止、上下文预算、最终证据选择及回答校验，并通过 Dispatcher 收尾。

## 测试覆盖

ToolCallingHttpTest 验证原生 SDK 的工具定义、两轮消息、调用 ID、唯一执行和模型用量；ToolExecutorTest 覆盖白名单、重复注册、参数边界、越界论文、缺失元数据、证据共享、取消、权限撤销、重复调用拒绝和单工具超时；RunStoreTest 覆盖预算快照、原子计数、持久化调用 ID、完成事件去重、取消后拒绝迟到进度，以及原有消息、引用和终态语义。数据层使用模拟对象，真实行锁竞争仍需后续 MySQL 集成验证。

2026-11-10 验证记录：第二批先运行37项相关测试，全部通过后开发第三批。第三批完成后分批运行工具层、Run 状态、固定 RAG、调度、SSE、评测和 Mapper 配置测试；10个测试类的最新报告共79项，失败0、错误0、跳过0。最后针对变更后的执行器和异常边界复跑 ToolExecutorTest、ToolCallingHttpTest、RunStoreTest，共47项全部通过。主代码及测试代码编译通过，差异检查没有发现空白错误。
