package com.citydi.logexplorer.labels;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * نگهدارندهٔ config.json با بازخوانی زنده.
 *
 * تیم پشتیبانی باید بتواند نیمه‌شب یک میکروسرویس تازه را به فارسی اضافه کند
 * و بلافاصله نتیجه را ببیند — بدون استقرار مجدد و بدون قطعی.
 *
 * اگر فایل جدید خراب باشد، نسخهٔ سالم قبلی حفظ می‌شود.
 */
@Component
public class LabelConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(LabelConfigProvider.class);

    private final Path path;
    private final AtomicReference<LabelConfig> current = new AtomicReference<>();
    private final AtomicReference<Instant> loadedAt = new AtomicReference<>();

    public LabelConfigProvider(@Value("${logexplorer.labels:config/config.json}") String location) {
        this.path = resolve(location);
        LabelConfig cfg = LabelConfigLoader.load(this.path);
        current.set(cfg);
        loadedAt.set(Instant.now());
        log.info("برچسب‌ها از «{}» خوانده شد — {} میکروسرویس، {} نوع دستور، {} عنوان، {} هشدار",
                this.path, cfg.routingKeys.size(), cfg.commandTypes.size(),
                cfg.titles.size(), cfg.warnings.size());
        cfg.warnings.forEach(w -> log.warn("برچسب‌ها: {}", w));
    }

    private static Path resolve(String location) {
        Path p = Path.of(location);
        if (Files.isReadable(p)) {
            return p;
        }
        Path alt = Path.of("..").resolve(location).normalize();
        return Files.isReadable(alt) ? alt : p;
    }

    public LabelConfig get() {
        return current.get();
    }

    public Path path() {
        return path;
    }

    public Instant loadedAt() {
        return loadedAt.get();
    }

    /** @return نسخهٔ جدید در صورت موفقیت، یا همان قبلی اگر فایل خراب بود */
    public synchronized LabelConfig reload() {
        LabelConfig previous = current.get();
        try {
            LabelConfig next = LabelConfigLoader.load(path);
            boolean broken = next.warnings.stream()
                    .anyMatch(w -> w.contains("ناموفق") || w.contains("پیدا نشد"));
            if (broken && previous != null) {
                log.error("بازخوانی برچسب‌ها ناموفق بود؛ نسخهٔ قبلی حفظ شد. {}", next.warnings);
                return previous;
            }
            current.set(next);
            loadedAt.set(Instant.now());
            log.info("برچسب‌ها بازخوانی شد ({} هشدار)", next.warnings.size());
            return next;
        } catch (Exception e) {
            log.error("بازخوانی برچسب‌ها با خطا مواجه شد؛ نسخهٔ قبلی حفظ شد", e);
            return previous;
        }
    }
}
