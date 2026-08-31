package com.citydi.logexplorer.service;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.flow.FlowGraph;
import com.citydi.logexplorer.flow.FlowGraphBuilder;
import com.citydi.logexplorer.labels.LabelConfig;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import com.citydi.logexplorer.labels.LabelResolver;
import com.citydi.logexplorer.mask.MaskingService;
import com.citydi.logexplorer.mongo.LogCollection;
import com.citydi.logexplorer.parse.DocumentFlattener;
import com.citydi.logexplorer.parse.FieldNode;
import com.citydi.logexplorer.parse.JsonStrings;
import com.citydi.logexplorer.parse.PathResolver;
import com.citydi.logexplorer.parse.TypeCoercion;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * یافتن و آماده‌سازی **یک** لاگ.
 *
 * قید مرکزی این کلاس، که همه‌جا رعایت می‌شود:
 *
 *   یافتن لاگ = دقیقاً یک findOne. تمام.
 *   گراف، جدول و JSON خام همگی از همان سند در حافظه ساخته می‌شوند.
 *   هیچ lookup، هیچ aggregate، هیچ کوئری دوم.
 *
 * برای اینکه این ادعا قابل بررسی باشد، تعداد واقعی پرس‌وجوها در پاسخ
 * برمی‌گردد (`mongoOperations`) — نه به‌عنوان تزیین، بلکه چون ادعای
 * بررسی‌نشدنی ارزش ندارد.
 */
@Service
public class LogLookupService {

    private static final Logger log = LoggerFactory.getLogger(LogLookupService.class);

    private final LogCollection collection;
    private final ConfigProvider config;
    private final LabelConfigProvider labels;
    private final LabelResolver resolver;
    private final FlowGraphBuilder graphBuilder;
    private final DocumentFlattener flattener;
    private final MaskingService masking;

    public LogLookupService(LogCollection collection, ConfigProvider config,
                            LabelConfigProvider labels, LabelResolver resolver,
                            FlowGraphBuilder graphBuilder, DocumentFlattener flattener,
                            MaskingService masking) {
        this.collection = collection;
        this.config = config;
        this.labels = labels;
        this.resolver = resolver;
        this.graphBuilder = graphBuilder;
        this.flattener = flattener;
        this.masking = masking;
    }

    // ------------------------------------------------------------ مدل‌ها

    /**
     * @param path  مسیر کامل فیلد — برای کپی و برای جستجوی همان مقدار در ELK
     * @param label برچسب فارسی اگر در config.json تعریف شده باشد
     */
    public record TableRow(String path, String label, String key, String type, String value,
                           int depth, boolean masked, boolean truncated, long sizeBytes,
                           boolean container, Integer childCount) {
    }

    public record SummaryItem(String path, String label, String value, String rawValue,
                              String type, boolean copy, boolean translated) {
    }

    public record LogView(String id, SummaryHeader header, List<SummaryItem> summary,
                          FlowGraph graph, List<TableRow> table, boolean tableTruncated,
                          String rawJson, long rawSizeBytes, String maskingProfile,
                          List<String> warnings) {
    }

    public record SummaryHeader(String title, String rawTitle, String status, String rawStatus,
                                String severity, String startedAt, String completedAt,
                                String durationText, int stepCount, int errorCount) {
    }

    public record SearchHit(String id, Map<String, String> fields) {
    }

    public record SearchResult(List<SearchHit> hits, boolean capped, int limit,
                               List<LabelConfig.ResultField> columns, List<String> notes) {
    }

    // ------------------------------------------------- جستجوی عادی (سریع)

