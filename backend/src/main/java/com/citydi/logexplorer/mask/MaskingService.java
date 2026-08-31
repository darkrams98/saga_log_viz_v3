package com.citydi.logexplorer.mask;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import com.citydi.logexplorer.parse.JsonStrings;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * پوشاندن دادهٔ حساس — کاملاً config-driven.
 *
 * تفاوت با نسخهٔ قبلی پروژه: هیچ فهرست فیلدی در کد نیست. همهٔ قواعد از
 * `masking` در config.yaml می‌آید، پس وقتی لاگ‌ها فیلد حساس جدیدی پیدا
 * می‌کنند، فقط یک سطر به YAML اضافه می‌شود.
 *
 * سیاست: ماسکینگ همیشگی و سمت سرور. مقدار خام هرگز از API خارج نمی‌شود.
 */
@Service
public class MaskingService {

    /**
     * نام فیلد عمداً به ۶۵ نویسه محدود شده است.
     *
     * با `*` بی‌کران، این الگو روی رشته‌های طولانیِ بدون جداکننده (مثل یک
     * payload base64 داخل commandContent) رفتار درجه‌دو پیدا می‌کند: موتور
     * regex از هر موقعیت تا انتهای رشته پیش می‌رود و شکست می‌خورد.
     * اندازه‌گیری‌شده روی ۲۰ کیلوبایت «x»: ۶٫۹ ثانیه — یعنی روی ۳۰۰ کیلوبایت
     * حدود ۲۶ دقیقه، که عملاً یک نخِ سرویس را قفل می‌کند.
     * با کران ۶۴، همان ۳۰۰ کیلوبایت زیر یک ثانیه پردازش می‌شود و نتیجهٔ
     * تطبیق دقیقاً همان است (نام فیلد بلندتر از ۶۵ نویسه در عمل وجود ندارد).
     */
    private static final Pattern KV_IN_TEXT = Pattern.compile(
            "\"?([A-Za-z_][A-Za-z0-9_]{0,64})\"?\\s*[:=]\\s*\"([^\"]{1,64})\"");

