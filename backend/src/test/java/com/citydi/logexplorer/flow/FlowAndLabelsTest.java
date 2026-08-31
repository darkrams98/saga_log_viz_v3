package com.citydi.logexplorer.flow;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigLoader;
import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.labels.LabelConfig;
import com.citydi.logexplorer.labels.LabelConfigLoader;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import com.citydi.logexplorer.labels.LabelResolver;
import com.citydi.logexplorer.mask.MaskingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * برچسب فارسی و گراف جریان — دو چیزی که پشتیبان مستقیماً می‌بیند.
 *
 * تمرکز این کلاس روی همان قراردادی است که کل پروژه روی آن بنا شده:
 * سند ممکن است هر شکلی داشته باشد و config ممکن است ناقص باشد؛
 * خروجی باید همیشه چیزی *قابل استفاده* باشد، نه استثنا و نه «نامشخص».
 */
class FlowAndLabelsTest {

    private LabelResolver resolver;
    private FlowGraphBuilder builder;

    private static final String LABELS_JSON = """
            {
              "_comment": "کلیدهای زیرخط‌دار باید نادیده گرفته شوند",
              "routingKeys": {
                "orchestration25.profile.main": "پروفایل — اصلی",
                "rabbitmq.yaghoot25.client.deposit.routing.key": "سپرده"
              },
              "routingKeyPatterns": [
                { "match": "\\\\.deposit\\\\.", "label": "سپرده" },
                { "match": "[unclosed", "label": "الگوی خراب" },
                { "match": "\\\\.loan\\\\.", "label": "تسهیلات" }
              ],
              "commandTypes": { "GET_CARD_DEPOSIT_LIST": "دریافت فهرست کارت و سپرده" },
              "statuses": { "COMPLETED": "موفق", "ROLL_BACKED": "بازگشت خورده" },
              "statusSeverity": { "COMPLETED": "success", "ROLL_BACKED": "error" },
              "titles": { "SEQ__GET_CARD_DEPOSIT_LIST": "دریافت فهرست کارت و سپرده" },
              "fieldLabels": {
                "commandList.rollbackDescription": "شرح خطا / بازگشت",
                "commandList.response": "خروجی پاسخ"
              },
              "graph": {
                "source": "commandList",
                "detailFields": ["title", "commandType", "response", "rollbackDescription"],
                "errorTextFrom": ["rollbackDescription"]
              },
              "privacy": { "maskingProfile": "secretsOnly" }
            }
            """;

    private static final String YAML = """
            time:
              candidates: [startDate]
              queryField: startDate
            transforms:
              stripTrailingTimestamp:
                type: regexReplace
                pattern: "_{1,2}\\\\d{4}-\\\\d{2}-\\\\d{2}[_ T]\\\\d{2}[:.]\\\\d{2}[:.]\\\\d{2}.*$"
                replacement: ""
              stripTrailingIdentifier:
                type: regexReplace
                pattern: "_[A-Za-z0-9.]*[0-9][A-Za-z0-9.]*$"
                replacement: ""
              stripTrailingUnderscore:
                type: regexReplace
                pattern: "_+$"
                replacement: ""
              normalizeTitle:
                type: chain
                steps: [stripTrailingTimestamp, stripTrailingIdentifier, stripTrailingUnderscore]
            masking:
              enabled: true
              secretFields: [password, otp, token]
            """;

