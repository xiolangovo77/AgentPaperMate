# Skill 注册、加载与路由

## 研究技能扩展（2026-11-11）

当前注册四个技能：`paper-qa`、`paper-summary`、`paper-comparison`、`research-analysis`。新增技能均通过现有快照、工具白名单、预算、引用校验与 Run 完成流程执行。

在聊天室选择 **Agent 自主问答**，可直接使用：

| 请求示例 | 技能 | 资料范围 |
| --- | --- | --- |
| 请比较这些论文的方法、实验结果与局限 | `paper-comparison` | 2～5 篇；不符合时直接澄清，不调用模型 |
| 请分析这些论文的研究方向，提出可验证的假设与实验方案 | `research-analysis` | 当前范围内的单篇或多篇论文 |

接口仍为 `/agentRun/add`，提交 `executorType: "agent"` 和上述 `question`；省略执行器仍使用固定 RAG。前端增加比较与研究分析快捷问题及范围提示，不增加 `requestedSkillId` 字段。

规则仅匹配当前问题的明确命令：比较/对比需包含“论文/文献”，支持“多论文比较”、`paper-comparison`、`compare ... papers`；研究命令匹配分析/梳理/探索/提出/推荐/生成与研究思路/方向/选题，也支持“研究方向分析”、`research-analysis`、`research analysis`。研究分析优先于比较、总结；未识别时仍回退问答，不保证所有自然语言表达均命中。

新增资源：

```text
agent-skills/paper-comparison/SKILL.md
agent-skills/paper-comparison/references/comparison-template.md
agent-skills/research-analysis/SKILL.md
agent-skills/research-analysis/references/analysis-template.md
```

两个技能要求通过已有 `search_paper_chunks` 的 `requestedPaperIds` 逐篇取证。比较模板输出证据覆盖矩阵；研究模板区分作者结论、系统推测和待验证建议，列出假设、实验、对照、指标和缺口。矩阵随 `answer` 保存，不新增结构化数据库字段或语义覆盖校验器。逐篇检索和内容完整性由指令引导；后端强制保证权限、工具白名单和引用身份，不能保证模型始终遵循任务步骤。

测试使用本地 HTTP 模型替身与真实 SDK/Harness/工具流程，检查路由、模板展开、快照保存、逐篇检索参数及跨论文引用；未通过真实模型评测任务质量，后续仍需固定样本及质量、成本评估。

以下为基础技能接入时的设计记录；其中“本批”指最初两个基础技能。

本批在[第四批串行循环](./agent-loop.md)上接入 `paper-qa`、`paper-summary`。只使用 `get_paper_metadata`、`search_paper_chunks`，没有新增模型执行入口、自动工具执行主体、联网、脚本、多 Agent、章节或页面工具。

## 文件与职责

Java 路径相对于 `src/main/java/com/ecnu/agentpapermate`。

| 文件 | 职责 |
| --- | --- |
| `agent/skill/SkillLoader.java` | 打包资源读取、严格元数据解析、引用闭包、路径/大小/深度边界、SHA-256、快照重建校验 |
| `agent/skill/SkillRegistry.java` | 显式注册、启用策略、默认问答回退、声明工具上限 |
| `agent/skill/SkillRouter.java` | 基于当前问题的保守规则路由，创建时按需加载选中技能 |
| `agent/skill/SkillSnapshot.java` | 不可变的版本、哈希、允许工具、展开指令与原始文件集合 |
| `service/run/rag/RunStore.java` | Agent 创建事务内保存快照；领取时追加 `skill_selected` 事件 |
| `agent/context/AgentRunContext.java` | 从持久化配置恢复并核对技能字段；固定 RAG 不加载 Skill |
| `support/json/RagJson.java` | 快照反序列化及完整性校验 |
| `agent/AgentHarness.java` | 注入技能指令、权限交集、多篇总结澄清；复用现有循环与上下文预算 |
| `agent/tool/ToolExecutor.java` | 执行端再次约束 Skill 白名单，防止上层传入过大集合 |
| `entity/model/AgentRun.java` | 更新既有 `modelConfig` 字段注释，无新字段 |

技能及参考资料位于：

```text
src/main/resources/agent-skills/
├── paper-qa/SKILL.md
├── paper-qa/references/citation-guide.md
├── paper-summary/SKILL.md
└── paper-summary/references/summary-template.md
```

项目此前没有技能发现或外部技能安装机制。本批选择随应用打包的 classpath 目录，既支持开发时解包资源，也支持 JAR 内资源，不依赖启动工作目录。没有扫描任意磁盘目录、用户上传技能或运行时热加载接口。

测试新增 `agent/skill/SkillLoaderTest`、`SkillTestSupport`；扩展 `AgentHarnessHttpTest`、`RunStoreTest`、`ToolExecutorTest`。说明同步至本文件、`agent-loop.md` 和 `rag-runs.md`。

## 注册、加载与触发

