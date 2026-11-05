# GROBID + Spring AI + Elasticsearch 入库

执行流程为 COS 下载 → GROBID TEI → MySQL 保存解析元数据 → 结构分块 → Spring AI EmbeddingModel / ElasticsearchVectorStore → 索引验证 → MySQL 原子发布。真实调用由 `paper.processing.pipeline.enabled` 开启；默认关闭，关闭时不领取任务。模型为百炼 `qwen3.7-text-embedding`、1024 维，通过 DashScope 原生接口调用。

使用 `spring-ai-alibaba-starter:1.0.0-M6.1`，通过 Spring AI BOM 将相关模块统一到 `1.0.0-M6`。Elasticsearch Vector Store 仍需单独依赖，Alibaba starter 不包含它。模型由 `PaperIngestionConfiguration` 创建为 `DashScopeEmbeddingModel`，保留超时和单次调用重试策略，starter 自动配置退让，不重复创建模型。

解析成功后，在独立短事务中更新 `paper.title`、可信的 `authors`、`abstract_text`、可确定的 `page_count`，同时校验任务租约和版本。空值不覆盖已有信息。后续向量化或 ES 写入失败时，这些元数据仍保留，但论文不会提前成为 `ready`。作者可信度采用保守启发式，并非识别准确率保证；例如无机构关联、无 forename 的单词拉丁姓名会阻止整组作者自动覆盖，原始提取结果仍保留在解析对象中。

分块使用自定义结构分块器，再输出 Spring AI `Document`：

- 同一章节内合并短段落，优先按 GROBID `<s>` 或句子边界切分；不跨章节合并。可识别的编号标题形成章节路径。
- 目标 600、硬上限 900 个估算 token，标题和章节前缀计入预算。相邻正文块最多复用两个完整短句，合计不超过 100 个估算 token；超长单句才按 Unicode 码点边界切分。
- 摘要单独成组；公式尽量与相邻正文保留在一起。图注单独处理，表格按“表注 + 表头 + 行内容”分块，超长行保留表头上下文。
- 表格列错位、合并单元格和疑似表头被识别为章节的片段标记 `lowConfidence`，不猜测修复实验数值。参考文献不进入正文索引。
- `sourceBlockIds`、`referenceIds`、`blockTypes` 和 `sectionId` 保存来源。块内任一来源页码未知时，设置 `pageKnown=false`，不填造假的页码范围。

长度计数使用 JTokkit `cl100k_base`，仅作为稳定估算，**不是 Qwen 原生 tokenizer**，900 的限制不是 Qwen token 数保证；真实服务仍需验证输入限制。分块规则版本为 `grobid-structure-cl100k-est-v2`。实际样本结果见 [GROBID 样本分析](grobid-sample-analysis.md)。

ES 使用 Spring AI 的 `id/content/metadata/embedding` 字段布局。`content` 使用 standard，额外提供 `content.en`（english）和 `content.cjk`（cjk）。先用无需插件的中英文基线；中文占比高时再评测 SmartCN/IK。Embedding 始终接收自然文本，无需提前用 ES 分词器处理。

每次领取使用独立尝试命名空间，ES ID 包含 `paperId:indexVersion:leaseToken:chunkIndex`；同一次批量重试覆盖同一 ID，失去租约的旧线程只能写旧命名空间。校验成功后，事务发布 `paper.active_index_version` 和 `paper.active_index_attempt`。未来 BM25 和向量两路检索都必须过滤 ownerId、paperId、indexVersion、attemptToken，并使用同一授权快照；旧尝试不能参与检索，清理策略后续独立实现。

检索本轮不实现。计划用 ES 原生 BM25 + kNN，再在 Java 中 RRF 融合，避免绑定某一 Spring AI VectorStore 的单路 similaritySearch；查询向量必须使用与入库相同的模型和维度。

长任务周期续约，续约失败立即失去执行资格；每次外部操作、进度及完成提交前校验租约，外部调用配置超时，同时限制任务总时长。成功更新 job completed / paper ready，异常按现有预算重试。已就绪旧索引在新版本构建期间继续服务。

部署前执行新增迁移、配置 GROBID 地址及镜像版本、Embedding 地址/密钥/模型/维度、ES 地址及认证，再开启 pipeline。拉取 Docker 镜像不等于 GROBID 服务已经监听，应用需要可访问 `/api/isalive` 和 `/api/processFulltextDocument`。

## 启用配置

新建库使用 `sql/create_table.sql`。已有库若缺少 `paper.active_index_attempt`，停止旧应用后执行一次以下变更（不要重复执行）：

```sql
ALTER TABLE paper ADD COLUMN active_index_attempt VARCHAR(64)
    CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT '有效索引的执行尝试令牌，隔离旧执行者写入'
    AFTER active_index_version;
```