    /**
     * سقف پویش متن آزاد. بالاتر از این، مقدار در هیچ نمایی کامل نشان داده
     * نمی‌شود (نمای خلاصه ۴۰۰ نویسه و پنل جزئیات ۲۰٬۰۰۰ نویسه است)، پس
     * پویشش فقط هزینه است.
     */
    private static final int FREE_TEXT_MAX_CHARS = 1_000_000;
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<![0-9])([0-9]{10,20})(?![0-9])");
    private static final Pattern IBAN_TXT = Pattern.compile("\\b[A-Z]{2}[0-9]{22,30}\\b");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    private final ConfigProvider configProvider;
    private final LabelConfigProvider labelProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public MaskingService(ConfigProvider configProvider, LabelConfigProvider labelProvider) {
        this.configProvider = configProvider;
        this.labelProvider = labelProvider;
    }

    /** سازندهٔ کوتاه برای تست — رفتار «partial» یعنی همان قواعد کامل config.yaml */
    public MaskingService(ConfigProvider configProvider) {
        this(configProvider, null);
    }

    /**
     * پروفایل پوشاندن، از `privacy.maskingProfile` در config.json.
     *
     * چرا لازم شد؟ چون هدف این مرحله عیب‌یابی *یک* لاگ است. وقتی پشتیبان
     * می‌خواهد بفهمد چرا انتقال وجه شکست خورده، دیدن `۱۲۳****۴۵` به‌جای
     * شمارهٔ حساب کارش را غیرممکن می‌کند. ولی رمز و توکن در هیچ حالتی
     * نمایش داده نمی‌شوند — آن‌ها برای عیب‌یابی هم لازم نیستند.
     */
    public String profile() {
        if (labelProvider == null) {
            return "partial";
        }
        String p = labelProvider.get().maskingProfile;
        return p == null ? "secretsOnly" : p.trim();
    }

    private AppConfig.MaskingConfig cfg() {
        AppConfig.MaskingConfig base = configProvider.get().masking;
        return switch (profile()) {
            // هیچ پوشاندنی — فقط برای محیط کاملاً قابل اعتماد
            case "off" -> new AppConfig.MaskingConfig(false, base.placeholder(),
                    base.secretFields(), List.of(), base.allowList(), false);
            // فقط راز‌ها حذف می‌شوند؛ ماسک جزئی و ماسک متن آزاد خاموش
            case "secretsOnly" -> new AppConfig.MaskingConfig(base.enabled(), base.placeholder(),
                    base.secretFields(), List.of(), base.allowList(), false);
            // "partial" و هر مقدار ناشناخته: همهٔ قواعد config.yaml
            default -> base;
        };
    }

    public String placeholder() {
        return cfg().placeholder();
    }

    // ----------------------------------------------------------- classify

    /** null یعنی حساس نیست */
    public AppConfig.MaskRule ruleFor(String fieldName) {
        AppConfig.MaskingConfig c = cfg();
        if (!c.enabled() || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        String f = normalizeFieldName(fieldName);
        if (c.allowList().contains(f)) {
            return null;
        }
        for (AppConfig.MaskRule rule : c.rules()) {
            for (String key : rule.fields()) {
                if (matches(f, key)) {
                    return rule;
                }
            }
        }
        return null;
    }

    public boolean isSecret(String fieldName) {
        AppConfig.MaskingConfig c = cfg();
        if (!c.enabled() || fieldName == null) {
            return false;
        }
        String f = normalizeFieldName(fieldName);
        if (c.allowList().contains(f)) {
            return false;
        }
        for (String key : c.secretFields()) {
            if (matches(f, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * تطبیق نام فیلد با قاعده.
     *
     * برای کلیدهای کوتاه (≤۴ حرف) فقط تساوی دقیق پذیرفته می‌شود.
     * دلیل: یک بار در نسخهٔ قبلی، `timeSpan` به قاعدهٔ `pan` (شماره کارت)
     * می‌خورد و ماسک می‌شد. تطبیق «شامل بودن» روی کلیدهای کوتاه خطرناک است.
     */
    private boolean matches(String fieldName, String rule) {
        if (rule.length() <= 4) {
            return fieldName.equals(rule);
        }
        return fieldName.equals(rule) || fieldName.endsWith(rule) || fieldName.startsWith(rule);
    }

    private String normalizeFieldName(String fieldName) {
        String f = fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        int bracket = f.indexOf('[');
        if (bracket > 0) {
            f = f.substring(0, bracket);
        }
        int dot = f.lastIndexOf('.');
        if (dot >= 0 && dot < f.length() - 1) {
            f = f.substring(dot + 1);
        }
        return f;
    }

    // -------------------------------------------------------------- mask

    public String maskValue(String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (isSecret(fieldName)) {
            return placeholder();
        }
        AppConfig.MaskRule rule = ruleFor(fieldName);
        return rule == null ? value : applyStrategy(rule, value);
    }

    public String applyStrategy(AppConfig.MaskRule rule, String value) {
        try {
            return switch (rule.strategy() == null ? "keepEdges" : rule.strategy()) {
                case "keepEdges" -> keepEdges(digitsIfNumeric(value), rule.head(), rule.tail());
                case "mobileIR" -> keepEdges(normalizeIranianMobile(value), 4, 3);
                case "fixed" -> rule.value();
                case "truncate" -> truncate(value, rule.keep());
                case "initials" -> initials(value);
                case "yearOnly" -> yearOnly(value);
                case "ipPrefix" -> ipPrefix(value);
                case "remove" -> placeholder();
                default -> keepEdges(value, rule.head(), rule.tail());
            };
        } catch (Exception e) {
            // یک قاعدهٔ بد نباید داده را لو بدهد → محافظه‌کارانه همه را بپوشان
            return "***";
        }
    }

    private String digitsIfNumeric(String value) {
        String digits = NON_DIGIT.matcher(value).replaceAll("");
        return digits.length() >= value.length() - 4 && !digits.isEmpty() ? digits : value;
    }

    public String keepEdges(String value, int head, int tail) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        int h = Math.max(0, head);
        int t = Math.max(0, tail);
        if (v.length() <= h + t) {
            return "*".repeat(Math.max(3, v.length()));
        }
        return v.substring(0, h) + "*".repeat(v.length() - h - t)
                + (t > 0 ? v.substring(v.length() - t) : "");
    }

    /** 09018917308 / +989018917308 / ۰۹۰۱۸۹۱۷۳۰۸ → همه به 09018917308 */
    public String normalizeIranianMobile(String value) {
        String d = NON_DIGIT.matcher(normalizeDigits(value)).replaceAll("");
        if (d.startsWith("0098")) {
            d = d.substring(4);
        } else if (d.startsWith("98") && d.length() == 12) {
            d = d.substring(2);
        }
        if (d.length() == 10 && d.startsWith("9")) {
            d = "0" + d;
        }
        return d.isEmpty() ? value : d;
    }

    /** ارقام فارسی/عربی → لاتین */
    public static String normalizeDigits(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            if (c >= '۰' && c <= '۹') {
                sb.append((char) (c - '۰' + '0'));
            } else if (c >= '٠' && c <= '٩') {
                sb.append((char) (c - '٠' + '0'));
            } else if (c == '‌' || c == '‎' || c == '‏' || c == '﻿') {
                // کاراکترهای نامرئی حذف می‌شوند
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String truncate(String value, int keep) {
        String v = value.trim();
        int k = Math.max(1, keep);
        return v.length() <= k ? "*".repeat(Math.max(3, v.length())) : v.substring(0, k) + " …***";
    }

    private String initials(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                out.add(part.charAt(0) + "*".repeat(Math.max(1, part.length() - 1)));
            }
        }
        return out.isEmpty() ? "***" : String.join(" ", out);
    }

    private String yearOnly(String value) {
        Matcher m = Pattern.compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").matcher(value.trim());
        if (m.find()) {
            return m.group(1) + "-**-**";
        }
        String d = NON_DIGIT.matcher(value).replaceAll("");
        return d.length() == 8 ? d.substring(0, 4) + "****" : keepEdges(value, 4, 0);
    }

    private String ipPrefix(String value) {
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("AnonymousIP")) {
            return v;
        }
        String[] parts = v.split("\\.");
        return parts.length == 4 ? parts[0] + "." + parts[1] + ".*.*" : keepEdges(v, 4, 0);
    }

    // ------------------------------------------------------ recursive obj

    /** ماسک بازگشتی روی هر گراف شیء — Map، List، رشتهٔ JSON تودرتو */
    public Object maskObject(Object value, String fieldName) {
        return maskObject(value, fieldName, 0);
    }

    private Object maskObject(Object value, String fieldName, int depth) {
        if (value == null || depth > 30) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (isSecret(key)) {
                    out.put(key, placeholder());
                } else {
                    out.put(key, maskObject(e.getValue(), key, depth + 1));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(maskObject(o, fieldName, depth + 1));
            }
            return out;
        }
        if (value instanceof String s) {
            if (isSecret(fieldName)) {
                return placeholder();
            }
            AppConfig.MaskRule rule = ruleFor(fieldName);
            if (rule != null) {
                return applyStrategy(rule, s);
            }
            Object nested = JsonStrings.tryParse(s);
            if (nested != null) {
                return JsonStrings.write(maskObject(nested, fieldName, depth + 1));
            }
            return maskFreeText(s);
        }
        AppConfig.MaskRule rule = ruleFor(fieldName);
        if (rule != null && "fixed".equals(rule.strategy())) {
            return rule.value();
        }
        return value;
    }

    // ------------------------------------------------------------- text

    /**
     * ماسک متن آزاد.
     * پیام‌های فنی اغلب مقدار حساس را داخل خودشان دارند، مثلاً:
     *   Query { "registrationNationalCode" : "1273368304" } returned non unique result
     * پیام باید خوانا بماند ولی کد ملی نه.
     */
    public String maskFreeText(String text) {
        if (text == null || text.isBlank() || !cfg().enabled() || !cfg().freeText()) {
            return text;
        }
        if (text.length() > FREE_TEXT_MAX_CHARS) {
            return text;
        }
        String out = text;

        Matcher m = KV_IN_TEXT.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2);
            String masked = maskValue(key, val);
            String replacement = masked.equals(val) ? m.group(0) : m.group(0).replace(val, masked);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        out = sb.toString();

        out = IBAN_TXT.matcher(out).replaceAll(mr ->
                Matcher.quoteReplacement(keepEdges(mr.group(), 4, 4)));

        out = LONG_DIGITS.matcher(out).replaceAll(mr -> {
            String d = mr.group(1);
            if (d.length() == 10) {
                return Matcher.quoteReplacement(keepEdges(d, 3, 2));
            }
            if (d.length() == 11 && d.startsWith("09")) {
                return Matcher.quoteReplacement(keepEdges(d, 4, 3));
            }
            if (d.length() >= 13 && !looksLikeEpochMillis(d)) {
                return Matcher.quoteReplacement(keepEdges(d, 4, 3));
            }
            return Matcher.quoteReplacement(d);
        });
        return out;
    }

    /**
     * timestampهای epoch سیزده‌رقمی‌اند و نباید با شمارهٔ سپرده اشتباه شوند،
     * وگرنه پیام‌های فنی ناخوانا می‌شوند.
     */
    private boolean looksLikeEpochMillis(String digits) {
        if (digits.length() != 13) {
            return false;
        }
        try {
            long v = Long.parseLong(digits);
            return v > 1_000_000_000_000L && v < 3_700_000_000_000L;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
