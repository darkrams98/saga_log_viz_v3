package com.citydi.logexplorer.api;

import com.citydi.logexplorer.mongo.ReadOnlyViolationException;
import com.citydi.logexplorer.service.MongoErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * هیچ stack trace یا جزئیات داخلی به کلاینت نمی‌رود.
 * فقط پیام فارسی + راهنمای عملی + کد پیگیری برای لاگ سرور.
 *
 * قاعدهٔ پایداری: هر خطایی که به اینجا برسد یعنی *یک درخواست* شکست خورده،
 * نه اینکه سرویس از کار افتاده باشد.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MongoErrors.FriendlyException.class)
    public ResponseEntity<Map<String, Object>> handleFriendly(MongoErrors.FriendlyException ex) {
        String traceId = traceId();
        log.warn("خطای پایگاه داده [traceId={}]: {}", traceId, ex.getMessage(), ex.getCause());
        return ResponseEntity.status(ex.status())
                .body(body(ex.getMessage(), ex.hint(), traceId));
    }

    @ExceptionHandler(ReadOnlyViolationException.class)
    public ResponseEntity<Map<String, Object>> handleReadOnly(ReadOnlyViolationException ex) {
        String traceId = traceId();
        log.error("⛔ نقض فقط-خواندنی [traceId={}]: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(
                "این سرویس فقط اجازهٔ خواندن دارد.",
                "عملیات «" + ex.commandName() + "» مسدود شد. این یک ایراد برنامه‌نویسی است "
                        + "و باید به تیم فنی گزارش شود.", traceId));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(body(
                ex.getReason() == null ? "درخواست قابل انجام نیست" : ex.getReason(), null, null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(body(ex.getMessage(), "ورودی را بررسی کنید.", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex) {
        String traceId = traceId();
        log.error("خطای پیش‌بینی‌نشده [traceId={}]", traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(
                "خطای غیرمنتظره در سرور رخ داد.",
                "لطفاً کد پیگیری را به تیم فنی اعلام کنید.", traceId));
    }

    private static String traceId() {
        return Long.toHexString(System.nanoTime());
    }

    private Map<String, Object> body(String message, String hint, String traceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", true);
        out.put("message", message);
        if (hint != null) {
            out.put("hint", hint);
        }
        if (traceId != null) {
            out.put("traceId", traceId);
        }
        out.put("timestamp", Instant.now().toString());
        return out;
    }
}