百炼使用环境变量 `DASHSCOPE_API_KEY` 和可选 `DASHSCOPE_BASE_URL`，绑定到 `spring.ai.dashscope`。也可以在本地 `application-dev.yml` 中覆盖同名配置。原来的 `apm.embedding.*` 已不再使用。GROBID 和 ES 连接仍可在 dev 配置的 `apm` 下填写；ES 标准入口为 `spring.elasticsearch.uris`，支持地址列表。

模型直接读取 `DashScopeConnectionProperties` 和 `DashScopeEmbeddingProperties`；ES 客户端和索引管理分别读取 `ElasticsearchProperties`、`ElasticsearchVectorStoreProperties`。`paper.processing.pipeline` 只保留入库业务配置，不再转接模型地址、密钥、text-type、ES 连接、索引初始化开关和相似度。`paper.processing` 中的模型、维度和索引引用用于保存任务配置快照，继续保留。

- dev 中的 `apm.grobid.url`：Java 应用能访问的虚拟机地址，例如 `http://虚拟机IP:8070`，缺省为 `http://localhost:8070`；`paper.processing.parser-version` 已设为 `0.9.1-crf`，应与实际部署的镜像版本一致。
- `spring.ai.dashscope.base-url` 默认为 `https://dashscope.aliyuncs.com`，客户端拼接 `/api/v1/services/embeddings/text-embedding/text-embedding`，不能填写 `/compatible-mode/v1`。`embedding.options.model` 为 `qwen3.7-text-embedding`，入库 `text-type` 为 `document`，每批最多 10 个片段。
- **M6.1 限制：**虽然 options 有 dimensions 属性，该版客户端不发送原生 `dimension` 参数。当前使用 qwen3.7-text-embedding 的服务端默认 1024 维，配置值用于任务快照及响应校验；不能仅改 YAML 就切换输出维度。返回向量数量或维度不一致时停止写入。原生接口返回浮点数组，不使用 OpenAI 的 `encoding-format`。应用配置版本为 `qwen37-1024-v1`，不是服务商发布版本。
- dev 中的 `apm.elasticsearch.url`、可选 `username` / `password`，缺省为本机 9200、无认证。`spring.ai.vectorstore.elasticsearch.index-name` 为 `agentpapermate-paper-chunks-qwen37-1024-v1`，维度引用 DashScope 的 1024，`similarity` 为 `cosine`。关闭 ES Vector Store 自动配置，由 `PaperVectorWriter` 按任务快照及自定义映射创建和校验索引，保留结构来源和租约字段。`initialize-schema=false` 时缺失索引会直接失败；当前仅支持 cosine。使用 ES 8.x；实际版本需要验证客户端兼容性。
- 最后将 `application.yml` 中 `paper.processing.pipeline.enabled` 改为 `true`。假配置开启会阻止应用启动，避免无效任务消耗重试预算。

任务保存配置快照。之前用 unknown/占位配置创建的任务不会自动变为真实模型配置，应创建新的处理版本。修改模型或分块规则后同样需要新版本；切换模型时使用新的 ES 索引。

## 验证和边界

`DashScopeConfigurationTest` 读取实际 YAML，检查配置绑定、唯一的 DashScope Embedding Bean，以及其他模型未启用。入库 HTTP 替身使用 DashScope 原生请求和响应格式，并验证返回维度不匹配、关闭索引初始化时均不会写入或发布。

`PaperIngestionTest` 使用本地 HTTP 服务替身，覆盖真实 GROBID multipart 请求、TEI 解析、Spring AI Embedding HTTP、ES Bulk 协议、索引创建和失败不发布；不调用真实模型、不产生费用。`PaperChunkerTest` 覆盖中英文、章节合并和未知页码；`GrobidSampleTest` 使用提供的真实 TEI，检查公式、表格、来源覆盖和长度预算，并生成 `target/grobid-analysis.json`。`PaperJobClaimServiceTest` 检查元数据独立提交和失效租约回滚；`PaperLeaseTest` 覆盖续约失败中断。`MySqlPaperJobMapperTest` 通过显式测试环境变量启用，使用会话临时表验证发布和元数据 SQL，不修改永久业务表。

真实 MySQL / GROBID / 模型 / ES 端到端联调尚未完成，需服务地址和有效凭据。代码没有实现扫描件 OCR、旧尝试自动清理或混合检索 API。结构边界会产生不足目标长度的小块；这比强行将摘要、正文和图表混合更便于引用。TEI 缺失的公式符号、表格布局或物理页码不能仅靠分块恢复。

参考：[GROBID 坐标](https://grobid.readthedocs.io/en/latest/Coordinates-in-PDF/)、[Spring AI Elasticsearch](https://docs.spring.io/spring-ai/reference/api/vectordbs/elasticsearch.html)。
