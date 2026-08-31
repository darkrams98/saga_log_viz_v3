package com.citydi.logexplorer.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * خواندن مقدار از یک گراف شیء ناشناخته.
 *
 * قواعدی که عمداً «بخشنده» هستند:
 *  - نبودن فیلد = فهرست خالی، نه استثنا
 *  - اگر مسیر روی یک آرایه بیفتد و اندیس مشخص نشده باشد، روی همهٔ عناصر
 *    اعمال می‌شود (رفتار آشنای MongoDB)
 *  - اگر مقدار یک رشتهٔ JSON باشد و مسیر ادامه داشته باشد، خودکار parse می‌شود
 *  - سقف تعداد نتیجه و عمق، تا یک سند غول‌پیکر برنامه را از پا درنیاورد
 *
 * هیچ متدی در این کلاس استثنا پرتاب نمی‌کند.
 */
public final class PathResolver {

    private static final int MAX_RESULTS = 500;
    private static final int MAX_VISITS = 20_000;

    private static final Map<String, PathExpression> CACHE = new ConcurrentHashMap<>();

    private PathResolver() {
    }

    public static PathExpression compile(String path) {
        if (path == null) {
            return PathExpression.parse("");
        }
        // جلوی رشد بی‌حد cache را می‌گیریم (مسیرها از config می‌آیند و محدودند،
        // ولی جستجوی کاربر هم می‌تواند مسیر بسازد)
        if (CACHE.size() > 5_000) {
            CACHE.clear();
        }
        return CACHE.computeIfAbsent(path, PathExpression::parse);
    }

    /** اولین مقدار غیرخالی، یا null */
    public static Object first(Object root, String path) {
        List<Object> all = resolve(root, path);
        return all.isEmpty() ? null : all.get(0);
    }

    public static List<Object> resolve(Object root, String path) {
        return resolve(root, compile(path));
    }

    /**
     * همهٔ مقادیری که مسیر به آن‌ها می‌رسد.
     * مقادیر null حذف می‌شوند — «نبود مقدار» و «مقدار null» برای کاربر یکی است.
     */
    public static List<Object> resolve(Object root, PathExpression expr) {
        if (root == null || expr == null || expr.isEmpty()) {
            return List.of();
        }
        try {
            List<Object> current = new ArrayList<>(2);
            current.add(root);
            int[] visits = {0};

            for (PathExpression.Segment segment : expr.segments()) {
                List<Object> next = new ArrayList<>(Math.min(current.size() * 2, 64));
                for (Object node : current) {
                    if (visits[0]++ > MAX_VISITS || next.size() >= MAX_RESULTS) {
                        break;
                    }
                    step(node, segment, next);
                }
                if (next.isEmpty()) {
                    return List.of();
                }
                current = next;
            }
            List<Object> out = new ArrayList<>(current.size());
            for (Object o : current) {
                if (o != null) {
                    out.add(o);
                }
            }
            return Collections.unmodifiableList(out);
        } catch (Exception | StackOverflowError e) {
            // یک سند عجیب نباید کل درخواست را بشکند
            return List.of();
        }
    }

    private static void step(Object node, PathExpression.Segment segment, List<Object> out) {
        if (node == null) {
            return;
        }
        switch (segment.kind()) {
            case FIELD -> field(node, segment.name(), out);
            case INDEX -> index(node, segment.index(), out);
            case WILDCARD -> wildcard(node, out);
            case JSON -> {
                if (node instanceof String s) {
                    Object parsed = JsonStrings.tryParse(s);
                    if (parsed != null) {
                        out.add(parsed);
                    }
                } else {
                    out.add(node);
                }
            }
            default -> {
            }
        }
    }

    private static void field(Object node, String name, List<Object> out) {
        if (node instanceof Map<?, ?> map) {
            Object v = map.get(name);
            if (v != null) {
                out.add(v);
            }
            return;
        }
        if (node instanceof List<?> list) {
            // رفتار MongoDB: مسیر روی آرایه، روی همهٔ عناصر اعمال می‌شود
            for (Object item : list) {
                if (out.size() >= MAX_RESULTS) {
                    return;
                }
                field(item, name, out);
            }
            return;
        }
        if (node instanceof String s) {
            // شاید رشتهٔ JSON باشد و کاربر #json ننوشته باشد
            Object parsed = JsonStrings.tryParse(s);
            if (parsed != null) {
                field(parsed, name, out);
            }
        }
    }

    private static void index(Object node, int idx, List<Object> out) {
        if (node instanceof List<?> list) {
            if (idx >= 0 && idx < list.size()) {
                Object v = list.get(idx);
                if (v != null) {
                    out.add(v);
                }
            }
            return;
        }
        if (node instanceof Object[] arr) {
            if (idx >= 0 && idx < arr.length && arr[idx] != null) {
                out.add(arr[idx]);
            }
            return;
        }
        if (idx == 0) {
            // اندیس ۰ روی یک مقدار تکی = خودِ مقدار (رفتار بخشنده)
            out.add(node);
        }
    }

    private static void wildcard(Object node, List<Object> out) {
        if (node instanceof List<?> list) {
            for (Object item : list) {
                if (out.size() >= MAX_RESULTS) {
                    return;
                }
                if (item != null) {
                    out.add(item);
                }
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                if (out.size() >= MAX_RESULTS) {
                    return;
                }
                if (v != null) {
                    out.add(v);
                }
            }
            return;
        }
        out.add(node);
    }
}
