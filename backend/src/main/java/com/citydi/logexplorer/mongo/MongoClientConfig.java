package com.citydi.logexplorer.mongo;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigProvider;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * ساخت کلاینت MongoDB.
 *
 * تصمیم مهم: **از spring-boot-starter-data-mongodb استفاده نمی‌کنیم.**
 * آن استارتر یک MongoTemplate می‌سازد که پر از متد نوشتن است (save، remove،
 * upsert، ...). وقتی الزام «فقط خواندن» است، بهترین کار این است که آن ابزار
 * اصلاً در دسترس نباشد. فقط درایور خام را برمی‌داریم و روی آن یک نمای
 * فقط-خواندنی می‌سازیم (LogCollection).
 */
@Configuration
public class MongoClientConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoClientConfig.class);

    @Bean
    public ReadOnlyGuard readOnlyGuard() {
        return new ReadOnlyGuard();
    }

    @Bean(destroyMethod = "close")
    public MongoClient mongoClient(ConfigProvider configProvider, ReadOnlyGuard guard) {
        AppConfig.Mongo c = configProvider.get().mongo;

        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(c.uri()))
                .readPreference(LogCollection.readPreference(c.readPreference()))
                // خواندن از secondary نیازی به تأیید نوشتن ندارد؛ اینجا هیچ نوشتنی نیست
                .retryWrites(false)
                .retryReads(true)
                .applyToClusterSettings(s -> s.serverSelectionTimeout(
                        c.serverSelectionTimeoutMs(), TimeUnit.MILLISECONDS))
                .applyToSocketSettings(s -> s
                        .connectTimeout(c.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                        .readTimeout(c.socketTimeoutMs(), TimeUnit.MILLISECONDS))
                .applicationName("saga-log-explorer(read-only)");

        if (c.enforceReadOnly()) {
            builder.addCommandListener(guard);
            log.info("نگهبان READ-ONLY فعال شد — هر دستور نوشتن در سطح درایور مسدود می‌شود");
        } else {
            log.warn("⚠️ نگهبان READ-ONLY غیرفعال است (mongo.enforceReadOnly=false). "
                    + "این تنظیم برای محیط تولید توصیه نمی‌شود.");
        }

        log.info("اتصال به MongoDB: db={} collection={} readPreference={}",
                c.database(), c.collection(), c.readPreference());
        return MongoClients.create(builder.build());
    }
}
