package com.citydi.logexplorer.mongo;

import com.citydi.logexplorer.labels.LabelConfig;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * راستی‌آزمایی ادعای «این فیلد ایندکس دارد».
 *
 * در config.json هر فیلد جستجوی عادی یک `indexed: true` دارد. آن مقدار
 * فقط یک *ادعا*ست — نوشته‌شده به دست آدم، و آدم‌ها اشتباه می‌کنند.
 * این کلاس با `listIndexes` بررسی می‌کند که ادعا درست باشد.
 *
 * چرا مهم است؟ چون کل تفکیک «جستجوی عادی» از «جستجوی پیشرفته» بر این
 * فرض بنا شده که حالت عادی همیشه سریع است. اگر کسی فیلدی را بدون ایندکس
 * فعال کند، آن تفکیک بی‌معنا می‌شود و بار ناخواسته روی سرور می‌افتد.
 *
 * این کلاس **هرگز ایندکس نمی‌سازد** — فقط می‌خواند و گزارش می‌دهد.
 * ساخت ایندکس کار DBA است: ops/indexes.js
 */
@Component
public class IndexInspector {

    private static final Logger log = LoggerFactory.getLogger(IndexInspector.class);

    private final LogCollection collection;
    private final LabelConfigProvider labels;

    private volatile Report lastReport = new Report(List.of(), List.of(), false);

    public IndexInspector(LogCollection collection, LabelConfigProvider labels) {
        this.collection = collection;
        this.labels = labels;
    }

    /**
     * @param field    نام فیلد در config.json
     * @param claimed  آنچه config ادعا می‌کند
     * @param actual   آنچه واقعاً در MongoDB هست
     * @param status   ok | missing-index | not-claimed | disabled
     */
    public record FieldStatus(String field, String label, boolean enabled, boolean claimed,
                              boolean actual, String status, String advice) {
    }

    public record Report(List<String> existingIndexes, List<FieldStatus> fields, boolean reachable) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void inspectOnStartup() {
        Report r = inspect();
        if (!r.reachable()) {
            log.warn("بررسی ایندکس‌ها ممکن نشد (اتصال به MongoDB برقرار است؟)");
            return;
        }
        for (FieldStatus f : r.fields()) {
            if ("missing-index".equals(f.status())) {
                log.error("⚠ فیلد «{}» در config.json ایندکس‌شده معرفی شده ولی در MongoDB "
                        + "ایندکسی روی آن نیست. جستجوی عادی روی این فیلد کل مجموعه را "
                        + "اسکن می‌کند. یا ایندکس بسازید یا enabled را false کنید.", f.field());
            }
        }
    }

    public Report inspect() {
        LabelConfig.SearchConfig search = labels.get().search;
        List<Document> raw;
        try {
            raw = collection.listIndexes();
        } catch (Exception e) {
            log.debug("listIndexes ناموفق: {}", e.toString());
            lastReport = new Report(List.of(), List.of(), false);
            return lastReport;
        }

        // فیلدهایی که *پیشوند* یک ایندکس‌اند؛ فقط پیشوند به تنهایی قابل استفاده است
        Set<String> indexedPrefixes = new LinkedHashSet<>();
        List<String> signatures = new ArrayList<>();
        for (Document idx : raw) {
            if (idx.get("key") instanceof Document key) {
                signatures.add(signature(key));
                key.keySet().stream().findFirst().ifPresent(indexedPrefixes::add);
            }
        }

        List<FieldStatus> fields = new ArrayList<>();
        for (LabelConfig.NormalField f : search.normalFields()) {
            boolean actual = indexedPrefixes.contains(f.field());
            String status;
            String advice;
            if (!f.enabled()) {
                status = "disabled";
                advice = actual
                        ? "ایندکس وجود دارد؛ برای فعال‌سازی enabled و indexed را true کنید."
                        : "ابتدا ایندکس بسازید (ops/indexes.js)، بعد فعالش کنید.";
            } else if (f.indexed() && actual) {
                status = "ok";
                advice = null;
            } else if (f.indexed()) {
                status = "missing-index";
                advice = "config ادعا می‌کند ایندکس دارد ولی ندارد. جستجو روی این فیلد "
                        + "کل مجموعه را اسکن می‌کند — یا ایندکس بسازید یا enabled را false کنید.";
            } else {
                status = "not-claimed";
                advice = "در حالت عادی قابل استفاده نیست چون indexed:false است.";
            }
            fields.add(new FieldStatus(f.field(), f.label(), f.enabled(), f.indexed(),
                    actual, status, advice));
        }

        lastReport = new Report(List.copyOf(signatures), List.copyOf(fields), true);
        return lastReport;
    }

    public Report last() {
        return lastReport;
    }

    private static String signature(Document keyDoc) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : keyDoc.entrySet()) {
            parts.add(e.getKey() + ":" + e.getValue());
        }
        return String.join(",", parts);
    }

    public Map<String, Object> asMap() {
        Report r = last();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reachable", r.reachable());
        out.put("existingIndexes", r.existingIndexes());
        out.put("fields", r.fields());
        out.put("problems", r.fields().stream()
                .filter(f -> "missing-index".equals(f.status())).map(FieldStatus::field).toList());
        out.put("note", "این سرویس ایندکس نمی‌سازد؛ ساخت آن‌ها با DBA است (ops/indexes.js).");
        return out;
    }
}
