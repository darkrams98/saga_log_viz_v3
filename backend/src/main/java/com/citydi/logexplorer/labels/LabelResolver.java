package com.citydi.logexplorer.labels;

import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.parse.TextTransforms;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ترجمهٔ مقدار خام به برچسب فارسی.
 *
 * قاعدهٔ حاکم بر همهٔ متدها: **اگر ترجمه‌ای نبود، مقدار خام برمی‌گردد.**
 * هرگز رشتهٔ خالی، هرگز «نامشخص»، هرگز استثنا. پشتیبانی که مقدار خام را
 * می‌بیند دست‌کم می‌تواند در ELK دنبالش بگردد؛ کسی که «نامشخص» می‌بیند نه.
 *
 * برای هر برچسب، «منبع» ترجمه هم برمی‌گردد (exact / pattern / normalized /
 * fallback) تا در UI مشخص باشد کدام برچسب واقعاً از config آمده و کدام
 * فقط مقدار خام است — همان چیزی که به تیم می‌گوید چه چیزی هنوز ترجمه نشده.
 */
@Component
public class LabelResolver {

    /** نام تبدیلی که در config.yaml عنوان را از مهر زمانی و شناسه پاک می‌کند */
    private static final String TITLE_TRANSFORM = "normalizeTitle";

    /** commandList[3].status → commandList.status */
    private static final Pattern ARRAY_INDEX = Pattern.compile("\\[\\d+]|\\.\\d+(?=\\.|$)");

    private final LabelConfigProvider labels;
    private final ConfigProvider config;

    public LabelResolver(LabelConfigProvider labels, ConfigProvider config) {
        this.labels = labels;
        this.config = config;
    }

    /**
     * @param value  متن نمایشی
     * @param raw    مقدار اصلی (برای کپی و برای جستجو در ELK)
     * @param source exact | pattern | normalized | fallback
     */
    public record Label(String value, String raw, String source) {
        public boolean translated() {
            return !"fallback".equals(source);
        }

        static Label fallback(String raw) {
            return new Label(raw, raw, "fallback");
        }
    }

    // ------------------------------------------------------- میکروسرویس

    /**
     * routingKey → نام فارسی میکروسرویس.
     *
     * زنجیره: کلید دقیق → الگو → مقدار خام.
     * الگو برای وقتی است که نسخهٔ سرویس عوض شده ولی خودش همان است
     * (rabbitmq.yaghoot25.client.deposit… و rabbitmq.yaghoot26.client.deposit…).
     */
    public Label service(String routingKey) {
        if (isBlank(routingKey)) {
            return new Label("بدون میکروسرویس", null, "fallback");
        }
        LabelConfig c = labels.get();
        String exact = c.routingKeys.get(routingKey);
        if (exact != null) {
            return new Label(exact, routingKey, "exact");
        }
        for (LabelConfig.PatternRule rule : c.routingKeyPatterns) {
            try {
                if (rule.pattern().matcher(routingKey).find()) {
                    return new Label(rule.label(), routingKey, "pattern");
                }
            } catch (Exception ignored) {
                // یک الگوی خراب نباید بقیه را از کار بیندازد
            }
        }
        return Label.fallback(routingKey);
    }

    // ------------------------------------------------------------ دستور

    public Label commandType(String type) {
        if (isBlank(type)) {
            return new Label("—", null, "fallback");
        }
        String exact = labels.get().commandTypes.get(type);
        return exact != null ? new Label(exact, type, "exact") : Label.fallback(type);
    }

    // ------------------------------------------------------------ وضعیت

    public Label status(String status) {
        if (isBlank(status)) {
            return new Label("نامشخص", null, "fallback");
        }
        String exact = labels.get().statuses.get(status.trim().toUpperCase(Locale.ROOT));
        return exact != null ? new Label(exact, status, "exact") : Label.fallback(status);
    }

