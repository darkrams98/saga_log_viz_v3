package com.citydi.logexplorer.flow;

import java.util.List;
import java.util.Map;

/**
 * شماتیک جریان اجرا بین میکروسرویس‌ها.
 *
 * این همان چیزی است که پشتیبان اول از همه نگاه می‌کند: با یک نگاه باید
 * بفهمد فرایند از کجا شروع شد، به کدام سرویس‌ها رفت، و کجا شکست.
 *
 * ساختار عمداً ساده است (فهرست گره + فهرست یال) تا هر کتابخانهٔ نمایشی
 * بتواند رسمش کند و ما به هیچ کتابخانهٔ گرافی وابسته نباشیم.
 */
public record FlowGraph(
        List<Node> nodes,
        List<Edge> edges,
        String layout,
        Summary summary,
        List<String> notes
) {

    /**
     * یک مرحله از commandList = یک گره.
     *
     * @param id            شناسهٔ گره در گراف (start / s0 / s1 / … / end)
     * @param kind          step | start | end
     * @param index         ترتیب در commandList؛ برای start/end برابر -1
     * @param service       نام فارسی میکروسرویس (از routingKey)
     * @param routingKey    مقدار خام routingKey — برای کپی و جستجو در ELK
     * @param serviceSource exact | pattern | fallback — یعنی این برچسب واقعاً ترجمه شده یا نه
     * @param severity      success | error | unknown → رنگ گره
     * @param errorText     متن خطا اگر این مرحله شکست خورده
     * @param detail        فیلدهای خواسته‌شده در config برای پنل جزئیات
     * @param truncated     یعنی مقداری در detail به‌خاطر حجم بریده شده
     */
    public record Node(
            String id,
            String kind,
            int index,
            String service,
            String routingKey,
            String serviceSource,
            String title,
            String rawTitle,
            String commandType,
            String rawCommandType,
            String status,
            String rawStatus,
            String severity,
            String errorText,
            String startedAt,
            Map<String, DetailValue> detail,
            boolean truncated
    ) {
    }

    /**
     * @param value متن قابل نمایش (ماسک‌شده)
     * @param json  اگر مقدار یک رشتهٔ JSON بوده، نسخهٔ خوانا؛ وگرنه null
     */
    public record DetailValue(String label, String value, String json, String type,
                              long sizeBytes, boolean truncated) {
    }

    public record Edge(String from, String to, String label) {
    }

    /**
     * @param failedIndex اندیس اولین مرحلهٔ ناموفق، یا -1
     * @param failedNodeId شناسهٔ گره ناموفق برای اسکرول و برجسته‌سازی در UI
     */
    public record Summary(int stepCount, int successCount, int errorCount, int unknownCount,
                          int failedIndex, String failedNodeId, String failedService,
                          String failedErrorText, String overallStatus, String overallSeverity) {
    }

    public static FlowGraph empty(String layout, String note) {
        return new FlowGraph(List.of(), List.of(), layout,
                new Summary(0, 0, 0, 0, -1, null, null, null, null, "unknown"),
                List.of(note));
    }
}
