package com.citydi.logexplorer.mongo;

import java.util.ArrayList;
import java.util.List;

/**
 * شمارندهٔ پرس‌وجوهای MongoDB در طول **یک درخواست HTTP**.
 *
 * چرا وجود دارد؟ چون یکی از قیدهای صریح این پروژه این است:
 * «بعد از جستجوی شناسه، بیش از یک find اجرا نشود.»
 *
 * ادعا کردنِ چنین چیزی آسان است؛ اثباتش سخت. این شمارنده عدد واقعی را
 * در پاسخ API برمی‌گرداند (`mongoOperations`) تا هر کسی — بدون خواندن کد —
 * ببیند سرور برای ساختن این صفحه دقیقاً چند بار به پایگاه داده رفته است.
 *
 * پیاده‌سازی با ThreadLocal است چون Spring MVC هر درخواست را روی یک نخ
 * اجرا می‌کند؛ پس عدد دقیق است، نه تقریبی.
 */
public final class OperationCounter {

    private static final ThreadLocal<Recording> ACTIVE = new ThreadLocal<>();

    private OperationCounter() {
    }

    public record Result(int count, List<String> operations) {
    }

    private static final class Recording {
        private final List<String> operations = new ArrayList<>(4);
    }

    /** شروع ضبط برای درخواست جاری */
    public static void start() {
        ACTIVE.set(new Recording());
    }

    /** ثبت یک پرس‌وجو */
    static void record(String operation) {
        Recording r = ACTIVE.get();
        if (r != null && r.operations.size() < 100) {
            r.operations.add(operation);
        }
    }

    /** پایان ضبط و گرفتن نتیجه */
    public static Result stop() {
        Recording r = ACTIVE.get();
        ACTIVE.remove();
        if (r == null) {
            return new Result(0, List.of());
        }
        return new Result(r.operations.size(), List.copyOf(r.operations));
    }
}
