package com.citydi.logexplorer.service;

import com.citydi.logexplorer.mongo.ReadOnlyViolationException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;

/**
 * تبدیل خطاهای MongoDB به پیام‌هایی که پشتیبان بفهمد.
 *
 * پشتیبان نباید «MongoSocketReadTimeoutException» ببیند. باید بداند
 * چه اتفاقی افتاده و چه کاری از دستش برمی‌آید.
 */
public final class MongoErrors {

    private MongoErrors() {
    }

    /** خطای قابل نمایش به کاربر، همراه با کد وضعیت پیشنهادی */
    public static class FriendlyException extends RuntimeException {
        private final int status;
        private final String hint;

        public FriendlyException(String message, String hint, int status, Throwable cause) {
            super(message, cause);
            this.hint = hint;
            this.status = status;
        }

        /** خطای اعتبارسنجی ورودی — پیام برای کاربر، وضعیت ۴۰۰ */
        public FriendlyException(String message) {
            this(message, null, 400, null);
        }

        public int status() {
            return status;
        }

        public String hint() {
            return hint;
        }
    }

    public static RuntimeException translate(Exception e) {
        if (e instanceof ReadOnlyViolationException v) {
            return new FriendlyException(
                    "این سرویس فقط اجازهٔ خواندن دارد و عملیات درخواستی مسدود شد.",
                    "دستور مسدودشده: " + v.commandName(), 403, e);
        }
        if (e instanceof MongoExecutionTimeoutException) {
            return new FriendlyException(
                    "پرس‌وجو در زمان مجاز کامل نشد.",
                    "بازهٔ زمانی را کوتاه‌تر کنید یا فیلتر دقیق‌تری اضافه کنید. "
                            + "اگر تکرار شد، احتمالاً ایندکس لازم روی این فیلد وجود ندارد.",
                    504, e);
        }
        if (e instanceof MongoTimeoutException || e instanceof MongoSocketException) {
            return new FriendlyException(
                    "ارتباط با پایگاه دادهٔ لاگ برقرار نشد.",
                    "از در دسترس بودن MongoDB و درستی آدرس اتصال مطمئن شوید.", 503, e);
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (message.contains("not authorized") || message.contains("unauthorized")) {
            return new FriendlyException(
                    "دسترسی خواندن به این collection وجود ندارد.",
                    "کاربر MongoDB باید نقش read روی این پایگاه داده داشته باشد.", 403, e);
        }
        if (message.contains("exceeded memory limit") || message.contains("sort exceeded")) {
            return new FriendlyException(
                    "پرس‌وجو از حافظهٔ مجاز MongoDB فراتر رفت.",
                    "فیلتر را دقیق‌تر کنید تا تعداد اسناد مطابق کمتر شود.", 507, e);
        }
        return new FriendlyException(
                "خطای غیرمنتظره هنگام خواندن از پایگاه داده.",
                "اگر تکرار شد، کد پیگیری را به تیم فنی بدهید.", 500, e);
    }
}
