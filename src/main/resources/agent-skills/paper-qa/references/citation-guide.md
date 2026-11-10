# 引用规范
遵守系统规定的最终 JSON 格式，answer 为中文回答，evidenceIds 为正文实际引用的 ID 列表。
论文事实后使用 [E1] 这样的标记，ID 必须来自当前成功工具结果 evidence；元数据和 retrievedChunkIds 不是原文证据。
不得将旧答案、历史引用或参考模板当成论文证据。无原文证据时输出无法确认或澄清内容，evidenceIds 为 []。
