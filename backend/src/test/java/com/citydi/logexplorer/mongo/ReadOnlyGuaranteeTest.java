package com.citydi.logexplorer.mongo;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * تضمین READ-ONLY — مهم‌ترین تست امنیتی این پروژه.
 *
 * سه لایهٔ دفاعی داریم و هر سه اینجا سنجیده می‌شوند:
 *
 *   ۱) {@link ReadOnlyGuard} — نگهبان زمان اجرا روی درایور: هر دستوری که
 *      در فهرست مجاز نباشد پیش از رسیدن به شبکه رد می‌شود.
 *   ۲) زمان کامپایل — {@link LogCollection} اصلاً متد نوشتن ندارد، هیچ متد
 *      پویش کل مجموعه ندارد، و هیچ فایلی در src/main فراخوانی نوشتن ندارد.
 *   ۳) {@link OperationCounter} — قید «بعد از یافتن لاگ، بیش از یک find اجرا
 *      نشود» را در زمان اجرا قابل بررسی می‌کند.
 *
 * لایهٔ چهارم در استقرار توصیه می‌شود و اینجا قابل تست نیست:
 * کاربر MongoDB با نقش `read` (به docs/OPERATIONS.md مراجعه کنید).
 */
class ReadOnlyGuaranteeTest {

    // ------------------------------------------------ لایهٔ ۱: نگهبان درایور

    /**
     * دستورهایی که MongoDB برای نوشتن استفاده می‌کند. اگر روزی یکی از این‌ها
     * به فهرست مجاز اضافه شود، این تست باید فوراً قرمز شود.
     */
    private static final List<String> WRITE_COMMANDS = List.of(
            "insert", "update", "delete", "findandmodify", "bulkwrite",
            "create", "createindexes", "dropindexes", "drop", "dropdatabase",
            "renamecollection", "collmod", "mapreduce", "createuser", "updateuser",
            "dropuser", "grantrolestouser", "shutdown", "fsync", "compact",
            "cleanuporphaned", "convertocapped", "emptycapped", "setparameter",
            "applyops", "eval", "copydb", "clone", "reindex", "repairdatabase");

    @Test
    @DisplayName("فهرست مجاز هیچ دستور نویسنده‌ای ندارد")
    void allowListContainsNoWriteCommand() {
        for (String write : WRITE_COMMANDS) {
            assertFalse(ReadOnlyGuard.allowedCommands().contains(write),
                    "دستور نویسندهٔ «" + write + "» نباید در فهرست مجاز باشد");
        }
    }

    @Test
    @DisplayName("همهٔ دستورهای نویسنده در زمان اجرا مسدود می‌شوند")
    void everyWriteCommandIsBlocked() {
        ReadOnlyGuard guard = new ReadOnlyGuard();
        for (String write : WRITE_COMMANDS) {
            ReadOnlyViolationException ex = assertThrows(ReadOnlyViolationException.class,
                    () -> guard.checkCommand(write, new BsonDocument()),
                    "دستور «" + write + "» باید مسدود می‌شد");
            assertEquals(write, ex.commandName());
        }
        assertEquals(WRITE_COMMANDS.size(), guard.blockedCount());
        assertEquals(0, guard.allowedCount());
        assertFalse(guard.isClean(), "پس از تخلف، وضعیت نباید «پاک» گزارش شود");
        assertFalse(guard.recentViolations().isEmpty(), "تخلف‌ها باید ثبت شوند");
    }

    @Test
    @DisplayName("دستورهای خواندن عبور می‌کنند")
    void readCommandsPass() {
        ReadOnlyGuard guard = new ReadOnlyGuard();
        for (String read : List.of("find", "getMore", "aggregate", "count",
                "distinct", "listIndexes", "hello", "ping", "killCursors")) {
            assertDoesNotThrow(() -> guard.checkCommand(read, new BsonDocument()),
                    "دستور خواندن «" + read + "» نباید مسدود شود");
        }
        assertTrue(guard.isClean(), "هیچ تخلفی نباید ثبت شده باشد");
        assertEquals(9, guard.allowedCount());
    }