    /**
     * جستجوی عادی: فقط روی فیلدی که در config.json هم `enabled` است و هم
     * `indexed`. هر چیز دیگری با استثنا رد می‌شود.
     *
     * دلیل سخت‌گیری: پشتیبان شناسه را از قبل و به روش بهینه از ELK گرفته.
     * اگر اجازه دهیم اینجا روی فیلد بی‌ایندکس بگردد، همان اسکنی می‌شود که
     * کل این معماری برای پرهیز از آن ساخته شده.
     *
     * @throws MongoErrors.FriendlyException اگر فیلد مجاز نباشد
     */
    public LogView findByIndexedField(String field, String value) {
        LabelConfig.NormalField rule = labels.get().search.allowed(field);
        if (rule == null) {
            throw new MongoErrors.FriendlyException(
                    "جستجو روی «" + field + "» در حالت عادی مجاز نیست، چون این فیلد در "
                    + "config.json به‌عنوان فیلد ایندکس‌شده معرفی نشده است. "
                    + "یا ایندکس را بسازید و در config.json فعالش کنید، "
                    + "یا از «جستجوی پیشرفته» استفاده کنید.");
        }
        if (value == null || value.isBlank()) {
            throw new MongoErrors.FriendlyException("مقدار جستجو خالی است.");
        }

        Document filter = new Document(field, typedValue(rule.type(), value.trim()));
        Document found;
        try {
            // ⬇ تنها پرس‌وجوی این مسیر
            found = collection.findOne(filter, null);
        } catch (Exception e) {
            throw MongoErrors.translate(e);
        }
        if (found == null) {
            return null;
        }
        return present(found);
    }

    // ------------------------------------------ جستجوی پیشرفته (سنگین)

    /**
     * جستجوی پیشرفته: فیلترهای پویا روی هر فیلدی، حتی بدون ایندکس.
     *
     * سه محافظ دارد و هر سه لازم‌اند:
     *   ۱) سقف تعداد نتیجه (پیش‌فرض ۲۰)
     *   ۲) سقف زمان اجرا در سمت سرور MongoDB
     *   ۳) **بدون sort** — مرتب‌سازی روی فیلد بی‌ایندکس یا کل نتیجه را در
     *      حافظه می‌ریزد یا سرور را وادار به اسکن تا آخر می‌کند. بدون sort،
     *      MongoDB به‌محض یافتن n سند متوقف می‌شود.
     */
    public SearchResult advancedSearch(List<Filter> filters) {
        LabelConfig.AdvancedConfig adv = labels.get().search.advanced();
        List<String> notes = new ArrayList<>();

        if (!adv.enabled()) {
            throw new MongoErrors.FriendlyException("جستجوی پیشرفته در config.json غیرفعال است.");
        }
        if (filters == null || filters.isEmpty()) {
            throw new MongoErrors.FriendlyException("حداقل یک فیلتر لازم است. "
                    + "جستجوی بدون فیلتر یعنی خواندن کل مجموعه، که مجاز نیست.");
        }
        if (filters.size() > 10) {
            throw new MongoErrors.FriendlyException("حداکثر ۱۰ فیلتر پشتیبانی می‌شود.");
        }

        List<Document> conditions = new ArrayList<>(filters.size());
        for (Filter f : filters) {
            conditions.add(toCondition(f, adv));
        }
        Document filter = conditions.size() == 1 ? conditions.get(0)
                : new Document("$and", conditions);

        int limit = adv.maxResults();
        List<Document> docs;
        try {
            // بدون sort و بدون projection سنگین — فقط فیلدهای ستون‌های نتیجه
            docs = collection.find(filter, null, resultProjection(adv), limit + 1, adv.maxTimeMs());
        } catch (Exception e) {
            throw MongoErrors.translate(e);
        }

        boolean capped = docs.size() > limit;
        if (capped) {
            docs = docs.subList(0, limit);
            notes.add("بیش از " + limit + " نتیجه وجود دارد؛ فقط " + limit
                    + " مورد اول نمایش داده شد. فیلتر را دقیق‌تر کنید.");
        }
        notes.add("نتایج به ترتیب زمانی نیستند — مرتب‌سازی روی فیلد بدون ایندکس "
                + "سنگین است و عمداً انجام نشده.");

        List<SearchHit> hits = new ArrayList<>(docs.size());
        for (Document d : docs) {
            hits.add(toHit(d, adv));
        }
        return new SearchResult(hits, capped, limit, adv.resultFields(), notes);
    }

    /** یک فیلتر پویا از UI */
    public record Filter(String field, String op, String value) {
    }

