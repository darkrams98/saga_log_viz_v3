package com.citydi.logexplorer.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * مسیر دسترسی به یک مقدار داخل سندی که ساختارش را نمی‌شناسیم.
 *
 * نحو پشتیبانی‌شده:
 *   a.b.c          فیلد تودرتو
 *   a[0].b         عنصر مشخص از آرایه
 *   a[*].b         همهٔ عناصر آرایه (چند مقدار برمی‌گرداند)
 *   a#json.b       فیلد a یک *رشتهٔ* JSON است → parse شود، بعد ادامه
 *   a.b            اگر a آرایه باشد و [] ننوشته باشید، مثل MongoDB
 *                  خودکار روی همهٔ عناصر اعمال می‌شود
 *
 * آن آخری عمدی است: در MongoDB نوشتن `commandList.status` روی آرایه کار می‌کند،
 * و کاربر انتظار دارد همین‌جا هم کار کند.
 *
 * این کلاس immutable است و parse آن cache می‌شود (PathResolver).
 */
public final class PathExpression {

    /** حداکثر تعداد قطعه — جلوی مسیرهای بی‌معنی و حملهٔ ورودی را می‌گیرد */
    private static final int MAX_SEGMENTS = 32;

    public enum Kind { FIELD, INDEX, WILDCARD, JSON }

    public record Segment(Kind kind, String name, int index) {
        static Segment field(String n) {
            return new Segment(Kind.FIELD, n, -1);
        }

        static Segment index(int i) {
            return new Segment(Kind.INDEX, null, i);
        }

        static Segment wildcard() {
            return new Segment(Kind.WILDCARD, null, -1);
        }

        static Segment json() {
            return new Segment(Kind.JSON, null, -1);
        }
    }

    private final String raw;
    private final List<Segment> segments;

    private PathExpression(String raw, List<Segment> segments) {
        this.raw = raw;
        this.segments = segments;
    }

    public String raw() {
        return raw;
    }

    public List<Segment> segments() {
        return segments;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /** آیا این مسیر می‌تواند چند مقدار برگرداند؟ */
    public boolean isMultiValued() {
        return segments.stream().anyMatch(s -> s.kind() == Kind.WILDCARD);
    }

    /**
     * مسیر معادل برای پرس‌وجوی MongoDB: قطعه‌های wildcard و index حذف می‌شوند،
     * چون MongoDB خودش روی آرایه‌ها تطبیق می‌دهد و `a.0.b` معنای دیگری دارد.
     * مثال: commandList[*].status → commandList.status
     * اگر مسیر شامل #json باشد، null برمی‌گردد چون در MongoDB قابل بیان نیست.
     */
    public String toMongoPath() {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            switch (s.kind()) {
                case FIELD -> {
                    if (sb.length() > 0) {
                        sb.append('.');
                    }
                    sb.append(s.name());
                }
                case INDEX -> {
                    if (sb.length() > 0) {
                        sb.append('.');
                    }
                    sb.append(s.index());
                }
                case WILDCARD -> {
                    // در MongoDB لازم نیست نوشته شود
                }
                case JSON -> {
                    return null;
                }
                default -> {
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    public static PathExpression parse(String path) {
        if (path == null || path.isBlank()) {
            return new PathExpression("", List.of());
        }
        List<Segment> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        String p = path.trim();

        for (int i = 0; i < p.length() && out.size() < MAX_SEGMENTS; i++) {
            char c = p.charAt(i);
            if (c == '.') {
                flush(token, out);
            } else if (c == '[') {
                flush(token, out);
                int close = p.indexOf(']', i);
                if (close < 0) {
                    // براکت باز و بسته‌نشده — بقیه را به‌عنوان نام فیلد بگیر
                    token.append(p.substring(i + 1));
                    break;
                }
                String inside = p.substring(i + 1, close).trim();
                if ("*".equals(inside)) {
                    out.add(Segment.wildcard());
                } else {
                    try {
                        out.add(Segment.index(Integer.parseInt(inside)));
                    } catch (NumberFormatException e) {
                        out.add(Segment.field(inside));
                    }
                }
                i = close;
            } else {
                token.append(c);
            }
        }
        flush(token, out);
        return new PathExpression(path, List.copyOf(out));
    }

    private static void flush(StringBuilder token, List<Segment> out) {
        String name = token.toString().trim();
        token.setLength(0);
        if (name.isEmpty()) {
            return;
        }
        // پسوند #json روی نام فیلد
        if (name.endsWith("#json")) {
            String base = name.substring(0, name.length() - "#json".length());
            if (!base.isEmpty()) {
                out.add(Segment.field(base));
            }
            out.add(Segment.json());
            return;
        }
        if ("*".equals(name)) {
            out.add(Segment.wildcard());
            return;
        }
        out.add(Segment.field(name));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PathExpression other && Objects.equals(raw, other.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(raw);
    }

    @Override
    public String toString() {
        return raw;
    }
}
