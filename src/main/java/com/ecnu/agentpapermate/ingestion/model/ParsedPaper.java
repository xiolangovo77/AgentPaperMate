package com.ecnu.agentpapermate.ingestion.model;

import java.util.List;

/**
 * 论文解析结果，保存元数据、按解析顺序排列的内容块及作者信息可信标记
 *
 * @param title 论文标题
 * @param authors 格式化后的作者列表
 * @param abstractText 摘要文本
 * @param pageCount 页数，无法确定时为 null
 * @param paragraphs 按解析顺序保存的内容块
 * @param authorsTrusted 作者信息是否可信，决定是否允许自动覆盖已存作者
 */
public record ParsedPaper(String title, String authors, String abstractText, Integer pageCount,
                          List<Paragraph> paragraphs, boolean authorsTrusted) {
    /**
     * 构造默认信任作者信息的论文解析结果
     */
    public ParsedPaper(String title, String authors, String abstractText, Integer pageCount, List<Paragraph> paragraphs) {
        this(title, authors, abstractText, pageCount, paragraphs, true);
    }
    /**
     * 按 TEI 原始顺序保存的内容块；表格按行保存，避免丢失表头。
     *
     * @param text 内容块文本
     * @param section 所属章节的标题路径
     * @param pageStart 起始页码，未知时为 null
     * @param pageEnd 结束页码，未知时为 null
     * @param blockId 来源内容块标识
     * @param kind 内容类型，如 paragraph、formula、table
     * @param sectionId 章节或图表分组标识
     * @param lowConfidence 是否存在结构解析不确定性
     * @param references 内容块引用的目标标识列表
     * @param sentences 完整覆盖正文段落的句子列表，无可用句子信息时为空
     */
    public record Paragraph(String text, String section, Integer pageStart, Integer pageEnd,
                            String blockId, String kind, String sectionId, boolean lowConfidence,
                            List<String> references, List<String> sentences) {
        /**
         * 构造基础正文内容块，默认无来源标识、引用和句子信息
         */
        public Paragraph(String text, String section, Integer pageStart, Integer pageEnd) {
            this(text, section, pageStart, pageEnd, "", "paragraph", section, false, List.of(), List.of());
        }
    }
}
