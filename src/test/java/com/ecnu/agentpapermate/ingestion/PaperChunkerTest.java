package com.ecnu.agentpapermate.ingestion;

import com.ecnu.agentpapermate.config.paper.PaperIngestionProperties;
import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PaperChunkerTest {
    @Test
    void mergesShortParagraphsButDoesNotCrossSectionsOrClaimMixedPagesAreKnown() {
        var parsed = new ParsedPaper("Title", "", "", null, List.of(
                new ParsedPaper.Paragraph("First short paragraph.", "One", 2, 2),
                new ParsedPaper.Paragraph("Second short paragraph.", "One", null, null),
                new ParsedPaper.Paragraph("Different section.", "Two", 3, 3)));
        var paper = new Paper(); paper.setId(1L); paper.setUserId(2L); paper.setTitle("Title");
        var job = new PaperProcessingJob(); job.setIndexVersion(1); job.setLeaseToken("a");
        job.setEmbeddingModel("test"); job.setEmbeddingVersion("v1"); job.setParserVersion("test"); job.setChunkingVersion("v2");
        job.setChunkingConfig("{\"unit\":\"tokens\",\"chunkSize\":600,\"maxChunkSize\":900,\"chunkOverlap\":100}");
        var documents = new PaperChunker(new ObjectMapper()).split(parsed, paper, job);
        assertEquals(2, documents.size());
        assertTrue(documents.getFirst().getText().contains("Second short paragraph"));
        assertFalse(documents.getFirst().getText().contains("Different section"));
        assertEquals(false, documents.getFirst().getMetadata().get("pageKnown"));
        assertFalse(documents.getFirst().getMetadata().containsKey("pageStart"));
    }
    @Test
    void keepsParagraphPageRangeAndIsolatesAttemptIds() {
        Paper paper = new Paper(); paper.setId(1L); paper.setUserId(2L); paper.setTitle("title");
        PaperProcessingJob job = new PaperProcessingJob(); job.setIndexVersion(1); job.setLeaseToken("a");
        job.setParserVersion("grobid-test"); job.setChunkingVersion("chunk-v1");
        job.setEmbeddingModel("test-model"); job.setEmbeddingVersion("v1");
        job.setChunkingConfig("{\"unit\":\"tokens\",\"chunkSize\":64,\"chunkOverlap\":8}");
        String text = "中文论文与 English retrieval，可靠的索引和引用。🙂 ".repeat(20);
        var parsed = new ParsedPaper("Title", "", "", null, List.of(new ParsedPaper.Paragraph(text, "Methods", 2, 3)));
        var chunker = new PaperChunker(new ObjectMapper());
        var documents = chunker.split(parsed, paper, job);
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        assertTrue(documents.size() > 1);
        for (var document : documents) {
            assertFalse(document.getText().contains("\uFFFD"), document.getText());
            assertTrue(encoding.countTokens(document.getText()) <= 64);
            assertEquals(2, document.getMetadata().get("pageStart"));
            assertEquals(3, document.getMetadata().get("pageEnd"));
        }
        job.setLeaseToken("b");
        assertNotEquals(documents.getFirst().getId(), chunker.split(parsed, paper, job).getFirst().getId());
    }

    @Test
    void grobidCoordinatesPreserveCrossPageRangeAndRejectExternalEntities() {
        var parser = new GrobidClient(new PaperIngestionProperties());
        var parsed = parser.parseTei("""
                <TEI xmlns="http://www.tei-c.org/ns/1.0"><text><body><div><head>Methods</head>
                <p><s coords="3,1,2,3,4">First sentence.</s><s coords="4,1,2,3,4"> Second sentence.</s></p>
                <p>No coordinates.</p></div></body></text></TEI>
                """);
        assertEquals(3, parsed.paragraphs().getFirst().pageStart());
        assertEquals(4, parsed.paragraphs().getFirst().pageEnd());
        assertNull(parsed.paragraphs().get(1).pageStart());
        assertEquals("Methods", parsed.paragraphs().getFirst().section());
        assertThrows(IllegalArgumentException.class, () -> parser.parseTei("<!DOCTYPE TEI [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><TEI>&x;</TEI>"));
    }
}
