package com.citydi.logexplorer.mongo;

import com.mongodb.ReadPreference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigProvider;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * تنها دروازهٔ دسترسی به MongoDB.
 *
 * این کلاس عمداً **هیچ متد نوشتنی ندارد**. نه insert، نه update، نه delete،
 * نه upsert، نه createIndex. اگر کسی بخواهد بنویسد، باید این کلاس را تغییر دهد
 * که در بازبینی کد فوراً دیده می‌شود — و حتی آن‌وقت هم ReadOnlyGuard جلویش را می‌گیرد.
 *
 * همچنین هر پرس‌وجو:
 *   - سقف زمان اجرا (maxTimeMS) دارد → یک کوئری بد سرور را قفل نمی‌کند
 *   - سقف تعداد نتیجه دارد → حافظه منفجر نمی‌شود
 *   - از cursor استفاده می‌کند → کل collection در حافظه بارگذاری نمی‌شود
 */
@Component
public class LogCollection {

    private static final Logger log = LoggerFactory.getLogger(LogCollection.class);

    private final MongoClient client;
    private final ConfigProvider configProvider;
    private final ReadOnlyGuard guard;

    public LogCollection(MongoClient client, ConfigProvider configProvider, ReadOnlyGuard guard) {
        this.client = client;
        this.configProvider = configProvider;
        this.guard = guard;
    }

    private AppConfig.Mongo cfg() {
        return configProvider.get().mongo;
    }

    private MongoCollection<Document> collection() {
        AppConfig.Mongo c = cfg();
        return client.getDatabase(c.database())
                .getCollection(c.collection())
                .withReadPreference(readPreference(c.readPreference()));
    }

    static ReadPreference readPreference(String name) {
        if (name == null) {
            return ReadPreference.secondaryPreferred();
        }
        return switch (name.trim().toLowerCase()) {
            case "primary" -> ReadPreference.primary();
            case "primarypreferred" -> ReadPreference.primaryPreferred();
            case "secondary" -> ReadPreference.secondary();
            case "nearest" -> ReadPreference.nearest();
            default -> ReadPreference.secondaryPreferred();
        };
    }

    // -------------------------------------------------------------- read

    /**
     * صفحه‌ای از اسناد — با cursor و سقف، هرگز «همه» را نمی‌خواند.
     *
     * @param filter     شرط
     * @param sort       ترتیب (باید با ایندکس هم‌راستا باشد)
     * @param projection فیلدهایی که لازم داریم — فیلدهای سنگین حذف می‌شوند
     * @param limit      حداکثر تعداد
     */
    public List<Document> find(Bson filter, Bson sort, Bson projection, int limit) {
        return find(filter, sort, projection, limit, cfg().queryTimeoutMs());
    }

    /** همان find، با سقف زمان جداگانه — جستجوی پیشرفته مهلت بیشتری لازم دارد */
    public List<Document> find(Bson filter, Bson sort, Bson projection, int limit, long maxTimeMs) {
        OperationCounter.record("find");
        int safeLimit = Math.max(1, limit);
        List<Document> out = new ArrayList<>(Math.min(safeLimit, 256));

        var iterable = collection()
                .find(filter)
                .limit(safeLimit)
                .batchSize(Math.min(safeLimit, 200))
                .maxTime(Math.max(1000L, maxTimeMs), TimeUnit.MILLISECONDS)
                .comment("log-explorer:find");

        if (sort != null) {
            iterable = iterable.sort(sort);
        }
        if (projection != null) {
            iterable = iterable.projection(projection);
        }
        try (MongoCursor<Document> cursor = iterable.iterator()) {
            while (cursor.hasNext() && out.size() < safeLimit) {
                out.add(cursor.next());
            }
        }
        return out;
    }

    /** یک سند بر اساس شرط — تنها پرس‌وجوی مسیر «نمایش یک لاگ» */
    public Document findOne(Bson filter, Bson projection) {
        OperationCounter.record("findOne");
        var iterable = collection()
                .find(filter)
                .limit(1)
                .maxTime(cfg().queryTimeoutMs(), TimeUnit.MILLISECONDS)
                .comment("log-explorer:findOne");
        if (projection != null) {
            iterable = iterable.projection(projection);
        }
        return iterable.first();
    }

    /** تخمین سریع کل collection (از فراداده، بدون اسکن) — فقط برای صفحهٔ سلامت */
    public long estimatedTotal() {
        OperationCounter.record("estimatedDocumentCount");
        try {
            return collection().estimatedDocumentCount();
        } catch (Exception e) {
            log.debug("تخمین تعداد اسناد ناموفق بود: {}", e.toString());
            return -1;
        }
    }

    // ------------------------------------------------- عمداً وجود ندارند
    //
    //  aggregate() / sample() / countDocuments() در نسخهٔ قبلی بودند و
    //  عمداً حذف شدند. دلیلش قید صریح این مرحله است: «هیچ بار اضافه‌ای روی
    //  سرور ایجاد نکن» و «داشبورد پایش همهٔ لاگ‌ها ساخته نشود».
    //
    //  aggregation و شمارش، ذاتاً روی کل collection کار می‌کنند. با نبودشان
    //  در این کلاس، اجرای‌شان دیگر یک تصمیم زمان اجرا نیست — یک خطای کامپایل
    //  است. پایش کلی کار Grafana است و جستجوی گسترده کار ELK.
    //
    // ------------------------------------------------------------------

    /** ایندکس‌های موجود — فقط خواندن فراداده */
    public List<Document> listIndexes() {
        OperationCounter.record("listIndexes");
        List<Document> out = new ArrayList<>();
        try (MongoCursor<Document> cursor = collection().listIndexes().iterator()) {
            while (cursor.hasNext()) {
                out.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("خواندن فهرست ایندکس‌ها ناموفق بود: {}", e.toString());
        }
        return out;
    }

    /** اطلاعات اتصال برای endpoint سلامت */
    public Map<String, Object> connectionInfo() {
        AppConfig.Mongo c = cfg();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("database", c.database());
        info.put("collection", c.collection());
        info.put("readPreference", c.readPreference());
        info.put("queryTimeoutMs", c.queryTimeoutMs());
        info.put("readOnlyEnforced", c.enforceReadOnly());
        info.put("blockedWriteAttempts", guard.blockedCount());
        info.put("readCommands", guard.allowedCount());
        return info;
    }
}
