package com.citydi.logexplorer.flow;

import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.labels.LabelConfig;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import com.citydi.logexplorer.labels.LabelResolver;
import com.citydi.logexplorer.mask.MaskingService;
import com.citydi.logexplorer.parse.JsonStrings;
import com.citydi.logexplorer.parse.PathResolver;
import com.citydi.logexplorer.parse.TypeCoercion;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ساخت گراف جریان اجرا از **همان سندی که یک بار خوانده شده**.
 *
 * هیچ کوئری اضافه‌ای اینجا زده نمی‌شود؛ ورودی این کلاس سند در حافظه است.
 * این عمدی است: قرارداد پروژه می‌گوید بعد از یافتن لاگ با شناسه، هیچ
 * پرس‌وجوی دومی به MongoDB نمی‌رود.
 *
 * قرارداد دوم: این متد هرگز پرتاب نمی‌کند. اگر commandList نبود، آرایه نبود،
 * یا عناصرش شیء نبودند، گراف خالی با یک یادداشت برمی‌گردد — نه استثنا.
 */
@Component
public class FlowGraphBuilder {

    private static final int MAX_STEPS = 200;
    private static final int DETAIL_PREVIEW_CHARS = 20_000;

    private final LabelConfigProvider labels;
    private final LabelResolver resolver;
    private final ConfigProvider config;
    private final MaskingService masking;

    public FlowGraphBuilder(LabelConfigProvider labels, LabelResolver resolver,
                            ConfigProvider config, MaskingService masking) {
        this.labels = labels;
        this.resolver = resolver;
        this.config = config;
        this.masking = masking;
    }

    public FlowGraph build(Map<String, Object> document) {
        LabelConfig.GraphConfig g = labels.get().graph;
        List<String> notes = new ArrayList<>();

        if (document == null || document.isEmpty()) {
            return FlowGraph.empty(g.layout(), "سند خالی است.");
        }

        List<Object> steps = readSteps(document, g, notes);
        if (steps.isEmpty()) {
            return FlowGraph.empty(g.layout(), notes.isEmpty()
                    ? "این لاگ هیچ مرحله‌ای در «" + g.source() + "» ندارد."
                    : String.join(" ", notes));
        }

        List<FlowGraph.Node> nodes = new ArrayList<>(steps.size() + 2);
        List<FlowGraph.Edge> edges = new ArrayList<>(steps.size() + 1);

        if (g.showStartEnd()) {
            nodes.add(marker("start", g.startLabel()));
        }

        int success = 0;
        int error = 0;
        int unknown = 0;
        int failedIndex = -1;
        String failedNodeId = null;
        String failedService = null;
        String failedError = null;

        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = asMap(steps.get(i));
            if (step == null) {
                notes.add("مرحلهٔ " + (i + 1) + " شیء نبود و نمایش داده نشد.");
                continue;
            }
            FlowGraph.Node node = toNode(i, step, g);
            nodes.add(node);

            switch (node.severity()) {
                case "success" -> success++;
                case "error" -> {
                    error++;
                    if (failedIndex < 0) {
                        failedIndex = i;
                        failedNodeId = node.id();
                        failedService = node.service();
                        failedError = node.errorText();
                    }
                }
                default -> unknown++;
            }
        }

        // یال‌ها: ترتیب اجرا. گره‌هایی که رد شدند (شیء نبودند) در زنجیره نیستند.
        List<FlowGraph.Node> chain = nodes.stream().filter(n -> "step".equals(n.kind())).toList();
        String previous = g.showStartEnd() ? "start" : null;
        for (FlowGraph.Node n : chain) {
            if (previous != null) {
                edges.add(new FlowGraph.Edge(previous, n.id(), null));
            }
            previous = n.id();
        }
        if (g.showStartEnd() && previous != null) {
            nodes.add(marker("end", g.endLabel()));
            edges.add(new FlowGraph.Edge(previous, "end", null));
        }

        String overall = TypeCoercion.toText(document.get("status"));
        LabelResolver.Label overallLabel = resolver.status(overall);
        String overallSeverity = error > 0 ? "error" : resolver.severity(overall);

        if (error > 0 && !"error".equals(resolver.severity(overall))) {
            // اختلاف بین وضعیت کلی و مراحل — پشتیبان باید بداند
            notes.add("وضعیت کلی لاگ «" + overallLabel.value() + "» است ولی "
                    + error + " مرحله ناموفق بوده. مرحله‌ها معتبرترند.");
        }

        FlowGraph.Summary summary = new FlowGraph.Summary(chain.size(), success, error, unknown,
                failedIndex, failedNodeId, failedService, failedError,
                overallLabel.value(), overallSeverity);

