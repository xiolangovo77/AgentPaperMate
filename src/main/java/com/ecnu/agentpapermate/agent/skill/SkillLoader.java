package com.ecnu.agentpapermate.agent.skill;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** 仅加载应用内 agent-skills 资源；不接受用户路径、URL、脚本或通用 YAML 对象。 */
@Component
public class SkillLoader {
    /** 单文件、完整技能、文件数量和递归引用深度的硬上限。 */
    static final int MAX_FILE_BYTES = 16 * 1024, MAX_TOTAL_BYTES = 48 * 1024, MAX_FILES = 8, MAX_DEPTH = 2;
    /** 唯一支持的参考语法；路径相对于本技能目录。 */
    private static final Pattern INCLUDE = Pattern.compile("\\{\\{include:([^}\\r\\n]+)}}");
    /** 测试可提供受控字节源；生产构造器始终绑定打包资源目录。 */
    private final Source source;

    public SkillLoader() { this(SkillLoader::readResource); }

    SkillLoader(Source source) { this.source = source; }

    /** 注册时只解释元数据，不加载正文引用；读入量仍受单文件限制。 */
    public Metadata metadata(String id) { return parse(read(id, "SKILL.md"), id).metadata(); }

    /** 选定技能后加载引用闭包；注册后元数据变化会拒绝本次创建。 */
    public SkillSnapshot load(Metadata registered) {
        var files = new LinkedHashMap<String, String>();
        String main = read(registered.id(), "SKILL.md");
        Parsed parsed = parse(main, registered.id());
        if (!registered.equals(parsed.metadata())) throw invalid();
        files.put("SKILL.md", main);
        String instructions = expand(registered.id(), parsed.body(), 0, new HashSet<>(Set.of("SKILL.md")), files);
        return snapshot(registered, instructions, files);
    }

    /** 重建持久化快照并验证所有字段及哈希，绝不回读当前磁盘内容或补齐缺失文件。 */
    public static SkillSnapshot verify(SkillSnapshot saved) {
        if (saved == null || saved.files().size() > MAX_FILES) throw invalid();
        var loader = new SkillLoader((id, path) -> {
            String content = saved.files().get(path);
            if (content == null) throw invalid();
            return content.getBytes(StandardCharsets.UTF_8);
        });
        var rebuilt = loader.load(loader.metadata(saved.id()));
        if (!rebuilt.equals(saved)) throw invalid();
        return rebuilt;
    }

    /** 参考深度、环、总量与路径都在读取前/后检查，重复引用只登记一个原始文件。 */
    private String expand(String id, String body, int depth, Set<String> stack, Map<String, String> files) {
        var matcher = INCLUDE.matcher(body);
        var result = new StringBuilder();
        int offset = 0;
        while (matcher.find()) {
            result.append(body, offset, matcher.start());
            String path = matcher.group(1);
            validatePath(path);
            if (depth >= MAX_DEPTH || !stack.add(path)) throw invalid();
            if (!files.containsKey(path)) {
                if (files.size() >= MAX_FILES) throw invalid();
                files.put(path, read(id, path));
                checkTotal(files);
            }
            result.append("\n[参考资料：").append(path).append("]\n")
                    .append(expand(id, files.get(path), depth + 1, stack, files));
            stack.remove(path);
            offset = matcher.end();
            if (result.length() > MAX_TOTAL_BYTES) throw invalid();
        }
        result.append(body.substring(offset));
        // 非法/未闭合引用不能被默默当成正文；展开文本另设总字节上限以防重复放大。
        if (result.indexOf("{{include") >= 0
                || result.toString().getBytes(StandardCharsets.UTF_8).length > MAX_TOTAL_BYTES) throw invalid();
        return result.toString();
    }

