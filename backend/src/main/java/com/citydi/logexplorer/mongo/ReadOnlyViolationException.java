package com.citydi.logexplorer.mongo;

/**
 * وقتی پرتاب می‌شود که برنامه تلاش کند چیزی در MongoDB بنویسد.
 *
 * این استثنا نباید هرگز در عمل رخ دهد — وجودش برای این است که اگر
 * روزی کسی سهواً کد نوشتن اضافه کرد، بلافاصله و با صدای بلند شکست بخورد،
 * نه اینکه بی‌سروصدا دادهٔ تولیدی را تغییر دهد.
 */
public class ReadOnlyViolationException extends RuntimeException {

    private final String commandName;

    public ReadOnlyViolationException(String commandName, String detail) {
        super("تلاش برای عملیات نوشتن در MongoDB مسدود شد: «" + commandName + "» — " + detail);
        this.commandName = commandName;
    }

    public String commandName() {
        return commandName;
    }
}