        return new FlowGraph(List.copyOf(nodes), List.copyOf(edges), g.layout(), summary,
                List.copyOf(notes));
    }

    // ------------------------------------------------------------- گره‌ها

    private FlowGraph.Node toNode(int index, Map<String, Object> step, LabelConfig.GraphConfig g) {
        String routingKey = text(step.get(g.nodeLabelFrom()));
        String commandType = text(step.get(g.nodeSubLabelFrom()));
        String status = text(step.get(g.statusFrom()));
        String rawTitle = text(step.get("title"));

        LabelResolver.Label service = resolver.service(routingKey);
        LabelResolver.Label type = resolver.commandType(commandType);
        LabelResolver.Label statusLabel = resolver.status(status);
        LabelResolver.Label title = resolver.title(rawTitle, commandType);
        String severity = resolver.severity(status);

        String errorText = null;
        for (String path : g.errorTextFrom()) {
            String candidate = text(step.get(path));
            if (candidate != null && !candidate.isBlank()) {
                errorText = masking.maskFreeText(candidate);
                break;
            }
        }

        Map<String, FlowGraph.DetailValue> detail = new LinkedHashMap<>();
        boolean truncated = false;
        for (String field : g.detailFields()) {
            Object raw = step.get(field);
            if (TypeCoercion.isEmpty(raw)) {
                continue;
            }
            FlowGraph.DetailValue dv = detailValue(field, raw);
            detail.put(field, dv);
            truncated |= dv.truncated();
        }

        Instant started = TypeCoercion.toInstant(
                firstNonNull(step.get("StartDate"), step.get("startDate")),
                config.get().time.stringFormats());

        return new FlowGraph.Node(
                "s" + index, "step", index,
                service.value(), routingKey, service.source(),
                title.value(), rawTitle,
                type.value(), commandType,
                statusLabel.value(), status,
                severity, errorText,
                started == null ? null : started.toString(),
                Map.copyOf(detail), truncated);
    }

    /**
     * یک فیلد از پنل جزئیات.
     *
     * commandContent و response رشته‌های JSON چندکیلوبایتی‌اند. اگر خام
     * نشان داده شوند، غیرقابل خواندن‌اند؛ پس اگر JSON معتبر بودند، نسخهٔ
     * مرتب‌شده هم همراهشان می‌رود تا UI بتواند زیبا نمایش دهد.
     */
    private FlowGraph.DetailValue detailValue(String field, Object raw) {
        String type = TypeCoercion.typeName(raw);
        long size = JsonStrings.estimateSize(raw, 6);

        Object masked = masking.maskObject(raw, field);
        String text = TypeCoercion.toText(masked, DETAIL_PREVIEW_CHARS);
        boolean truncated = text != null && size > DETAIL_PREVIEW_CHARS;

        String pretty = null;
        if (masked instanceof String s) {
            Object parsed = JsonStrings.tryParse(s);
            if (parsed != null) {
                pretty = JsonStrings.writePretty(parsed);
                type = "json-string";
            }
        } else if (masked instanceof Map<?, ?> || masked instanceof List<?>) {
            pretty = JsonStrings.writePretty(masked);
        }
        if (pretty != null && pretty.length() > DETAIL_PREVIEW_CHARS) {
            pretty = pretty.substring(0, DETAIL_PREVIEW_CHARS);
            truncated = true;
        }
        return new FlowGraph.DetailValue(resolver.field(field), text, pretty, type, size, truncated);
    }

    private static FlowGraph.Node marker(String id, String label) {
        return new FlowGraph.Node(id, id, -1, label, null, "exact", label, null,
                null, null, null, null, "marker", null, null, Map.of(), false);
    }

    // ------------------------------------------------------------- کمکی

    private List<Object> readSteps(Map<String, Object> document, LabelConfig.GraphConfig g,
                                   List<String> notes) {
        Object raw;
        try {
            raw = PathResolver.first(document, g.source());
        } catch (Exception e) {
            notes.add("خواندن مسیر «" + g.source() + "» ناموفق بود.");
            return List.of();
        }
        if (raw == null) {
            notes.add("فیلد «" + g.source() + "» در این لاگ وجود ندارد.");
            return List.of();
        }
        if (raw instanceof String s) {
            // بعضی صادرکننده‌ها آرایه را به‌صورت رشتهٔ JSON می‌نویسند
            Object parsed = JsonStrings.tryParse(s);
            if (parsed instanceof List<?>) {
                raw = parsed;
            }
        }
        if (!(raw instanceof List<?> list)) {
            notes.add("فیلد «" + g.source() + "» آرایه نیست (" + TypeCoercion.typeName(raw)
                    + ")؛ گراف قابل رسم نیست، ولی نمای جدولی و JSON خام کامل‌اند.");
            return List.of();
        }
        List<Object> out = new ArrayList<>(Math.min(list.size(), MAX_STEPS));
        for (Object o : list) {
            if (out.size() >= MAX_STEPS) {
                notes.add("این لاگ بیش از " + MAX_STEPS + " مرحله دارد؛ فقط "
                        + MAX_STEPS + " مرحلهٔ اول رسم شد.");
                break;
            }
            out.add(o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (o instanceof String s) {
            Map<String, Object> parsed = JsonStrings.tryParseObject(s);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static String text(Object value) {
        return TypeCoercion.isEmpty(value) ? null : TypeCoercion.toText(value, 400);
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    /** پیکربندی گراف جاری (رنگ‌ها و چیدمان به UI می‌رود) */
    public LabelConfig.GraphConfig graphConfig() {
        return labels.get().graph;
    }
}
