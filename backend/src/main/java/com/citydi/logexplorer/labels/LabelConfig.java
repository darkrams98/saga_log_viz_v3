package com.citydi.logexplorer.labels;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * مدل تایپ‌دار config.json — «چطور نشان بده».
 *
 * تقسیم کار با config.yaml عمدی است:
 *   config.yaml → چطور لاگ را *بخوان* (فیلد زمان، مسیرها، محدودیت‌ها، ماسک)
 *   config.json → چطور آن را *نشان بده* (برچسب فارسی، گراف، جستجو)
 *
 * تیم عملیات معمولاً فقط با فایل دوم کار دارد: افزودن یک میکروسرویس تازه
 * یعنی یک سطر در routingKeys، نه یک استقرار جدید.
 *
 * هیچ بخشی اجباری نیست؛ هر چیزی که نباشد به مقدار خام برمی‌گردد.
 */
public final class LabelConfig {

    public final Map<String, String> routingKeys;
    public final List<PatternRule> routingKeyPatterns;
    public final Map<String, String> commandTypes;
    public final Map<String, String> statuses;
    public final Map<String, String> statusSeverity;
    public final Map<String, String> titles;
    public final Map<String, String> fieldLabels;
    public final List<SummaryField> summaryFields;
    public final GraphConfig graph;
    public final SearchConfig search;
    public final String maskingProfile;

    /** مشکلاتی که هنگام خواندن دیده شد — در /meta/health گزارش می‌شود */
    public final List<String> warnings;

    public LabelConfig(Map<String, String> routingKeys, List<PatternRule> routingKeyPatterns,
                       Map<String, String> commandTypes, Map<String, String> statuses,
                       Map<String, String> statusSeverity, Map<String, String> titles,
                       Map<String, String> fieldLabels, List<SummaryField> summaryFields,
                       GraphConfig graph, SearchConfig search, String maskingProfile,
                       List<String> warnings) {
        this.routingKeys = routingKeys;
        this.routingKeyPatterns = routingKeyPatterns;
        this.commandTypes = commandTypes;
        this.statuses = statuses;
        this.statusSeverity = statusSeverity;
        this.titles = titles;
        this.fieldLabels = fieldLabels;
        this.summaryFields = summaryFields;
        this.graph = graph;
        this.search = search;
        this.maskingProfile = maskingProfile;
        this.warnings = warnings;
    }

    // ----------------------------------------------------------- records

    /** الگوی پشتیبان برای routingKeyهایی که نسخه‌شان عوض شده ولی سرویس همان است */
    public record PatternRule(Pattern pattern, String source, String label) {
    }

    /**
     * @param translate نام نگاشتی که مقدار باید از آن ترجمه شود (titles/statuses/...)
     */
    public record SummaryField(String path, String label, String translate, String type,
                               boolean copy) {
    }

    public record GraphConfig(String layout, String source, String nodeLabelFrom,
                              String nodeSubLabelFrom, String statusFrom,
                              List<String> errorTextFrom, List<String> detailFields,
                              boolean mergeRepeatedService, boolean showStartEnd,
                              String startLabel, String endLabel, Map<String, String> colors) {
    }

    /**
     * @param indexed ادعای پیکربندی دربارهٔ وجود ایندکس؛ در راه‌اندازی با
     *                listIndexes راستی‌آزمایی می‌شود و اگر دروغ باشد هشدار می‌دهیم.
     * @param enabled اگر false باشد در UI نمایش داده نمی‌شود
     */
    public record NormalField(String field, String label, String type, boolean indexed,
                              boolean enabled, boolean isDefault, String placeholder, String hint) {
    }

    public record Operator(String op, String label, String mongo) {
    }

    public record SuggestedField(String field, String label) {
    }

    public record ResultField(String path, String label, String translate, String type) {
    }

    public record AdvancedConfig(boolean enabled, int maxResults, long maxTimeMs, String warning,
                                 List<Operator> operators, List<SuggestedField> suggestedFields,
                                 List<ResultField> resultFields) {
        public Operator operator(String op) {
            for (Operator o : operators) {
                if (o.op().equalsIgnoreCase(op)) {
                    return o;
                }
            }
            return null;
        }
    }

    public record SearchConfig(List<NormalField> normalFields, AdvancedConfig advanced) {
        /** فقط فیلدهایی که هم فعال‌اند و هم ادعای ایندکس دارند */
        public NormalField allowed(String field) {
            for (NormalField f : normalFields) {
                if (f.field().equals(field) && f.enabled() && f.indexed()) {
                    return f;
                }
            }
            return null;
        }

        public NormalField defaultField() {
            for (NormalField f : normalFields) {
                if (f.isDefault() && f.enabled() && f.indexed()) {
                    return f;
                }
            }
            for (NormalField f : normalFields) {
                if (f.enabled() && f.indexed()) {
                    return f;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------- پیش‌فرض

    /**
     * پیکربندی حداقلی وقتی config.json پیدا نشود.
     * برنامه باید کار کند — فقط همه‌چیز به انگلیسیِ خام نمایش داده می‌شود.
     */
    public static LabelConfig minimal(List<String> warnings) {
        return new LabelConfig(
                Map.of(), List.of(), Map.of(),
                Map.of("COMPLETED", "موفق", "ROLL_BACKED", "بازگشت خورده", "FAILED", "ناموفق"),
                Map.of("COMPLETED", "success", "ROLL_BACKED", "error", "FAILED", "error"),
                Map.of(), Map.of(), List.of(),
                new GraphConfig("horizontal-rtl", "commandList", "routingKey", "commandType",
                        "status", List.of("rollbackDescription"),
                        List.of("title", "commandType", "routingKey", "status",
                                "commandContent", "response", "rollbackDescription"),
                        false, true, "درخواست کاربر", "پایان فرایند",
                        Map.of("success", "#0ca30c", "error", "#d03b3b", "unknown", "#8a8f98")),
                new SearchConfig(
                        List.of(new NormalField("_id", "شناسهٔ لاگ (_id)", "auto", true, true,
                                true, "شناسهٔ مونگو", "روی _id همیشه ایندکس یکتا وجود دارد.")),
                        new AdvancedConfig(true, 20, 15000,
                                "این جستجو روی فیلدهای بدون ایندکس اجرا می‌شود و سنگین است.",
                                List.of(new Operator("eq", "برابر است با", "$eq")),
                                List.of(), List.of())),
                "secretsOnly",
                warnings);
    }
}
