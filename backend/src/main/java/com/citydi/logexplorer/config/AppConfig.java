package com.citydi.logexplorer.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * مدل تایپ‌دار پیکربندی — ساخته‌شده از config.yaml.
 *
 * همهٔ فیلدها مقدار پیش‌فرض دارند. اگر بخشی از فایل نبود یا خراب بود،
 * برنامه با پیش‌فرض بالا می‌آید و مشکل را در `warnings` گزارش می‌کند.
 *
 * این کلاس فقط «داده» است؛ هیچ منطقی ندارد تا تست‌پذیر بماند.
 */
public final class AppConfig {

    public final Mongo mongo;
    public final Limits limits;
    public final TimeConfig time;
    public final Map<String, FieldRule> fields;
    public final LevelConfig level;
    public final Map<String, Transform> transforms;
    public final List<Column> columns;
    public final List<Facet> facets;
    public final SearchConfig search;
    public final MaskingConfig masking;
    public final DisplayConfig display;
    public final DashboardConfig dashboard;

    /** مشکلاتی که هنگام خواندن پیکربندی دیده شد — در endpoint سلامت نمایش داده می‌شود */
    public final List<String> warnings;

    public AppConfig(Mongo mongo, Limits limits, TimeConfig time, Map<String, FieldRule> fields,
                     LevelConfig level, Map<String, Transform> transforms, List<Column> columns,
                     List<Facet> facets, SearchConfig search, MaskingConfig masking,
                     DisplayConfig display, DashboardConfig dashboard, List<String> warnings) {
        this.mongo = mongo;
        this.limits = limits;
        this.time = time;
        this.fields = fields;
        this.level = level;
        this.transforms = transforms;
        this.columns = columns;
        this.facets = facets;
        this.search = search;
        this.masking = masking;
        this.display = display;
        this.dashboard = dashboard;
        this.warnings = warnings;
    }

    // ------------------------------------------------------------ records

    public record Mongo(String uri, String database, String collection, String readPreference,
                        int connectTimeoutMs, int serverSelectionTimeoutMs, int socketTimeoutMs,
                        long queryTimeoutMs, long aggregationTimeoutMs, boolean enforceReadOnly) {
    }

    public record Limits(int defaultPageSize, int maxPageSize, int countCap,
                         int defaultRangeHours, int maxRangeDays, int maxFlattenNodes,
                         int maxDepth, int previewChars, int largeValueBytes,
                         long maxDocumentBytes, int schemaSampleSize) {
    }

    public record TimeConfig(List<String> candidates, String queryField, String queryFieldType,
                             String tieBreakField, String displayTimezone, List<String> stringFormats) {
    }

    /** یک فیلد منطقی: فهرست مسیرهای کاندید + تبدیل اختیاری */
    public record FieldRule(String name, List<String> candidates, String transform) {
    }

    /**
     * @param precedence ترتیب تصمیم‌گیری: "field" (فیلد صریح level) و "derive"
     *                   (استنتاج از وضعیت). پیش‌فرض ["field","derive"] است، ولی
     *                   بعضی سامانه‌ها فیلد level را همیشه INFO می‌نویسند حتی برای
     *                   خطاها — آنجا باید ["derive","field"] گذاشت.
     */
    public record LevelConfig(List<String> candidates, List<String> deriveFrom,
                              Map<String, String> map, String defaultLevel,
                              List<String> errorLevels, Map<String, String> labels,
                              List<String> precedence) {
        public boolean isError(String lvl) {
            return lvl != null && errorLevels.contains(lvl.toUpperCase());
        }
    }

    /** تبدیل متنی عمومی: regexReplace | chain | upper | lower | trim */
    public record Transform(String name, String type, String pattern, String replacement,
                            List<String> steps) {
    }

    public record Column(String key, String label, String source, String path, String type,
                         Integer width, boolean grow, boolean copy, String transform) {
    }

    public record Facet(String path, String label, boolean indexed, int topN, String transform) {
    }

    public record SearchField(String path, String label) {
    }