    private Document toCondition(Filter f, LabelConfig.AdvancedConfig adv) {
        if (f == null || f.field() == null || f.field().isBlank()) {
            throw new MongoErrors.FriendlyException("نام فیلد در یکی از فیلترها خالی است.");
        }
        String field = f.field().trim();
        if (field.startsWith("$")) {
            throw new MongoErrors.FriendlyException("نام فیلد نمی‌تواند با $ شروع شود.");
        }
        LabelConfig.Operator op = adv.operator(f.op() == null ? "eq" : f.op());
        if (op == null) {
            throw new MongoErrors.FriendlyException("عملگر «" + f.op() + "» شناخته نشد.");
        }
        String raw = f.value() == null ? "" : f.value().trim();

        return switch (op.op()) {
            case "exists" -> new Document(field,
                    new Document("$exists", !"false".equalsIgnoreCase(raw)));
            case "contains" -> new Document(field, new Document("$regex",
                    java.util.regex.Pattern.quote(requireValue(raw))).append("$options", "i"));
            case "prefix" -> new Document(field, new Document("$regex",
                    "^" + java.util.regex.Pattern.quote(requireValue(raw))).append("$options", "i"));
            case "in" -> new Document(field, new Document("$in",
                    java.util.Arrays.stream(requireValue(raw).split(","))
                            .map(String::trim).filter(s -> !s.isEmpty()).map(this::guessType).toList()));
            case "eq" -> new Document(field, guessType(requireValue(raw)));
            default -> new Document(field, new Document(op.mongo(), guessType(requireValue(raw))));
        };
    }

    private String requireValue(String raw) {
        if (raw.isEmpty()) {
            throw new MongoErrors.FriendlyException("مقدار یکی از فیلترها خالی است.");
        }
        return raw;
    }

