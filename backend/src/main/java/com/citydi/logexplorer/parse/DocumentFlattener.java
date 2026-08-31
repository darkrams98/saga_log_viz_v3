package com.citydi.logexplorer.parse;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.mask.MaskingService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * تبدیل یک سند با ساختار ناشناخته به درختی که می‌شود نمایش داد.
 *
 * سه مسئله را حل می‌کند:
 *  ۱) فیلدهای ناشناخته: هیچ فیلدی حذف نمی‌شود مگر اینکه در hiddenPaths باشد.
 *  ۲) رشته‌های JSON: خودکار باز می‌شوند و با نشان «از JSON» علامت می‌خورند.
 *  ۳) اسناد غول‌پیکر: بودجهٔ گره و عمق دارد؛ وقتی تمام شد، گره را
 *     «سنگین» علامت می‌زند تا UI با درخواست جدا آن را بگیرد.
 *
 * هیچ‌وقت استثنا پرتاب نمی‌کند.
 */
@Component
public class DocumentFlattener {

    private final ConfigProvider configProvider;
    private final MaskingService masking;

    public DocumentFlattener(ConfigProvider configProvider, MaskingService masking) {
        this.configProvider = configProvider;
        this.masking = masking;
    }

    /** شمارندهٔ بودجه — یک نمونه برای هر بار flatten */
    private static final class Budget {
        int nodesLeft;
        final int maxDepth;
        final int previewChars;
        final int heavyBytes;
        boolean exhausted;

        Budget(AppConfig.Limits limits) {
            this.nodesLeft = limits.maxFlattenNodes();
            this.maxDepth = limits.maxDepth();
            this.previewChars = limits.previewChars();
            this.heavyBytes = limits.largeValueBytes();
        }

        boolean take() {
            if (nodesLeft <= 0) {
                exhausted = true;
                return false;
            }
            nodesLeft--;
            return true;
        }
    }

    public record FlattenResult(List<FieldNode> nodes, boolean truncated, int nodeCount) {
    }

    /** درخت کامل سند از ریشه */
    public FlattenResult flatten(Object document) {
        return flattenAt(document, "");
    }