    @Test
    @DisplayName("دستور ناشناخته هم مسدود می‌شود (فهرست مجاز است، نه فهرست ممنوع)")
    void unknownCommandIsBlocked() {
        ReadOnlyGuard guard = new ReadOnlyGuard();
        assertThrows(ReadOnlyViolationException.class,
                () -> guard.checkCommand("someFutureCommandWeNeverHeardOf", new BsonDocument()));
        assertThrows(ReadOnlyViolationException.class,
                () -> guard.checkCommand(null, new BsonDocument()));
        assertThrows(ReadOnlyViolationException.class,
                () -> guard.checkCommand("", new BsonDocument()));
    }

    @Test
    @DisplayName("aggregate با $out یا $merge مسدود می‌شود، بدون آن‌ها عبور می‌کند")
    void aggregateWithWriteStageIsBlocked() {
        ReadOnlyGuard guard = new ReadOnlyGuard();

        assertThrows(ReadOnlyViolationException.class,
                () -> guard.checkCommand("aggregate", aggregateCommand(
                        new BsonDocument("$match", new BsonDocument("x", new BsonInt32(1))),
                        new BsonDocument("$out", new BsonString("stolen_copy")))));

        assertThrows(ReadOnlyViolationException.class,
                () -> guard.checkCommand("aggregate", aggregateCommand(
                        new BsonDocument("$merge", new BsonDocument("into", new BsonString("logs"))))));

        assertDoesNotThrow(() -> guard.checkCommand("aggregate", aggregateCommand(
                new BsonDocument("$match", new BsonDocument("x", new BsonInt32(1))),
                new BsonDocument("$group", new BsonDocument("_id", new BsonString("$status"))),
                new BsonDocument("$limit", new BsonInt32(10)))));

        assertEquals(2, guard.blockedCount());
        assertEquals(1, guard.allowedCount());
    }

    @Test
    @DisplayName("aggregate بدون pipeline یا با pipeline خراب، نگهبان را نمی‌شکند")
    void malformedAggregateDoesNotBreakGuard() {
        ReadOnlyGuard guard = new ReadOnlyGuard();
        assertDoesNotThrow(() -> guard.checkCommand("aggregate", new BsonDocument()));
        assertDoesNotThrow(() -> guard.checkCommand("aggregate", null));
        assertDoesNotThrow(() -> guard.checkCommand("aggregate",
                new BsonDocument("pipeline", new BsonString("این آرایه نیست"))));
    }

    private static BsonDocument aggregateCommand(BsonDocument... stages) {
        BsonArray pipeline = new BsonArray();
        for (BsonDocument stage : stages) {
            pipeline.add(stage);
        }
        return new BsonDocument("aggregate", new BsonString("logs")).append("pipeline", pipeline);
    }

    // ------------------------------------------ لایهٔ ۲: تضمین زمان کامپایل

