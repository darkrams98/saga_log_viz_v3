# CLAUDE.md — وضعیت پروژه و قواعد ادامهٔ کار

> این فایل برای ادامهٔ کار پس از پرشدن context است. اول این را بخوان،
> بعد `docs/02-architecture.md`، بعد `config/config.json`.

---

## فاز فعلی

**فاز ۳ — نمایشگر تک‌لاگ: کامل.**

| فاز | وضعیت |
|---|---|
| فاز ۱ — داشبورد پشتیبانی روی نمونه‌های Elasticsearch | ✅ تحویل شده |
| فاز ۲ — خواندن مستقیم از MongoDB، عمومی و فقط-خواندنی | ✅ تحویل شده |
| **فاز ۳ — نمایش یک لاگ + گراف جریان + دو حالت جستجو** | ✅ **این نسخه** |
| فاز ۴ — پیشنهادی: پیوند دوطرفه با ELK، اشتراک‌گذاری یادداشت | ⬜ شروع نشده |

### تغییر جهت مهم در فاز ۳

کاربر صریحاً گفت: **«به هیچ وجه داشبورد پایش همه logها را نساز»**.
پایش با Grafana و جستجو با ELK انجام می‌شود.

پس در این فاز **حذف شد**: داشبورد آمار، فهرست لاگ‌ها، صفحهٔ ساختار داده،
صفحه‌بندی keyset، `aggregate`/`sample`/`countDocuments` از دروازهٔ داده،
و `TimeFieldCodec`/`QueryBuilder`/`SearchInterpreter`.

**اضافه شد**: `config.json` (برچسب فارسی)، `flow/` (گراف جریان)،
`OperationCounter` (اثبات «یک find»)، دو حالت جستجوی مجزا.

---

## پنج قاعده‌ای که هرگز نباید شکسته شوند

### ۱) نمایش یک لاگ = دقیقاً یک پرس‌وجو

گراف، جدول و JSON خام همه از همان سند در حافظه ساخته می‌شوند.
هیچ lookup، هیچ aggregate، هیچ کوئری دوم.

`OperationCounter` این را می‌شمارد و `mongoOperations` در پاسخ برمی‌گرداند.
اگر عددی جز ۱ دیدی، قید شکسته شده.

### ۲) داشبورد پایش ساخته نمی‌شود

هیچ endpointی که فهرست، آمار، نمودار یا شمارش برگرداند اضافه نشود.
`LogCollection` عمداً `aggregate` و `count` ندارد — اضافه‌کردنشان یعنی
برگشتن به چیزی که کاربر صریحاً نخواست.

### ۳) جستجوی عادی فقط روی فیلد ایندکس‌شده

فیلد باید در `config.json` هم `enabled` باشد هم `indexed`.
هر چیز دیگری با پیام صریح رد می‌شود. جستجوی سنگین جای جداگانه و
با هشدار خودش را دارد.

### ۴) هیچ نام فیلدی در کد جاوا نوشته نمی‌شود

اگر وسوسه شدی `"routingKey"` یا `"commandList"` را در یک کلاس بنویسی —
نکن. جایش `config.json` (بخش `graph`) یا `config.yaml` است.

معیار: اگر مشکلی با ویرایش config حل می‌شود، با config حلش کن.
(نمونهٔ واقعی این فاز: ۹ برچسب گم‌شده که راستی‌آزمایی پیدا کرد، با
افزودن ۲۱ سطر به config.json حل شد — بدون یک خط جاوا.)

### ۵) هیچ برچسبی «نامشخص» نمی‌شود

اگر ترجمه نبود، **مقدار خام** نمایش داده می‌شود. پشتیبانی که
`orchestration26.x.routing.key` را می‌بیند می‌تواند در ELK دنبالش بگردد؛
کسی که «نامشخص» می‌بیند نه.

همین‌طور: وضعیت ناشناخته «unknown» است، نه «موفق».

---

## نقشهٔ کد

```
labels/    LabelConfig, LabelConfigLoader, LabelConfigProvider, LabelResolver
           ← زنجیرهٔ ترجمه: دقیق → الگو → نرمال‌شده → commandType → خام
flow/      FlowGraph (رکوردها), FlowGraphBuilder
           ← از commandList گره و یال می‌سازد؛ هرگز پرتاب نمی‌کند
mongo/     LogCollection (۵ متد، بدون نوشتن و بدون پویش)
           ReadOnlyGuard (فهرست مجاز روی درایور)
           OperationCounter (ThreadLocal، اثبات «یک find»)
           IndexInspector (راستی‌آزمایی ادعای indexed در config.json)
service/   LogLookupService (یافتن + آماده‌سازی سه نما), MongoErrors
api/       LogController (۴ مسیر), MetaController (۴ مسیر)
parse/     دست‌نخورده از فاز ۲ — مسیرها، تبدیل نوع، تخت‌کردن
mask/      MaskingService + پروفایل از config.json
```