注册表在服务端显式列出两个技能。额外文件不会自动注册；重复 ID、无效元数据、超出本批工具上限的声明会阻止注册。`paper-qa` 必须存在并启用，不能配置出失效的默认回退。

注册阶段对受大小限制的 SKILL.md 只解释元数据，保留 ID、描述、版本、启用状态、允许工具，不递归读取参考资料。选中技能后才读取正文及引用闭包，元数据若与注册阶段不同则拒绝创建，避免混合版本。

仍使用已有登录、聊天室归属及资料范围设置，调用 `POST /api/agentRun/add`：

```json
{
  "sessionId": "已有聊天室ID",
  "requestKey": "summary-request-0001",
  "question": "请总结这篇论文",
  "executorType": "agent"
}
```

当前问题以“总结”“概括”“生成总结/结构化总结”或英文 `summarize`、`summarise`、`summary` 开头（支持“请”“帮我”“请帮我”前缀）时选择 `paper-summary`；其他情况选择 `paper-qa`。规则只看本次问题，不把历史问题、论文正文或模型输出用于选 Skill。所有候选再经注册表过滤，未知/停用候选回退问答；选中后的内容损坏会失败，不静默替换。

这是有限的命令规则，不是语义分类器；不保证识别所有总结表达。没有新增 `skillId` 请求字段或任意路径入口。省略 `executorType`/传 null 继续执行固定 RAG，旧接口和执行器幂等规则保持不变。

初期总结只支持资料快照恰好一篇。多篇（包括文字要求“总结全部”或只在问题中指明其中一篇）会返回要求把本次资料范围调整为一篇的澄清，不猜目标、不挑第一篇、不自动逐篇。该回答经既有 `agentAnswerReady → complete` 链路保存，零模型/工具调用，最终证据与引用为空；`completed` 表示已发布澄清，并不表示已经完成论文总结。

## 文件格式与加载边界

SKILL.md 使用严格的 YAML 子集：`name`、`version`、`description`、`enabled`、`allowed-tools` 五个必填且唯一的键。名称必须与注册 ID 一致，版本为三段数字，描述非空且不超过 300 字符，enabled 仅接受 `true/false`。工具为两空格缩进的 `- name` 列表；显式空集合写 `allowed-tools: []`。拒绝未知键、重复工具、普通内联列表、嵌套对象或可执行类型；不运行通用 YAML 对象反序列化。

参考文件只有一种实际加载语法：

```text
{{include:references/summary-template.md}}
```

路径相对于当前技能目录。只接受 `references/` 下由字母、数字、下划线、短横线目录组成的 `.md` 文件。拒绝绝对路径、盘符、反斜线、`.`/`..`、URL、编码路径、跨技能引用与脚本后缀；不完整 include 语法也失败。普通 Markdown 链接只保留为文本，不触发读取或联网，不属于参考加载语法。

- 单文件最多 16 KiB，必须为有效 UTF-8；生产流最多读上限加一字节后判定。
- 原始文件闭包最多 48 KiB、8 个文件（含 SKILL.md）；展开指令另限 48 KiB，防止重复引用放大。
- 从正文开始最多两层 include；循环引用、缺失文件安全失败。
- 解包部署核验资源真实路径仍在允许根目录及本技能目录内，防止链接/目录联接越界；JAR 内访问固定前缀，禁止任意路径解析。
- 技能为应用部署资产，只有服务端能修改；本批不支持外部目录配置。修改后需重新构建/部署；元数据变更需重新初始化注册表。

## 快照、版本、权限与事件

Agent 在创建 Run 的原有事务中保存：既有 `skill_id`、`skill_version`、`skill_content_hash`，以及 `model_config.skillSnapshot`。快照包含实际展开指令、工具集合和所有实际加载的原始文件。SHA-256 对按路径排序的每个“相对路径 UTF-8 字节、原始内容 UTF-8 字节”逐段加四字节大端长度前缀后计算，覆盖参考内容及原始换行；不只哈希 SKILL.md，也不只记录人工版本号。

创建成功后，排队与运行均不重读这些内容。执行前从快照重建引用闭包和指令、重算哈希，并核对三个技能列；新增、遗漏或篡改的文件/指令/工具集合都会拒绝。文件随后变更不改变已有 Run，同一幂等键也不会重新选择或加载。再次请求使用新幂等键，才取得新内容。

系统当前注册工具 ∩ 本批后端 Run 策略 ∩ 创建时 Skill allowed-tools 为模型可见与执行使用的集合。当前策略上限固定为两个已有只读工具；工具注册增加不会自动扩大权限。ToolExecutor 另核对技能快照和运行策略，传入更大集合也不能突破。项目尚无独立的逐用户工具 ACL；现有用户/会话归属、Run 范围和当前资料授权仍由 `executionAllowed`、`PaperTools` 与 RunStore 检查，不由技能元数据代替。Skill 被当前部署停用时，已有快照不能绕过停用检查。

