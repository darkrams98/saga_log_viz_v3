package com.citydi.logexplorer.parse;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigLoader;
import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.mask.MaskingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * تست‌های «ساختار غیرمنتظره».
 *
 * قرارداد اصلی پروژه این است: هیچ سندی، هرچقدر هم عجیب، نباید برنامه را بشکند.
 * این کلاس همان قرارداد را با بدترین ورودی‌هایی که به ذهن می‌رسد می‌سنجد.
 */
class GenericParsingTest {

    private LogRecordMapper mapper;
    private DocumentFlattener flattener;
    private ConfigProvider configProvider;

    /** ConfigProvider ساختگی که از یک AppConfig در حافظه تغذیه می‌شود */
    private static ConfigProvider providerOf(AppConfig config) {
        return new ConfigProvider("nonexistent.yaml") {
            @Override
            public AppConfig get() {
                return config;
            }
        };
    }

    private static AppConfig testConfig() {
        String yaml = """
                time:
                  candidates: [startDate, creationDate, "@timestamp", timestamp, ts,
                               "commandList[0].StartDate", "commandList[0].startDate"]
                  queryField: startDate
                  tieBreakField: _id
                fields:
                  id: { candidates: ["_id", "id"] }
                  message: { candidates: [message, msg, "commandList[*].rollbackDescription"] }
                  status: { candidates: [status, state] }
                  service: { candidates: [service, applicationName, _class], transform: shortClass }
                level:
                  precedence: ["derive", "field"]
                  candidates: [level, severity]
                  deriveFrom: [status]
                  map: { COMPLETED: INFO, ROLL_BACKED: ERROR, FAILED: ERROR }
                  default: INFO
                  errorLevels: [ERROR]
                transforms:
                  shortClass: { type: regexReplace, pattern: "^.*\\\\.", replacement: "" }
                columns:
                  - { key: time, label: "زمان", source: field, type: datetime }
                  - { key: level, label: "سطح", source: field, type: level }
                  - { key: message, label: "پیام", source: field, type: text }
                masking:
                  enabled: true
                  secretFields: [password, otp, token]
                  rules:
                    - { fields: [nationalCode], strategy: keepEdges, head: 3, tail: 2 }
                    - { fields: [mobile], strategy: mobileIR }
                display:
                  autoParseJsonStrings: true
                  hiddenPaths: ["_class"]
                limits:
                  maxFlattenNodes: 200
                  maxDepth: 6
                  largeValueBytes: 500
                  previewChars: 80
                """;
        return ConfigLoader.loadFromStream(
                new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @BeforeEach
    void setUp() {
        AppConfig config = testConfig();
        configProvider = providerOf(config);
        MaskingService masking = new MaskingService(configProvider);
        mapper = new LogRecordMapper(configProvider, masking);
        flattener = new DocumentFlattener(configProvider, masking);
    }

    private static Map<String, Object> doc(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------- ساختار خراب

    @Test
    @DisplayName("سند null و سند خالی، استثنا نمی‌دهند")
    void nullAndEmptyDocuments() {
        LogRecord fromNull = assertDoesNotThrow(() -> mapper.map(null));
        assertNotNull(fromNull);
        assertFalse(fromNull.warnings().isEmpty());

        LogRecord fromEmpty = assertDoesNotThrow(() -> mapper.map(Map.of()));
        assertNotNull(fromEmpty);
        assertNull(fromEmpty.time());
    }

    @Test
    @DisplayName("نوع اشتباه در هر فیلد: عدد به‌جای رشته، رشته به‌جای آرایه")
    void wrongTypesEverywhere() {
        Map<String, Object> d = doc(
                "_id", 12345,                      // عدد به‌جای رشته
                "status", 42,                      // عدد به‌جای وضعیت
                "message", List.of("a", "b"),      // آرایه به‌جای رشته
                "startDate", "این یک تاریخ نیست",
                "commandList", "رشته به‌جای آرایه");

        LogRecord record = assertDoesNotThrow(() -> mapper.map(d));
        assertEquals("12345", record.id(), "عدد باید به متن تبدیل شود");
        assertNull(record.time(), "تاریخ نامعتبر یعنی زمان نامشخص، نه استثنا");
        assertTrue(record.warnings().stream().anyMatch(w -> w.contains("زمان")));
    }

    @Test
    @DisplayName("آرایه‌ای پر از null و آرایهٔ تودرتو")
    void nullsInsideArrays() {
        Map<String, Object> d = doc(
                "_id", "x",
                "startDate", 1787050099053L,
                "commandList", java.util.Arrays.asList(null, null, doc("status", "ROLL_BACKED")),
                "matrix", List.of(List.of(1, 2), List.of(3, List.of(4, List.of(5)))));

        LogRecord record = assertDoesNotThrow(() -> mapper.map(d));
        assertNotNull(record.time());
        DocumentFlattener.FlattenResult flat = assertDoesNotThrow(() -> flattener.flatten(d));
        assertFalse(flat.nodes().isEmpty());
    }

    @Test
    @DisplayName("سند عمیق‌تر از سقف عمق، بریده می‌شود نه اینکه بشکند")
    void veryDeepDocument() {
        Map<String, Object> deepest = doc("bottom", "پایین‌ترین");
        Map<String, Object> node = deepest;
        for (int i = 0; i < 40; i++) {
            node = doc("level" + i, node);
        }
        node.put("_id", "deep");
        node.put("startDate", 1787050099053L);

        final Map<String, Object> d = node;
        DocumentFlattener.FlattenResult flat = assertDoesNotThrow(() -> flattener.flatten(d));
        assertFalse(flat.nodes().isEmpty());
        assertTrue(flat.truncated() || countNodes(flat.nodes()) <= 200,
                "درخت باید در بودجه بماند");
    }

    @Test
    @DisplayName("ارجاع حلقوی برنامه را در حلقهٔ بی‌نهایت نمی‌اندازد")
    void cyclicReference() {
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        a.put("_id", "cycle");
        a.put("startDate", 1787050099053L);
        a.put("child", b);
        b.put("parent", a);   // حلقه

        assertDoesNotThrow(() -> mapper.map(a));
        DocumentFlattener.FlattenResult flat = assertDoesNotThrow(() -> flattener.flatten(a));
        assertTrue(flat.truncated() || countNodes(flat.nodes()) <= 200,
                "بودجهٔ گره باید حلقه را متوقف کند");
    }

    @Test
    @DisplayName("فیلد کاملاً ناشناخته حذف نمی‌شود")
    void unknownFieldsSurvive() {
        Map<String, Object> d = doc(
                "_id", "u",
                "startDate", 1787050099053L,
                "someBrandNewField", "مقداری که هنگام توسعه وجود نداشت",
                "nested", doc("deeperUnknown", doc("evenDeeper", List.of(1, "two"))));

        List<String> paths = new ArrayList<>();
        collectPaths(flattener.flatten(d).nodes(), paths);

        assertTrue(paths.contains("someBrandNewField"));
        assertTrue(paths.stream().anyMatch(p -> p.contains("deeperUnknown")));
        assertTrue(paths.stream().anyMatch(p -> p.contains("evenDeeper")));
    }

    @Test
    @DisplayName("مسیرهای پیکربندی که در سند وجود ندارند، فقط «نامشخص» می‌دهند")
    void missingConfiguredPaths() {
        LogRecord record = mapper.map(doc("_id", "only-id"));
        assertEquals("only-id", record.id());
        assertNull(record.message());
        assertNull(record.status());
        assertNull(record.service());
    }

    // ------------------------------------------------------------- زمان

    @Test
    @DisplayName("زمان از هر پنج شکل ذخیره‌سازی خوانده می‌شود")
    void timeFromEveryRepresentation() {
        assertNotNull(mapper.map(doc("_id", "a", "startDate", 1787050099053L)).time(),
                "epoch میلی‌ثانیه");
        assertNotNull(mapper.map(doc("_id", "b", "startDate", 1787050099L)).time(),
                "epoch ثانیه");
        assertNotNull(mapper.map(doc("_id", "c", "startDate", "2026-08-24T09:16:59.001Z")).time(),
                "رشتهٔ ISO");
        assertNotNull(mapper.map(doc("_id", "d", "startDate",
                new java.util.Date(1787050099053L))).time(), "java.util.Date (BSON Date)");
        assertNotNull(mapper.map(doc("_id", "e", "startDate",
                Map.of("$date", "2026-08-24T09:16:59Z"))).time(), "Extended JSON");
    }

    @Test
    @DisplayName("ترتیب کاندیدهای زمان رعایت می‌شود و منبع گزارش می‌شود")
    void timeCandidateOrderAndSource() {
        LogRecord record = mapper.map(doc(
                "_id", "t",
                "creationDate", 1700000000000L,
                "startDate", 1787050099053L));
        assertEquals("startDate", record.timeSource(), "startDate اولویت بالاتری دارد");

        LogRecord fallback = mapper.map(doc("_id", "t2", "creationDate", 1787050099053L));
        assertEquals("creationDate", fallback.timeSource());
    }

    @Test
    @DisplayName("زمان از داخل آرایهٔ مراحل هم پیدا می‌شود")
    void timeFromNestedArray() {
        LogRecord record = mapper.map(doc(
                "_id", "n",
                "commandList", List.of(doc("StartDate", "2026-08-24T09:16:59.001Z"))));
        assertNotNull(record.time());
        assertEquals("commandList[0].StartDate", record.timeSource());
    }

    // -------------------------------------------------------------- سطح

    @Test
    @DisplayName("سطح از وضعیت استنتاج می‌شود وقتی فیلد level وجود ندارد")
    void levelDerivedFromStatus() {
        assertEquals("ERROR", mapper.map(doc("_id", "1", "status", "ROLL_BACKED")).level());
        assertEquals("INFO", mapper.map(doc("_id", "2", "status", "COMPLETED")).level());
        assertEquals("INFO", mapper.map(doc("_id", "3")).level(), "پیش‌فرض");
    }

    @Test
    @DisplayName("با precedence=derive، فیلد levelِ گمراه‌کننده نادیده گرفته می‌شود")
    void derivePrecedenceBeatsMisleadingLevelField() {
        // این دقیقاً حالت لاگ‌های قدیمی است: level=INFO ولی status=ROLL_BACKED
        LogRecord record = mapper.map(doc("_id", "x", "level", "INFO", "status", "ROLL_BACKED"));
        assertEquals("ERROR", record.level());
        assertTrue(record.error(),
                "اگر اینجا ERROR نشود، فیلتر «فقط خطاها» و جدول با هم اختلاف پیدا می‌کنند");
    }

    // --------------------------------------------------------- JSON تودرتو

    @Test
    @DisplayName("رشتهٔ JSON خودکار باز می‌شود و «از JSON» علامت می‌خورد")
    void jsonStringExpanded() {
        Map<String, Object> d = doc("_id", "j", "startDate", 1787050099053L,
                "payload", "{\"customer\":{\"id\":\"abc\"},\"items\":[1,2,3]}");

        List<FieldNode> nodes = flattener.flatten(d).nodes();
        FieldNode payload = find(nodes, "payload");
        assertNotNull(payload);
        assertEquals("json-string", payload.type());
        assertFalse(payload.children().isEmpty(), "باید باز شده باشد");
        assertTrue(payload.children().stream().anyMatch(c -> c.parsedFromJson()));
    }

    @Test
    @DisplayName("رشتهٔ JSON خراب به‌عنوان متن ساده می‌ماند")
    void brokenJsonStaysText() {
        Map<String, Object> d = doc("_id", "b", "payload", "{\"unclosed\": \"json");
        FieldNode payload = find(flattener.flatten(d).nodes(), "payload");
        assertNotNull(payload);
        assertEquals("string", payload.type());
    }

    @Test
    @DisplayName("مسیر می‌تواند از داخل رشتهٔ JSON عبور کند")
    void pathThroughJsonString() {
        Map<String, Object> d = doc("payload", "{\"customer\":{\"nationalCode\":\"1273368304\"}}");
        Object value = PathResolver.first(d, "payload#json.customer.nationalCode");
        assertEquals("1273368304", value);

        // بدون #json هم باید کار کند (تشخیص خودکار)
        assertEquals("1273368304", PathResolver.first(d, "payload.customer.nationalCode"));
    }

    // -------------------------------------------------------- حجم زیاد

    @Test
    @DisplayName("رشتهٔ بزرگ «سنگین» علامت می‌خورد و کامل رندر نمی‌شود")
    void hugeStringIsMarkedHeavy() {
        Map<String, Object> d = doc("_id", "h", "blob", "x".repeat(300_000));
        FieldNode blob = find(flattener.flatten(d).nodes(), "blob");
        assertNotNull(blob);
        assertTrue(blob.heavy());
        assertTrue(blob.truncated());
        assertTrue(blob.value().length() < 200, "فقط پیش‌نمایش کوتاه");
        assertTrue(blob.sizeBytes() > 100_000, "حجم واقعی گزارش می‌شود");
    }

    @Test
    @DisplayName("آرایهٔ خیلی بزرگ بودجهٔ گره را رعایت می‌کند")
    void hugeArrayRespectsBudget() {
        List<Object> big = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            big.add(doc("index", i, "value", "مقدار " + i));
        }
        Map<String, Object> d = doc("_id", "big", "items", big);
        DocumentFlattener.FlattenResult flat = flattener.flatten(d);
        assertTrue(countNodes(flat.nodes()) <= 210, "سقف گره باید رعایت شود");
        assertTrue(flat.truncated());
    }

    // -------------------------------------------------------- مسیرها

    @Test
    @DisplayName("نحو مسیر: تودرتو، اندیس، wildcard، و آرایهٔ ضمنی")
    void pathSyntax() {
        Map<String, Object> d = doc("commandList", List.of(
                doc("status", "COMPLETED", "id", "a"),
                doc("status", "ROLL_BACKED", "id", "b")));

        assertEquals("COMPLETED", PathResolver.first(d, "commandList[0].status"));
        assertEquals("ROLL_BACKED", PathResolver.first(d, "commandList[1].status"));
        assertEquals(2, PathResolver.resolve(d, "commandList[*].status").size());
        assertEquals(2, PathResolver.resolve(d, "commandList.status").size(),
                "بدون [] هم باید روی همهٔ عناصر اعمال شود، مثل MongoDB");
        assertTrue(PathResolver.resolve(d, "commandList[9].status").isEmpty(),
                "اندیس خارج از محدوده = خالی، نه استثنا");
        assertTrue(PathResolver.resolve(d, "doesNotExist.at.all").isEmpty());
    }

    @Test
    @DisplayName("مسیر به فرم MongoDB تبدیل می‌شود (wildcard حذف می‌شود)")
    void mongoPathConversion() {
        assertEquals("commandList.status",
                PathResolver.compile("commandList[*].status").toMongoPath());
        assertEquals("a.b.c", PathResolver.compile("a.b.c").toMongoPath());
        assertNull(PathResolver.compile("payload#json.x").toMongoPath(),
                "مسیر عبوری از JSON در MongoDB قابل بیان نیست");
    }

    @Test
    @DisplayName("مسیر خراب یا خالی، استثنا نمی‌دهد")
    void malformedPaths() {
        Map<String, Object> d = doc("a", "b");
        assertDoesNotThrow(() -> PathResolver.resolve(d, "a[unclosed"));
        assertDoesNotThrow(() -> PathResolver.resolve(d, "..."));
        assertDoesNotThrow(() -> PathResolver.resolve(d, ""));
        assertDoesNotThrow(() -> PathResolver.resolve(d, null));
    }

    // ------------------------------------------------------------- utils

    private static FieldNode find(List<FieldNode> nodes, String key) {
        for (FieldNode n : nodes) {
            if (key.equals(n.key())) {
                return n;
            }
        }
        return null;
    }

    private static int countNodes(List<FieldNode> nodes) {
        int total = 0;
        for (FieldNode n : nodes) {
            total += 1 + countNodes(n.children() == null ? List.of() : n.children());
        }
        return total;
    }

    private static void collectPaths(List<FieldNode> nodes, List<String> out) {
        for (FieldNode n : nodes) {
            out.add(n.path());
            collectPaths(n.children() == null ? List.of() : n.children(), out);
        }
    }
}
