package com.ecnu.agentpapermate.mange;

import com.ecnu.agentpapermate.config.CosClientConfig;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.apache.http.client.methods.HttpGet;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CosManagerTest {
    private static final String KEY = "papers/2026/11/03/0123456789abcdef0123456789abcdef.pdf";
    private static final String URL = "https://files.example.com/" + KEY;
    private COSClient client;
    private CosManager manager;

    @BeforeEach
    void setUp() {
        client = mock(COSClient.class);
        CosClientConfig config = new CosClientConfig();
        config.setHost("https://files.example.com/");
        config.setBucket("test-bucket");
        manager = new CosManager(config, client);
    }

    @Test
    void uploadPreservesChineseFilenameAndOwner() {
        when(client.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());
        assertEquals(URL, manager.uploadPaper(KEY, new File("unused.pdf"), "研究论文.pdf", 42L));
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture());
        ObjectMetadata metadata = captor.getValue().getMetadata();
        assertEquals("42", metadata.getUserMetaDataOf("owner-id"));
        assertEquals("研究论文.pdf", new String(Base64.getDecoder().decode(
                metadata.getUserMetaDataOf("original-filename-base64")), StandardCharsets.UTF_8));
        assertEquals("application/pdf", metadata.getContentType());
    }

    @Test
    void readsActualBytesForHashAndSizeAndClosesStream() throws Exception {
        byte[] bytes = "%PDF-1.7\n测试正文\n%%EOF".getBytes(StandardCharsets.UTF_8);
        ClosingStream stream = object(bytes, "42", (long) bytes.length, true);
        CosManager.PaperFileInfo result = manager.readPaper(URL, 42L);
        assertEquals("研究论文.pdf", result.getOriginalFilename());
        assertEquals((long) bytes.length, result.getFileSize());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                result.getFileHash());
        assertTrue(stream.closed);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/private.pdf",
            "https://files.example.com.evil.com/papers/a.pdf",
            "https://files.example.com/papers/../secret.pdf",
            "https://files.example.com/avatars/2026/11/03/0123456789abcdef0123456789abcdef.pdf",
            URL + "?sign=temporary",
            URL + "#fragment"
    })
    void rejectsForeignOrNonCanonicalUrlsWithoutNetwork(String url) {
        assertThrows(BusinessException.class, () -> manager.readPaper(url, 42L));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsAnotherUsersFileBeforeReadingContent() {
        ClosingStream stream = object("%PDF-file".getBytes(StandardCharsets.UTF_8), "99", 9L, true);
        BusinessException error = assertThrows(BusinessException.class, () -> manager.readPaper(URL, 42L));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), error.getCode());
        assertEquals(9, stream.available());
        assertTrue(stream.closed);
    }

    @Test
    void rejectsLegacyUploadWithoutFilename() {
        object("%PDF-file".getBytes(StandardCharsets.UTF_8), "42", 9L, false);
        assertThrows(BusinessException.class, () -> manager.readPaper(URL, 42L));
    }

    @Test
    void rejectsTruncatedDownload() {
        ClosingStream stream = object("%PDF-file".getBytes(StandardCharsets.UTF_8), "42", 100L, true);
        assertThrows(BusinessException.class, () -> manager.readPaper(URL, 42L));
        assertTrue(stream.closed);
    }

    @Test
    void rejectsInvalidPdfHeader() {
        object("not a PDF".getBytes(StandardCharsets.UTF_8), "42", 9L, true);
        assertThrows(BusinessException.class, () -> manager.readPaper(URL, 42L));
    }

    @Test
    void rejectsOversizedObjectBeforeReadingContent() {
        ClosingStream stream = object("%PDF-file".getBytes(StandardCharsets.UTF_8), "42",
                CosManager.PAPER_MAX_SIZE + 1, true);
        assertThrows(BusinessException.class, () -> manager.readPaper(URL, 42L));
        assertEquals(9, stream.available());
        assertTrue(stream.closed);
    }

    private ClosingStream object(byte[] bytes, String owner, long length, boolean includeName) {
        COSObject object = new COSObject();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(length);
        metadata.addUserMetadata("owner-id", owner);
        if (includeName) {
            metadata.addUserMetadata("original-filename-base64", Base64.getEncoder()
                    .encodeToString("研究论文.pdf".getBytes(StandardCharsets.UTF_8)));
        }
        object.setObjectMetadata(metadata);
        ClosingStream stream = new ClosingStream(bytes);
        object.setObjectContent(new COSObjectInputStream(stream, new HttpGet()));
        when(client.getObject("test-bucket", KEY)).thenReturn(object);
        return stream;
    }

    private static class ClosingStream extends ByteArrayInputStream {
        private boolean closed;
        ClosingStream(byte[] bytes) { super(bytes); }
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
