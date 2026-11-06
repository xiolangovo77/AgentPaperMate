package com.ecnu.agentpapermate.ingestion;

import com.ecnu.agentpapermate.config.paper.PaperIngestionProperties;
import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.ingestion.grobid.GrobidClient;
import com.ecnu.agentpapermate.ingestion.model.ParsedPaper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class GrobidSampleTest {
    @Test
    void parsesRealPaperWithoutLosingFormulasTablesOrInventingPages() throws Exception {
        String xml;
        try (var input = new ClassPathResource("grobid/hypertrace.tei.xml").getInputStream()) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var parsed = new GrobidClient(new PaperIngestionProperties()).parseTei(xml);
        assertEquals("HyperTrace: Hypothesis-Based Preference Tracing for Online LLM Personalization", parsed.title());
        assertTrue(parsed.authors().contains("Jianzhi Shen"));
        assertFalse(parsed.authors().contains("JianzhiShen"));
        assertFalse(parsed.authorsTrusted());
        assertEquals(9, parsed.paragraphs().stream().filter(p -> p.kind().equals("formula")).count());
        assertTrue(parsed.paragraphs().stream().anyMatch(p -> p.kind().equals("table") && p.lowConfidence()));
        assertTrue(parsed.paragraphs().stream().anyMatch(p -> p.section().contains("2 Related Work / 2.1")));
        assertTrue(parsed.paragraphs().stream().noneMatch(p -> p.text().equals(".")));
        assertTrue(parsed.paragraphs().stream().noneMatch(p -> p.section().contains("Acc>20")));
        assertTrue(parsed.paragraphs().stream().filter(p -> p.kind().equals("paragraph")).allMatch(p -> p.pageStart() == null));
        assertNull(parsed.pageCount());
        var paper = new Paper(); paper.setId(1L); paper.setUserId(2L); paper.setTitle(parsed.title());
        var job = new PaperProcessingJob(); job.setIndexVersion(1); job.setLeaseToken("preview");
        job.setEmbeddingModel("qwen3.7-text-embedding"); job.setEmbeddingVersion("qwen37-1024-v1");
        job.setParserVersion("0.9.1-crf"); job.setChunkingVersion("grobid-structure-cl100k-est-v2");
        job.setChunkingConfig("{\"unit\":\"tokens\",\"chunkSize\":600,\"maxChunkSize\":900,\"chunkOverlap\":100}");
        var json = new ObjectMapper();
        var chunks = new PaperChunker(json).split(parsed, paper, job);
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        assertTrue(chunks.stream().allMatch(c -> encoding.countTokens(c.getText()) <= 900));
        Set<String> represented = chunks.stream().flatMap(c -> ((List<?>) c.getMetadata().get("sourceBlockIds")).stream())
                .map(Object::toString).collect(Collectors.toSet());
        assertTrue(parsed.paragraphs().stream().allMatch(p -> represented.contains(p.blockId())));
        assertTrue(chunks.stream().anyMatch(c -> ((List<?>) c.getMetadata().get("sourceBlockIds")).size() > 1));
        Map<String,Object> preview = new LinkedHashMap<>();
        preview.put("title", parsed.title()); preview.put("authors", parsed.authors()); preview.put("abstract", parsed.abstractText());
        preview.put("authorsAutoUpdate", parsed.authorsTrusted());
        preview.put("pageCount", parsed.pageCount()); preview.put("tokenCounter", "cl100k_base_estimate_not_qwen_native");
        preview.put("blockCounts", parsed.paragraphs().stream().collect(Collectors.groupingBy(ParsedPaper.Paragraph::kind, TreeMap::new, Collectors.counting())));
        preview.put("chunkCount", chunks.size());
        preview.put("chunks", chunks.stream().map(c -> Map.of("id", c.getId(), "estimatedTokens", encoding.countTokens(c.getText()),
                "text", c.getText(), "metadata", c.getMetadata())).toList());
        Files.writeString(Path.of("target/grobid-analysis.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(preview));
    }
}
