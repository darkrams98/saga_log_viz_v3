package com.citydi.logexplorer.parse;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.mask.MaskingService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * قلب schema-agnostic بودن: سند خام → ردیف قابل نمایش، فقط با تکیه بر config.
 *
 * هیچ نام فیلدی در این کلاس hard-code نشده. تنها «مفهوم»هایی که می‌شناسد
 * (زمان، سطح، پیام، سرویس، وضعیت) از config.yaml مسیرهایشان را می‌گیرند
 * و اگر پیدا نشدند، مقدارشان «نامشخص» می‌شود، نه استثنا.
 *
 * قرارداد: این متد هرگز پرتاب نمی‌کند. بدترین حالت، یک رکورد با
 * فیلدهای خالی و یک warning است.
 */
@Component
public class LogRecordMapper {

    private final ConfigProvider configProvider;
    private final MaskingService masking;

    public LogRecordMapper(ConfigProvider configProvider, MaskingService masking) {
        this.configProvider = configProvider;
        this.masking = masking;
    }

    public LogRecord map(Map<String, Object> document) {
        AppConfig config = configProvider.get();
        List<String> warnings = new ArrayList<>(2);

        if (document == null || document.isEmpty()) {
            return new LogRecord(null, null, null, config.level.defaultLevel(),
                    label(config, config.level.defaultLevel()), false,
                    null, null, null, null, Map.of(), Map.of(),
                    List.of("سند خالی بود"), 0, false);
        }

        long size = JsonStrings.estimateSize(document, 8);
        boolean oversized = size > config.limits.maxDocumentBytes();

        Resolved id = resolveField(document, config, "id", warnings);
        Resolved time = resolveTime(document, config, warnings);
        Resolved message = resolveField(document, config, "message", warnings);
        Resolved service = resolveField(document, config, "service", warnings);
        Resolved status = resolveField(document, config, "status", warnings);

        String level = resolveLevel(document, config, status.text);
        boolean isError = config.level.isError(level);

        Map<String, String> columns = buildColumns(document, config,
                id, time, message, service, status, level, warnings);
        Map<String, String> highlights = buildHighlights(document, config);

        return new LogRecord(
                id.text,
                time.instant,
                time.source,
                level,
                label(config, level),
                isError,
                message.text,
                message.source,
                service.text,
                status.text,
                columns,
                highlights,
                List.copyOf(warnings),
                size,
                oversized);
    }

    // ------------------------------------------------------------ fields

    /** مقدار + مسیری که مقدار از آن آمد */
    private record Resolved(String text, String source, Instant instant, Object rawValue) {
        static Resolved empty() {
            return new Resolved(null, null, null, null);
        }
    }

    private Resolved resolveField(Map<String, Object> doc, AppConfig config,
                                  String fieldName, List<String> warnings) {
        AppConfig.FieldRule rule = config.field(fieldName);
        if (rule == null) {
            return Resolved.empty();
        }
        for (String candidate : rule.candidates()) {
            try {
                Object value = PathResolver.first(doc, candidate);
                if (TypeCoercion.isEmpty(value)) {
                    continue;
                }
                String text = TypeCoercion.toText(value, config.limits.previewChars());
                text = TextTransforms.apply(text, rule.transform(), config);
                text = maskFor(candidate, text);
                if (text != null && !text.isBlank()) {
                    return new Resolved(text, candidate, null, value);
                }
            } catch (Exception e) {
                warnings.add("خواندن «" + candidate + "» ناموفق بود: " + shortMessage(e));
            }
        }
        return Resolved.empty();
    }

    private Resolved resolveTime(Map<String, Object> doc, AppConfig config, List<String> warnings) {
        for (String candidate : config.time.candidates()) {
            try {
                Object value = PathResolver.first(doc, candidate);
                if (value == null) {
                    continue;
                }
                Instant instant = TypeCoercion.toInstant(value, config.time.stringFormats());
                if (instant != null) {
                    return new Resolved(instant.toString(), candidate, instant, value);
                }
            } catch (Exception e) {
                warnings.add("تفسیر زمان از «" + candidate + "» ناموفق بود: " + shortMessage(e));
            }
        }
        warnings.add("هیچ فیلد زمانی قابل تفسیری پیدا نشد ("
                + String.join("، ", config.time.candidates()) + ")");
        return Resolved.empty();
    }

