package com.citydi.logexplorer.api;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.labels.LabelConfig;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import com.citydi.logexplorer.mongo.IndexInspector;
import com.citydi.logexplorer.mongo.LogCollection;
import com.citydi.logexplorer.mongo.OperationCounter;
import com.citydi.logexplorer.mongo.ReadOnlyGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * فراداده برای رابط کاربری و تیم عملیات.
 *
 * `/meta/ui` مهم‌ترین endpoint اینجاست: فرانت‌اند هیچ برچسب یا رنگی را
 * از پیش نمی‌شناسد و همه را از اینجا می‌گیرد. یعنی افزودن یک میکروسرویس
 * تازه فقط ویرایش config.json و یک reload است — نه build جدید فرانت‌اند.
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    private final ConfigProvider configProvider;
    private final LabelConfigProvider labelProvider;
    private final IndexInspector indexInspector;
    private final LogCollection collection;
    private final ReadOnlyGuard guard;

    public MetaController(ConfigProvider configProvider, LabelConfigProvider labelProvider,
                          IndexInspector indexInspector, LogCollection collection,
                          ReadOnlyGuard guard) {
        this.configProvider = configProvider;
        this.labelProvider = labelProvider;
        this.indexInspector = indexInspector;
        this.collection = collection;
        this.guard = guard;
    }

    /** آنچه UI برای ساختن خودش لازم دارد — همه از config.json */
    @GetMapping("/ui")
    public Map<String, Object> ui() {
        LabelConfig c = labelProvider.get();
        AppConfig base = configProvider.get();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graph", Map.of(
                "layout", c.graph.layout(),
                "colors", c.graph.colors(),
                "showStartEnd", c.graph.showStartEnd(),
                "startLabel", c.graph.startLabel(),
                "endLabel", c.graph.endLabel(),
                "detailFields", c.graph.detailFields()));
        out.put("timezone", base.time.displayTimezone());
        out.put("maskingProfile", c.maskingProfile);
        out.put("counts", Map.of(
                "routingKeys", c.routingKeys.size(),
                "commandTypes", c.commandTypes.size(),
                "titles", c.titles.size(),
                "statuses", c.statuses.size()));
        out.put("warnings", c.warnings);
        out.put("labelsPath", labelProvider.path().toString());
        out.put("loadedAt", labelProvider.loadedAt().toString());
        return out;
    }

    /**
     * بازخوانی هر دو فایل پیکربندی بدون ری‌استارت سرویس.
     *
     * سناریوی واقعی: تیم یک میکروسرویس تازه اضافه می‌کند، پشتیبان در گراف
     * `orchestration26.new.service.routing.key` خام می‌بیند، یک سطر به
     * config.json اضافه می‌شود و همین‌جا اعمال می‌شود.
     */
    @PostMapping("/config/reload")
    public Map<String, Object> reload() {
        AppConfig base = configProvider.reload();
        LabelConfig labels = labelProvider.reload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reloaded", true);
        out.put("loadedAt", labelProvider.loadedAt().toString());
        out.put("routingKeys", labels.routingKeys.size());
        out.put("commandTypes", labels.commandTypes.size());
        out.put("titles", labels.titles.size());
        out.put("labelWarnings", labels.warnings);
        out.put("configWarnings", base.warnings);
        return out;
    }

    /** سلامت سرویس + تأیید فقط-خواندنی بودن + وضعیت ایندکس فیلدهای جستجو */
    @GetMapping("/health")
    public Map<String, Object> health() {
        OperationCounter.start();
        try {
            Map<String, Object> out = new LinkedHashMap<>();

            Map<String, Object> readOnly = new LinkedHashMap<>();
            readOnly.put("enforced", configProvider.get().mongo.enforceReadOnly());
            readOnly.put("blockedWriteAttempts", guard.blockedCount());
            readOnly.put("readCommandsExecuted", guard.allowedCount());
            readOnly.put("clean", guard.isClean());
            readOnly.put("recentViolations", guard.recentViolations());
            out.put("readOnly", readOnly);

            Map<String, Object> mongo = new LinkedHashMap<>();
            try {
                mongo.putAll(collection.connectionInfo());
                mongo.put("estimatedDocuments", collection.estimatedTotal());
                mongo.put("reachable", true);
            } catch (Exception e) {
                mongo.put("reachable", false);
                mongo.put("error", e.getClass().getSimpleName());
            }
            out.put("mongo", mongo);

            out.put("searchFields", indexInspector.asMap());
            out.put("configWarnings", configProvider.get().warnings);
            out.put("labelWarnings", labelProvider.get().warnings);
            out.put("maskingProfile", labelProvider.get().maskingProfile);
            return out;
        } finally {
            OperationCounter.stop();
        }
    }

    /**
     * راستی‌آزمایی ایندکس فیلدهای جستجوی عادی.
     *
     * ادعای `indexed: true` در config.json را با listIndexes مقایسه می‌کند.
     * اگر کسی فیلدی را فعال کرده ولی ایندکسش را نساخته، اینجا معلوم می‌شود —
     * قبل از اینکه پشتیبان با یک کوئری چندثانیه‌ای غافلگیر شود.
     */
    @PostMapping("/indexes/inspect")
    public Map<String, Object> inspectIndexes() {
        OperationCounter.start();
        try {
            indexInspector.inspect();
            return indexInspector.asMap();
        } finally {
            OperationCounter.stop();
        }
    }
}