领取事务按序写 `run_started`、`skill_selected`，后者载荷仅为 `skillId/version/contentHash`，无正文或提示词。事件不计模型轮次/工具次数、不改变业务阶段，并通过既有归属校验和 SSE/事件查询获取。固定 RAG 不产生该事件。现有前端尚未增加技能展示或 Agent 选择控件；新事件可经事件查询读取，旧订阅器遇到序号缺口仍通过原有补发逻辑恢复。

数据库已具备三个 Skill 列和 JSON 配置列，本批无 DDL 迁移，不改请求 DTO、固定 RAG 事件或评测字段。上线前应正常处理旧版本任务：已存在但没有 Skill 快照的旧 Agent pending 任务领取后会以 `SKILL_SNAPSHOT_INVALID` 失败，需使用新幂等键重新创建；不会事后替它选择一份新技能冒充历史快照。旧固定 RAG Run 不受此检查影响。既有 running 任务仍不支持进程重启续跑。

## 信任边界、上下文与证据

系统协议先于服务端技能指令，技能正文和已展开参考资料一起进入 SystemMessage，并明确不是论文证据。当前问题、历史问题、论文列表放入 UserMessage；原文和工具结果保持对应的 ToolResponseMessage。系统和技能都要求忽略资料中的指令，不允许其改变规则、路径或权限。提示词不能从机制上保证模型绝不受注入影响；工具访问和引用身份仍由后端强制校验。

每轮预算计算包含整个 SystemMessage，因此新增技能和参考内容、实际工具说明也计入上下文预算。继续保留完整工具历史；不做新的裁剪/摘要，也不把旧问题作为证据。历史问题仍沿用原有六条/4000 字符选择规则。超过预算在模型调用前报 `AGENT_CONTEXT_LIMIT`，不重复计数或调用模型。

工具和模型调用 ID、预算、取消、超时、迟到结果丢弃及完成事务均沿用原实现。`retrieval_snapshot` 仍是成功检索候选并集；成功回答的 `evidence_snapshot` 仍只选择实际送入模型且已保存的证据，不能用候选全集、Skill 内容或注册器全集替代。最终解析与引用校验、助手消息和引用落库均只走原入口一次。

无证据或工具不可用：指令要求说明无法确认的范围，允许无引用澄清；已有证据时继续执行原有引用要求。缺失/损坏快照为 `SKILL_SNAPSHOT_INVALID`，当前部署未注册/停用该快照技能为 `SKILL_UNAVAILABLE`；运行中模型/工具预算耗尽、取消与超时沿用原失败码和终态仲裁。

## 总结能力与剩余范围

`paper-summary` 要求 answer 字符串按研究背景、研究问题、方法、创新、实验设置、实验结果、局限、主要引用、覆盖缺口组织，外层仍为 `{answer,evidenceIds}`。本批结构与覆盖要求由技能指令表达，后端继续校验既有 JSON/引用协议，没有新增语义完整性评分器或九段格式强制校验。

当前工具只能读少量元数据和检索相关片段，无法按章节枚举、逐页读取、保证全文/图表/公式/附录覆盖，也无法证明已找到所有实验、反例或作者局限。未命中不能等同于论文没有讨论。即使各维度都有片段，也必须说明未验证全文覆盖；没有证据的维度标注无法确认。不得声称已经读取全文或完整章节。

后续批次仍需按计划处理章节/页面工具及引用、实际模型任务质量与成本对比评测、语义覆盖验证、必要的上下文裁剪与同步证据追踪、前端技能与运行记录展示。本批不实现这些内容。

## 验证

仅使用缓存依赖、本地 HTTP 模型替身、内存资源及模拟 Mapper；未调用收费模型或连接业务数据库。测试覆盖加载边界、JAR 资源、真实文件路径越界检查、快照内容变化及校验、路由回退、工具交集、多篇澄清、总结模板、空证据、上下文预算、信任来源隔离，以及原有串行循环、调用 ID、取消、超时、预算、引用和固定 RAG 回归。

```powershell
mvn -o -q -DskipTests test-compile
mvn -o -q '-Dtest=SkillLoaderTest,AgentHarnessHttpTest,RunStoreTest,RunDispatcherTest,ToolExecutorTest,ToolCallingHttpTest,RagHttpTest,RagCallsTest,RunEvaluationTest,RunEventStreamTest,RunSchedulerTest,RunMapperConfigurationTest' test
git diff --check
```

尚未验证真实 MySQL 并发事务、部署环境的 Windows 链接/联接配置、真实论文检索及云端模型的总结质量、前端端到端流程。测试中的文件越界用重定向 classpath URL 验证真实路径拒绝分支，不冒充操作系统链接集成测试。

2026-11-10：主代码和测试代码离线编译通过；上述 12 个测试类共 135 项通过，失败 0、错误 0、跳过 0。
