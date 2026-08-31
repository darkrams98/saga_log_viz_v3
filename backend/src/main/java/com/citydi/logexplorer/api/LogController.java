package com.citydi.logexplorer.api;

import com.citydi.logexplorer.labels.LabelConfig;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import com.citydi.logexplorer.mongo.OperationCounter;
import com.citydi.logexplorer.service.LogLookupService;
import com.citydi.logexplorer.service.MongoErrors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API نمایش یک لاگ.
 *
 * سه مسیر، و همین. عمداً هیچ endpointی برای «فهرست لاگ‌ها»، «آمار» یا
 * «نمودار زمانی» وجود ندارد: پایش کلی کار Grafana است و جستجوی گسترده
 * کار ELK. این سرویس فقط یک کار می‌کند و همان را خوب انجام می‌دهد.
 *
 * هر پاسخ `mongoOperations` دارد: تعداد واقعی پرس‌وجوهایی که برای ساختن
 * همان پاسخ به MongoDB رفته است. برای مسیر شناسه، این عدد باید همیشه ۱ باشد.
 */
@RestController
@RequestMapping("/api/v1/log")
public class LogController {

    private final LogLookupService lookup;
    private final LabelConfigProvider labels;

    public LogController(LogLookupService lookup, LabelConfigProvider labels) {
        this.lookup = lookup;
        this.labels = labels;
    }

    // ------------------------------------------------------ جستجوی عادی

    /**
     * یافتن لاگ با شناسه — مسیر اصلی و سریع.
     *
     * `field` اختیاری است و پیش‌فرضش `_id`. اگر تیم بعداً روی فیلد دیگری
     * ایندکس ساخت، کافی است آن را در config.json با `indexed: true` و
     * `enabled: true` معرفی کند؛ همین endpoint بدون تغییر کد قبولش می‌کند.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable String id,
                                                    @RequestParam(required = false) String field) {
        OperationCounter.start();
        try {
            String target = (field == null || field.isBlank()) ? defaultField() : field;
            LogLookupService.LogView view = lookup.findByIndexedField(target, id);
            if (view == null) {
                Map<String, Object> body = envelope(null);
                body.put("found", false);
                body.put("message", "لاگی با این شناسه پیدا نشد.");
                body.put("hint", "شناسه را از ELK دوباره کپی کنید؛ فاصله یا نویسهٔ اضافه "
                        + "شایع‌ترین علت است. اگر مطمئنید شناسه درست است، ممکن است لاگ "
                        + "هنوز به MongoDB نرسیده یا بر اساس سیاست نگهداشت حذف شده باشد.");
                body.put("searchedField", target);
                return ResponseEntity.status(404).body(body);
            }
            Map<String, Object> body = envelope(view);
            body.put("found", true);
            body.put("searchedField", target);
            return ResponseEntity.ok(body);
        } finally {
            // شمارنده حتی در مسیر خطا هم باید پاک شود
            leftovers();
        }
    }

    /** فیلدهای مجاز جستجوی عادی — UI از همین فهرست dropdown می‌سازد */
    @GetMapping("/search/fields")
    public Map<String, Object> searchFields() {
        LabelConfig.SearchConfig search = labels.get().search;
        List<Map<String, Object>> fields = new ArrayList<>();
        for (LabelConfig.NormalField f : search.normalFields()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", f.field());
            item.put("label", f.label());
            item.put("type", f.type());
            item.put("indexed", f.indexed());
            item.put("enabled", f.enabled());
            item.put("usable", f.enabled() && f.indexed());
            item.put("default", f.isDefault());
            item.put("placeholder", f.placeholder());
            item.put("hint", f.hint());
            fields.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fields", fields);
        out.put("note", "در حالت عادی فقط فیلدهای ایندکس‌شده قابل جستجو هستند. "
                + "برای افزودن فیلد تازه: اول ایندکس بسازید، بعد در config.json فعالش کنید.");
        return out;
    }

    // -------------------------------------------------- جستجوی پیشرفته

    /** پیکربندی جستجوی پیشرفته: عملگرها، فیلدهای پیشنهادی، و متن هشدار */
    @GetMapping("/advanced/config")
    public Map<String, Object> advancedConfig() {
        LabelConfig.AdvancedConfig adv = labels.get().search.advanced();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", adv.enabled());
        out.put("maxResults", adv.maxResults());
        out.put("maxTimeMs", adv.maxTimeMs());
        out.put("warning", adv.warning());
        out.put("operators", adv.operators().stream()
                .map(o -> Map.of("op", o.op(), "label", o.label())).toList());
        out.put("suggestedFields", adv.suggestedFields().stream()
                .map(f -> Map.of("field", f.field(), "label", f.label())).toList());
        out.put("resultFields", adv.resultFields().stream()
                .map(f -> Map.of("path", f.path(), "label", f.label())).toList());
        return out;
    }

    public record AdvancedRequest(List<LogLookupService.Filter> filters) {
    }

    /**
     * جستجوی سنگین روی فیلدهای بدون ایندکس.
     *
     * POST است، نه GET — نه به‌خاطر معنای REST، بلکه چون این عملیات
     * گران است و نباید سهواً از نوار آدرس، bookmark یا prefetch مرورگر
     * اجرا شود.
     */
    @PostMapping("/advanced")
    public Map<String, Object> advanced(@RequestBody AdvancedRequest request) {
        OperationCounter.start();
        try {
            LogLookupService.SearchResult result = lookup.advancedSearch(
                    request == null ? List.of() : request.filters());
            OperationCounter.Result ops = OperationCounter.stop();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hits", result.hits());
            out.put("capped", result.capped());
            out.put("limit", result.limit());
            out.put("columns", result.columns().stream()
                    .map(c -> Map.of("path", c.path(), "label", c.label())).toList());
            out.put("notes", result.notes());
            out.put("mongoOperations", ops.count());
            out.put("operations", ops.operations());
            return out;
        } finally {
            leftovers();
        }
    }

    // ------------------------------------------------------------- کمکی

    private String defaultField() {
        LabelConfig.NormalField f = labels.get().search.defaultField();
        if (f == null) {
            throw new MongoErrors.FriendlyException(
                    "هیچ فیلد جستجوی ایندکس‌شده‌ای در config.json فعال نیست.");
        }
        return f.field();
    }

    private Map<String, Object> envelope(LogLookupService.LogView view) {
        OperationCounter.Result ops = OperationCounter.stop();
        Map<String, Object> body = new LinkedHashMap<>();
        if (view != null) {
            body.put("id", view.id());
            body.put("header", view.header());
            body.put("summary", view.summary());
            body.put("graph", view.graph());
            body.put("table", view.table());
            body.put("tableTruncated", view.tableTruncated());
            body.put("rawJson", view.rawJson());
            body.put("rawSizeBytes", view.rawSizeBytes());
            body.put("maskingProfile", view.maskingProfile());
            body.put("warnings", view.warnings());
        }
        // ادعای «فقط یک find» را قابل بررسی می‌کند
        body.put("mongoOperations", ops.count());
        body.put("operations", ops.operations());
        return body;
    }

    /** پاک‌کردن شمارندهٔ نخ حتی وقتی مسیر با استثنا تمام شده */
    private void leftovers() {
        OperationCounter.stop();
    }
}