    /**
     * سطح لاگ: اول فیلد صریح، بعد نگاشت از روی وضعیت، بعد پیش‌فرض.
     * هیچ‌کدام hard-code نیست.
     */
    private String resolveLevel(Map<String, Object> doc, AppConfig config, String statusText) {
        AppConfig.LevelConfig lc = config.level;
        for (String stage : lc.precedence()) {
            String resolved = "derive".equalsIgnoreCase(stage)
                    ? fromDerive(doc, lc) : fromExplicitField(doc, lc);
            if (resolved != null) {
                return resolved;
            }
        }
        if (statusText != null) {
            String mapped = lc.map().get(statusText.trim().toUpperCase(Locale.ROOT));
            if (mapped != null) {
                return mapped;
            }
        }
        return lc.defaultLevel();
    }

    /** فیلد صریح سطح، اگر وجود داشته باشد */
    private String fromExplicitField(Map<String, Object> doc, AppConfig.LevelConfig lc) {
        for (String candidate : lc.candidates()) {
            Object value = PathResolver.first(doc, candidate);
            if (!TypeCoercion.isEmpty(value)) {
                String raw = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
                return lc.map().getOrDefault(raw, raw);
            }
        }
        return null;
    }

    /** استنتاج از وضعیت — فقط وقتی نگاشت صریحی برای آن وضعیت تعریف شده باشد */
    private String fromDerive(Map<String, Object> doc, AppConfig.LevelConfig lc) {
        for (String candidate : lc.deriveFrom()) {
            Object value = PathResolver.first(doc, candidate);
            if (!TypeCoercion.isEmpty(value)) {
                String mapped = lc.map().get(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        return null;
    }

    private String label(AppConfig config, String level) {
        return config.level.labels().getOrDefault(level, level);
    }

    // ----------------------------------------------------------- columns

    private Map<String, String> buildColumns(Map<String, Object> doc, AppConfig config,
                                             Resolved id, Resolved time, Resolved message,
                                             Resolved service, Resolved status, String level,
                                             List<String> warnings) {
        Map<String, String> out = new LinkedHashMap<>();
        for (AppConfig.Column column : config.columns) {
            try {
                String value;
                if ("path".equalsIgnoreCase(column.source()) && column.path() != null) {
                    Object raw = PathResolver.first(doc, column.path());
                    value = TypeCoercion.toText(raw, config.limits.previewChars());
                    value = TextTransforms.apply(value, column.transform(), config);
                    value = maskFor(column.path(), value);
                } else {
                    value = switch (column.key()) {
                        case "time" -> time.instant == null ? null : time.instant.toString();
                        case "level" -> level;
                        case "message" -> message.text;
                        case "service" -> service.text;
                        case "status" -> status.text;
                        case "id" -> id.text;
                        default -> {
                            // ستونی که به فیلد منطقیِ هم‌نام اشاره دارد
                            Resolved r = resolveField(doc, config, column.key(), warnings);
                            yield r.text;
                        }
                    };
                    value = TextTransforms.apply(value, column.transform(), config);
                }
                if (value != null) {
                    out.put(column.key(), value);
                }
            } catch (Exception e) {
                warnings.add("ساخت ستون «" + column.key() + "» ناموفق بود: " + shortMessage(e));
            }
        }
        return Map.copyOf(out);
    }

    private Map<String, String> buildHighlights(Map<String, Object> doc, AppConfig config) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String path : config.display.highlightPaths()) {
            try {
                Object raw = PathResolver.first(doc, path);
                if (TypeCoercion.isEmpty(raw)) {
                    continue;
                }
                String text = maskFor(path, TypeCoercion.toText(raw, 120));
                if (text != null && !text.isBlank()) {
                    out.put(path, text);
                }
            } catch (Exception ignored) {
                // یک مسیر برجسته که کار نکرد، نباید ردیف را خراب کند
            }
        }
        return Map.copyOf(out);
    }

    // ------------------------------------------------------------- utils

    private String maskFor(String path, String text) {
        if (text == null) {
            return null;
        }
        String key = lastSegment(path);
        if (masking.isSecret(key)) {
            return masking.placeholder();
        }
        AppConfig.MaskRule rule = masking.ruleFor(key);
        return rule != null ? masking.applyStrategy(rule, text) : masking.maskFreeText(text);
    }

    private static String lastSegment(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        int bracket = p.indexOf('[');
        while (bracket >= 0) {
            int close = p.indexOf(']', bracket);
            if (close < 0) {
                break;
            }
            p = p.substring(0, bracket) + p.substring(close + 1);
            bracket = p.indexOf('[');
        }
        int dot = p.lastIndexOf('.');
        return dot >= 0 ? p.substring(dot + 1) : p;
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.substring(0, Math.min(m.length(), 120));
    }
}