    @BeforeEach
    void setUp() {
        AppConfig appConfig = ConfigLoader.loadFromStream(
                new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)));
        LabelConfig labelConfig = LabelConfigLoader.loadFromStream(
                new ByteArrayInputStream(LABELS_JSON.getBytes(StandardCharsets.UTF_8)));

        ConfigProvider configProvider = new ConfigProvider("nonexistent.yaml") {
            @Override
            public AppConfig get() {
                return appConfig;
            }
        };
        LabelConfigProvider labelProvider = new LabelConfigProvider("nonexistent.json") {
            @Override
            public LabelConfig get() {
                return labelConfig;
            }
        };

        resolver = new LabelResolver(labelProvider, configProvider);
        builder = new FlowGraphBuilder(labelProvider, resolver, configProvider,
                new MaskingService(configProvider, labelProvider));
    }

    // ----------------------------------------------------- زنجیرهٔ برچسب

    @Test
    @DisplayName("زنجیرهٔ ترجمهٔ میکروسرویس: دقیق → الگو → خام")
    void serviceLabelFallbackChain() {
        assertEquals("پروفایل — اصلی", resolver.service("orchestration25.profile.main").value());
        assertEquals("exact", resolver.service("orchestration25.profile.main").source());

        // نسخهٔ آینده: کلید دقیق نیست ولی الگو می‌گیردش
        LabelResolver.Label future = resolver.service("rabbitmq.yaghoot99.client.deposit.routing.key");
        assertEquals("سپرده", future.value());
        assertEquals("pattern", future.source());

        // ناشناخته: مقدار خام، نه «نامشخص» — پشتیبان باید بتواند در ELK دنبالش بگردد
        LabelResolver.Label unknown = resolver.service("brand.new.service.key");
        assertEquals("brand.new.service.key", unknown.value());
        assertEquals("fallback", unknown.source());
        assertFalse(unknown.translated());
    }

    @Test
    @DisplayName("الگوی regex خراب فقط خودش نادیده گرفته می‌شود، نه بقیه")
    void brokenPatternDoesNotBreakTheRest() {
        // "[unclosed" در پیکربندی نامعتبر است؛ الگوی بعدی باید همچنان کار کند
        assertEquals("تسهیلات",
                resolver.service("orchestration25.loan.service.routing.key").value());
    }

    @Test
    @DisplayName("عنوانِ مهرزمانی‌دار پس از نرمال‌سازی ترجمه می‌شود")
    void titleIsNormalisedBeforeLookup() {
        LabelResolver.Label label =
                resolver.title("SEQ__GET_CARD_DEPOSIT_LIST__2026-08-24_12:46:59", null);
        assertEquals("دریافت فهرست کارت و سپرده", label.value());
        assertEquals("normalized", label.source());
    }

    @Test
    @DisplayName("اگر عنوان ترجمه نشد، نوع دستور جایگزین می‌شود")
    void titleFallsBackToCommandType() {
        assertEquals("دریافت فهرست کارت و سپرده",
                resolver.title("SOMETHING_NOBODY_MAPPED", "GET_CARD_DEPOSIT_LIST").value());
    }

    @Test
    @DisplayName("وضعیت ناشناخته «unknown» است، نه «موفق»")
    void unknownStatusIsNeverAssumedSuccessful() {
        assertEquals("success", resolver.severity("COMPLETED"));
        assertEquals("error", resolver.severity("ROLL_BACKED"));
        assertEquals("unknown", resolver.severity("SOME_NEW_STATUS"));
        assertEquals("unknown", resolver.severity(null));
    }

    @Test
    @DisplayName("برچسب فیلد از آخرین بخش مسیر هم پیدا می‌شود")
    void fieldLabelMatchesByLeafSegment() {
        assertEquals("شرح خطا / بازگشت", resolver.field("commandList[2].rollbackDescription"));
        assertEquals("شرح خطا / بازگشت", resolver.field("rollbackDescription"));
        assertEquals("خروجی پاسخ", resolver.field("commandList.0.response"));
        assertEquals("unknownField", resolver.field("unknownField"));
    }

    @Test
    @DisplayName("کلیدهای توضیحیِ «_» در config.json نادیده گرفته می‌شوند")
    void underscoreKeysAreComments() {
        LabelConfig config = LabelConfigLoader.loadFromStream(
                new ByteArrayInputStream(LABELS_JSON.getBytes(StandardCharsets.UTF_8)));
        assertFalse(config.routingKeys.containsKey("_comment"));
        assertEquals(2, config.routingKeys.size());
    }

    // ------------------------------------------------------------- گراف

    @Test
    @DisplayName("گراف از یک زنجیرهٔ سالم، گره و یال درست می‌سازد")
    void healthyChainProducesOrderedGraph() {
        FlowGraph graph = builder.build(doc(
                step("orchestration25.profile.main", "GET_CARD_DEPOSIT_LIST", "COMPLETED", null),
                step("rabbitmq.yaghoot25.client.deposit.routing.key", "X", "COMPLETED", null)));

        List<FlowGraph.Node> steps = graph.nodes().stream()
                .filter(n -> "step".equals(n.kind())).toList();
        assertEquals(2, steps.size());
        assertEquals("پروفایل — اصلی", steps.get(0).service());
        assertEquals("سپرده", steps.get(1).service());

        // start → s0 → s1 → end
        assertEquals(3, graph.edges().size());
        assertEquals("start", graph.edges().get(0).from());
        assertEquals("end", graph.edges().get(2).to());
        assertEquals("s0", graph.edges().get(0).to());
        assertEquals("s1", graph.edges().get(1).to());
        assertEquals(2, graph.summary().successCount());
        assertEquals(-1, graph.summary().failedIndex());
    }

    @Test
    @DisplayName("اولین مرحلهٔ ناموفق شناسایی و خطایش استخراج می‌شود")
    void firstFailedStepIsIdentified() {
        FlowGraph graph = builder.build(doc(
                step("a", "X", "COMPLETED", null),
                step("orchestration25.profile.main", "Y", "ROLL_BACKED", "ValidatorWSException"),
                step("b", "Z", "ROLL_BACKED", "SecondError")));

        FlowGraph.Summary s = graph.summary();
        assertEquals(1, s.failedIndex());
        assertEquals("s1", s.failedNodeId());
        assertEquals("پروفایل — اصلی", s.failedService());
        assertEquals("ValidatorWSException", s.failedErrorText());
        assertEquals(2, s.errorCount());
        assertEquals("error", s.overallSeverity());
    }

    @Test
    @DisplayName("اختلاف وضعیت کلی با وضعیت مراحل صریحاً گزارش می‌شود")
    void disagreementBetweenOverallAndStepsIsReported() {
        Map<String, Object> document = doc(step("a", "X", "ROLL_BACKED", "boom"));
        document.put("status", "COMPLETED");   // ادعای موفقیت، ولی مرحله شکست خورده

        FlowGraph graph = builder.build(document);
        assertEquals("error", graph.summary().overallSeverity(),
                "مرحله‌ها معتبرتر از وضعیت کلی‌اند");
        assertTrue(graph.notes().stream().anyMatch(n -> n.contains("مرحله ناموفق")));
    }

    // ------------------------------------------- ساختارهای غیرمنتظره

    @Test
    @DisplayName("نبودِ commandList → گراف خالی با توضیح، نه استثنا")
    void missingCommandListIsExplained() {
        FlowGraph graph = assertDoesNotThrow(() -> builder.build(Map.of("_id", "x")));
        assertTrue(graph.nodes().isEmpty());
        assertFalse(graph.notes().isEmpty());
        assertEquals(0, graph.summary().stepCount());
    }

    @Test
    @DisplayName("commandList غیرآرایه → توضیح می‌دهد که کدام نما هنوز کامل است")
    void nonArrayCommandListIsExplained() {
        FlowGraph graph = assertDoesNotThrow(() -> builder.build(
                Map.of("_id", "x", "commandList", "این آرایه نیست")));
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.notes().get(0).contains("آرایه نیست"));
        assertTrue(graph.notes().get(0).contains("جدولی"),
                "کاربر باید بداند کجا هنوز می‌تواند داده را ببیند");
    }

    @Test
    @DisplayName("عناصر خرابِ داخل آرایه رد می‌شوند و بقیه رسم می‌شوند")
    void brokenElementsAreSkipped() {
        Map<String, Object> document = new LinkedHashMap<>();
        List<Object> steps = new ArrayList<>();
        steps.add(step("a", "X", "COMPLETED", null));
        steps.add("یک رشته، نه شیء");
        steps.add(null);
        steps.add(42);
        steps.add(step("b", "Y", "ROLL_BACKED", "boom"));
        document.put("commandList", steps);

        FlowGraph graph = builder.build(document);
        assertEquals(2, graph.summary().stepCount());
        assertTrue(graph.notes().size() >= 3, "هر عنصر ردشده باید توضیح داشته باشد");
        assertEquals(1, graph.summary().errorCount());
        assertEquals(1, graph.summary().successCount());
    }

    @Test
    @DisplayName("commandList به‌صورت رشتهٔ JSON هم پذیرفته می‌شود")
    void jsonStringCommandListIsParsed() {
        FlowGraph graph = builder.build(Map.of("_id", "x", "commandList",
                "[{\"routingKey\":\"orchestration25.profile.main\",\"status\":\"COMPLETED\"}]"));
        assertEquals(1, graph.summary().stepCount());
        assertEquals("پروفایل — اصلی", graph.nodes().stream()
                .filter(n -> "step".equals(n.kind())).findFirst().orElseThrow().service());
    }

    @Test
    @DisplayName("زنجیرهٔ خیلی بلند سقف می‌خورد و کاربر مطلع می‌شود")
    void veryLongChainIsCapped() {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            many.add(step("svc" + i, "X", "COMPLETED", null));
        }
        FlowGraph graph = builder.build(Map.of("_id", "x", "commandList", many));
        assertEquals(200, graph.summary().stepCount());
        assertTrue(graph.notes().stream().anyMatch(n -> n.contains("۲۰۰") || n.contains("200")));
    }

    @Test
    @DisplayName("سند خالی یا null، گراف خالی می‌دهد")
    void emptyDocumentIsSafe() {
        assertDoesNotThrow(() -> builder.build(null));
        assertDoesNotThrow(() -> builder.build(Map.of()));
        assertEquals(0, builder.build(null).summary().stepCount());
    }

    @Test
    @DisplayName("رمز داخل جزئیات مرحله بیرون نمی‌آید")
    void secretsInsideStepDetailAreRemoved() {
        Map<String, Object> stepWithSecret = step("a", "X", "COMPLETED", null);
        stepWithSecret.put("response", "{\"password\":\"hunter2\",\"ok\":true}");

        FlowGraph graph = builder.build(Map.of("_id", "x", "commandList", List.of(stepWithSecret)));
        FlowGraph.Node node = graph.nodes().stream()
                .filter(n -> "step".equals(n.kind())).findFirst().orElseThrow();
        FlowGraph.DetailValue response = node.detail().get("response");
        assertNotNull(response);
        assertFalse(response.value().contains("hunter2"),
                "رمز در هیچ پروفایلی نباید به مرورگر برسد");
    }

    // ------------------------------------------------------------- کمکی

    private static Map<String, Object> doc(Object... steps) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_id", "test");
        out.put("commandList", List.of(steps));
        return out;
    }

    private static Map<String, Object> step(String routingKey, String type, String status,
                                            String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("routingKey", routingKey);
        out.put("commandType", type);
        out.put("status", status);
        out.put("title", type + "_TASK");
        if (error != null) {
            out.put("rollbackDescription", error);
        }
        return out;
    }
}
