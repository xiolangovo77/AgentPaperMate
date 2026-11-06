package com.ecnu.agentpapermate.ingestion.grobid;

import com.ecnu.agentpapermate.ingestion.model.ParsedPaper;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 只提取 TEI 数据，不解释论文中出现的提示词或指令。每次解析使用独立状态。
 */
final class GrobidTeiParser {
    /**
     * 识别被误标为段落或列表项的带编号英文标题（如 1 Introduction、2.1 Related Work）
     */
    private static final Pattern MISSED_HEADING = Pattern.compile("\\d+(?:\\.\\d+)*\\s+[A-Z][A-Za-z -]{2,70}");

    /**
     * 按解析顺序保存提取的内容块，包括摘要、正文、公式和图表等
     */
    private final List<ParsedPaper.Paragraph> blocks = new ArrayList<>();

    /**
     * 自增序号，用于生成章节、内容块和图表等对象的标识
     */
    private int sequence;

    /**
     * 章节编号与带编号标题的映射，用于拼接包含父章节的标题路径
     */
    private final Map<String, String> numberedTitles = new HashMap<>();
    /**
     * 解析过程中的章节上下文，保存标题路径与章节标识
     */
    private record Section(String title, String id) { }

    /**
     * 禁用外部实体及 DTD，提取 TEI 中的论文元数据、摘要、正文和显式附录
     */
    ParsedPaper parse(String xml) {
        // 1、检查 GROBID 返回的内容，禁用外部 DTD 和 Schema 后解析 XML
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("GROBID 返回空 TEI");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                /**
                 * 将 XML 致命解析错误向上传递，由解析入口统一处理
                 */
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }
            });
            var root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!"TEI".equals(root.getLocalName())) {
                throw new IllegalArgumentException("响应不是 TEI");
            }
            // 2、从 TEI 头部提取标题和作者，并判断作者信息是否可信
            Element header = child(root, "teiHeader");
            Element file = child(header, "fileDesc");
            String title = text(child(child(file, "titleStmt"), "title"));
            Element analytic = child(child(child(file, "sourceDesc"), "biblStruct"), "analytic");
            if (title.isBlank()) {
                title = text(child(analytic, "title"));
            }
            List<String> authors = new ArrayList<>();
            boolean authorsTrusted = true;
            for (Element author : children(analytic, "author")) {
                Element name = child(author, "persName");
                List<String> parts = new ArrayList<>();
                for (Element part : children(name, null)) {
                    if (Set.of("forename", "surname", "nameLink", "genName").contains(part.getLocalName())) {
                        parts.add(text(part));
                    }
                }
                String display = String.join(" ", parts).strip();
                if (!display.isBlank()) {
                    authors.add(display);
                }
                // 无关联机构的单词拉丁姓名可能是地址误识别；保留原值但不自动覆盖作者。
                if (display.matches("[A-Za-z]+") && child(name, "forename") == null
                        && child(author, "affiliation") == null) {
                    authorsTrusted = false;
                }
            }
            // 3、按摘要、正文、显式附录的顺序提取可索引内容
            Element abstractElement = child(child(header, "profileDesc"), "abstract");
            if (abstractElement != null) {
                add(abstractElement, new Section("Abstract", "abstract"), "abstract", text(abstractElement), false);
            }
            Element body = child(child(root, "text"), "body");
            walk(body, new Section("", "body"));
            // back 中仅处理显式附录，不索引参考文献。
            for (Element div : children(child(child(root, "text"), "back"), "div")) {
                if ("appendix".equals(div.getAttribute("type"))) {
                    walk(div, new Section("Appendix", "appendix-" + sequence++));
                }
            }
            // 4、根据 surface 元素数量记录页数，并汇总论文解析结果
            int surfaces = root.getElementsByTagNameNS("*", "surface").getLength();
            return new ParsedPaper(title, String.join(", ", authors), text(abstractElement),
                    surfaces == 0 ? null : surfaces, List.copyOf(blocks), authorsTrusted);
        } catch (Exception e) {
            throw new IllegalArgumentException("GROBID TEI 解析失败", e);
        }
    }

    /**
     * 按元素顺序遍历正文，识别章节、段落、公式及图表，并返回当前章节上下文
     */
    private Section walk(Element container, Section section) {
        boolean tableFragment = false;
        // 1、按文档顺序遍历子元素，遇到正文分区时递归处理
        for (Element element : children(container, null)) {
            String tag = element.getLocalName();
            switch (tag) {
                case "div" -> {
                    if (!"references".equals(element.getAttribute("type"))) {
                        section = walk(element, section);
                    }
                }
                case "head" -> {
                    // 2、区分章节标题与误标为标题的表格片段，更新当前章节或表格标记
                    String heading = text(element);
                    if (tableHeading(heading)) {
                        tableFragment = true;
                        add(element, section, "table-fragment", heading, true);
                    } else if (!heading.isBlank()) {
                        tableFragment = false;
                        String number = element.getAttribute("n");
                        section = section(number, heading);
                    }
                }
                case "p", "item" -> {
                    // 3、识别段落中的编号标题，其余文本按正文或表格片段保存
                    String value = text(element);
                    if (MISSED_HEADING.matcher(value).matches()) {
                        int space = value.indexOf(' ');
                        section = section(value.substring(0, space), value.substring(space + 1));
                        tableFragment = false;
                    } else {
                        add(element, section, tableFragment ? "table-fragment" : "paragraph", value, tableFragment);
                    }
                }
                // 4、分别处理公式、图表和列表，保留对应的内容类型
                case "formula" -> add(element, section, "formula", text(element), false);
                case "figure" -> figure(element, section);
                case "list" -> walk(element, section);
                default -> { /* 排除参考文献、页眉脚注等非正文对象。 */ }
            }
        }
        return section;
    }

    /**
     * 记录带编号标题并拼接已知父章节路径，为章节生成独立标识
     */
    private Section section(String number, String title) {
        // 1、为无编号标题建立独立章节，按编号登记其余标题
        if (number.isBlank()) {
            return new Section(title, "section-" + sequence++);
        }
        numberedTitles.put(number, number + " " + title);
        // 2、查找已知父章节，将父标题和当前标题拼成路径
        List<String> path = new ArrayList<>();
        for (int dot = number.indexOf('.'); dot >= 0; dot = number.indexOf('.', dot + 1)) {
            String parent = numberedTitles.get(number.substring(0, dot));
            if (parent != null) {
                path.add(parent);
            }
        }
        path.add(numberedTitles.get(number));
        return new Section(String.join(" / ", path), "section-" + sequence++);
    }

    /**
     * 提取图注或按行提取表格，保留表头并标记结构不确定的表格内容
     */
    private void figure(Element figure, Section section) {
        // 1、提取图表说明，为直接挂在正文下的浮动图表单独分配章节
        String caption = String.join(" ", text(child(figure, "head")), text(child(figure, "figDesc"))).strip();
        Element table = child(figure, "table");
        // GROBID 常将浮动图表集中放在 body 末尾，不能归属到最后一个正文小节。
        if (figure.getParentNode() instanceof Element parent && "body".equals(parent.getLocalName())) {
            section = new Section(table == null ? "Figures" : "Tables", "object-" + sequence++);
        }
        // 2、插图保存图注，缺少行结构的表格保留全文并标记低置信度
        if (table == null) {
            add(figure, section, "figure", caption, false);
            return;
        }
        List<Element> rows = children(table, "row");
        if (rows.isEmpty()) {
            add(figure, section, "table", caption + "\n" + text(table), true);
            return;
        }
        // 3、提取表头，检查列数、合并列和单元格文本是否提示结构异常
        String header = rowText(rows.getFirst());
        int columns = children(rows.getFirst(), "cell").size();
        boolean uncertain = rows.stream().anyMatch(row -> children(row, "cell").size() != columns
                || children(row, "cell").stream().anyMatch(cell -> cell.hasAttribute("cols")
                || text(cell).matches(".*\\d\\.\\d+\\s+\\d\\.\\d+\\s+\\d\\.\\d+.*")));
        // 4、为表格分配独立标识，逐行保存图表说明、表头及对应行文本
        String tableId = figure.getAttributeNS(XMLConstants.XML_NS_URI, "id");
        Section tableSection = new Section(section.title(), "table:" + (tableId.isBlank() ? sequence++ : tableId));
        if (rows.size() == 1) {
            add(figure, tableSection, "table", caption + "\n" + header, uncertain);
        }
        for (int i = 1; i < rows.size(); i++) {
            // 行坐标缺失时不借用表格中其他行的页码。
            add(rows.get(i), tableSection, "table", caption + "\n" + header + "\n" + rowText(rows.get(i)), uncertain);
        }
    }

    /**
     * 按单元格顺序提取表格行文本，以竖线分隔各列
     */
    private String rowText(Element row) { return String.join(" | ", children(row, "cell").stream().map(GrobidTeiParser::text).toList()); }

    /**
     * 保存有效内容块，提取页码和引用标识，并仅保留完整覆盖段落的句子列表
     */
    private void add(Element element, Section section, String kind, String value, boolean uncertain) {
        // 1、跳过空白或没有字母、数字的内容
        if (value.isBlank() || value.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return;
        }
        // 2、收集页码并确定来源块 ID，缺失 ID 时按解析顺序生成
        List<Integer> pages = new ArrayList<>();
        collectPages(element, pages);
        String id = element.getAttributeNS(XMLConstants.XML_NS_URI, "id");
        if (id.isBlank()) {
            id = "block-" + sequence++;
        }
        // 3、提取引用目标，去掉本地引用的井号前缀
        List<String> refs = new ArrayList<>();
        var references = element.getElementsByTagNameNS("*", "ref");
        for (int i = 0; i < references.getLength(); i++) {
            String target = ((Element) references.item(i)).getAttribute("target");
            for (String ref : target.split("\\s+")) {
                if (!ref.isBlank()) {
                    refs.add(ref.replaceFirst("^#", ""));
                }
            }
        }
        // 4、提取正文句子，只有完整覆盖段落文本时才保留句子列表
        List<String> sentences = new ArrayList<>();
        if (kind.equals("paragraph")) {
            var nodes = element.getElementsByTagNameNS("*", "s");
            for (int i = 0; i < nodes.getLength(); i++) {
                sentences.add(text((Element) nodes.item(i)));
            }
            // 仅在句子完整覆盖段落时使用它们，避免丢掉 <s> 外的残余文本。
            if (!String.join("", sentences).replaceAll("\\s", "").equals(value.replaceAll("\\s", ""))) {
                sentences.clear();
            }
        }
        // 5、保存内容块及其章节、页码范围、引用和置信度标记
        blocks.add(new ParsedPaper.Paragraph(value, section.title(), pages.stream().min(Integer::compare).orElse(null),
                pages.stream().max(Integer::compare).orElse(null), id, kind, section.id(), uncertain,
                refs.stream().distinct().toList(), List.copyOf(sentences)));
    }

    /**
     * 递归读取元素坐标中的正整数页码，忽略无法解析的页码
     */
    private static void collectPages(Element element, List<Integer> pages) {
        // 1、从当前元素的坐标读取有效物理页码
        String coords = element.getAttribute("coords");
        if (!coords.isBlank()) {
            for (String box : coords.split(";")) {
                try {
                    int page = Integer.parseInt(box.strip().split(",")[0]);
                    if (page > 0) {
                        pages.add(page);
                    }
                } catch (NumberFormatException ignored) {
                    // 坏坐标按未知处理，不编造页码。
                }
            }
        }
        // 2、递归收集子元素页码，供内容块计算起止页
        for (Element child : children(element, null)) {
            collectPages(child, pages);
        }
    }

    /**
     * 根据特定表头特征识别疑似误标为章节标题的表格片段
     */
    private static boolean tableHeading(String heading) { return heading.matches(".*(?:Acc>|GPT>|Cost\\s*[↓↑]|Prof\\.).*"); }
    /**
     * 按元素本地名称查找第一个直接子元素，未找到时返回 null
     */
    private static Element child(Element parent, String name) { return children(parent, name).stream().findFirst().orElse(null); }
    /**
     * 按原始顺序获取匹配名称的直接子元素，名称为 null 时获取全部直接子元素
     */
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent != null) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && (name == null || name.equals(e.getLocalName()))) {
                    result.add(e);
                }
            }
        }
        return result;
    }
    /**
     * 提取元素文本并合并连续空白，元素不存在时返回空字符串
     */
    private static String text(Element e) {
        if (e == null) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        appendText(e, value);
        return value.toString().replaceAll("\\s+", " ").strip();
    }
    /**
     * 递归拼接文本节点，在段落、句子和表格等结构边界补充空格以避免文本粘连
     */
    private static void appendText(Node node, StringBuilder value) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            value.append(node.getNodeValue());
        } else {
            boolean boundary = Set.of("p", "s", "head", "row", "cell").contains(Objects.toString(node.getLocalName(), ""));
            if (boundary) {
                value.append(' ');
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                appendText(child, value);
            }
            if (boundary) {
                value.append(' ');
            }
        }
    }
}
