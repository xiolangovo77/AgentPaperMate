package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.mange.CosManager;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperFileServiceTest {
    private final PaperService papers = mock(PaperService.class);
    private final CosManager cos = mock(CosManager.class);
    private final PaperFileService service = new PaperFileService(papers, cos);
    private final byte[] bytes = "%PDF-12345".getBytes(StandardCharsets.US_ASCII);
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private COSObject object;

    @BeforeEach
    void setup() {
        Paper paper = new Paper(); paper.setId(100L); paper.setFileUrl("stored-url");
        when(papers.requireOwnedPaper(100L, 42L)).thenReturn(paper);
        ObjectMetadata metadata = new ObjectMetadata(); metadata.setContentLength(bytes.length);
        metadata.setHeader("ETag", "abc");
        when(cos.getPaperMetadata("stored-url", 42L)).thenReturn(metadata);
        when(cos.openPaperRange(eq("stored-url"), eq(42L), anyLong(), anyLong(), any())).thenAnswer(call -> {
            int start = Math.toIntExact(call.<Long>getArgument(2));
            int end = Math.toIntExact(call.<Long>getArgument(3));
            object = spy(new COSObject());
            object.setObjectContent(new ByteArrayInputStream(Arrays.copyOfRange(bytes, start, end + 1)));
            return object;
        });
    }

    @Test
    void streamsFullPdfWithPrivateHeadersAndClosesObject() throws Exception {
        service.writeFile(100L, 42L, null, null, response);
        assertEquals(200, response.getStatus()); assertEquals("application/pdf", response.getContentType());
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        assertEquals(10, response.getContentLengthLong()); assertArrayEquals(bytes, response.getContentAsByteArray());
        assertTrue(response.getHeader("Content-Disposition").startsWith("inline;"));
        assertNull(response.getHeader("Content-Range"));
        var order = inOrder(papers, cos);
        order.verify(papers).requireOwnedPaper(100L, 42L);
        order.verify(cos).getPaperMetadata("stored-url", 42L);
        order.verify(cos).openPaperRange("stored-url", 42L, 0, 9, "abc");
        verify(object).close();
    }

    @ParameterizedTest
    @CsvSource({"bytes=0-0,0,0", "bytes=2-5,2,5", "bytes=5-,5,9", "bytes=-3,7,9", "bytes=7-99,7,9", "bytes=-99,0,9"})
    void returnsExactSingleRange(String range, int start, int end) throws Exception {
        service.writeFile(100L, 42L, range, null, response);
        assertEquals(206, response.getStatus());
        assertEquals("bytes " + start + "-" + end + "/10", response.getHeader("Content-Range"));
        assertEquals(end - start + 1, response.getContentLengthLong());
        assertArrayEquals(Arrays.copyOfRange(bytes, start, end + 1), response.getContentAsByteArray());
        verify(cos).openPaperRange("stored-url", 42L, start, end, "abc"); verify(object).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=10-", "bytes=6-3", "bytes=-0", "bytes=abc", "garbage", "bytes=999999999999999999999-"})
    void invalidRangesReturn416WithoutOpeningObject(String range) throws Exception {
        service.writeFile(100L, 42L, range, null, response);
        assertEquals(416, response.getStatus()); assertEquals("bytes */10", response.getHeader("Content-Range"));
        assertEquals(0, response.getContentAsByteArray().length);
        verify(cos, never()).openPaperRange(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void multipleRangesAndIfRangeFallBackToFullFile() throws Exception {
        service.writeFile(100L, 42L, "bytes=0-1,4-5", null, response);
        assertEquals(200, response.getStatus()); assertArrayEquals(bytes, response.getContentAsByteArray());
        var conditional = new MockHttpServletResponse();
        service.writeFile(100L, 42L, "bytes=0-1", "old-etag", conditional);
        assertEquals(200, conditional.getStatus()); assertArrayEquals(bytes, conditional.getContentAsByteArray());
    }

    @Test
    void accessDeniedNeverReadsStorage() {
        when(papers.requireOwnedPaper(100L, 99L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));
        assertThrows(BusinessException.class, () -> service.writeFile(100L, 99L, null, null, response));
        verifyNoInteractions(cos);
    }

    @Test
    void truncatedStorageStreamFailsAndClosesObject() throws Exception {
        COSObject truncated = spy(new COSObject());
        truncated.setObjectContent(new ByteArrayInputStream(new byte[2]));
        doReturn(truncated).when(cos).openPaperRange(any(), any(), anyLong(), anyLong(), any());
        assertThrows(IOException.class, () -> service.writeFile(100L, 42L, null, null, response));
        verify(truncated).close();
    }

    @Test
    void storageFailureDoesNotSetPdfHeaders() {
        when(cos.getPaperMetadata("stored-url", 42L)).thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR));
        assertThrows(BusinessException.class, () -> service.writeFile(100L, 42L, null, null, response));
        assertNull(response.getContentType()); assertNull(response.getHeader("Content-Disposition"));
    }
}
