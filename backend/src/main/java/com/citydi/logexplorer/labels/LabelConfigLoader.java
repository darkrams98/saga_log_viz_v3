package com.citydi.logexplorer.labels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * خواندن config.json.
 *
 * همان سه اصل ConfigLoader:
 *  ۱) هیچ کلیدی اجباری نیست.
 *  ۲) نوع اشتباه برنامه را نمی‌شکند؛ در warnings ثبت می‌شود.
 *  ۳) یک الگوی regex نامعتبر فقط خودش نادیده گرفته می‌شود، نه کل فایل.
 *
 * کلیدهایی که با «_» شروع می‌شوند (مثل "_راهنما") توضیح‌اند و نادیده گرفته می‌شوند،
 * چون JSON کامنت ندارد و فایل بدون توضیح غیرقابل نگهداری می‌شود.
 */
public final class LabelConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(LabelConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LabelConfigLoader() {
    }

    public static LabelConfig load(Path file) {
        List<String> warnings = new ArrayList<>();
        try {
            if (file == null || !Files.isReadable(file)) {
                warnings.add("فایل برچسب‌ها پیدا نشد (" + file + ") — برچسب‌های خام نمایش داده می‌شوند.");
                log.warn("config.json پیدا نشد: {}", file);
                return LabelConfig.minimal(warnings);
            }
            return build(MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8)), warnings);
        } catch (Exception e) {
            warnings.add("خواندن config.json ناموفق بود: " + e.getMessage()
                    + " — برچسب‌های خام نمایش داده می‌شوند.");
            log.error("خواندن config.json ناموفق بود", e);
            return LabelConfig.minimal(warnings);
        }
    }

    public static LabelConfig loadFromStream(InputStream in) {
        List<String> warnings = new ArrayList<>();
        try {
            return build(MAPPER.readTree(in), warnings);
        } catch (Exception e) {
            warnings.add("خواندن config.json ناموفق بود: " + e.getMessage());
            return LabelConfig.minimal(warnings);
        }
    }

    // ------------------------------------------------------------- build

    static LabelConfig build(JsonNode root, List<String> warnings) {
        LabelConfig d = LabelConfig.minimal(List.of());
        if (root == null || !root.isObject()) {
            warnings.add("ریشهٔ config.json یک شیء نیست — برچسب‌های پیش‌فرض استفاده شد.");
            return LabelConfig.minimal(warnings);
        }

        Map<String, String> statuses = stringMap(root.get("statuses"));
        Map<String, String> severity = upperKeys(stringMap(root.get("statusSeverity")));

        return new LabelConfig(
                stringMap(root.get("routingKeys")),
                patterns(root.get("routingKeyPatterns"), warnings),
                stringMap(root.get("commandTypes")),
                statuses.isEmpty() ? d.statuses : upperKeys(statuses),
                severity.isEmpty() ? d.statusSeverity : severity,
                stringMap(root.get("titles")),
                stringMap(root.get("fieldLabels")),
                summaryFields(root.get("summaryFields")),
                graph(root.get("graph"), d.graph),
                search(root.get("search"), d.search, warnings),
                text(path(root, "privacy", "maskingProfile"), d.maskingProfile),
                warnings);
    }

    private static List<LabelConfig.PatternRule> patterns(JsonNode node, List<String> warnings) {
        List<LabelConfig.PatternRule> out = new ArrayList<>();
        for (JsonNode n : array(node)) {
            String match = text(n.get("match"), null);
            String label = text(n.get("label"), null);
            if (match == null || label == null) {
                continue;
            }
            try {
                out.add(new LabelConfig.PatternRule(Pattern.compile(match), match, label));
            } catch (Exception e) {
                warnings.add("الگوی routingKey نامعتبر و نادیده گرفته شد: «" + match + "»");
            }
        }
        return List.copyOf(out);
    }

    private static List<LabelConfig.SummaryField> summaryFields(JsonNode node) {
        List<LabelConfig.SummaryField> out = new ArrayList<>();
        for (JsonNode n : array(node)) {
            String p = text(n.get("path"), null);
            if (p == null) {
                continue;
            }
            out.add(new LabelConfig.SummaryField(p, text(n.get("label"), p),
                    text(n.get("translate"), null), text(n.get("type"), "text"),
                    bool(n.get("copy"), false)));
        }
        return List.copyOf(out);
    }

    private static LabelConfig.GraphConfig graph(JsonNode n, LabelConfig.GraphConfig d) {
        if (n == null || !n.isObject()) {
            return d;
        }
        Map<String, String> colors = stringMap(n.get("colors"));
        return new LabelConfig.GraphConfig(
                text(n.get("layout"), d.layout()),
                text(n.get("source"), d.source()),
                text(n.get("nodeLabelFrom"), d.nodeLabelFrom()),
                text(n.get("nodeSubLabelFrom"), d.nodeSubLabelFrom()),
                text(n.get("statusFrom"), d.statusFrom()),
                stringList(n.get("errorTextFrom"), d.errorTextFrom()),
                stringList(n.get("detailFields"), d.detailFields()),
                bool(n.get("mergeRepeatedService"), d.mergeRepeatedService()),
                bool(n.get("showStartEnd"), d.showStartEnd()),
                text(n.get("startLabel"), d.startLabel()),
                text(n.get("endLabel"), d.endLabel()),
                colors.isEmpty() ? d.colors() : colors);
    }

    private static LabelConfig.SearchConfig search(JsonNode n, LabelConfig.SearchConfig d,
                                                   List<String> warnings) {
        if (n == null || !n.isObject()) {
            return d;
        }
        List<LabelConfig.NormalField> normal = new ArrayList<>();
        for (JsonNode f : array(n.get("normalFields"))) {
            String field = text(f.get("field"), null);
            if (field == null) {
                continue;
            }
            normal.add(new LabelConfig.NormalField(field, text(f.get("label"), field),
                    text(f.get("type"), "string"), bool(f.get("indexed"), false),
                    bool(f.get("enabled"), true), bool(f.get("default"), false),
                    text(f.get("placeholder"), ""), text(f.get("hint"), "")));
        }
        if (normal.isEmpty()) {
            warnings.add("هیچ فیلد جستجوی عادی تعریف نشده — فقط _id در دسترس است.");
            normal = new ArrayList<>(d.normalFields());
        }
        boolean anyUsable = normal.stream().anyMatch(f -> f.enabled() && f.indexed());
        if (!anyUsable) {
            warnings.add("هیچ فیلد جستجوی عادیِ فعال و ایندکس‌شده‌ای وجود ندارد؛ "
                    + "جستجوی عادی کار نخواهد کرد.");
        }
        return new LabelConfig.SearchConfig(List.copyOf(normal),
                advanced(n.get("advanced"), d.advanced()));
    }

    private static LabelConfig.AdvancedConfig advanced(JsonNode n, LabelConfig.AdvancedConfig d) {
        if (n == null || !n.isObject()) {
            return d;
        }
        List<LabelConfig.Operator> ops = new ArrayList<>();
        for (JsonNode o : array(n.get("operators"))) {
            String op = text(o.get("op"), null);
            String mongo = text(o.get("mongo"), null);
            if (op != null && mongo != null) {
                ops.add(new LabelConfig.Operator(op, text(o.get("label"), op), mongo));
            }
        }
        List<LabelConfig.SuggestedField> suggested = new ArrayList<>();
        for (JsonNode s : array(n.get("suggestedFields"))) {
            String field = text(s.get("field"), null);
            if (field != null) {
                suggested.add(new LabelConfig.SuggestedField(field, text(s.get("label"), field)));
            }
        }
        List<LabelConfig.ResultField> results = new ArrayList<>();
        for (JsonNode r : array(n.get("resultFields"))) {
            String p = text(r.get("path"), null);
            if (p != null) {
                results.add(new LabelConfig.ResultField(p, text(r.get("label"), p),
                        text(r.get("translate"), null), text(r.get("type"), "text")));
            }
        }
        return new LabelConfig.AdvancedConfig(
                bool(n.get("enabled"), d.enabled()),
                clamp(intOf(n.get("maxResults"), d.maxResults()), 1, 200),
                clamp(longOf(n.get("maxTimeMs"), d.maxTimeMs()), 1000L, 60_000L),
                text(n.get("warning"), d.warning()),
                ops.isEmpty() ? d.operators() : List.copyOf(ops),
                List.copyOf(suggested),
                List.copyOf(results));
    }

    // ------------------------------------------------------------- utils

    private static JsonNode path(JsonNode root, String... keys) {
        JsonNode n = root;
        for (String k : keys) {
            if (n == null) {
                return null;
            }
            n = n.get(k);
        }
        return n;
    }

    private static Iterable<JsonNode> array(JsonNode n) {
        return n instanceof ArrayNode a ? a : List.of();
    }

    /** کلیدهای توضیحی که با «_» شروع می‌شوند نادیده گرفته می‌شوند */
    private static Map<String, String> stringMap(JsonNode n) {
        if (n == null || !n.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (key.startsWith("_")) {
                continue;
            }
            JsonNode v = n.get(key);
            if (v != null && v.isValueNode() && !v.isNull()) {
                out.put(key, v.asText());
            }
        }
        return Map.copyOf(out);
    }

    private static Map<String, String> upperKeys(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k.toUpperCase(java.util.Locale.ROOT), v));
        return Map.copyOf(out);
    }

    private static List<String> stringList(JsonNode n, List<String> fallback) {
        if (!(n instanceof ArrayNode a) || a.isEmpty()) {
            return fallback;
        }
        List<String> out = new ArrayList<>(a.size());
        for (JsonNode v : a) {
            if (v != null && v.isValueNode()) {
                out.add(v.asText());
            }
        }
        return out.isEmpty() ? fallback : List.copyOf(out);
    }

    private static String text(JsonNode n, String fallback) {
        if (n == null || n.isNull() || !n.isValueNode()) {
            return fallback;
        }
        String s = n.asText();
        return s == null || s.isBlank() ? fallback : s;
    }

    private static boolean bool(JsonNode n, boolean fallback) {
        if (n == null || n.isNull()) {
            return fallback;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        String s = n.asText("").trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> fallback;
        };
    }

    private static int intOf(JsonNode n, int fallback) {
        return n != null && n.isNumber() ? n.asInt() : fallback;
    }

    private static long longOf(JsonNode n, long fallback) {
        return n != null && n.isNumber() ? n.asLong() : fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