    @Test
    @DisplayName("LogCollection هیچ متد نوشتنی ندارد")
    void logCollectionExposesNoWriteMethod() {
        List<String> writeVerbs = List.of("insert", "update", "delete", "replace", "save",
                "remove", "drop", "create", "rename", "bulk", "upsert", "write", "modify");
        List<String> offenders = new ArrayList<>();
        for (var method : LogCollection.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            for (String verb : writeVerbs) {
                if (name.startsWith(verb)) {
                    offenders.add(method.getName());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "دروازهٔ پایگاه داده نباید متد نوشتن داشته باشد، ولی این‌ها را دارد: " + offenders);
    }

    /**
     * قید این مرحله: «هیچ بار اضافه‌ای روی سرور ایجاد نکن» و «داشبورد پایش
     * همهٔ لاگ‌ها ساخته نشود». aggregation و شمارش ذاتاً روی کل مجموعه کار
     * می‌کنند، پس از دروازه حذف شده‌اند — نه غیرفعال، بلکه *ناموجود*.
     */
    @Test
    @DisplayName("LogCollection هیچ متد پویش کل مجموعه ندارد")
    void logCollectionExposesNoFullScanMethod() {
        List<String> scanVerbs = List.of("aggregate", "sample", "count", "distinct",
                "watch", "mapreduce", "findall", "list");
        List<String> offenders = new ArrayList<>();
        for (var method : LogCollection.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            for (String verb : scanVerbs) {
                // listIndexes فراداده است، نه پویش داده
                if (name.startsWith(verb) && !name.equals("listindexes")) {
                    offenders.add(method.getName());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "دروازه نباید متد پویش کل مجموعه داشته باشد: " + offenders);
    }

    // -------------------------------------- قید «فقط یک find» در هر نمایش

    @Test
    @DisplayName("شمارندهٔ پرس‌وجو، ادعای «فقط یک find» را قابل بررسی می‌کند")
    void operationCounterCountsExactly() {
        OperationCounter.start();
        OperationCounter.record("findOne");
        OperationCounter.Result r = OperationCounter.stop();
        assertEquals(1, r.count(), "نمایش یک لاگ باید دقیقاً یک پرس‌وجو باشد");
        assertEquals(List.of("findOne"), r.operations());

        // بدون start، ضبط انجام نمی‌شود و چیزی نشت نمی‌کند
        OperationCounter.record("find");
        assertEquals(0, OperationCounter.stop().count());
    }

    @Test
    @DisplayName("شمارنده بین درخواست‌ها نشت نمی‌کند")
    void operationCounterDoesNotLeakBetweenRequests() {
        OperationCounter.start();
        OperationCounter.record("findOne");
        assertEquals(1, OperationCounter.stop().count());

        OperationCounter.start();
        assertEquals(0, OperationCounter.stop().count(),
                "درخواست بعدی باید از صفر شروع شود");
    }

    /**
     * پویش کد منبع: هیچ فایلی در src/main نباید API نوشتن MongoDB را صدا بزند.
     *
     * این تست چیزی را می‌سنجد که هیچ mockای نمی‌تواند: اینکه *هرگز* چنین کدی
     * نوشته نشده. اگر فردا کسی `insertOne` اضافه کند، اینجا شکست می‌خورد
     * حتی اگر آن مسیر هیچ‌وقت اجرا نشود.
     */
    @Test
    @DisplayName("هیچ فراخوانی نوشتن در کل کد منبع وجود ندارد")
    void noWriteApiCallAnywhereInSources() throws IOException {
        Path sourceRoot = findSourceRoot();
        assertTrue(sourceRoot != null && Files.isDirectory(sourceRoot),
                "ریشهٔ کد منبع پیدا نشد؛ این تست بدون آن بی‌معناست");

        List<String> forbidden = List.of(
                ".insertOne(", ".insertMany(", ".updateOne(", ".updateMany(",
                ".replaceOne(", ".deleteOne(", ".deleteMany(", ".bulkWrite(",
                ".findOneAndUpdate(", ".findOneAndReplace(", ".findOneAndDelete(",
                ".createIndex(", ".createIndexes(", ".dropIndex(", ".dropIndexes(",
                ".renameCollection(", ".drop()", "MongoTemplate", "$out", "$merge",
                // پویش کل مجموعه هم ممنوع است، نه فقط نوشتن
                ".aggregate(", ".countDocuments(", "$sample", "$lookup", "$graphLookup");

        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
                for (String token : forbidden) {
                    if (code.contains(token)) {
                        hits.add(sourceRoot.relativize(file) + " → " + token);
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "فراخوانی نوشتن در کد منبع پیدا شد: " + hits);
    }

    /**
     * pom.xml نباید spring-boot-starter-data-mongodb داشته باشد: MongoTemplate
     * متد نوشتن دارد و صرفِ بودنش روی classpath، تضمین زمان کامپایل را می‌شکند.
     */
    @Test
    @DisplayName("Spring Data MongoDB روی classpath نیست")
    void springDataMongoIsNotOnClasspath() throws IOException {
        Path pom = findUpwards("pom.xml");
        assertTrue(pom != null, "pom.xml پیدا نشد");
        String xml = Files.readString(pom, StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");   // توضیحات، که *دربارهٔ* این وابستگی حرف می‌زنند
        assertFalse(xml.contains("spring-boot-starter-data-mongodb"),
                "این وابستگی MongoTemplate را می‌آورد که متد نوشتن دارد");
        assertTrue(xml.contains("mongodb-driver-sync"),
                "درایور رسمی باید مستقیم استفاده شود");
    }

    // ------------------------------------------------------------- کمکی‌ها

    /** حذف توضیحات و رشته‌ها تا واژه‌ای داخل یک کامنت فارسی، تست را قرمز نکند */
    private static String stripCommentsAndStrings(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLine = noBlock.replaceAll("(?m)//.*$", " ");
        String noText = noLine.replaceAll("\"\"\"(?s).*?\"\"\"", "\" \"");
        return noText.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\" \"");
    }

    private static Path findSourceRoot() {
        for (String candidate : List.of("src/main/java", "backend/src/main/java",
                "../backend/src/main/java", "../src/main/java")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    private static Path findUpwards(String fileName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
