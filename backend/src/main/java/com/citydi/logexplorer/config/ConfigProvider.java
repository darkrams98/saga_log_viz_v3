package com.citydi.logexplorer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * نگهدارندهٔ پیکربندی جاری.
 *
 * پیکربندی در حافظه cache می‌شود ولی می‌توان بدون ری‌استارت بازخوانی‌اش کرد
 * (`POST /api/v1/meta/config/reload`). این برای تیم عملیات مهم است: وقتی شکل
 * لاگ‌ها عوض شد، فقط config.yaml را ویرایش و reload می‌کنند.
 *
 * اگر بازخوانی شکست بخورد، پیکربندی *قبلی* دست‌نخورده می‌ماند —
 * یک فایل خراب نباید سرویسِ در حال کار را از کار بیندازد.
 */
@Component
public class ConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(ConfigProvider.class);

    private final Path configPath;
    private final AtomicReference<AppConfig> current = new AtomicReference<>();
    private final AtomicReference<Instant> loadedAt = new AtomicReference<>();

    public ConfigProvider(@Value("${logexplorer.config:config/config.yaml}") String configLocation) {
        this.configPath = resolve(configLocation);
        AppConfig cfg = ConfigLoader.load(configPath);
        current.set(cfg);
        loadedAt.set(Instant.now());
        log.info("پیکربندی از «{}» خوانده شد — {} ستون، {} facet، {} هشدار",
                configPath, cfg.columns.size(), cfg.facets.size(), cfg.warnings.size());
        cfg.warnings.forEach(w -> log.warn("پیکربندی: {}", w));
    }

    private static Path resolve(String location) {
        Path p = Path.of(location);
        if (Files.isReadable(p)) {
            return p;
        }
        // مسیر نسبی هنگام اجرا از backend/ هم امتحان می‌شود
        Path alt = Path.of("..").resolve(location).normalize();
        return Files.isReadable(alt) ? alt : p;
    }

    public AppConfig get() {
        return current.get();
    }

    public Path path() {
        return configPath;
    }

    public Instant loadedAt() {
        return loadedAt.get();
    }

    /**
     * بازخوانی از دیسک.
     *
     * @return پیکربندی جدید در صورت موفقیت، یا همان قبلی اگر فایل خراب بود
     */
    public synchronized AppConfig reload() {
        AppConfig previous = current.get();
        try {
            AppConfig next = ConfigLoader.load(configPath);
            // اگر فایل خراب بود، loader پیکربندی حداقلی برمی‌گرداند.
            // در آن حالت ترجیح می‌دهیم نسخهٔ سالم قبلی را نگه داریم.
            boolean fellBackToMinimal = next.warnings.stream()
                    .anyMatch(w -> w.contains("پیکربندی حداقلی"));
            if (fellBackToMinimal && previous != null) {
                log.error("بازخوانی پیکربندی ناموفق بود؛ نسخهٔ قبلی حفظ شد. {}", next.warnings);
                return previous;
            }
            current.set(next);
            loadedAt.set(Instant.now());
            log.info("پیکربندی بازخوانی شد ({} هشدار)", next.warnings.size());
            return next;
        } catch (Exception e) {
            log.error("بازخوانی پیکربندی با خطا مواجه شد؛ نسخهٔ قبلی حفظ شد", e);
            return previous;
        }
    }
}