    /**
     * درخت یک زیرشاخه — برای بارگذاری تنبل.
     *
     * @param basePath مسیری که این زیردرخت از آن شروع می‌شود (برای ساخت path فرزندان)
     */
    public FlattenResult flattenAt(Object value, String basePath) {
        AppConfig config = configProvider.get();
        Budget budget = new Budget(config.limits);
        List<FieldNode> nodes = new ArrayList<>();
        try {
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    String path = basePath.isEmpty() ? key : basePath + "." + key;
                    if (isHidden(path, key, config)) {
                        continue;
                    }
                    FieldNode node = build(key, path, e.getValue(), 0, budget, config, false);
                    if (node != null) {
                        nodes.add(node);
                    }
                }
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    String path = basePath + "[" + i + "]";
                    FieldNode node = build("[" + i + "]", path, list.get(i), 0, budget, config, false);
                    if (node != null) {
                        nodes.add(node);
                    }
                }
            } else if (value != null) {
                FieldNode node = build(lastKey(basePath), basePath, value, 0, budget, config, false);
                if (node != null) {
                    nodes.add(node);
                }
            }
        } catch (Exception | StackOverflowError e) {
            // درخت ناقص بهتر از صفحهٔ خطاست
            budget.exhausted = true;
        }
        return new FlattenResult(List.copyOf(nodes), budget.exhausted,
                configProvider.get().limits.maxFlattenNodes() - budget.nodesLeft);
    }

    private FieldNode build(String key, String path, Object value, int depth,
                            Budget budget, AppConfig config, boolean fromJson) {
        if (!budget.take()) {
            return new FieldNode(path, key, "truncated", null, null, List.of(),
                    true, 0, true, fromJson, false);
        }
        if (value == null) {
            return new FieldNode(path, key, "null", null, null, List.of(),
                    false, 0, false, fromJson, false);
        }

        // ---- مقادیر محرمانه: قبل از هر کاری حذف می‌شوند
        if (masking.isSecret(key)) {
            return new FieldNode(path, key, "secret", masking.placeholder(), null, List.of(),
                    false, 0, false, fromJson, true);
        }

        long size = JsonStrings.estimateSize(value, 6);

        // ---- شیء
        if (value instanceof Map<?, ?> map) {
            if (depth >= budget.maxDepth) {
                return new FieldNode(path, key, "object", null, map.size(), List.of(),
                        true, size, true, fromJson, false);
            }
            List<FieldNode> children = new ArrayList<>(Math.min(map.size(), 64));
            boolean cut = false;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String childKey = String.valueOf(e.getKey());
                String childPath = path.isEmpty() ? childKey : path + "." + childKey;
                if (isHidden(childPath, childKey, config)) {
                    continue;
                }
                if (budget.nodesLeft <= 0) {
                    cut = true;
                    break;
                }
                FieldNode child = build(childKey, childPath, e.getValue(), depth + 1,
                        budget, config, fromJson);
                if (child != null) {
                    children.add(child);
                }
            }
            return new FieldNode(path, key, "object", null, map.size(), List.copyOf(children),
                    cut, size, false, fromJson, false);
        }

        // ---- آرایه
        if (value instanceof List<?> list) {
            if (depth >= budget.maxDepth) {
                return new FieldNode(path, key, "array", null, list.size(), List.of(),
                        true, size, true, fromJson, false);
            }
            List<FieldNode> children = new ArrayList<>(Math.min(list.size(), 64));
            boolean cut = false;
            for (int i = 0; i < list.size(); i++) {
                if (budget.nodesLeft <= 0) {
                    cut = true;
                    break;
                }
                FieldNode child = build("[" + i + "]", path + "[" + i + "]", list.get(i),
                        depth + 1, budget, config, fromJson);
                if (child != null) {
                    children.add(child);
                }
            }
            return new FieldNode(path, key, "array", null, list.size(), List.copyOf(children),
                    cut, size, false, fromJson, false);
        }

        // ---- رشته
        if (value instanceof String s) {
            int bytes = JsonStrings.utf8Size(s);

            // رشتهٔ JSON: باز کن تا کاربر مجبور نباشد رشتهٔ ۳۰ کیلوبایتی بخواند
            if (config.display.autoParseJsonStrings() && JsonStrings.looksLikeJson(s)) {
                if (bytes > budget.heavyBytes) {
                    // سنگین: باز نکن، فقط علامت بزن تا با درخواست جدا بیاید
                    return new FieldNode(path, key, "json-string",
                            preview(key, s, budget.previewChars), null, List.of(),
                            true, bytes, true, fromJson, isMasked(key));
                }
                if (depth < budget.maxDepth) {
                    Object parsed = JsonStrings.tryParse(s);
                    if (parsed != null) {
                        FieldNode inner = build(key, path + "#json", parsed, depth + 1,
                                budget, config, true);
                        if (inner != null) {
                            return new FieldNode(path, key, "json-string", null,
                                    inner.childCount(), inner.children(),
                                    inner.truncated(), bytes, false, fromJson, false);
                        }
                    }
                }
            }

            if (bytes > budget.heavyBytes) {
                return new FieldNode(path, key, "string",
                        preview(key, s, budget.previewChars), null, List.of(),
                        true, bytes, true, fromJson, isMasked(key));
            }
            return new FieldNode(path, key, "string", maskText(key, s), null, List.of(),
                    false, bytes, false, fromJson, isMasked(key));
        }

        // ---- اسکالر
        String type = TypeCoercion.typeName(value);
        String text;
        if (value instanceof Date d) {
            text = d.toInstant().toString();
        } else {
            text = maskText(key, String.valueOf(value));
        }
        return new FieldNode(path, key, type, text, null, List.of(),
                false, size, false, fromJson, isMasked(key));
    }

    private boolean isMasked(String key) {
        return masking.isSecret(key) || masking.ruleFor(key) != null;
    }

    private String maskText(String key, String value) {
        AppConfig.MaskRule rule = masking.ruleFor(key);
        if (rule != null) {
            return masking.applyStrategy(rule, value);
        }
        return masking.maskFreeText(value);
    }

    private String preview(String key, String s, int chars) {
        String cut = s.length() <= chars ? s : s.substring(0, chars) + "…";
        return maskText(key, cut);
    }

    private boolean isHidden(String path, String key, AppConfig config) {
        for (String hidden : config.display.hiddenPaths()) {
            if (hidden.equals(path) || hidden.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String lastKey(String path) {
        if (path == null || path.isEmpty()) {
            return "$";
        }
        int i = Math.max(path.lastIndexOf('.'), path.lastIndexOf('['));
        return i < 0 ? path : path.substring(i + 1).replace("]", "");
    }
}
