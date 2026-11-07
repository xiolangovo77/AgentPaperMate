# 论文查询、PDF 阅读与会话范围更新

以下接口均需要登录。应用的 context-path 为 `/api`，用户 ID 从登录态取得。
JSON 接口沿用 `{ "code": 0, "data": ..., "message": "ok" }`；业务错误沿用现有响应体错误码，不能仅通过 HTTP 状态判断成功。
论文、任务及范围查询过滤逻辑删除数据，越权和不存在统一返回业务码 `40400`。

## 论文分页

`POST /api/paper/list/page`

```json
{
  "pageNum": 1,
  "pageSize": 10,
  "title": "attention",
  "authors": "Alice",
  "topic": "AI",
  "status": "ready"
}
```

- `pageNum` 默认 1，`pageSize` 默认 10，范围为 1～100。
- `title`、`authors`、`topic` 使用包含匹配，空白表示不筛选，单个条件最长 255 字符。
- `status` 精确匹配 `uploaded`、`processing`、`ready`、`failed`。
- 条件以 AND 组合，只查询当前用户的论文，固定按 `create_time DESC, id DESC` 排序。
- `data` 包含 `records`、`total`、`current`、`size`；记录含 `id`、标题、作者、主题、文件名、页数、文件大小、状态及创建/更新时间。不返回 COS 地址、文件哈希或内部执行令牌。

## 论文详情

`GET /api/paper/get?id=论文ID`

`data` 在列表字段基础上增加：

| 字段 | 含义 |
| --- | --- |
| `abstractText` | 摘要，未解析时可能为空 |
| `canAsk` | 有效索引及执行令牌存在，且该版本任务已完成、向量模型/版本/维度与当前配置兼容 |
| `jobId` | 最新处理任务 ID，无任务时为空 |
| `errorCode`、`errorMessage` | 最新任务的错误代码与错误摘要 |

`canAsk` 表示论文索引的可用性，不包含问答功能总开关、会话活动任务或资料数量限制。
重建索引失败时，旧的兼容索引仍可用于问答；因此 `canAsk=true` 与最新任务存在错误可以同时出现。

## 处理任务进度

`GET /api/paperProcessingJob/get?id=上传后返回的jobId`

先校验任务所属论文的归属，再返回 `id`、`paperId`、`status`、`currentStep`、`totalChunks`、`indexedChunks`、`retryCount`、`errorCode`、`errorMessage` 和 `updateTime`。
不返回租约令牌或内部处理配置。

## PDF 文件流

`GET /api/paper/file?id=论文ID`

- 校验论文归属及 COS 上传者元数据后流式读取文件，`Content-Type: application/pdf`，`Content-Disposition: inline`。
- 无 Range 时返回 `200`；支持 `Range: bytes=0-1023`、`bytes=1024-`、`bytes=-1024`，返回 `206` 及 `Content-Range`、`Content-Length`。
- 无效或越界单段范围返回 `416` 和 `Content-Range: bytes */文件总长度`。
- 合法多段 Range 或携带 `If-Range` 的请求保守返回完整文件 `200`，暂不输出 multipart 响应或执行条件范围读取。
- 返回 `Accept-Ranges: bytes` 和 `Cache-Control: private, no-store`。范围直接传给 COS SDK，使用固定大小缓冲区，不将整篇 PDF 载入内存。
- COS HEAD 与 GET 之间通过 ETag 条件约束对象版本；流关闭或客户端断开时释放存储连接。
- 阅读器可用此接口作为文件来源，并在阅读器内跳转页码；浏览器内置阅读器也可使用 `/api/paper/file?id=论文ID#page=页码`。

成功响应为二进制流，不使用 JSON 包装；开始输出前的权限、文件读取等业务错误仍遵循现有 JSON 错误约定。

## 完整替换会话论文范围

`POST /api/sessionPaper/updateScope`

```json
{
  "sessionId": "123",
  "paperIds": ["200", "201"],
  "scopeVersion": 1
}
```

成功时 `data` 为：

```json
{
  "sessionId": "123",
  "paperIds": ["200", "201"],
  "scopeVersion": 2
}
```

- `paperIds` 是完整目标集合，必填；`[]` 清空范围，`null` 不合法。最多提交 100 个 ID，重复 ID 去重后按升序返回。
- 所有目标论文必须有效且属于当前用户，允许选中仍在处理的论文；开始问答时继续由现有流程检查索引就绪和问答资料数量限制。
- `scopeVersion` 必填且必须匹配会话当前版本。版本过期返回业务码 `40900`，客户端应重新读取会话详情和资料列表后提交。
- 会话锁、版本检查、全部目标论文校验、移出/恢复/新增关联、一次版本递增及一条 `scope_change` 系统消息处于同一事务，任意失败整体回滚。
- 集合未变化时保持原版本，不新增变更消息；旧版本请求即使集合相同也返回冲突。
- 已移出的论文恢复原关联记录，保留首次关联时间。范围改变只影响后续问答，当前活动任务仍使用创建时的资料快照。

本次沿用现有表及索引，无需新增数据库迁移。
