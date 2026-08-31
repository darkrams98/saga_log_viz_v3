package com.citydi.logexplorer.parse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * یک ردیف لاگ، آماده برای نمایش — مستقل از شکل سند اصلی.
 *
 * نکتهٔ طراحی: هر مقدار «منبع» خودش را همراه دارد (timeSource, messageSource).
 * وقتی برنامه هیچ فرضی دربارهٔ schema ندارد، کاربر باید بتواند ببیند
 * عددی که نشانش می‌دهیم از کدام فیلد سند آمده است. بدون این، اشکال‌زدایی
 * پیکربندی غیرممکن می‌شود.
 *
 * @param columns مقدار متنی هر ستونی که در config.yaml تعریف شده
 * @param warnings مشکلاتی که هنگام تفسیر همین سند دیده شد (سند خراب نشکند، ولی ساکت هم نماند)
 */
public record LogRecord(
        String id,
        Instant time,
        String timeSource,
        String level,
        String levelLabel,
        boolean error,
        String message,
        String messageSource,
        String service,
        String status,
        Map<String, String> columns,
        Map<String, String> highlights,
        List<String> warnings,
        long rawSizeBytes,
        boolean oversized
) {
}
