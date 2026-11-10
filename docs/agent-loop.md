# 第四批：最小 Agent 循环

本批只新增最小串行 Agent，使用 `get_paper_metadata`、`search_paper_chunks` 两个只读工具。没有实现 Skill 注册、加载、路由或新工具；固定 RAG 仍为默认入口，不需要新增数据库字段或迁移。

## 修改文件与职责

以下 Java 路径均位于 `src/main/java/com/ecnu/agentpapermate`，测试位于对应的 `src/test/java`。

| 文件 | 职责 |
| --- | --- |
| `agent/AgentHarness.java`（新增） | 串行多轮编排、消息与 ID 配对、可见证据追踪、上下文预算 |
| `service/run/AgentExecutor.java`（新增） | 将 Harness 注册为 agent 执行策略 |
| `service/run/RunDispatcher.java` | 统一处理 Harness 的受控失败码，沿用完成/失败入口 |
| `service/run/rag/RunStore.java` | 创建类型校验和幂等、Agent 多轮已保存证据合并、最终证据选择 |
| `entity/dto/run/RunCreateRequest.java` | 可选 executorType 请求字段 |
| `rag/RagAnswerGenerator.java` | 将现有 parse 方法开放给 Agent 复用，无新增模型调用 |
| `config/rag/RagProperties.java`、`src/main/resources/application.yml` | Agent 上下文预算配置、合法范围及快照来源 |
| `rag/AgentHarnessHttpTest.java`（新增） | 本地 HTTP 模型下的生产循环测试 |
| `rag/RunStoreTest.java`、`rag/RunEvaluationTest.java` | 状态事务、证据/评测一致性、创建接口回归 |
| `docs/agent-loop.md`（新增）、`docs/rag-runs.md`、`docs/tool-calling-validation.md` | 当前入口、执行决策、边界与验证说明 |

## 触发与兼容

使用已有登录 Session 和选好论文的聊天室，调用 `POST /api/agentRun/add`：

```json
{
  "sessionId": "已有聊天室ID",
  "requestKey": "agent-request-0001",
  "question": "比较论文中两组实验，并引用原文说明差异",
  "executorType": "agent"
}
```

`executorType` 仅接受精确的 `rag`、`agent`；省略或 JSON null 使用 `rag`。空字符串、大小写变体、任意其他类型均拒绝。幂等校验增加执行器类型：同一键换执行器返回冲突；省略类型与显式 `rag` 等价。重试仍需新幂等键，可以关联本聊天室的已结束 Run。

登录、会话归属、论文范围、索引兼容性、活动 Run 占用、队列领取全部沿用原链路。创建请求不能传入模型、白名单或预算。查询、取消、SSE、评测继续使用现有接口；本批未增加前端执行器选择控件。

## 执行与职责

```text
RunScheduler 领取并提交
  → RunDispatcher 选择 agent，创建一次 AgentRunContext
  → AgentExecutor → AgentHarness
      → 检查状态、权限与上下文预算
      → ToolCallModelAdapter → RunChatCaller 单轮模型请求
      → 检查迟到结果
      → 有工具调用：整批检查 ID/协议 → 串行 ToolExecutor → 原 AssistantMessage + 匹配 Tool Result
      → 下一轮模型请求
      → 无工具调用：复用 RagAnswerGenerator.parse / AnswerValidator
      → RunStore.agentAnswerReady 核对并选择最终证据
  → RunDispatcher → RunStore.complete
      → 再检查终态/权限/截止时间/引用 → 助手消息、引用、成功事件及释放会话一次事务提交
```

SDK 设置 `proxyToolCalls=true`，没有可执行回调或自动工具 Advisor。Harness 保留原始 AssistantMessage，不重新拼造工具调用；每个 Tool Result 的 ID、名称必须匹配对应调用。模型响应同时含工具与文本时先处理工具，文本不作为最终答案发布。无工具响应必须符合既有答案 JSON 格式，空文本或非法引用失败，不额外请求模型修复。

RunDispatcher 是执行结果与异常的统一协调入口；Harness 和 Executor 不调用 complete/fail，不另建上下文，不重试整轮。RunStore 仍是唯一状态事务实现。取消、扫描超时、预算耗尽仍在既有事务入口仲裁，Dispatcher 的后续中断报告不会覆盖已经提交的终态。

## 预算、错误与取消