    /** 支持严格的 YAML 子集：四个必填标量及 allowed-tools 块列表，拒绝重复键与未知键。 */
    private Parsed parse(String text, String id) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) throw invalid();
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) throw invalid();
        Map<String, String> values = new HashMap<>();
        Set<String> tools = new LinkedHashSet<>();
        boolean inTools = false;
        for (String line : normalized.substring(4, end).split("\n")) {
            if (line.startsWith("  - ") && inTools) {
                String tool = line.substring(4);
                if (!tool.matches("[a-z][a-z0-9_]{0,63}") || !tools.add(tool)) throw invalid();
            } else {
                int colon = line.indexOf(':');
                if (colon <= 0) throw invalid();
                String key = line.substring(0, colon), value = line.substring(colon + 1).strip();
                if (!Set.of("name", "version", "description", "enabled", "allowed-tools").contains(key)
                        || values.putIfAbsent(key, value) != null) throw invalid();
                inTools = key.equals("allowed-tools");
                if (inTools && !value.isEmpty() && !value.equals("[]")) throw invalid();
                if (value.equals("[]")) inTools = false;
            }
        }
        if (values.size() != 5 || !id.equals(values.get("name"))
                || !values.get("version").matches("[0-9]+\\.[0-9]+\\.[0-9]+") || values.get("version").length() > 64
                || values.get("description").isBlank() || values.get("description").length() > 300
                || !Set.of("true", "false").contains(values.get("enabled"))
                || tools.isEmpty() && !"[]".equals(values.get("allowed-tools"))) throw invalid();
        String body = normalized.substring(end + 5).strip();
        if (body.isBlank()) throw invalid();
        return new Parsed(new Metadata(id, values.get("version"), values.get("description"),
                Boolean.parseBoolean(values.get("enabled")), Set.copyOf(tools)), body);
    }

    private String read(String id, String path) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) throw invalid();
        if (!path.equals("SKILL.md")) validatePath(path);
        try {
            byte[] bytes = source.read(id, path);
            if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) throw invalid();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IOException e) { throw new IllegalArgumentException("技能资源不可用", e); }
    }

    /** 白名单路径不含点目录、编码、反斜线、协议、绝对路径，也不能跳到其他技能目录。 */
    private static void validatePath(String path) {
        if (!path.matches("references/(?:[a-zA-Z0-9_-]+/)*[a-zA-Z0-9_-]+\\.md")) throw invalid();
    }

    /** jar 内只访问固定前缀；解包部署另核验真实路径，阻止符号链接/目录联接越界。 */
    private static byte[] readResource(String id, String path) throws IOException {
        var resource = new ClassPathResource("agent-skills/" + id + "/" + path);
        var url = resource.getURL();
        if (url.getProtocol().equals("file")) {
            Path root = new ClassPathResource("agent-skills").getFile().toPath().toRealPath();
            Path skillRoot = root.resolve(id).toRealPath();
            Path target = resource.getFile().toPath().toRealPath();
            if (!skillRoot.startsWith(root) || !target.startsWith(skillRoot) || !Files.isRegularFile(target)) throw invalid();
        } else if (!url.getProtocol().equals("jar")
                || !(url.getFile().startsWith("file:") || url.getFile().startsWith("nested:"))) throw invalid();
        try (InputStream input = resource.getInputStream()) { return input.readNBytes(MAX_FILE_BYTES + 1); }
    }

    private static void checkTotal(Map<String, String> files) {
        if (files.values().stream().mapToInt(s -> s.getBytes(StandardCharsets.UTF_8).length).sum() > MAX_TOTAL_BYTES) throw invalid();
    }

    /** 哈希覆盖排序后的相对路径和原始 UTF-8 内容，以长度前缀消除拼接歧义。 */
    private SkillSnapshot snapshot(Metadata metadata, String instructions, Map<String, String> files) {
        checkTotal(files);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(files).forEach((path, content) -> {
                for (String value : List.of(path, content)) {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                }
            });
            return new SkillSnapshot(metadata.id(), metadata.version(), HexFormat.of().formatHex(digest.digest()),
                    metadata.allowedTools(), instructions, files);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("技能内容、元数据或引用不合法"); }

    /** 注册表只保留用于路由、启用校验和权限收缩的元数据。 */
    public record Metadata(String id, String version, String description, boolean enabled, Set<String> allowedTools) { }
    private record Parsed(Metadata metadata, String body) { }
    @FunctionalInterface
    interface Source { byte[] read(String id, String path) throws IOException; }
}
