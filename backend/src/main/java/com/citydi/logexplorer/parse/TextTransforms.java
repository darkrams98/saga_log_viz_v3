package com.citydi.logexplorer.parse;

import com.citydi.logexplorer.config.AppConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * تبدیل‌های متنی که در config.yaml تعریف می‌شوند.
 *
 * انگیزهٔ عملی: عنوان فرایندها در دادهٔ واقعی این شکلی است:
 *   SEQ__GET_CARD_DEPOSIT_LIST__2026-08-24_12:46:59
 * اگر همان را گروه‌بندی کنیم، هر لاگ یک گروه جدا می‌شود و نمودار
 * «پرتکرارترین‌ها» بی‌معنی می‌شود. ولی این «دانش» نباید در کد باشد،
 * چون فردا شکل عنوان عوض می‌شود. پس یک regex در config.yaml.
 */
public final class TextTransforms {

    private static final int MAX_CHAIN_DEPTH = 5;
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private TextTransforms() {
    }

    /**
     * اعمال یک تبدیل نام‌دار. اگر تبدیل وجود نداشت یا خطا داد،
     * مقدار *اصلی* برمی‌گردد — تبدیل نباید داده را از بین ببرد.
     */
    public static String apply(String value, String transformName, AppConfig config) {
        return apply(value, transformName, config, 0);
    }

    private static String apply(String value, String transformName, AppConfig config, int depth) {
        if (value == null || transformName == null || transformName.isBlank() || config == null) {
            return value;
        }
        if (depth > MAX_CHAIN_DEPTH) {
            return value;
        }
        AppConfig.Transform t = config.transforms.get(transformName);
        if (t == null) {
            return value;
        }
        try {
            return switch (t.type() == null ? "regexReplace" : t.type()) {
                case "regexReplace" -> regexReplace(value, t);
                case "chain" -> chain(value, t, config, depth);
                case "upper" -> value.toUpperCase();
                case "lower" -> value.toLowerCase();
                case "trim" -> value.trim();
                default -> value;
            };
        } catch (Exception e) {
            return value;
        }
    }

    private static String regexReplace(String value, AppConfig.Transform t) {
        if (t.pattern() == null) {
            return value;
        }
        Pattern p = PATTERNS.computeIfAbsent(t.pattern(), Pattern::compile);
        String replaced = p.matcher(value).replaceAll(
                t.replacement() == null ? "" : java.util.regex.Matcher.quoteReplacement(t.replacement()));
        // اگر تبدیل همه‌چیز را خورد، مقدار اصلی بهتر از رشتهٔ خالی است
        return replaced.isBlank() ? value : replaced;
    }

    private static String chain(String value, AppConfig.Transform t, AppConfig config, int depth) {
        String out = value;
        for (String step : t.steps()) {
            out = apply(out, step, config, depth + 1);
        }
        return out;
    }
}
