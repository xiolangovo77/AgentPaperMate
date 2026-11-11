# Agent 论文定位读取工具

四个读取工具通过 `PaperTools` 注册，使用现有 `ToolExecutor` 的调用次数、超时、取消与事件机制。无需新增 HTTP 接口、数据库表或 ES mapping；使用现有索引中的 sectionId、sectionTitle、chunkIndex、pageKnown 和页码字段。

## 参数与使用

| 工具 | 必填参数 | 可选参数 |
| --- | --- | --- |
| `list_paper_sections` | `paperId` 字符串 | `afterChunkIndex`，默认 -1 |
| `read_paper_section` | `paperId`、`sectionId` 字符串 | `afterChunkIndex`、`limit` |
| `read_paper_page` | `paperId` 字符串、`page` 正整数 | `afterChunkIndex`、`limit` |
| `read_neighbor_chunks` | `paperId`、`chunkId` 字符串 | `before`、`after`、`afterChunkIndex`、`limit` |

`limit` 为 1～20，默认取 maxEvidence 与 20 中较小值；最终正文仍受证据数量、Token 与去重限制。`before` / `after` 各为 0～3，默认各 1。相邻读取包含目标片段，锚点必须属于本次已登记证据，不能传引用编号 E1。

目录每次扫描最多 100 个分块，返回本批唯一章节 ID、标题和首次出现序号。章节可能跨批重复，需按 sectionId 合并。目录顺序来自 chunkIndex；无 sectionId 的片段不会形成目录项。不提供无法从分块准确恢复的完整目录树或章节总数。

目录与正文结果均返回 `hasMore` 和 `nextAfterChunkIndex`。调用方将后者作为下次的 `afterChunkIndex`，并保留原论文、章节、页码或锚点参数。正文按 chunkIndex 排序，结果 `chunks` 只包含定位信息，原文来自顶层 `evidence`。

示例调用：

```json
{"paperId":"30"}
```

将目录中实际返回的 sectionId 传给章节读取：

```json
{"paperId":"30","sectionId":"实际返回的章节ID","limit":4}
```

读取第六页或已检索证据的相邻片段：

```json
{"paperId":"30","page":6,"limit":4}
```

```json
{"paperId":"30","chunkId":"实际证据中的chunkId","before":1,"after":1}
```

## 证据与限制

- 查询始终限定当前用户、Run 论文范围、ES 索引、索引版本、attemptToken 和 embedding 版本；访问前后校验论文归属，返回后重新检查 Run 存活。
- 结构扫描保留页码未知和低置信度片段的定位信息；这类正文继续由已有证据筛选规则排除。
- 读取结果通过现有 EvidenceRegistry 登记、复用引用 ID，保存检索候选快照，再由 Harness 校验最终引用。
- `omittedChunkIds` 表示当前扫描批次中没有提供正文的片段，包括页码缺失、低置信度、空正文、重复内容或证据预算限制。游标按已扫描分块推进，不自动重读这些遗漏；必要时缩小 limit 并从对应分块前的游标重新读取。不可引用内容不会因缩小 limit 变成可引用内容。
- 页读取匹配 `pageStart <= page <= pageEnd`，可能携带跨页文字，不是 PDF 单页渲染或 OCR。页码未知的分块无法被页查询定位。
- `hasMore=false` 仅表示本次定位查询没有更多分块；不代表原始 PDF 已完整解析，也不代表所有正文都已提供。章节或页码查询无匹配时返回空结果。
- 这些工具只做 ES 条件查询，不调用 embedding。旧数据缺少结构字段时，结构能力相应受限，必要时重新入库。

## 技能与发布

四个现有 Skill 的版本更新为 1.1.0，并允许使用全部六个论文工具。原先已创建 Run 的技能快照保留原工具权限；重启后端后创建新任务才能获得新增工具。工具按需选择，不要求每个任务执行全部工具。

本次仅进行 Maven 编译检查，按用户要求未运行测试，也未对接真实 ES / 模型进行运行验证。