    /**
     * تشخیص نوع مقدار ورودی کاربر.
     *
     * چرا لازم است: `{sagaVersion: "12"}` هیچ سندی را پیدا نمی‌کند وقتی
     * مقدار در پایگاه داده عدد ۱۲ است. کاربر نباید مجبور باشد این را بداند.
     */
    private Object guessType(String raw) {
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        if (raw.matches("-?\\d{1,15}")) {
            try {
                long v = Long.parseLong(raw);
                return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? (int) v : v;
            } catch (NumberFormatException ignored) {
                return raw;
            }
        }
        if (raw.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                return raw;
            }
        }
        return raw;
    }

    /** `_id` می‌تواند ObjectId، رشتهٔ hex یا عدد باشد — نوع اشتباه یعنی صفر نتیجه */
    private Object typedValue(String type, String value) {
        if (type == null || "string".equalsIgnoreCase(type)) {
            return value;
        }
        if ("objectId".equalsIgnoreCase(type)) {
            return ObjectId.isValid(value) ? new ObjectId(value) : value;
        }
        if ("number".equalsIgnoreCase(type)) {
            return guessType(value);
        }
        // auto: اگر شکل ObjectId داشت ObjectId، وگرنه همان رشته
        if (value.length() == 24 && ObjectId.isValid(value)) {
            return new ObjectId(value);
        }
        return value;
    }

    private Document resultProjection(LabelConfig.AdvancedConfig adv) {
        if (adv.resultFields().isEmpty()) {
            return null;
        }
        Document projection = new Document();
        for (LabelConfig.ResultField f : adv.resultFields()) {
            projection.put(f.path().replaceAll("\\[\\d+]", ""), 1);
        }
        return projection;
    }

    private SearchHit toHit(Document doc, LabelConfig.AdvancedConfig adv) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (LabelConfig.ResultField f : adv.resultFields()) {
            Object raw = safeResolve(doc, f.path());
            String text = TypeCoercion.toText(raw, 200);
            if (f.translate() != null) {
                text = resolver.byMap(f.translate(), text, null).value();
            } else if ("datetime".equals(f.type())) {
                Instant i = TypeCoercion.toInstant(raw, config.get().time.stringFormats());
                text = i == null ? text : i.toString();
            }
            fields.put(f.path(), text == null ? "" : masking.maskFreeText(text));
        }
        return new SearchHit(TypeCoercion.toText(doc.get("_id")), fields);
    }

    // ----------------------------------------------- آماده‌سازی نمایش

    /**
     * تبدیل سند خام به هر سه نمای مورد نیاز — همه در حافظه، بدون کوئری جدید.
     */
    public LogView present(Document found) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> raw = new LinkedHashMap<>(found);
        String id = TypeCoercion.toText(raw.get("_id"));

        FlowGraph graph;
        try {
            graph = graphBuilder.build(raw);
        } catch (Exception e) {
            log.warn("ساخت گراف ناموفق بود: {}", e.toString());
            warnings.add("گراف ساخته نشد: " + e.getClass().getSimpleName());
            graph = FlowGraph.empty(labels.get().graph.layout(), "گراف قابل ساخت نبود.");
        }

        List<TableRow> table = new ArrayList<>();
        boolean truncated = false;
        try {
            DocumentFlattener.FlattenResult flat = flattener.flatten(raw);
            truncated = flat.truncated();
            for (FieldNode node : flat.nodes()) {
                collectRows(node, 0, table);
            }
        } catch (Exception e) {
            log.warn("ساخت نمای جدولی ناموفق بود: {}", e.toString());
            warnings.add("نمای جدولی کامل ساخته نشد: " + e.getClass().getSimpleName());
        }

        String json;
        long size;
        try {
            Object masked = masking.maskObject(raw, "");
            json = JsonStrings.writePretty(masked);
            size = JsonStrings.estimateSize(raw, 8);
        } catch (Exception e) {
            json = "{}";
            size = 0;
            warnings.add("تولید JSON خام ناموفق بود.");
        }

        return new LogView(id, header(raw, graph), summary(raw), graph, List.copyOf(table),
                truncated, json, size, masking.profile(), List.copyOf(warnings));
    }

    private void collectRows(FieldNode node, int depth, List<TableRow> out) {
        if (node == null || out.size() > 5_000) {
            return;
        }
        boolean container = !node.isLeaf() || node.childCount() != null;
        out.add(new TableRow(node.path(), resolver.field(node.path()), node.key(), node.type(),
                node.value(), depth, node.masked(), node.truncated(), node.sizeBytes(),
                container, node.childCount()));
        if (node.children() != null) {
            for (FieldNode child : node.children()) {
                collectRows(child, depth + 1, out);
            }
        }
    }

    private SummaryHeader header(Map<String, Object> raw, FlowGraph graph) {
        String rawTitle = TypeCoercion.toText(safeResolve(raw, "title"));
        String rawStatus = TypeCoercion.toText(safeResolve(raw, "status"));
        LabelResolver.Label title = resolver.title(rawTitle, null);
        LabelResolver.Label status = resolver.status(rawStatus);

        AppConfig cfg = config.get();
        Instant start = null;
        for (String candidate : cfg.time.candidates()) {
            start = TypeCoercion.toInstant(safeResolve(raw, candidate), cfg.time.stringFormats());
            if (start != null) {
                break;
            }
        }
        Instant end = TypeCoercion.toInstant(safeResolve(raw, "completeDate"),
                cfg.time.stringFormats());
        String duration = null;
        if (start != null && end != null && !end.isBefore(start)) {
            long ms = java.time.Duration.between(start, end).toMillis();
            duration = ms < 1000 ? ms + " میلی‌ثانیه"
                    : String.format(java.util.Locale.US, "%.1f ثانیه", ms / 1000d);
        }

        return new SummaryHeader(title.value(), rawTitle, status.value(), rawStatus,
                graph.summary().overallSeverity(),
                start == null ? null : start.toString(),
                end == null ? null : end.toString(),
                duration, graph.summary().stepCount(), graph.summary().errorCount());
    }

    private List<SummaryItem> summary(Map<String, Object> raw) {
        List<SummaryItem> out = new ArrayList<>();
        for (LabelConfig.SummaryField f : labels.get().summaryFields) {
            Object value = safeResolve(raw, f.path());
            if (TypeCoercion.isEmpty(value)) {
                continue;
            }
            String text = TypeCoercion.toText(value, 300);
            String rawText = text;
            boolean translated = false;

            if (f.translate() != null) {
                LabelResolver.Label label = resolver.byMap(f.translate(), text, null);
                text = label.value();
                translated = label.translated();
            } else if ("datetime".equals(f.type())) {
                Instant i = TypeCoercion.toInstant(value, config.get().time.stringFormats());
                text = i == null ? text : i.toString();
            }
            out.add(new SummaryItem(f.path(), f.label(), masking.maskValue(f.path(), text),
                    rawText, f.type(), f.copy(), translated));
        }
        return List.copyOf(out);
    }

    private Object safeResolve(Object root, String path) {
        try {
            return PathResolver.first(root, path);
        } catch (Exception e) {
            return null;
        }
    }
}
