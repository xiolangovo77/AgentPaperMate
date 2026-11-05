package com.ecnu.agentpapermate.ingestion;

import com.ecnu.agentpapermate.config.paper.PaperIngestionProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import java.nio.file.Path;
import java.time.Duration;

/**
 * GROBID 客户端，负责提交 PDF 并将返回的 TEI XML 转换为论文解析结果
 */
@Component
public class GrobidClient {
    /**
     * 用于调用 GROBID 服务的 HTTP 客户端，包含连接与读取超时设置
     */
    private final RestClient client;

    /**
     * 根据入库配置初始化 GROBID 服务地址及请求超时
     */
    public GrobidClient(PaperIngestionProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
        client = RestClient.builder().baseUrl(properties.getGrobidUrl()).requestFactory(factory).build();
    }

    /**
     * 上传本地 PDF，启用句子分割和指定元素的坐标输出后解析返回结果
     */
    public ParsedPaper parse(Path pdf) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("input", new FileSystemResource(pdf));
        body.add("consolidateHeader", "0");
        body.add("consolidateCitations", "0");
        body.add("segmentSentences", "1");
        body.add("teiCoordinates", "p");
        body.add("teiCoordinates", "s");
        body.add("teiCoordinates", "formula");
        body.add("teiCoordinates", "figure");
        body.add("teiCoordinates", "head");
        String xml = client.post().uri("/api/processFulltextDocument").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve().body(String.class);
        return parseTei(xml);
    }

    /**
     * 使用独立解析器读取 TEI XML，避免不同论文共享解析状态
     */
    public ParsedPaper parseTei(String xml) {
        return new GrobidTeiParser().parse(xml);
    }
}
