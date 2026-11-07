# 前后端接口核对与缺口

核对日期：2026-11-07。依据根目录 `接口文档.md`、Java Controller、DTO、Service 和原型源码。用户已确认本次只开发前端，不修改 Java 后端。

本文“建议契约”均是后续开发建议，不是现有接口；前端没有请求这些未实现的地址。

## 已有接口及前端接入

统一前缀 `/api`，以下路径不重复该前缀。所有 JSON 接口处理 `code/data/message`。业务错误可能仍使用 HTTP 200，不能仅依靠 HTTP 状态码。

| 功能 | 真实接口 | 实现说明 |
| --- | --- | --- |
| 用户 | POST `/appUser/register`、`/login`、`/logout`、`/update`；GET `/appUser/get` | Cookie Session，Axios 和 SSE 带凭据，未登录跳转；昵称、头像和密码更新 |
| 上传 | POST `/common/upload/paper`、`/common/upload/avatar` | `FormData` 的 `file` 字段；PDF 50 MB、头像 2 MB；上传进度来自实际传输 |
| 创建论文 | POST `/paper/add` | `{fileUrl}` → `{paperId,jobId,status}`；上传回执不冒充实时状态 |
| 笔记 | GET `/readingNote/get?paperId=`；POST `/readingNote/add`、`/update` | 六个字段；不存在返回 null；新增版本 1，更新返回新版本号；冲突保留草稿 |
| 会话 | POST `/chatSession/add`、`/list/page`、`/update`、`/delete`；GET `/chatSession/get?id=` | 分页历史、创建、重命名、草稿保存、先确认活动 Run 结束再删除 |
| 论文关联 | POST `/sessionPaper/list/page`、`/add`、`/update` | 当前资料范围查询；按已知 ID 逐篇添加；`{id,isDelete:1}` 逐篇移出 |
| 消息 | GET `/chatMessage/list/page?sessionId=&pageNum=&pageSize=` | 后端按 ID 倒序，前端合并去重并按 ID 正序展示；加载更早消息 |
| Run | GET `/agentRun/request-key`、`/get?id=`；POST `/agentRun/add`、`/cancel` | `{sessionId,requestKey,question,retryOfRunId?}`；add 直接返回 runId 字符串 |
| SSE | GET `/agentRun/events?id=&afterEventSeq=` | 具名事件，EventView 的序号字段是 `eventId`，不是 `eventSeq`；`run_snapshot` 载荷为 RunView |
| 补发 | GET `/runEvent/list?runId=&afterEventSeq=` | 与 SSE 合作恢复，RunView 的最新序号字段为 `eventSeq` |
| 引用 | GET `/messageCitation/list?messageId=` | 展示服务端快照中的标题、物理页码、摘录、位置与证据 ID |

## 1. 论文分页查询、详情、统计（阻塞核心论文库）

**源码现状：** `PaperController` 只有 `POST /paper/add`。`Paper` 实体虽然含元数据，但没有暴露读取 Controller。不能通过数据库、静态论文或会话关联伪造论文库。

**需要的能力：**

- 分页请求：`pageNum`、`pageSize`、标题或关键词、作者、主题、处理状态。
- 分页结果：`records`、`total`、`current`、`pages`、`size`。
- 每条记录：字符串 `id`、`title`、可信 `authors`、`topic`、`abstractText`、`originalFilename`、`pageCount`、真实 `status`、是否可用于问答、上传时间。若需会议或期刊，还需定义对应元数据字段；现有实体没有该字段。
- 详情请求：`paperId`；结果包含上述元数据、有效索引状态、处理失败原因。
- 准确统计：论文总数、可问答数、总物理页数、主题计数。也可用完整且一致的数据集计算；不能用单页记录推断全库统计。
- 所有读取均限定当前用户所有权，不由前端传 userId 决定权限。

**当前界面：** 搜索、主题和状态筛选置灰；论文列表显示缺口；统计显示 `—`。研究会话数量正常来自真实分页总数。上传回执只在当前页面内存展示，刷新后消失，不是论文数据源。

## 2. 处理状态查询与失败重试

