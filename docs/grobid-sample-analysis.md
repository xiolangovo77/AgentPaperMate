# GROBID 样本解析与分块结果

输入：`grobid论文解析结果/2026.findings-emnlp.1038.tei.xml`，测试副本位于 `src/test/resources/grobid/hypertrace.tei.xml`。

提取标题：**HyperTrace: Hypothesis-Based Preference Tracing for Online LLM Personalization**。

标题、摘要可先更新数据库。原始作者列表含 13 项，其中 `Tokyo`、`Nyu Tandon`、`Abu Nyu`、`Dhabi` 疑似地址或机构误识别；本次标记作者不可信，保留原始提取结果供检查，不自动覆盖数据库作者。没有根据姓名猜测删除某个作者。

原始正文有 77 个 `<p>`，其中包含漏识别的章节标题、表格片段和标点噪声。按章节结构解析、移除纯标点后，输出如下：

| 类型 | 结构块数量 | 处理方式 |
| --- | ---: | --- |
| 摘要 | 1 | 独立分组 |
| 正文段落 | 72 | 同章合并，优先按句子切分 |
| 公式 | 9 | 保留位置，尽量与上下文合并 |
| 图注 | 6 | 独立分组 |
| 表格行 | 46 | 来自 5 张表，附表注和表头 |
| 疑似表格片段 | 5 | 标记低可信，不充当章节标题 |

共 139 个结构块生成 **48 个检索块**。测试确认每个结构块的来源 ID 均在输出中出现。参数为目标 600、硬上限 900、完整短句重叠不超过 100；实际块长度为 34–787 个 cl100k_base 估算 token，包含标题和章节前缀。这不是 Qwen 原生 token 计数，也不是检索质量评测结果。

正文没有可用的段落或句子坐标，不能恢复可靠物理页码；页数未知，不覆盖数据库已有页数。表格存在合并单元格或列错位，保留提取文本并标记 `lowConfidence`，没有猜测重建实验数值关系。正文中漏标的 `2 Related Work` 被恢复为章节，疑似表头 `Setting Acc>20 GPT>20 Prof.` 不作为章节。

完整的标题、原始作者、摘要、分块文本及来源元数据见 [解析结果 JSON](../target/grobid-analysis.json)。该文件是本地分析产物，未写入真实业务库或 ES；`target` 清理后可运行以下命令重新生成：

```powershell
mvn '-Dtest=GrobidSampleTest' test
```

模型默认配置为 `qwen3.7-text-embedding`、1024 维。真实入库在 GROBID 解析后先提交元数据，再执行上述分块和 Spring AI 向量化；部署参数见 [入库说明](paper-ingestion.md)。
