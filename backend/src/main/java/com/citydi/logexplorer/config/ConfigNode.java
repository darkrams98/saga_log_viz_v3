package com.citydi.logexplorer.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * دسترسی «بخشنده» به درخت پیکربندی.
 *
 * چرا مستقیم روی Map کار می‌کنیم و نه یک کلاس typed؟
 * چون config.yaml را آدم دستی ویرایش می‌کند و یک اشتباه تایپی نباید
 * کل برنامه را از کار بیندازد. اینجا هر مقدار گم‌شده یا نوع اشتباه،
 * به مقدار پیش‌فرض تبدیل می‌شود و برنامه بالا می‌آید.
 *
 * خطاهای پیکربندی جمع‌آوری و در endpoint سلامت گزارش می‌شوند،
 * نه اینکه باعث crash شوند.
 */
public final class ConfigNode {

    private static final ConfigNode EMPTY = new ConfigNode(Collections.emptyMap(), "");

    private final Object value;
    private final String path;

    private ConfigNode(Object value, String path) {
        this.value = value;
        this.path = path;
    }

    public static ConfigNode of(Object value) {
        return new ConfigNode(value, "");
    }

    public static ConfigNode empty() {
        return EMPTY;
    }

    public String path() {
        return path;
    }

    public boolean exists() {
        return value != null;
    }

    /** فرزند با نام مشخص؛ اگر نبود یک گره خالی برمی‌گردد، نه null. */
    public ConfigNode get(String key) {
        if (value instanceof Map<?, ?> map) {
            Object child = map.get(key);
            return new ConfigNode(child, path.isEmpty() ? key : path + "." + key);
        }
        return new ConfigNode(null, path.isEmpty() ? key : path + "." + key);
    }

    /** مسیر تودرتو: get("a.b.c") */
    public ConfigNode at(String dottedPath) {
        ConfigNode node = this;
        for (String part : dottedPath.split("\\.")) {
            node = node.get(part);
        }
        return node;
    }

    public String asString(String fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof String s) {
            return s.isBlank() ? fallback : s;
        }
        return String.valueOf(value);
    }

    public int asInt(int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public long asLong(long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean asBoolean(boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true") || t.equals("yes") || t.equals("on")) {
                return true;
            }
            if (t.equals("false") || t.equals("no") || t.equals("off")) {
                return false;
            }
        }
        return fallback;
    }

    /**
     * فهرست رشته‌ها. اگر مقدار یک رشتهٔ تکی باشد، به فهرست تک‌عضوی تبدیل می‌شود
     * تا کاربر مجبور نباشد برای یک مقدار هم `[]` بنویسد.
     */
    public List<String> asStringList() {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s);
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return List.copyOf(out);
        }
        return List.of(String.valueOf(value));
    }

    /** فهرست گره‌ها (برای فهرست‌هایی از شیء مثل columns و facets) */
    public List<ConfigNode> asNodeList() {
        if (value instanceof List<?> list) {
            List<ConfigNode> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(new ConfigNode(list.get(i), path + "[" + i + "]"));
            }
            return List.copyOf(out);
        }
        if (value instanceof Map<?, ?>) {
            return List.of(this);
        }
        return List.of();
    }

    /** نگاشت رشته→رشته با کلیدهای دست‌نخورده */
    public Map<String, String> asStringMap() {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** کلیدهای این گره (اگر Map باشد) */
    public List<String> keys() {
        if (value instanceof Map<?, ?> map) {
            List<String> out = new ArrayList<>(map.size());
            for (Object k : map.keySet()) {
                out.add(String.valueOf(k));
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    public Object raw() {
        return value;
    }
}
