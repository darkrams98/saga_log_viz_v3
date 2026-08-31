package com.citydi.logexplorer.parse;

import java.util.List;

/**
 * یک گره در «نمای درختی» سند.
 *
 * چون شکل سند را نمی‌شناسیم، تنها راه نمایش کامل، یک درخت عمومی است:
 * هر فیلدی که در سند باشد — شناخته‌شده یا نه — اینجا دیده می‌شود.
 *
 * @param path          مسیر کامل، برای کپی کردن و برای بارگذاری تنبل زیردرخت
 * @param key           نام همین گره
 * @param type          object | array | string | json-string | int | double | bool | date | null
 * @param value         مقدار متنیِ ماسک‌شده (فقط برای برگ‌ها)
 * @param childCount    تعداد فرزندان (برای شیء و آرایه)
 * @param children      فرزندان؛ اگر بودجه تمام شده باشد خالی است و truncated=true
 * @param truncated     یعنی این گره کامل نمایش داده نشده
 * @param sizeBytes     حجم تقریبی
 * @param heavy         یعنی باید با درخواست جداگانه بارگذاری شود
 * @param parsedFromJson یعنی این زیردرخت از یک *رشتهٔ* JSON باز شده است
 * @param masked        یعنی مقدار پوشانده شده است
 */
public record FieldNode(
        String path,
        String key,
        String type,
        String value,
        Integer childCount,
        List<FieldNode> children,
        boolean truncated,
        long sizeBytes,
        boolean heavy,
        boolean parsedFromJson,
        boolean masked
) {
    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }
}
