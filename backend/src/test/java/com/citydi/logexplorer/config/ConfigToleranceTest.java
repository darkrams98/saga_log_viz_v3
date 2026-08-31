package com.citydi.logexplorer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * پیکربندی هم یک ورودی غیرقابل‌اعتماد است.
 *
 * config.yaml را تیم عملیات ویرایش می‌کند، اغلب نیمه‌شب و زیر فشار.
 * یک اشتباه تایپی نباید سرویس را زمین بزند — باید با پیش‌فرض بالا بیاید
 * و مشکل را در endpoint سلامت گزارش کند.
 */
class ConfigToleranceTest {

    private static AppConfig parse(String yaml) {
        return ConfigLoader.loadFromStream(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------- فایل‌های خراب

    @Test
    @DisplayName("YAML کاملاً خراب، برنامه را نمی‌شکند")
    void brokenYamlDoesNotThrow() {
        // تورفتگی با tab — پرتکرارترین اشتباه ویرایش دستی YAML
        AppConfig config = assertDoesNotThrow(() -> parse("time:\n\tqueryField: x\n"));
        assertNotNull(config);
        assertNotNull(config.limits);
        assertFalse(config.warnings.isEmpty(), "باید دلیل مشکل گزارش شود");
        assertTrue(config.limits.defaultPageSize() > 0, "سرویس باید قابل استفاده بماند");
    }

    @Test
    @DisplayName("کلید تکراری گرفته می‌شود، نه اینکه بی‌سروصدا یکی برنده شود")
    void duplicateKeyIsReported() {
        AppConfig config = assertDoesNotThrow(() -> parse("""
                time:
                  queryField: alpha
                time:
                  queryField: beta
                """));
        assertNotNull(config);
        assertFalse(config.warnings.isEmpty(),
                "کلید تکراری باید گزارش شود تا کسی فکر نکند مقدار دومش اعمال شده");
    }

    @Test
    @DisplayName("فایل خالی → پیکربندی حداقلی کارآمد")
    void emptyFileFallsBackToMinimal() {
        AppConfig config = parse("");
        assertNotNull(config.time.candidates());
        assertFalse(config.time.candidates().isEmpty(), "همیشه چند کاندید زمان هست");
        assertFalse(config.columns.isEmpty(), "همیشه چند ستون پایه هست");
        assertEquals(25, config.limits.defaultPageSize());
    }

    @Test
    @DisplayName("ریشهٔ YAML اگر لیست باشد (نه نگاشت) هم تحمل می‌شود")
    void rootThatIsNotAMapIsTolerated() {
        AppConfig config = assertDoesNotThrow(() -> parse("- یک\n- دو\n- سه\n"));
        assertNotNull(config);
        assertNotNull(config.mongo.uri());
    }

    @Test
    @DisplayName("فایل غایب → پیکربندی حداقلی، نه استثنا")
    void missingFileFallsBack() {
        AppConfig config = ConfigLoader.load(Path.of("/tmp/definitely-not-here-987654.yaml"));
        assertNotNull(config);
        assertTrue(config.warnings.stream().anyMatch(w -> w.contains("پیکربندی حداقلی")));
    }

    // ------------------------------------------------ نوع‌های نادرست

    @Test
    @DisplayName("مقدارهای با نوع نادرست به پیش‌فرض برمی‌گردند")
    void wrongTypesFallBackToDefaults() {
        AppConfig config = parse("""
                limits:
                  maxPageSize: "خیلی زیاد"
                  countCap: [1, 2, 3]
                  defaultRangeHours: { a: b }
                columns: "این باید لیست می‌بود"
                facets: 42
                fields: true
                masking: "بله"
                dashboard:
                  timeBuckets: "چهل و هشت"
                """);
        assertTrue(config.limits.maxPageSize() > 0, "اندازهٔ صفحه باید معتبر بماند");
        assertTrue(config.limits.countCap() >= 0);
        assertTrue(config.limits.defaultRangeHours() >= 1);
        assertFalse(config.columns.isEmpty(), "ستون‌ها باید به پیش‌فرض برگردند");
        assertNotNull(config.facets);
        assertFalse(config.fields.isEmpty());
        assertTrue(config.dashboard.timeBuckets() >= 4);
    }

    @Test
    @DisplayName("محدودیت‌های بی‌معنا اصلاح می‌شوند، نه اینکه بپذیریم")
    void nonsensicalLimitsAreClamped() {
        AppConfig config = parse("""
                limits:
                  maxPageSize: -50
                  defaultPageSize: 100000
                  maxDepth: 0
                  maxFlattenNodes: 1
                  maxDocumentBytes: 5
                """);
        assertTrue(config.limits.maxPageSize() >= 1);
        assertTrue(config.limits.defaultPageSize() <= config.limits.maxPageSize(),
                "اندازهٔ پیش‌فرض هرگز نباید از سقف بیشتر باشد");
        assertTrue(config.limits.maxDepth() >= 2);
        assertTrue(config.limits.maxFlattenNodes() >= 50);
        assertTrue(config.limits.maxDocumentBytes() >= 10_000L);
    }

    @Test
    @DisplayName("الگوی regex نامعتبر نادیده گرفته می‌شود و بقیه سالم می‌مانند")
    void invalidRegexIsSkippedNotFatal() {
        AppConfig config = parse("""
                transforms:
                  broken: { type: regexReplace, pattern: "([unclosed", replacement: "" }
                  healthy: { type: regexReplace, pattern: "^x", replacement: "y" }
                search:
                  detectors:
                    - { name: bad, pattern: "(((", fields: [a] }
                    - { name: good, pattern: "^\\\\d{10}$", label: "کد ملی", fields: [nationalCode] }
                """);
        assertFalse(config.transforms.containsKey("broken"), "تبدیل خراب نباید ثبت شود");
        assertTrue(config.transforms.containsKey("healthy"), "تبدیل سالم باید بماند");
        assertEquals(1, config.search.detectors().size(), "فقط قاعدهٔ سالم باید بماند");
        assertEquals("good", config.search.detectors().get(0).name());
        assertTrue(config.warnings.stream().anyMatch(w -> w.contains("معتبر نیست")));
    }

    // ------------------------------------------------ پیکربندی ناقص

    @Test
    @DisplayName("پیکربندی ناقص: بخش‌های نیامده پیش‌فرض می‌گیرند")
    void partialConfigKeepsDefaultsForTheRest() {
        AppConfig config = parse("""
                time:
                  candidates: [eventTime]
                  queryField: eventTime
                """);
        assertEquals("eventTime", config.time.queryField());
        assertEquals(java.util.List.of("eventTime"), config.time.candidates());
        assertNotNull(config.masking.placeholder(), "بخش masking باید پیش‌فرض بگیرد");
        assertFalse(config.level.errorLevels().isEmpty());
        assertNotNull(config.mongo.uri());
    }

    @Test
    @DisplayName("queryField نیامده → اولین کاندید زمان استفاده می‌شود")
    void queryFieldDefaultsToFirstCandidate() {
        AppConfig config = parse("""
                time:
                  candidates: [createdAt, "@timestamp"]
                """);
        assertEquals("createdAt", config.time.queryField());
    }

    @Test
    @DisplayName("کلیدهای ناشناخته نادیده گرفته می‌شوند، نه اینکه خطا بدهند")
    void unknownKeysAreIgnored() {
        AppConfig config = assertDoesNotThrow(() -> parse("""
                somethingFromTheFuture:
                  weWillAddThisLater: true
                  nested: { deep: [1, 2, 3] }
                time:
                  candidates: [ts]
                  aBrandNewOption: "?"
                """));
        assertEquals("ts", config.time.queryField());
    }

    @Test
    @DisplayName("ستون بدون key حذف می‌شود ولی بقیهٔ ستون‌ها می‌مانند")
    void columnWithoutKeyIsDropped() {
        AppConfig config = parse("""
                columns:
                  - { label: "بدون کلید" }
                  - { key: time, label: "زمان", type: datetime }
                  - { key: message, label: "پیام", grow: true }
                """);
        assertEquals(2, config.columns.size());
        assertTrue(config.warnings.stream().anyMatch(w -> w.contains("بدون key")));
    }

    @Test
    @DisplayName("نگارش مختصر فیلد (لیست به‌جای نگاشت) پشتیبانی می‌شود")
    void shorthandFieldSyntaxWorks() {
        AppConfig config = parse("""
                fields:
                  message: [msg, text, description]
                  service: { candidates: [applicationName], transform: shortClass }
                """);
        assertEquals(java.util.List.of("msg", "text", "description"),
                config.field("message").candidates());
        assertEquals("shortClass", config.field("service").transform());
    }

    @Test
    @DisplayName("precedence سطح لاگ قابل تنظیم است و پیش‌فرض معقولی دارد")
    void levelPrecedenceIsConfigurable() {
        assertEquals(java.util.List.of("field", "derive"),
                parse("level: { candidates: [level] }").level.precedence());
        assertEquals(java.util.List.of("derive", "field"),
                parse("level: { precedence: [derive, field] }").level.precedence());
    }

    // ------------------------------------------------ متغیر محیطی

    @Test
    @DisplayName("${ENV:default} وقتی متغیر تعریف نشده، مقدار پیش‌فرض را می‌گیرد")
    void envPlaceholderUsesDefault() {
        AppConfig config = parse("""
                mongo:
                  uri: "${MONGO_URI_THAT_DOES_NOT_EXIST_12345:mongodb://fallback:27017}"
                  database: "${ALSO_MISSING_98765:logsdb}"
                """);
        assertEquals("mongodb://fallback:27017", config.mongo.uri());
        assertEquals("logsdb", config.mongo.database());
    }

    @Test
    @DisplayName("${ENV} بدون پیش‌فرض، رمز عبور را در فایل جا نمی‌گذارد")
    void envPlaceholderWithoutDefaultResolvesToEmpty() {
        AppConfig config = parse("""
                mongo:
                  uri: "${MONGO_URI_MISSING_55555}"
                """);
        assertFalse(config.mongo.uri().contains("${"),
                "جای‌گزین حل‌نشده نباید مستقیم به درایور برود");
    }

    // ------------------------------------------------ بازخوانی زنده

    @Test
    @DisplayName("بازخوانی با فایل خراب، نسخهٔ سالمِ در حال کار را نگه می‌دارد")
    void reloadKeepsPreviousConfigWhenFileBreaks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, """
                time:
                  candidates: [goodField]
                  queryField: goodField
                """, StandardCharsets.UTF_8);

        ConfigProvider provider = new ConfigProvider(file.toString());
        AppConfig healthy = provider.get();
        assertEquals("goodField", healthy.time.queryField());

        // فایل ناپدید می‌شود (استقرار نیمه‌کاره، mount قطع‌شده، ...)
        Files.delete(file);
        AppConfig afterReload = provider.reload();

        assertSame(healthy, afterReload, "نسخهٔ سالم قبلی باید حفظ شود");
        assertEquals("goodField", provider.get().time.queryField());
    }

    @Test
    @DisplayName("بازخوانی موفق، پیکربندی جدید را اعمال می‌کند")
    void reloadAppliesNewConfig(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, "time: { candidates: [v1], queryField: v1 }", StandardCharsets.UTF_8);
        ConfigProvider provider = new ConfigProvider(file.toString());
        assertEquals("v1", provider.get().time.queryField());

        Files.writeString(file, "time: { candidates: [v2], queryField: v2 }", StandardCharsets.UTF_8);
        AppConfig next = provider.reload();

        assertEquals("v2", next.time.queryField());
        assertEquals("v2", provider.get().time.queryField());
    }
}
