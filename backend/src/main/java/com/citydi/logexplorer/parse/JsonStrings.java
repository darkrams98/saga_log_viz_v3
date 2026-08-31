package com.citydi.logexplorer.parse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * کار با فیلدهایی که «رشتهٔ JSON» هستند، نه شیء.
 *
 * این الگو در لاگ‌های واقعی خیلی رایج است (مثلاً commandContent و response در
 * لاگ‌های SAGA). اگر آن‌ها را باز نکنیم، کاربر یک رشتهٔ ۳۰ کیلوبایتی بی‌فایده
 * می‌بیند. اگر کورکورانه باز کنیم، روی رشته‌های عادی وقت تلف می‌کنیم.
 *
 * پس: تشخیص ارزان (اولین کاراکتر) قبل از parse گران.
 */
public final class JsonStrings {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** رشته‌های طولانی‌تر از این parse نمی‌شوند تا حافظه منفجر نشود */
    private static final int MAX_PARSE_BYTES = 8 * 1024 * 1024;

    private JsonStrings() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** آیا این رشته *به‌نظر* JSON می‌آید؟ (بدون parse) */
    public static boolean looksLikeJson(String s) {
        if (s == null) {
            return false;
        }
        int i = 0;
        int n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= n) {
            return false;
        }
        char first = s.charAt(i);
        if (first != '{' && first != '[') {
            return false;
        }
        int j = n - 1;
        while (j > i && Character.isWhitespace(s.charAt(j))) {
            j--;
        }
        char last = s.charAt(j);
        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }

    /**
     * تلاش برای parse. هرگز استثنا پرتاب نمی‌کند.
     *
     * @return Map یا List در صورت موفقیت، وگرنه null
     */
    public static Object tryParse(String s) {
        if (!looksLikeJson(s)) {
            return null;
        }
        if (s.length() > MAX_PARSE_BYTES) {
            return null;
        }
        try {
            return MAPPER.readValue(s, new TypeReference<Object>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> tryParseObject(String s) {
        Object o = tryParse(s);
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public static int utf8Size(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** تخمین ارزان حجم یک مقدار بدون سریالایز کامل */
    public static long estimateSize(Object value, int depthBudget) {
        if (value == null || depthBudget <= 0) {
            return 8;
        }
        if (value instanceof String s) {
            return s.length() * 2L;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return 8;
        }
        if (value instanceof Map<?, ?> map) {
            long total = 16;
            int seen = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (++seen > 200) {
                    break;
                }
                total += String.valueOf(e.getKey()).length() * 2L
                        + estimateSize(e.getValue(), depthBudget - 1);
            }
            return total;
        }
        if (value instanceof List<?> list) {
            long total = 16;
            int seen = 0;
            for (Object o : list) {
                if (++seen > 200) {
                    break;
                }
                total += estimateSize(o, depthBudget - 1);
            }
            return total;
        }
        return String.valueOf(value).length() * 2L;
    }

    public static Map<String, Object> emptyMap() {
        return new LinkedHashMap<>();
    }
}