فرانت‌اند: `pages/LogViewerPage.jsx` تنها صفحه است؛
`components/FlowGraph.jsx` گراف SVG بدون کتابخانهٔ خارجی.

---

## دام‌هایی که یک بار افتادیم

- **SVG و RTL**: معنای `text-anchor: start/end` به جهت متن وابسته است.
  در صفحهٔ RTL، `end` لبهٔ چپ می‌شود و متن از جعبه بیرون می‌زند.
  همهٔ برچسب‌های گره با `middle` چیده شده‌اند. دست نزن.
- **`scrollLeft` در ظرف RTL** بین مرورگرها ناسازگار است. مقداردهی دستی
  نکن؛ مرورگر خودش از راست شروع می‌کند.
- **`flex: 0 0 auto` روی عنصری با `width: 100%`** یعنی پایه ۱۰۰٪ و شکستن
  کل ردیف. برای هر عنصر flex پایهٔ صریح بده.
- **regex بی‌کران روی رشتهٔ بلند**: الگوی ماسک متن آزاد با `*` بی‌کران
  روی ۲۰ کیلوبایت ۶٫۹ ثانیه طول می‌کشید. کران `{0,64}` روی نام فیلد
  اضافه شد. جزئیات در `docs/04-readonly.md`.

---

## محدودیت‌های محیط توسعه

- **Maven Central در دسترس نبود** → `mvn compile` و `mvn test` اجرا نشده‌اند.
  به‌جایش: `javac -proc:none -XDshould-stop.ifNoError=PARSE` روی همهٔ
  فایل‌ها (موفق)، و موتور مرجع پایتون (`tools/reference_engine.py`) که
  همان منطق را آینه می‌کند.
- **`mongod` نصب نبود** → `mongomock`.
- **npm کار می‌کرد** → `npm run build` و اسکرین‌شات با Playwright.

**اولین کار در محیط واقعی:** `cd backend && mvn test`.

---

## ابزارها

```bash
python3 tools/build_fixtures.py      # ۱۵۴ سند آزمایشی از ۳ schema
python3 tools/verify_generic.py      # ۴۰ بررسی — باید ۴۰/۴۰ باشد
python3 tools/mock_api_server.py     # API ساختگی روی mongomock
```

`reference_engine.py` آینهٔ پایتونی منطق جاواست: `Labels` معادل
`LabelResolver` و `build_flow_graph` معادل `FlowGraphBuilder`.
**اگر منطق جاوا را عوض کردی، آینه را هم عوض کن.**

---

## نکته‌های واقعی دربارهٔ داده

- شناسهٔ مرحله `commandList[]._id` است، نه `id`. حرف اول `StartDate`
  داخل مرحله **بزرگ** است ولی `startDate` سطح ریشه کوچک.
- `commandList[].endDate` **وجود ندارد** → مدت هر مرحله محاسبه نمی‌شود.
- تنها متن خطا `rollbackDescription` است (`rollbackException` وجود ندارد).
- وضعیت مراحل فقط دو مقدار دارد: `COMPLETED` و `ROLL_BACKED`.
- عنوان‌ها مهر زمانی و شناسه دارند → `normalizeTitle`.
- `commandContent` و `response` رشتهٔ JSON تا ~۷۴ کیلوبایت‌اند.
- ۲۳ میکروسرویس و ۴۲ نوع دستور در دادهٔ واقعی دیده شده — همه در
  `config.json` ترجمه دارند.

---

## اگر کار تازه‌ای شروع می‌کنی

1. `docs/02-architecture.md` را بخوان.
2. قبل از افزودن کد، ببین با `config.json` حل نمی‌شود.
3. بعد از هر تغییر: `python3 tools/verify_generic.py` باید ۴۰/۴۰ بماند.
4. اگر منطق تفسیر یا گراف عوض شد، `tools/reference_engine.py` را هم به‌روز کن.
5. اگر چیزی به MongoDB اضافه کردی، `docs/04-readonly.md` را دوباره بخوان.
