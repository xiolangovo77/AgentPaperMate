package com.ecnu.agentpapermate.ingestion;

import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.ingestion.model.ParsedPaper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import java.text.BreakIterator;
import java.util.*;

/**
 * 结构边界 + 句子装箱；cl100k_base 仅作为稳定的长度估算，不冒充 Qwen tokenizer。
 */
@Component
public class PaperChunker {
    /**
     * 用于读取任务快照中的 JSON 分块配置
     */
    private final ObjectMapper json;
    /**
     * 用于估算文本 token 数量的编码器，不等同于实际向量模型的分词器
     */
    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    /**
     * 待装箱文本片段，保留来源内容块及是否为完整句子的标记
     */
    private record Piece(String text, ParsedPaper.Paragraph block, boolean wholeSentence) { }
    /**
     * 注入分块配置所需的 JSON 解析器
     */
    public PaperChunker(ObjectMapper json) { this.json = json; }

    /**
     * 按章节和内容类型划分连续分组，再依据任务配置生成带来源元数据的索引文档
     */
    public List<Document> split(ParsedPaper parsed, Paper paper, PaperProcessingJob job) {
        try {
            // 1、从任务快照读取目标长度、长度上限和重叠预算，并校验取值
            var config = json.readTree(job.getChunkingConfig());
            int target = config.path("chunkSize").asInt();
            int maximum = config.path("maxChunkSize").asInt(target);
            int overlap = config.path("chunkOverlap").asInt();
            if (!"tokens".equals(config.path("unit").asText()) || target < 16 || maximum < target
                    || overlap < 0 || overlap >= target - 4) {
                throw new IllegalArgumentException("无效的结构分块配置");
            }
            // 2、按章节和内容类型分组，正文与公式可放在同一组
            List<Document> result = new ArrayList<>();
            List<ParsedPaper.Paragraph> group = new ArrayList<>();
            String previousKey = null;
            for (var block : parsed.paragraphs()) {
                String kind = switch (block.kind()) {
                    case "paragraph", "formula" -> "narrative";
                    default -> block.kind();
                };
                String key = block.sectionId() + ":" + kind;
                if (!group.isEmpty() && !key.equals(previousKey)) {
                    pack(group, parsed, paper, job, target, maximum, overlap, result);
                    group.clear();
                }
                group.add(block);
                previousKey = key;
            }
            // 3、处理最后一组，确认论文至少生成一个可索引分块
            pack(group, parsed, paper, job, target, maximum, overlap, result);
            if (result.isEmpty()) {
                throw new IllegalArgumentException("论文没有可索引内容");
            }
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("任务分块快照无效", e);
        }
    }

    /**
     * 在长度预算内装箱同组句子，添加标题前缀，并按重叠预算复用最多两个完整正文短句
     */
    private void pack(List<ParsedPaper.Paragraph> group, ParsedPaper parsed, Paper paper, PaperProcessingJob job,
                      int target, int maximum, int overlap, List<Document> result) {
        // 1、跳过空组，为标题和章节名预留空间后计算正文预算
        if (group.isEmpty()) {
            return;
        }
        String title = parsed.title().isBlank() ? paper.getTitle() : parsed.title();
        String prefix = shorten(Objects.toString(title, "") + "\n" + group.getFirst().section(), Math.min(96, maximum / 4));
        int budget = maximum - encoding.countTokens(prefix) - 4;
        int desired = Math.max(8, target - encoding.countTokens(prefix) - 4);
        List<Piece> buffer = new ArrayList<>();
        // 2、按句子读取同组内容，将超长句或表格行拆成可容纳的片段
        for (var block : group) {
            for (String sentence : sentences(block)) {
                List<String> fragments = splitBlock(sentence, block.kind(), budget);
                for (String fragment : fragments) {
                    Piece next = new Piece(fragment, block, fragments.size() == 1);
                    // 3、追加后会超出上限时立即换块；仅达到目标长度时，优先让公式与相邻文本同块
                    boolean formulaBoundary = block.kind().equals("formula")
                            || (!buffer.isEmpty() && buffer.getLast().block().kind().equals("formula"));
                    if (!buffer.isEmpty() && (tokens(buffer, next) > budget
                            || (tokens(buffer, null) >= desired && !formulaBoundary))) {
                        emit(buffer, prefix, paper, job, result, maximum);
                        // 4、按重叠预算保留块尾的完整正文句，为下一个片段留出空间
                        List<Piece> carry = new ArrayList<>();
                        // 只在正文块之间复用完整短句；不截断句尾、不重复表格或公式。
                        for (int i = buffer.size() - 1; i >= Math.max(0, buffer.size() - 2); i--) {
                            Piece old = buffer.get(i);
                            if (!old.block().kind().equals("paragraph") || !old.wholeSentence()) {
                                break;
                            }
                            carry.addFirst(old);
                            if (tokens(carry, null) > overlap) {
                                carry.removeFirst();
                                break;
                            }
                        }
                        buffer = carry;
                        while (!buffer.isEmpty() && tokens(buffer, next) > budget) {
                            buffer.removeFirst();
                        }
                    }
                    buffer.add(next);
                }
            }
        }
        // 5、输出最后一批尚未生成分块的片段
        if (!buffer.isEmpty()) {
            emit(buffer, prefix, paper, job, result, maximum);
        }
    }

