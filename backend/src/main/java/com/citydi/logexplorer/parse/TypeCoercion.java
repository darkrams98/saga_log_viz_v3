package com.citydi.logexplorer.parse;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * تبدیل مقادیر با نوع ناشناخته به نوع مورد نیاز — بدون استثنا.
 *
 * چرا این‌قدر حالت مختلف برای زمان؟ چون در همین یک سیستم دیده‌ایم:
 *   1785398052459                       عدد epoch میلی‌ثانیه (خروجی Elasticsearch)
 *   "2026-08-24T09:16:59.001Z"          رشتهٔ ISO (خروجی CSV از MongoDB)
 *   Date/BSON Date                      نوع بومی MongoDB
 *   {"$date": "..."} یا {"$date": {"$numberLong": "..."}}   Extended JSON
 *   "2026-07-30T11:24:12.525+03:30"     ISO با آفست محلی
 * و هیچ تضمینی نیست که فردا شکل جدیدی اضافه نشود.
 */
public final class TypeCoercion {

    /** بازهٔ منطقی برای تشخیص «ثانیه یا میلی‌ثانیه»: ۲۰۰۱ تا ۲۰۸۶ میلادی */
    private static final long MILLIS_LOWER = 1_000_000_000_000L;
    private static final long MILLIS_UPPER = 3_700_000_000_000L;
    private static final long SECONDS_LOWER = 1_000_000_000L;
    private static final long SECONDS_UPPER = 3_700_000_000L;

    private TypeCoercion() {
    }

    // -------------------------------------------------------------- time

    public static Instant toInstant(Object value) {
        return toInstant(value, List.of());
    }

    /**
     * @param extraFormats قالب‌های رشته‌ای اضافی از config.yaml
     * @return زمان، یا null اگر قابل تفسیر نبود
     */
    public static Instant toInstant(Object value, List<String> extraFormats) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Instant i) {
                return i;
            }
            if (value instanceof Date d) {
                return d.toInstant();
            }
            if (value instanceof Number n) {
                return fromEpoch(n.longValue());
            }
            if (value instanceof Map<?, ?> map) {
                // Extended JSON: {"$date": ...}
                Object dollarDate = map.get("$date");
                if (dollarDate != null) {
                    return toInstant(dollarDate, extraFormats);
                }
                Object numberLong = map.get("$numberLong");
                if (numberLong != null) {
                    return toInstant(numberLong, extraFormats);
                }
                return null;
            }
            if (value instanceof String s) {
                return fromString(s.trim(), extraFormats);
            }
        } catch (Exception ignored) {
            // مقدار غیرقابل تفسیر → null، نه crash
        }
        return null;
    }

    private static Instant fromEpoch(long n) {
        if (n >= MILLIS_LOWER && n <= MILLIS_UPPER) {
            return Instant.ofEpochMilli(n);
        }
        if (n >= SECONDS_LOWER && n <= SECONDS_UPPER) {
            return Instant.ofEpochSecond(n);
        }
        // میکروثانیه یا نانوثانیه
        if (n > MILLIS_UPPER && n < MILLIS_UPPER * 1000L) {
            return Instant.ofEpochMilli(n / 1000L);
        }
        if (n >= MILLIS_UPPER * 1000L) {
            return Instant.ofEpochMilli(n / 1_000_000L);
        }
        return null;
    }

    private static Instant fromString(String s, List<String> extraFormats) {
        if (s.isEmpty()) {
            return null;
        }
        // عدد داخل رشته
        if (s.length() >= 10 && s.length() <= 20 && isAllDigits(s)) {
            try {
                return fromEpoch(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // ISO-8601 استاندارد
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            // ادامه
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception ignored) {
            // ادامه
        }
        try {
            return ZonedDateTime.parse(s).toInstant();
        } catch (Exception ignored) {
            // ادامه
        }
        // قالب‌های سفارشی از config
        for (String pattern : extraFormats) {
            try {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
                try {
                    return OffsetDateTime.parse(s, fmt).toInstant();
                } catch (Exception ignored) {
                    return LocalDateTime.parse(s, fmt).atZone(ZoneId.of("UTC")).toInstant();
                }
            } catch (Exception ignored) {
                // قالب بعدی
            }
        }
        // «2026-08-24 09:16:59» بدون T
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).atZone(ZoneId.of("UTC")).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ scalar

    /**
     * نمایش متنی امن برای هر نوعی.
     * شیء و آرایه به JSON فشرده تبدیل می‌شوند تا در ستون جدول جا شوند.
     */
    public static String toText(Object value, int maxChars) {
        if (value == null) {
            return null;
        }
        String s;
        if (value instanceof String str) {
            s = str;
        } else if (value instanceof Number || value instanceof Boolean) {
            s = String.valueOf(value);
        } else if (value instanceof Date d) {
            s = d.toInstant().toString();
        } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
            s = JsonStrings.write(value);
        } else {
            s = String.valueOf(value);
        }
        if (maxChars > 0 && s.length() > maxChars) {
            return s.substring(0, maxChars) + "…";
        }
        return s;
    }

    public static String toText(Object value) {
        return toText(value, 0);
    }

    public static Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof Boolean b) {
            return b ? 1d : 0d;
        }
        return null;
    }

    public static Long toLong(Object value) {
        Double d = toDouble(value);
        return d == null ? null : d.longValue();
    }

    public static Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true") || t.equals("yes") || t.equals("1")) {
                return true;
            }
            if (t.equals("false") || t.equals("no") || t.equals("0")) {
                return false;
            }
        }
        return null;
    }

    /** نام نوع برای نمایش در نمای درختی */
    public static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return JsonStrings.looksLikeJson(s) ? "json-string" : "string";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "int";
        }
        if (value instanceof Double || value instanceof Float) {
            return "double";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Date || value instanceof Instant) {
            return "date";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        return value.getClass().getSimpleName().toLowerCase();
    }

    /** آیا مقدار «خالی» است؟ (null، رشتهٔ تهی، آرایه/شیء خالی، رشتهٔ "null") */
    public static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            String t = s.trim();
            return t.isEmpty() || t.equalsIgnoreCase("null");
        }
        if (value instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        if (value instanceof List<?> l) {
            return l.isEmpty();
        }
        return false;
    }
}