    public record Detector(String name, String label, String pattern, String normalize,
                           List<String> fields, boolean deepScan, boolean asObjectId) {
    }

    public record SearchConfig(List<SearchField> exactFields, List<SearchField> regexFields,
                               List<SearchField> deepScanFields, int deepSearchMaxRangeHours,
                               boolean regexCaseInsensitive, List<Detector> detectors) {
    }

    public record MaskRule(List<String> fields, String strategy, int head, int tail,
                           int keep, String value) {
    }

    public record MaskingConfig(boolean enabled, String placeholder, List<String> secretFields,
                                List<MaskRule> rules, List<String> allowList, boolean freeText) {
    }

    public record DisplayConfig(List<String> heavyPaths, List<String> hiddenPaths,
                                boolean autoParseJsonStrings, String emptyLabel,
                                List<String> highlightPaths) {
    }

    public record Preset(String value, String label, int minutes) {
    }

    public record DashboardConfig(List<Preset> presets, String defaultPreset, int timeBuckets,
                                  int heavyAggregationAboveHours) {
    }

    // ----------------------------------------------------------- defaults

    /**
     * پیکربندی حداقلی که *هیچ* فرضی دربارهٔ schema ندارد.
     * اگر فایل config.yaml اصلاً پیدا نشود، برنامه با این بالا می‌آید:
     * لیست لاگ‌ها همچنان کار می‌کند، فقط ستون‌های اختصاصی ندارد.
     */
    public static AppConfig minimal(List<String> warnings) {
        Map<String, FieldRule> fields = new LinkedHashMap<>();
        fields.put("id", new FieldRule("id", List.of("_id", "id"), null));
        fields.put("message", new FieldRule("message", List.of("message", "msg", "title"), null));
        fields.put("status", new FieldRule("status", List.of("status", "state"), null));
        fields.put("service", new FieldRule("service", List.of("service", "applicationName"), null));

        List<Column> columns = new ArrayList<>();
        columns.add(new Column("time", "زمان", "field", null, "datetime", 168, false, false, null));
        columns.add(new Column("level", "سطح", "field", null, "level", 86, false, false, null));
        columns.add(new Column("message", "پیام", "field", null, "text", null, true, false, null));
        columns.add(new Column("id", "شناسه", "field", null, "id", 132, false, true, null));

        return new AppConfig(
                new Mongo("mongodb://localhost:27017", "logs", "logs", "secondaryPreferred",
                        5000, 5000, 30000, 8000, 15000, true),
                new Limits(25, 200, 10000, 24, 31, 3000, 15, 400, 2000, 4_000_000L, 300),
                new TimeConfig(List.of("timestamp", "@timestamp", "time", "createdAt", "startDate"),
                        "timestamp", "auto", "_id", "Asia/Tehran", List.of()),
                fields,
                new LevelConfig(List.of("level", "severity"), List.of("status"), Map.of(),
                        "INFO", List.of("ERROR", "FATAL"),
                        Map.of("ERROR", "خطا", "WARN", "هشدار", "INFO", "اطلاعات", "DEBUG", "اشکال‌زدایی"),
                        List.of("field", "derive")),
                Map.of(), columns, List.of(),
                new SearchConfig(List.of(new SearchField("_id", "شناسه")), List.of(), List.of(),
                        6, true, List.of()),
                new MaskingConfig(true, "[حذف‌شده به دلیل امنیتی]", List.of("password", "otp", "token"),
                        List.of(), List.of(), true),
                new DisplayConfig(List.of(), List.of(), true, "نامشخص", List.of()),
                new DashboardConfig(List.of(new Preset("24h", "۲۴ ساعت گذشته", 1440)), "24h", 48, 48),
                warnings);
    }

    public FieldRule field(String name) {
        return fields.get(name);
    }

    public Preset preset(String value) {
        for (Preset p : dashboard.presets()) {
            if (p.value().equalsIgnoreCase(value)) {
                return p;
            }
        }
        return null;
    }
}