    /** success | error | unknown — رنگ گره در گراف از همین می‌آید */
    public String severity(String status) {
        if (isBlank(status)) {
            return "unknown";
        }
        String s = labels.get().statusSeverity.get(status.trim().toUpperCase(Locale.ROOT));
        // عمداً حدس نمی‌زنیم: وضعیت ناشناخته «نمی‌دانم» است، نه «موفق».
        return s == null ? "unknown" : s;
    }

    // ------------------------------------------------------------- عنوان

    /**
     * عنوان عملیات یا مرحله.
     *
     * زنجیره: کلید دقیق → کلید نرمال‌شده (بدون مهر زمانی و شناسه) →
     *          نوع دستور → مقدار خام.
     *
     * مرحلهٔ دوم لازم است چون عنوان‌های تولید این شکلی‌اند:
     *   SEQ__SAVE_IBAN_INFO_IR4001700000003620880_2026-08-24_12:46:59
     */
    public Label title(String title, String commandTypeFallback) {
        LabelConfig c = labels.get();
        if (!isBlank(title)) {
            String exact = c.titles.get(title);
            if (exact != null) {
                return new Label(exact, title, "exact");
            }
            String normalized = TextTransforms.apply(title, TITLE_TRANSFORM, config.get());
            if (normalized != null && !normalized.equals(title)) {
                String byNormalized = c.titles.get(normalized);
                if (byNormalized != null) {
                    return new Label(byNormalized, title, "normalized");
                }
            }
        }
        if (!isBlank(commandTypeFallback)) {
            String byType = c.commandTypes.get(commandTypeFallback);
            if (byType != null) {
                return new Label(byType, title == null ? commandTypeFallback : title, "normalized");
            }
        }
        return isBlank(title) ? new Label("بدون عنوان", null, "fallback") : Label.fallback(title);
    }

    /** فقط نرمال‌سازی، بدون ترجمه — برای نمایش عنوان تمیز وقتی ترجمه نداریم */
    public String normalizedTitle(String title) {
        return isBlank(title) ? title : TextTransforms.apply(title, TITLE_TRANSFORM, config.get());
    }

    // -------------------------------------------------------- نام فیلد

    /**
     * برچسب فارسی یک مسیر فیلد.
     * اندیس آرایه حذف می‌شود تا commandList[0].status و commandList[3].status
     * هر دو به یک کلید config برسند.
     */
    public String field(String path) {
        if (isBlank(path)) {
            return path;
        }
        Map<String, String> map = labels.get().fieldLabels;
        String exact = map.get(path);
        if (exact != null) {
            return exact;
        }
        String generic = ARRAY_INDEX.matcher(path).replaceAll("");
        String byGeneric = map.get(generic);
        if (byGeneric != null) {
            return byGeneric;
        }
        // آخرین بخش مسیر: هم به‌عنوان کلید، هم در برابر آخرین بخشِ کلیدهای config.
        // بدون این، فیلدی که در جزئیات یک مرحله با نام کوتاه «rollbackDescription»
        // می‌آید، برچسبی را که زیر «commandList.rollbackDescription» تعریف شده
        // پیدا نمی‌کرد.
        String leaf = leafOf(generic);
        String byLeaf = map.get(leaf);
        if (byLeaf != null) {
            return byLeaf;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (leafOf(entry.getKey()).equals(leaf)) {
                return entry.getValue();
            }
        }
        return path;
    }

    private static String leafOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    /** ترجمه بر اساس نام نگاشت — برای summaryFields و resultFields در config */
    public Label byMap(String mapName, String value, String commandTypeFallback) {
        if (mapName == null) {
            return Label.fallback(value);
        }
        return switch (mapName) {
            case "titles" -> title(value, commandTypeFallback);
            case "statuses" -> status(value);
            case "commandTypes" -> commandType(value);
            case "routingKeys" -> service(value);
            default -> Label.fallback(value);
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
