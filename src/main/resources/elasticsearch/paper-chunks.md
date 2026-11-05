# paper-chunks.json 字段说明

同目录的 `paper-chunks.json` 是 Elasticsearch 建索引时读取的映射模板。标准 JSON 不支持注释，因此说明保存在此文件中，模板本身不添加注释或额外字段。

## 映射设置

| 配置 | 说明 |
| --- | --- |
| `mappings._meta` | 模板中为空对象；创建索引时由 `PaperVectorWriter` 写入 `embeddingModel` 和 `embeddingVersion`，用于兼容性检查。 |
| `mappings.dynamic` | 设为 `strict`，拒绝未在映射中声明的文档字段。 |
| `metadata.dynamic` | 同样设为 `strict`，限制分块元数据字段。 |

## 文档字段

| 字段 | 说明 |
| --- | --- |
| `id` | 分块文档标识，按 keyword 保存。 |
| `content` | 包含论文标题、章节前缀及分块正文的文本，默认使用 standard 分析器。 |
| `content.en` | 同一文本的英文分析子字段，使用 english 分析器。 |
| `content.cjk` | 同一文本的中日韩分析子字段，使用 cjk 分析器。 |
| `embedding` | 用于相似度检索的稠密向量，启用向量索引并使用 cosine 相似度。模板维度为 1024，创建索引时会替换为任务快照中的维度。 |
| `metadata` | 分块的归属、版本、来源和处理配置等元数据对象。 |

## metadata 字段

| 字段 | 说明 |
| --- | --- |
| `ownerId` | 论文所属用户 ID，以字符串形式保存，支持归属筛选。 |
| `paperId` | 论文 ID，以字符串形式保存。 |
| `indexVersion` | 本次处理对应的论文索引版本。 |
| `attemptToken` | 本次任务执行的租约令牌，用于隔离同一版本的不同重试写入。 |
| `chunkId` | 分块标识，由论文 ID、索引版本、尝试令牌及分块序号组成。 |
| `chunkIndex` | 分块在当前处理结果中的序号，从 0 开始。 |
| `sectionTitle` | 所属章节标题路径，按 keyword 保存；长度超过 1024 的值不建立该字段的索引。 |
| `title` | 论文标题，按 text 保存。 |
| `pageKnown` | 分块包含的全部来源内容块是否都有起止页码。 |
| `pageStart` | 来源内容块的最小起始页码，仅在全部页码已知时写入。 |
| `pageEnd` | 来源内容块的最大结束页码，仅在全部页码已知时写入。 |
| `embeddingModel` | 任务快照中的向量模型名称。 |
| `embeddingVersion` | 任务快照中的向量模型版本。 |
| `parserVersion` | 任务快照中的解析器版本。 |
| `chunkingVersion` | 任务快照中的分块策略版本。 |
| `sectionId` | 章节或图表分组标识，用于保留结构边界。 |
| `blockTypes` | 分块涉及的内容类型列表，如 paragraph、formula、table，已去重。 |
| `sourceBlockIds` | 来源内容块标识列表，过滤空标识并去重。 |
| `referenceIds` | 来源内容块引用的目标标识列表，已去重。 |
| `lowConfidence` | 是否包含结构解析不确定的内容块，如存在合并单元格的表格。 |