    /**
     * 优先采用解析器提供的句子；正文缺少句子信息时使用英文句子边界规则，其他类型整体保留
     */
    private List<String> sentences(ParsedPaper.Paragraph block) {
        // 1、保留非正文内容的原文，正文优先使用解析器提供的句子
        if (!block.kind().equals("paragraph")) {
            return List.of(block.text());
        }
        if (!block.sentences().isEmpty()) {
            return block.sentences();
        }
        // 2、缺少句子标记时，按英文句子边界提取非空文本
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        iterator.setText(block.text());
        List<String> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String value = block.text().substring(start, end).strip();
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 拆分超长内容；表格表头较短时为拆分后的行片段补充相同表头
     */
    private List<String> splitBlock(String text, String kind, int budget) {
        // 1、表格超长且表头较短时，为拆出的每个行片段补上表头
        if (kind.equals("table") && encoding.countTokens(text) > budget) {
            int rowStart = text.lastIndexOf('\n');
            if (rowStart > 0) {
                String header = text.substring(0, rowStart);
                int headerTokens = encoding.countTokens(header);
                if (headerTokens < budget / 2) {
                    return hardSplit(text.substring(rowStart + 1), budget - headerTokens - 4).stream()
                            .map(row -> header + "\n" + row).toList();
                }
            }
        }
        // 2、其余内容按统一的长度预算切分
        return hardSplit(text, budget);
    }

    /**
     * 计算片段拼接后的 token 数量，可包含一个待追加片段
     */
    private int tokens(List<Piece> pieces, Piece extra) {
        String text = String.join("\n", pieces.stream().map(Piece::text).toList());
        if (extra != null) {
            text += "\n" + extra.text();
        }
        return encoding.countTokens(text);
    }

    /**
     * 生成带版本、尝试令牌和来源信息的文档，并校验单块长度及总块数上限
     */
    private void emit(List<Piece> pieces, String prefix, Paper paper, PaperProcessingJob job,
                      List<Document> result, int maximum) {
        // 1、拼接标题前缀和正文，校验完整分块的长度
        String content = prefix + "\n\n" + String.join("\n", pieces.stream().map(Piece::text).toList());
        if (encoding.countTokens(content) > maximum) {
            throw new IllegalStateException("分块超过长度上限");
        }
        // 2、记录论文归属、本次执行令牌以及章节和来源块信息
        var metadata = new HashMap<String, Object>();
        metadata.put("ownerId", paper.getUserId().toString()); metadata.put("paperId", paper.getId().toString());
        metadata.put("indexVersion", job.getIndexVersion()); metadata.put("attemptToken", job.getLeaseToken());
        metadata.put("chunkIndex", result.size()); metadata.put("sectionTitle", pieces.getFirst().block().section());
        metadata.put("sectionId", pieces.getFirst().block().sectionId());
        metadata.put("blockTypes", pieces.stream().map(p -> p.block().kind()).distinct().toList());
        metadata.put("sourceBlockIds", pieces.stream().map(p -> p.block().blockId()).filter(s -> !s.isBlank()).distinct().toList());
        metadata.put("referenceIds", pieces.stream().flatMap(p -> p.block().references().stream()).distinct().toList());
        metadata.put("lowConfidence", pieces.stream().anyMatch(p -> p.block().lowConfidence()));
        // 3、所有片段都有页码时才记录分块的起止页
        boolean known = pieces.stream().allMatch(p -> p.block().pageStart() != null && p.block().pageEnd() != null);
        metadata.put("pageKnown", known);
        if (known) {
            metadata.put("pageStart", pieces.stream().mapToInt(p -> p.block().pageStart()).min().orElseThrow());
            metadata.put("pageEnd", pieces.stream().mapToInt(p -> p.block().pageEnd()).max().orElseThrow());
        }
        // 4、补齐处理版本，用论文 ID、索引版本、租约令牌和块序号生成文档 ID
        metadata.put("title", Objects.toString(paper.getTitle(), ""));
        metadata.put("embeddingModel", job.getEmbeddingModel()); metadata.put("embeddingVersion", job.getEmbeddingVersion());
        metadata.put("parserVersion", job.getParserVersion()); metadata.put("chunkingVersion", job.getChunkingVersion());
        String id = paper.getId() + ":" + job.getIndexVersion() + ":" + job.getLeaseToken() + ":" + result.size();
        metadata.put("chunkId", id);
        // 5、加入索引文档，并检查单篇论文的分块数量上限
        result.add(new Document(id, content, metadata));
        if (result.size() > 20000) {
            throw new IllegalArgumentException("论文分块过多");
        }
    }

    /**
     * 截取长度预算内的首个文本片段，用于限制标题前缀长度
     */
    private String shorten(String text, int budget) {
        return hardSplit(text, budget).stream().findFirst().orElse("");
    }

    /**
     * 超长单句才退回长度切分；在 Unicode 码点边界切，避免中文和 emoji 损坏。
     */
    private List<String> hardSplit(String text, int budget) {
        List<String> result = new ArrayList<>();
        String remaining = text.strip();
        // 1、剩余文本能放入一个块时，直接收下并结束切分
        while (!remaining.isEmpty()) {
            if (encoding.countTokens(remaining) <= budget) {
                result.add(remaining);
                break;
            }
            // 2、按 Unicode 码点二分查找当前预算能容纳的前缀
            int[] points = remaining.codePoints().toArray();
            int low = 1, high = points.length, best = 0;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (encoding.countTokens(new String(points, 0, mid)) <= budget) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            if (best == 0) {
                throw new IllegalArgumentException("长度预算无法容纳单个字符");
            }
            // 3、保存切出的片段，继续处理剩余文本
            String part = new String(points, 0, best).strip();
            if (!part.isEmpty()) {
                result.add(part);
            }
            remaining = new String(points, best, points.length - best).strip();
        }
        return result;
    }
}