- 每轮仅由 RunChatCaller 调用一次 `modelCallStarted` 并登记已知用量。Harness 不计步、不登记 Token；SDK 不隐式续轮。
- 允许的工具尝试仅由 ToolExecutor 调用一次 `toolCallStarted`。参数错误消耗一次工具尝试；未知工具不消耗工具预算，仍受模型轮数限制。
- 模型/工具预算耗尽分别保留 `failed / MODEL_CALL_LIMIT`、`failed / TOOL_CALL_LIMIT`。已允许的最后一轮可正常回答；试图发起超额调用时失败，不伪造答案。
- 同批或跨轮重复 ID 在整批执行前以 `DUPLICATE_TOOL_CALL` 失败，避免结果关联歧义；ToolExecutor/RunStore 仍保留持久化去重防线。未知工具和无效参数以对应 ID 回填受控错误，模型可用新 ID 修正。没有自动重试。
- 工具外部异常、权限撤销、取消、超时立即停止，后续调用及下一轮模型不再执行。参数/白名单拒绝属于可反馈的工具结果，不等同于外部执行故障。
- 在每轮请求前、模型响应后、每个工具前后和最终证据选择处检查运行状态；完成事务再次检查。等待超时会请求中断并丢弃迟到结果，即使底层请求不能及时终止，也不会发布成功答案。
- 当前 `application.yml` 已有模型调用 20 次、工具尝试 20 次、单工具 20 秒、请求 60 秒、Run 180 秒；本批保持这些值。类默认值分别为 8 次、12 次等，实际以创建时保存的配置为准。
- 新增 `rag.agent-context-token-budget=24000`，创建时保存快照。每轮估算全部历史、工具参数与结果、工具 Schema、协议余量和预留输出；超过上限为 `failed / AGENT_CONTEXT_LIMIT`。不静默裁掉已引用证据。cl100k_base 仅是估算，不是目标模型精确 Token 计费或模型上下文窗口的保证；它也不是累计 Token 费用预算。

## 多轮证据和评测

同一 AgentRunContext 的 EvidenceRegistry 为全 Run 分配稳定证据 ID，同一来源复用 ID，不允许相同 ID 对应不同文本。

1. `retrieval_snapshot` 仍为所有成功检索候选的去重并集，包括未进入模型的候选。
2. Agent 每轮 `retrieved` 合并已保存的原文证据，避免后续检索覆盖早期证据；固定 RAG 的本轮证据保存方式不变。`retrieval_completed.evidenceCount` 仍表示本次工具检索选中的证据数。
3. Harness 仅收集成功 Tool Result 的 evidence，并核对其属于共享注册器。元数据、候选 ID、旧问题、未交给模型的登记项不能成为引用依据。
4. 完整工具历史保留到最终模型请求。最终证据选择为其中实际提供的证据去重并集，而非仅被引用条目。RunStore 在锁内逐项确认这些证据已保存、内容一致，再写最终 evidence_snapshot。
5. 完成事务再次使用该快照校验答案，仅把实际引用条目写入 message_citation。因此评测中的 retrieved_chunk_ids、final_evidence、citations 分别表示候选、最终模型可见证据、实际引用。

无检索或检索为空时最终显式保存 `[]`，允许返回澄清/无法确认的结构化回答，不伪造检索事件。非空证据时仍沿用既有至少引用一条的规则。引用检查验证身份及标记一致性，不自动证明论断的语义正确性。

失败/取消/超时保留已提交的阶段快照；这些可能包含尚未送入下一轮模型的工具证据，不能当作成功回答的最终上下文。评测延续原语义：只有 completed 才有发布的答案和实际引用，其他状态的用量输出仍为 null。

## 验证与后续边界

`AgentHarnessHttpTest` 使用真实 DashScope SDK 和本机 HTTP 替身，验证三轮补充检索、串行工具、调用 ID、计数、早期证据引用、空结果、错误参数、未知工具、重复 ID、预算拒绝、取消、超时、外部故障、上下文超限和引用拒绝。RunStoreTest 使用事务代理及模拟 Mapper 验证类型校验/幂等、证据合并/选择、消息引用、终态与评测一致性；RunEvaluationTest 验证已有登录 HTTP 入口传递 agent 类型。

没有调用收费模型、业务数据库或修改真实业务数据。尚未验证真实 MySQL 行锁竞争、云端模型选工具/回答质量、真实论文多轮检索效果和端到端前端操作。

2026-11-10 验证：主代码及测试代码离线编译通过；以下 11 个测试类共 106 项测试通过，失败 0、错误 0、跳过 0。差异空白检查通过。初次在线 Maven 编译因本地仓库元数据写权限失败，随后使用已缓存依赖离线完成，没有修改仓库权限或依赖版本。

```powershell
mvn -o -q -DskipTests test-compile
mvn -o -q '-Dtest=AgentHarnessHttpTest,RunStoreTest,RunDispatcherTest,ToolExecutorTest,ToolCallingHttpTest,RagHttpTest,RagCallsTest,RunEvaluationTest,RunEventStreamTest,RunSchedulerTest,RunMapperConfigurationTest' test
git diff --check
```

第五批仍需实现 Skill 注册、受控文件加载和路由、版本及内容哈希记录、技能白名单与系统权限取交集，以及技能提示词和引用模板的上下文预算。若后续裁剪或摘要 Tool Result，必须同步调整实际提供证据的追踪，不能把历史注册器全集作为最终上下文。本批停在此边界。
