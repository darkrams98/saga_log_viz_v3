package com.citydi.logexplorer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * خواندن config.yaml و ساخت AppConfig.
 *
 * سه اصل:
 *  ۱) هیچ بخشی اجباری نیست — نبودنش یعنی پیش‌فرض، نه خطا.
 *  ۲) نوع اشتباه هم برنامه را نمی‌شکند؛ فقط در warnings ثبت می‌شود.
 *  ۳) پشتیبانی از ${ENV_VAR:default} تا اتصال دیتابیس در فایل نماند.
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");

    private ConfigLoader() {
    }

    public static AppConfig load(Path file) {
        List<String> warnings = new ArrayList<>();
        Object root;
        try {
            if (file == null || !Files.isReadable(file)) {
                warnings.add("فایل پیکربندی پیدا نشد (" + file + ") — پیکربندی حداقلی استفاده شد.");
                log.warn("config.yaml پیدا نشد: {} — با پیکربندی پیش‌فرض ادامه می‌دهیم", file);
                return AppConfig.minimal(warnings);
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            root = parse(resolveEnv(text));
        } catch (Exception e) {
            warnings.add("خواندن پیکربندی ناموفق بود: " + e.getMessage() + " — پیکربندی حداقلی استفاده شد.");
            log.error("خواندن config.yaml ناموفق بود", e);
            return AppConfig.minimal(warnings);
        }
        return build(ConfigNode.of(root), warnings);
    }

    public static AppConfig loadFromStream(InputStream in) {
        List<String> warnings = new ArrayList<>();
        try {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return build(ConfigNode.of(parse(resolveEnv(text))), warnings);
        } catch (Exception e) {
            warnings.add("خواندن پیکربندی ناموفق بود: " + e.getMessage());
            return AppConfig.minimal(warnings);
        }
    }

    private static Object parse(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(50);
        options.setAllowDuplicateKeys(false);
        return new Yaml(options).load(text);
    }

    /** ${MONGO_URI:mongodb://localhost:27017} → متغیر محیطی یا مقدار پیش‌فرض */
    static String resolveEnv(String text) {
        Matcher m = ENV_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String fallback = m.group(2) == null ? "" : m.group(2);
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                value = System.getProperty(name, fallback);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------- build

    static AppConfig build(ConfigNode root, List<String> warnings) {
        AppConfig defaults = AppConfig.minimal(List.of());

        AppConfig.Mongo mongo = buildMongo(root.get("mongo"), defaults.mongo);
        AppConfig.Limits limits = buildLimits(root.get("limits"), defaults.limits);
        AppConfig.TimeConfig time = buildTime(root.get("time"), defaults.time, warnings);
        Map<String, AppConfig.FieldRule> fields = buildFields(root.get("fields"), defaults.fields);
        AppConfig.LevelConfig level = buildLevel(root.get("level"), defaults.level);
        Map<String, AppConfig.Transform> transforms = buildTransforms(root.get("transforms"), warnings);
        List<AppConfig.Column> columns = buildColumns(root.get("columns"), defaults.columns, warnings);
        List<AppConfig.Facet> facets = buildFacets(root.get("facets"));
        AppConfig.SearchConfig search = buildSearch(root.get("search"), defaults.search, warnings);
        AppConfig.MaskingConfig masking = buildMasking(root.get("masking"), defaults.masking);
        AppConfig.DisplayConfig display = buildDisplay(root.get("display"), defaults.display);
        AppConfig.DashboardConfig dashboard = buildDashboard(root.get("dashboard"), defaults.dashboard);

        if (time.queryField() == null || time.queryField().isBlank()) {
            warnings.add("time.queryField تعیین نشده است؛ بازهٔ زمانی و مرتب‌سازی کار نخواهد کرد.");
        }
        return new AppConfig(mongo, limits, time, fields, level, transforms, columns, facets,
                search, masking, display, dashboard, warnings);
    }

    private static AppConfig.Mongo buildMongo(ConfigNode n, AppConfig.Mongo d) {
        return new AppConfig.Mongo(
                n.get("uri").asString(d.uri()),
                n.get("database").asString(d.database()),
                n.get("collection").asString(d.collection()),
                n.get("readPreference").asString(d.readPreference()),
                n.get("connectTimeoutMs").asInt(d.connectTimeoutMs()),
                n.get("serverSelectionTimeoutMs").asInt(d.serverSelectionTimeoutMs()),
                n.get("socketTimeoutMs").asInt(d.socketTimeoutMs()),
                n.get("queryTimeoutMs").asLong(d.queryTimeoutMs()),
                n.get("aggregationTimeoutMs").asLong(d.aggregationTimeoutMs()),
                n.get("enforceReadOnly").asBoolean(true));
    }

    private static AppConfig.Limits buildLimits(ConfigNode n, AppConfig.Limits d) {
        int maxPage = Math.max(1, n.get("maxPageSize").asInt(d.maxPageSize()));
        return new AppConfig.Limits(
                Math.min(maxPage, Math.max(1, n.get("defaultPageSize").asInt(d.defaultPageSize()))),
                maxPage,
                Math.max(0, n.get("countCap").asInt(d.countCap())),
                Math.max(1, n.get("defaultRangeHours").asInt(d.defaultRangeHours())),
                Math.max(1, n.get("maxRangeDays").asInt(d.maxRangeDays())),
                Math.max(50, n.get("maxFlattenNodes").asInt(d.maxFlattenNodes())),
                Math.max(2, n.get("maxDepth").asInt(d.maxDepth())),
                Math.max(40, n.get("previewChars").asInt(d.previewChars())),
                Math.max(200, n.get("largeValueBytes").asInt(d.largeValueBytes())),
                Math.max(10_000L, n.get("maxDocumentBytes").asLong(d.maxDocumentBytes())),
                Math.max(10, n.get("schemaSampleSize").asInt(d.schemaSampleSize())));
    }

    private static AppConfig.TimeConfig buildTime(ConfigNode n, AppConfig.TimeConfig d, List<String> warnings) {
        List<String> candidates = n.get("candidates").asStringList();
        if (candidates.isEmpty()) {
            candidates = d.candidates();
            warnings.add("time.candidates خالی است؛ فهرست پیش‌فرض استفاده شد.");
        }
        String type = n.get("queryFieldType").asString("auto").toLowerCase();
        return new AppConfig.TimeConfig(
                candidates,
                n.get("queryField").asString(candidates.isEmpty() ? d.queryField() : candidates.get(0)),
                type,
                n.get("tieBreakField").asString("_id"),
                n.get("displayTimezone").asString(d.displayTimezone()),
                n.get("stringFormats").asStringList());
    }

    private static Map<String, AppConfig.FieldRule> buildFields(ConfigNode n, Map<String, AppConfig.FieldRule> d) {
        List<String> keys = n.keys();
        if (keys.isEmpty()) {
            return d;
        }
        Map<String, AppConfig.FieldRule> out = new LinkedHashMap<>();
        for (String key : keys) {
            ConfigNode f = n.get(key);
            List<String> candidates = f.get("candidates").asStringList();
            if (candidates.isEmpty()) {
                // اجازه بده کاربر مختصر بنویسد: message: [a, b, c]
                candidates = f.asStringList();
            }
            out.put(key, new AppConfig.FieldRule(key, candidates, f.get("transform").asString(null)));
        }
        return Map.copyOf(out);
    }

    private static AppConfig.LevelConfig buildLevel(ConfigNode n, AppConfig.LevelConfig d) {
        Map<String, String> map = new LinkedHashMap<>();
        n.get("map").asStringMap().forEach((k, v) -> map.put(k.toUpperCase(), v.toUpperCase()));
        List<String> errorLevels = n.get("errorLevels").asStringList();
        Map<String, String> labels = n.get("labels").asStringMap();
        return new AppConfig.LevelConfig(
                orDefault(n.get("candidates").asStringList(), d.candidates()),
                orDefault(n.get("deriveFrom").asStringList(), d.deriveFrom()),
                Map.copyOf(map),
                n.get("default").asString(d.defaultLevel()).toUpperCase(),
                errorLevels.isEmpty() ? d.errorLevels()
                        : errorLevels.stream().map(String::toUpperCase).toList(),
                labels.isEmpty() ? d.labels() : labels,
                orDefault(n.get("precedence").asStringList(), d.precedence()));
    }

    private static Map<String, AppConfig.Transform> buildTransforms(ConfigNode n, List<String> warnings) {
        Map<String, AppConfig.Transform> out = new LinkedHashMap<>();
        for (String key : n.keys()) {
            ConfigNode t = n.get(key);
            String type = t.get("type").asString("regexReplace");
            String pattern = t.get("pattern").asString(null);
            if ("regexReplace".equals(type) && pattern != null) {
                try {
                    Pattern.compile(pattern);
                } catch (Exception e) {
                    warnings.add("الگوی تبدیل «" + key + "» معتبر نیست و نادیده گرفته شد: " + e.getMessage());
                    continue;
                }
            }
            out.put(key, new AppConfig.Transform(key, type, pattern,
                    t.get("replacement").asString(""), t.get("steps").asStringList()));
        }
        return Map.copyOf(out);
    }

    private static List<AppConfig.Column> buildColumns(ConfigNode n, List<AppConfig.Column> d,
                                                       List<String> warnings) {
        List<ConfigNode> nodes = n.asNodeList();
        if (nodes.isEmpty()) {
            return d;
        }
        List<AppConfig.Column> out = new ArrayList<>();
        for (ConfigNode c : nodes) {
            String key = c.get("key").asString(null);
            if (key == null) {
                warnings.add("یک ستون بدون key در پیکربندی نادیده گرفته شد (" + c.path() + ").");
                continue;
            }
            Integer width = c.get("width").exists() ? c.get("width").asInt(0) : null;
            out.add(new AppConfig.Column(
                    key,
                    c.get("label").asString(key),
                    c.get("source").asString("field"),
                    c.get("path").asString(null),
                    c.get("type").asString("text"),
                    width,
                    c.get("grow").asBoolean(false),
                    c.get("copy").asBoolean(false),
                    c.get("transform").asString(null)));
        }
        return out.isEmpty() ? d : List.copyOf(out);
    }

    private static List<AppConfig.Facet> buildFacets(ConfigNode n) {
        List<AppConfig.Facet> out = new ArrayList<>();
        for (ConfigNode f : n.asNodeList()) {
            String path = f.get("path").asString(null);
            if (path == null) {
                continue;
            }
            out.add(new AppConfig.Facet(path, f.get("label").asString(path),
                    f.get("indexed").asBoolean(false),
                    Math.max(1, f.get("topN").asInt(8)),
                    f.get("transform").asString(null)));
        }
        return List.copyOf(out);
    }

    private static AppConfig.SearchConfig buildSearch(ConfigNode n, AppConfig.SearchConfig d,
                                                      List<String> warnings) {
        List<AppConfig.Detector> detectors = new ArrayList<>();
        for (ConfigNode det : n.get("detectors").asNodeList()) {
            String pattern = det.get("pattern").asString(null);
            String name = det.get("name").asString("detector");
            if (pattern == null) {
                continue;
            }
            try {
                Pattern.compile(pattern);
            } catch (Exception e) {
                warnings.add("الگوی تشخیص «" + name + "» معتبر نیست و نادیده گرفته شد.");
                continue;
            }
            detectors.add(new AppConfig.Detector(name, det.get("label").asString(name), pattern,
                    det.get("normalize").asString(null), det.get("fields").asStringList(),
                    det.get("deepScan").asBoolean(false), det.get("asObjectId").asBoolean(false)));
        }
        List<AppConfig.SearchField> exact = searchFields(n.get("exactFields"));
        return new AppConfig.SearchConfig(
                exact.isEmpty() ? d.exactFields() : exact,
                searchFields(n.get("regexFields")),
                searchFields(n.get("deepScanFields")),
                Math.max(1, n.get("deepSearchMaxRangeHours").asInt(d.deepSearchMaxRangeHours())),
                n.get("regexCaseInsensitive").asBoolean(true),
                List.copyOf(detectors));
    }

    private static List<AppConfig.SearchField> searchFields(ConfigNode n) {
        List<AppConfig.SearchField> out = new ArrayList<>();
        for (ConfigNode f : n.asNodeList()) {
            String path = f.get("path").asString(null);
            if (path != null) {
                out.add(new AppConfig.SearchField(path, f.get("label").asString(path)));
            }
        }
        return List.copyOf(out);
    }

    private static AppConfig.MaskingConfig buildMasking(ConfigNode n, AppConfig.MaskingConfig d) {
        List<AppConfig.MaskRule> rules = new ArrayList<>();
        for (ConfigNode r : n.get("rules").asNodeList()) {
            List<String> f = r.get("fields").asStringList();
            if (f.isEmpty()) {
                continue;
            }
            rules.add(new AppConfig.MaskRule(
                    f.stream().map(s -> s.toLowerCase().replace("_", "")).toList(),
                    r.get("strategy").asString("keepEdges"),
                    r.get("head").asInt(2), r.get("tail").asInt(2),
                    r.get("keep").asInt(12), r.get("value").asString("***")));
        }
        List<String> secrets = n.get("secretFields").asStringList();
        return new AppConfig.MaskingConfig(
                n.get("enabled").asBoolean(true),
                n.get("placeholder").asString(d.placeholder()),
                (secrets.isEmpty() ? d.secretFields() : secrets)
                        .stream().map(s -> s.toLowerCase().replace("_", "")).toList(),
                List.copyOf(rules),
                n.get("allowList").asStringList().stream().map(s -> s.toLowerCase().replace("_", "")).toList(),
                n.get("freeText").asBoolean(true));
    }

    private static AppConfig.DisplayConfig buildDisplay(ConfigNode n, AppConfig.DisplayConfig d) {
        return new AppConfig.DisplayConfig(
                n.get("heavyPaths").asStringList(),
                n.get("hiddenPaths").asStringList(),
                n.get("autoParseJsonStrings").asBoolean(true),
                n.get("emptyLabel").asString(d.emptyLabel()),
                n.get("highlightPaths").asStringList());
    }

    private static AppConfig.DashboardConfig buildDashboard(ConfigNode n, AppConfig.DashboardConfig d) {
        List<AppConfig.Preset> presets = new ArrayList<>();
        for (ConfigNode p : n.get("presets").asNodeList()) {
            String value = p.get("value").asString(null);
            int minutes = p.get("minutes").asInt(0);
            if (value != null && minutes > 0) {
                presets.add(new AppConfig.Preset(value, p.get("label").asString(value), minutes));
            }
        }
        return new AppConfig.DashboardConfig(
                presets.isEmpty() ? d.presets() : List.copyOf(presets),
                n.get("defaultPreset").asString(d.defaultPreset()),
                Math.max(4, n.get("timeBuckets").asInt(d.timeBuckets())),
                Math.max(1, n.get("heavyAggregationAboveHours").asInt(d.heavyAggregationAboveHours())));
    }

    private static List<String> orDefault(List<String> value, List<String> fallback) {
        return value.isEmpty() ? fallback : value;
    }
}