**源码现状：** `PaperProcessingJobController` 为空。没有可用的任务进度查询或人工重试接口。

**建议契约：** 按 `jobId` 或 `paperId` 查询，返回任务状态、当前步骤、更新时间、错误代码和可公开的原因、是否允许重试、有效索引是否就绪。失败重试由后端校验权限、终态和预算，返回新任务标识与状态。

**当前界面：** 显示提交时状态和任务 ID；不轮询虚构地址，不生成假解析进度。`paper/add` 提交失败可复用已上传 URL 重试，不能把这个操作称为“解析失败重试”。

## 3. 经权限校验的 PDF 读取（阻塞真实原文阅读）

**源码现状：** 没有面向浏览器的论文原文接口。COS 管理代码供后台读取文件，数据库中的 `fileUrl` 不等于可供浏览器永久访问的公共 URL。

**建议契约：** 通过论文 ID 获取文件，校验登录、归属、删除状态；返回 `application/pdf` 文件流（或经授权的短时访问方案）。大文件建议支持 Range。错误响应需能区分未登录、无权访问、文件不存在。

**当前界面：** 原文 Tab 明确提示未接通。引用链接保留 `/library/:id?page=物理页码`，进入后显示目标页，但不会声称已经打开 PDF。`PdfViewer.vue` 已实现 PDF.js 页码、缩放和渲染资源释放；尚未挂载至正式页面，因为没有合法文件来源。不能将引用中的 COS 地址直接传给它。

## 4. 完整论文集合的原子调整

**源码现状：** `sessionPaper/add` 和 `update` 是单条关系变更，后端会加锁、增加 scopeVersion，并记录资料变更消息。但请求 DTO 不接收客户端 scopeVersion，也没有完整集合更新 API。

**建议契约：** `{sessionId,paperIds:string[],scopeVersion:number}` → `{scopeVersion,paperIds}`。后端在单事务内检查用户、去重、验证版本、增删差集并记录消息；过期返回 409/40900。空集合有效；不改写活动 Run 的资料快照与历史引用。

**当前界面：** “浏览论文库”页签说明缺口；已选论文支持 ID 搜索与分页，按已知 ID 逐篇添加/移出，操作即时保存。没有串行调用多次单条接口模拟“批量确认”，没有一键全选。逐篇调整无法提供客户端版本冲突保证，这一点需在后端补齐。

**就绪检查边界：** 当前论文状态不可查询，前端不能提前保证某篇资料就绪。RunStore 在创建 Run 时校验全部关联论文的有效索引、执行尝试及向量模型兼容性，不就绪会拒绝整个请求。前端显示服务端错误，不静默排除论文。

## 5. 清空聊天记录

**源码现状：** 只有查询消息，没有清空接口。

**需要的能力：** 删除/清空会话内消息与引用时先处理活动 Run，明确资料范围、草稿是否保留，并确保迟到结果不会重新写入。

**当前界面：** 不提供假清空按钮。删除会话有真实接口且独立实现。导出则逐页读取全部消息和各回答引用，前端生成 Markdown，无需新增接口。

## 已验证与未验证

**真实本地后端已验证：** 注册、登录、会话 Cookie、获取用户、修改昵称、会话创建/读取/列表/重命名/删除、草稿保存及刷新恢复、空消息与论文关联查询、会话 Markdown 导出、退出及再次登录。

**测试响应或单元测试已验证：** 笔记自动保存/版本冲突、上传字段和两步提交/重试、逐篇范围调整、Run 幂等重试与取消、SSE 事件去重/缺失补发/终态/快照/迟到响应、Markdown 清理与引用目标路由、登录失效、桌面和平板/手机布局。

**尚未通过真实数据全链路验证：** COS 上传、GROBID 解析、Embedding/ES 入库、真实论文笔记写入、已索引资料的 AI 问答及持久化引用、真实 SSE 网络中断恢复、受保护 PDF 读取。没有把浏览器测试响应宣称为真实模型验证。

本次没有修改任何 Java 后端源码、配置或数据库结构。真实联调创建了独立的 `frontend_qa_` 前缀测试账号；无删除用户接口，账号保留，成功验证的测试会话已通过真实删除接口清理。
