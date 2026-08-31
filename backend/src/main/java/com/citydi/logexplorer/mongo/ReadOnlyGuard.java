package com.citydi.logexplorer.mongo;

import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

/**
 * نگهبان READ-ONLY در سطح درایور MongoDB.
 *
 * چرا اینجا و نه فقط در کد خودمان؟ چون این آخرین نقطه‌ای است که همهٔ دستورها
 * از آن رد می‌شوند — چه از کد ما بیاید، چه از یک کتابخانه، چه از auto-configuration
 * فراموش‌شدهٔ اسپرینگ. هر دستوری که در فهرست مجاز نباشد، اجرا نمی‌شود.
 *
 * دو لایهٔ دفاعی داریم:
 *   ۱) این نگهبان (زمان اجرا، همه‌جانبه)
 *   ۲) ReadOnlyCollection که اصلاً متد نوشتن ندارد (زمان کامپایل)
 * و لایهٔ سوم که در استقرار توصیه می‌شود: کاربر MongoDB با نقش `read`.
 */
public class ReadOnlyGuard implements CommandListener {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyGuard.class);

    /** تنها دستورهایی که اجازهٔ اجرا دارند */
    private static final Set<String> ALLOWED = Set.of(
            // خواندن داده
            "find", "getmore", "aggregate", "count", "distinct", "explain",
            // فراداده (فقط خواندن)
            "listindexes", "listcollections", "listdatabases", "collstats", "dbstats",
            "connectionstatus", "getparameter", "buildinfo", "hostinfo", "serverstatus",
            // چرخهٔ عمر اتصال
            "hello", "ismaster", "ping", "killcursors", "endsessions", "abortransaction",
            "saslstart", "saslcontinue", "authenticate", "logout", "getnonce",
            "atlasversion", "topology"
    );

    /** مرحله‌های aggregation که خروجی می‌نویسند */
    private static final Set<String> WRITE_STAGES = Set.of("$out", "$merge");

    private final AtomicLong blockedCount = new AtomicLong();
    private final AtomicLong allowedCount = new AtomicLong();
    private final ConcurrentLinkedQueue<String> recentViolations = new ConcurrentLinkedQueue<>();

    @Override
    public void commandStarted(CommandStartedEvent event) {
        checkCommand(event.getCommandName(), event.getCommand());
    }

    /**
     * تصمیم واقعی نگهبان — جدا از رویداد درایور نگه داشته شده تا بتوان
     * بدون ساختن یک {@link CommandStartedEvent} کامل تستش کرد.
     *
     * @throws ReadOnlyViolationException اگر دستور خواندنی نباشد
     */
    public void checkCommand(String commandName, BsonDocument command) {
        String name = commandName == null ? "" : commandName.toLowerCase();

        if (!ALLOWED.contains(name)) {
            record(name, "دستور در فهرست مجاز خواندن نیست");
            throw new ReadOnlyViolationException(commandName,
                    "این سرویس فقط اجازهٔ خواندن دارد");
        }

        // aggregate مجاز است، مگر اینکه pipeline بخواهد بنویسد
        if ("aggregate".equals(name)) {
            String stage = findWriteStage(command);
            if (stage != null) {
                record(name, "مرحلهٔ " + stage);
                throw new ReadOnlyViolationException(commandName,
                        "مرحلهٔ «" + stage + "» در pipeline خروجی می‌نویسد");
            }
        }
        allowedCount.incrementAndGet();
    }

    private String findWriteStage(BsonDocument command) {
        try {
            if (command == null || !command.containsKey("pipeline")) {
                return null;
            }
            BsonValue pipeline = command.get("pipeline");
            if (pipeline == null || !pipeline.isArray()) {
                return null;
            }
            for (BsonValue stageValue : pipeline.asArray()) {
                if (!stageValue.isDocument()) {
                    continue;
                }
                for (String key : stageValue.asDocument().keySet()) {
                    if (WRITE_STAGES.contains(key.toLowerCase())) {
                        return key;
                    }
                }
            }
        } catch (Exception e) {
            // اگر نتوانستیم pipeline را بخوانیم، محافظه‌کارانه اجازه نمی‌دهیم
            return "نامشخص";
        }
        return null;
    }

    private void record(String command, String detail) {
        blockedCount.incrementAndGet();
        String entry = Instant.now() + " — " + command + " (" + detail + ")";
        recentViolations.add(entry);
        while (recentViolations.size() > 20) {
            recentViolations.poll();
        }
        log.error("⛔ عملیات نوشتن در MongoDB مسدود شد: {} — {}", command, detail);
    }

    public long blockedCount() {
        return blockedCount.get();
    }

    public long allowedCount() {
        return allowedCount.get();
    }

    public List<String> recentViolations() {
        return List.copyOf(recentViolations);
    }

    public boolean isClean() {
        return blockedCount.get() == 0;
    }

    /** برای تست: فهرست دستورهای مجاز */
    public static Set<String> allowedCommands() {
        return ALLOWED;
    }
}
