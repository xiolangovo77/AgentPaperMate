import com.ecnu.agentpapermate.mapper.PaperMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.ecnu.agentpapermate.rag.PaperLibraryRetriever.INDEX_NAME;

/**
 * 将项目编译产物（含 mapper 资源）和运行依赖加入类路径，再以 Java 源文件模式执行工具。
 * 参数依次为论文映射清单、dev/test、论文归属账号和 paper-library.json 模板。
 * 从 APM_DB_URL、APM_DB_USER、APM_DB_PASSWORD 和 APM_ES_URL 读取连接配置。
 * ES 如需认证，通过 APM_ES_USER、APM_ES_PASSWORD 提供。
 */
class BackfillPaperLibraryIndex {
    /** 解析论文清单、索引定义与应答，并序列化回填请求。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 执行 Elasticsearch 索引检查及批量写入，建立连接最多等待 10 秒。 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static void main(String[] args) throws Exception {
        //1、从指定分组的清单读取待回填的业务论文 ID
        if (args.length != 4) {
            throw new IllegalArgumentException("用法：BackfillPaperLibraryIndex <paper_mapping.json> <dev|test> <归属账号> <paper-library.json>");
        }
        var rows = OBJECT_MAPPER.readTree(Path.of(args[0]).toFile()).required("papers");
        var ids = new LinkedHashSet<Long>();
        for (JsonNode row : rows) {
            if (args[1].equals(row.path("split").asText()) && row.path("can_ask").asBoolean()) {
                ids.add(Long.parseLong(row.required("business_paper_id").asText()));
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("清单缺少指定分组的可问答论文");

        //2、读取指定账号下待回填的有效论文，范围缺失时终止操作
        var mapping = OBJECT_MAPPER.readTree(Path.of(args[3]).toFile());
        var entries = readPapers(List.copyOf(ids), args[2]);
        if (entries.size() != ids.size()) {
            throw new IllegalStateException("存在未就绪、已回收或不属于指定账号的论文，应回填 "
                    + ids.size() + " 篇，数据库返回 " + entries.size() + " 篇");
        }

        //3、创建缺失的论文级索引，并核对实际映射是否符合模板
        var es = requiredEnv("APM_ES_URL").replaceFirst("/+$", "");
        var indexUrl = es + "/" + INDEX_NAME;
        var mappingUrl = indexUrl + "/_mapping";
        var existing = send("GET", mappingUrl, null);
        if (existing.statusCode() == 404) {
            var created = send("PUT", indexUrl, OBJECT_MAPPER.writeValueAsString(mapping));
            if (created.statusCode() >= 300
                    && !"resource_already_exists_exception".equals(OBJECT_MAPPER.readTree(created.body()).at("/error/type").asText())) {
                requireSuccess(created, "创建论文索引");
            }
            existing = send("GET", mappingUrl, null);
        }
        requireSuccess(existing, "读取论文索引映射");
        var actual = OBJECT_MAPPER.readTree(existing.body()).path(INDEX_NAME).path("mappings");
        var expected = mapping.required("mappings");
        if (!expected.path("dynamic").equals(actual.path("dynamic"))
                || !expected.path("properties").equals(actual.path("properties"))) {
            throw new IllegalStateException("同名索引的映射与论文级索引模板不兼容");
        }

        //4、以业务论文 ID 写入元数据，核对批量写入结果
        var bulk = new StringBuilder();
        for (var entry : entries) {
            var operation = Map.of("_id", entry.get("paperId"), "version", entry.get("indexVersion"), "version_type", "external_gte");
            bulk.append(OBJECT_MAPPER.writeValueAsString(Map.of("index", operation))).append('\n');
            bulk.append(OBJECT_MAPPER.writeValueAsString(entry)).append('\n');
        }
        var written = send("POST", indexUrl + "/_bulk?refresh=wait_for", bulk.toString());
        requireSuccess(written, "回填论文索引");
        var result = OBJECT_MAPPER.readTree(written.body());
        if (result.path("errors").asBoolean(true) || result.path("items").size() != entries.size()) {
            long successes = 0;
            for (var item : result.path("items")) {
                if (!item.path("index").has("error")) successes++;
            }
            throw new IllegalStateException("回填未全部成功，已确认写入 " + successes + "/" + entries.size() + " 篇");
        }
        System.out.println("已写入 " + entries.size() + " 篇到 " + INDEX_NAME);
    }

    private static List<Map<String, Object>> readPapers(List<Long> ids, String owner) throws Exception {
        //1、加载项目的论文 Mapper，建立独立的数据库查询会话
        var dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", requiredEnv("APM_DB_URL"),
                requiredEnv("APM_DB_USER"), System.getenv("APM_DB_PASSWORD"));
        var configuration = new Configuration(new Environment("paper-library-backfill", new JdbcTransactionFactory(), dataSource));
        var mapperResource = "mapper/PaperMapper.xml";
        try (var input = Resources.getResourceAsStream(mapperResource)) {
            new XMLMapperBuilder(input, configuration, mapperResource, configuration.getSqlFragments()).parse();
        }
        //2、将有效论文转换为索引文档，缺少摘要时继续保留标题供检索
        List<Map<String, Object>> papers = new ArrayList<>();
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            var paperMapper = session.getMapper(PaperMapper.class);
            for (var paper : paperMapper.selectForLibraryIndex(owner, ids)) {
                String id = paper.getId().toString();
                int version = paper.getActiveIndexVersion();
                String abstractText = paper.getAbstractText();
                papers.add(Map.of("paperId", id, "ownerId", paper.getUserId().toString(), "indexVersion", version,
                        "title", paper.getTitle(), "abstractText", abstractText == null ? "" : abstractText,
                        "scopeKey", id + ":" + version + ":" + paper.getActiveIndexAttempt()));
            }
        }
        return papers;
    }

    private static HttpResponse<String> send(String method, String url, String body) throws Exception {
        //1、设置有界请求时间，并按请求类型和服务配置补充请求头
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45));
        if (body != null) request.header("Content-Type", url.contains("/_bulk?") ? "application/x-ndjson" : "application/json");
        var user = System.getenv("APM_ES_USER");
        if (user != null && !user.isBlank()) {
            String credentials = user + ":" + System.getenv().getOrDefault("APM_ES_PASSWORD", "");
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        //2、提交请求并读取响应，交由调用方核对操作结果
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(operation + "失败，HTTP " + response.statusCode());
        }
    }

    private static String requiredEnv(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少环境变量 " + name);
        return value;
    }
}
