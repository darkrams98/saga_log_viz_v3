# ۳ — راهنمای پیکربندی

دو فایل، دو مسئولیت:

| فایل | یعنی | چه کسی ویرایشش می‌کند |
|---|---|---|
| `config/config.json` | **چطور نشان بده** — برچسب فارسی، گراف، جستجو | تیم پشتیبانی و عملیات |
| `config/config.yaml` | **چطور بخوان** — اتصال، مسیرها، محدودیت‌ها، ماسک | تیم فنی |

**بازخوانی بدون ری‌استارت:**

```bash
curl -X POST http://localhost:8080/api/v1/meta/config/reload
```

اگر فایل جدید خراب باشد، **نسخهٔ سالم قبلی دست‌نخورده می‌ماند** و دلیل
خرابی در `/api/v1/meta/health` گزارش می‌شود.

---

# ⭐ افزودن یک میکروسرویس جدید

پرتکرارترین کاری که با این فایل انجام می‌شود. سه گام، بدون تغییر کد،
بدون استقرار مجدد:

### گام ۱ — پیدا کردن مقدار خام

در گراف، گرهی که هنوز ترجمه نشده **مقدار خام routingKey** را نشان می‌دهد
(نه «نامشخص»). روی گره کلیک کنید؛ در پنل جزئیات، کنار «میکروسرویس»
مقدار خام با دکمهٔ کپی هست:

```
orchestration26.wallet.service.routing.key
```

### گام ۲ — افزودن یک سطر به `config.json`

```json
"routingKeys": {
  …
  "orchestration26.wallet.service.routing.key": "کیف پول"
}
```

### گام ۳ — بازخوانی

```bash
curl -X POST http://localhost:8080/api/v1/meta/config/reload
```

صفحه را تازه کنید. تمام.

### افزودن نوع دستور جدید

دقیقاً همین‌طور، در بخش `commandTypes`:

```json
"commandTypes": {
  …
  "WALLET_CHARGE": "شارژ کیف پول"
}
```

### نکته: الگو به‌جای ده سطر تکراری

سرویس‌ها معمولاً چند نسخه دارند و `routingKey` هرکدام فرق می‌کند:

```
rabbitmq.yaghoot25.client.deposit.routing.key
rabbitmq.yaghoot25-2.client.deposit.routing.key
rabbitmq.yaghoot26.client.deposit.routing.key      ← فردا
```

به‌جای افزودن سطر برای هر نسخه، یک الگو بنویسید:

```json
"routingKeyPatterns": [
  { "match": "\\.deposit\\.", "label": "سپرده" }
]
```

الگوها **فقط وقتی** بررسی می‌شوند که کلید دقیق پیدا نشود، و به ترتیب —
اولین تطبیق برنده است. یعنی نسخهٔ ۲۶ فردا خودبه‌خود «سپرده» می‌شود،
بدون اینکه کسی کاری کند.

> الگوی نامعتبر فقط خودش نادیده گرفته می‌شود و در `warnings` می‌آید؛
> بقیهٔ الگوها سالم می‌مانند.

---

## `config.json` — بخش به بخش

> JSON کامنت ندارد، پس هر کلیدی که با `_` شروع شود توضیح است و
> نادیده گرفته می‌شود. مثل `"_راهنمای_titles"`.

### `routingKeys` و `routingKeyPatterns` — نام میکروسرویس

بالا توضیح داده شد. زنجیرهٔ ترجمه: **کلید دقیق → الگو → مقدار خام**.

### `commandTypes` — نام دستور

نگاشت ساده. زنجیره: **کلید دقیق → مقدار خام**.

### `statuses` و `statusSeverity` — وضعیت و رنگ

```json
"statuses":       { "ROLL_BACKED": "بازگشت خورده" },
"statusSeverity": { "ROLL_BACKED": "error" }
```

`statusSeverity` سه مقدار مجاز دارد: `success` | `error` | `unknown`.
**هر وضعیتی که اینجا نیامده باشد خاکستری (unknown) است** — عمداً، چون
حدس‌زدن موفقیت از روی وضعیت ناشناخته خطرناک است.

### `titles` — عنوان عملیات

عنوان‌های تولید مهر زمانی و شناسه دارند:

```
SEQ__GET_CARD_DEPOSIT_LIST__2026-08-24_12:46:59
LOAN_DETAILS_343.679.69507242.169507242
```

پس زنجیره چهار پله دارد:

```
کلید دقیق  →  کلید نرمال‌شده  →  نوع دستور  →  مقدار خام
```

نرمال‌سازی با تبدیل `normalizeTitle` در `config.yaml` انجام می‌شود
(حذف مهر زمانی، حذف شناسهٔ انتهایی، حذف زیرخط اضافه). یعنی کافی است
`SEQ__GET_CARD_DEPOSIT_LIST` را یک بار بنویسید و همهٔ نسخه‌های
مهرزمانی‌دارش ترجمه می‌شوند.

### `fieldLabels` — نام فیلدها در نمای جدولی

```json
"fieldLabels": {
  "commandList.rollbackDescription": "شرح خطا / بازگشت"
}
```

اندیس آرایه خودکار حذف می‌شود، پس یک سطر برای `commandList[0]`،
`commandList[3]` و همهٔ بقیه کافی است. آخرین بخش مسیر هم بررسی می‌شود،
پس همین سطر برای `rollbackDescription` تنها هم کار می‌کند.

### `summaryFields` — کارت خلاصه

فیلدهای **اضافی** بالای صفحه. عنوان، وضعیت، زمان و مدت اجرا همیشه
نمایش داده می‌شوند و اینجا تکرارشان لازم نیست.

```json
{ "path": "registerId", "label": "شناسهٔ مشتری", "copy": true }
```

`copy: true` یک دکمهٔ کپی کنارش می‌گذارد.

### `graph` — ظاهر و منبع گراف

```json
"graph": {
  "layout": "horizontal-rtl",
  "source": "commandList",
  "nodeLabelFrom": "routingKey",
  "nodeSubLabelFrom": "commandType",
  "statusFrom": "status",
  "errorTextFrom": ["rollbackDescription"],
  "detailFields": ["title", "commandType", "commandContent", "response", …],
  "colors": { "success": "#0ca30c", "error": "#d03b3b", "unknown": "#8a8f98" }
}
```

اگر ساختار لاگ عوض شد و مراحل جای دیگری رفتند، فقط `source` را عوض کنید.
`detailFields` تعیین می‌کند با کلیک روی گره چه چیزی دیده شود.

### `search.normalFields` — فیلدهای جستجوی سریع

```json
{
  "field": "registerId",
  "label": "شناسهٔ مشتری",
  "type": "string",
  "indexed": false,
  "enabled": false,
  "hint": "برای فعال‌سازی: اول ایندکس را بسازید، بعد اینجا فعالش کنید."
}
```

**هر دو باید `true` باشند** تا فیلد قابل استفاده شود. `indexed` یک *ادعا*ست
و سرویس هنگام راه‌اندازی با `listIndexes` راستی‌آزمایی‌اش می‌کند؛ اگر دروغ
باشد، هشدار می‌دهد.

`type`: `auto` (تشخیص ObjectId از روی شکل) | `string` | `objectId` | `number`.
نوع اشتباه یعنی صفر نتیجه بدون هیچ خطایی — بدترین نوع باگ.

**دستور کار برای افزودن فیلد جستجوی جدید:**

1. `mongosh … --eval "var APPLY=true" ops/indexes.js` — ایندکس را بسازید
2. در `config.json`: `"indexed": true, "enabled": true`
3. reload
4. `GET /api/v1/meta/health` → بخش `searchFields` باید `status: "ok"` بدهد

### `search.advanced` — جستجوی سنگین

```json
{
  "enabled": true,
  "maxResults": 20,
  "maxTimeMs": 15000,
  "warning": "متنی که به کاربر نشان داده می‌شود…",
  "operators": [ { "op": "contains", "label": "شامل متن", "mongo": "$regex" } ],
  "suggestedFields": [ { "field": "commandList.routingKey", "label": "میکروسرویس" } ],
  "resultFields": [ { "path": "_id", "label": "شناسه" } ]
}
```

`suggestedFields` فقط پیشنهاد خودکار در کادر است؛ کاربر می‌تواند هر مسیری
بنویسد. `enabled: false` کل بخش را از UI حذف می‌کند — اگر تیم تصمیم گرفت
جستجوی سنگین اصلاً در دسترس نباشد.

### `privacy.maskingProfile` — دادهٔ حساس

| مقدار | رفتار |
|---|---|
| `secretsOnly` *(پیش‌فرض)* | رمز، OTP، توکن و CVV **حذف** می‌شوند؛ کد ملی و شمارهٔ حساب کامل دیده می‌شوند |
| `partial` | علاوه بر آن، کد ملی و موبایل و حساب به شکل `۱۲۳****۴۵` ماسک می‌شوند |
| `off` | هیچ پوشاندنی — فقط برای محیط کاملاً قابل اعتماد |

پیش‌فرض `secretsOnly` است چون هدف این ابزار **عیب‌یابی** است: وقتی پشتیبان
می‌خواهد بفهمد چرا انتقال وجه شکست خورده، دیدن `۱۲۳****۴۵` به‌جای شمارهٔ
حساب کارش را غیرممکن می‌کند. ولی رمز و توکن در هیچ حالتی نمایش داده
نمی‌شوند — آن‌ها برای عیب‌یابی هم لازم نیستند.

---

## `config.yaml` — بخش‌هایی که هنوز مهم‌اند

### `mongo`

```yaml
mongo:
  uri: ${MONGO_URI:mongodb://localhost:27017}
  database: ${MONGO_DB:saga}
  collection: ${MONGO_COLLECTION:sagaSequence}
  readPreference: secondaryPreferred   # بار روی نود اصلی نیفتد
  queryTimeoutMs: 8000
  enforceReadOnly: true                # پیشنهاد: همیشه true
```

`${ENV:default}` پشتیبانی می‌شود تا رمز اتصال در فایل نماند.

### `time`

```yaml
time:
  candidates: [startDate, creationDate, "@timestamp", "commandList[0].StartDate"]
  displayTimezone: Asia/Tehran
  stringFormats: ["yyyy-MM-dd HH:mm:ss", …]
```

`candidates` به ترتیب بررسی می‌شود؛ اولین مقدار قابل تفسیر برنده است.
این نسخه بازهٔ زمانی نمی‌سازد، پس `queryField` فقط برای نمایش مهم است.

### `transforms`

`normalizeTitle` که بالا توضیح داده شد. الگوی نامعتبر نادیده گرفته می‌شود.

### `masking`

فهرست `secretFields` و `rules`. پروفایل در `config.json` تعیین می‌کند
کدام‌شان اعمال شوند.

### `limits`

| کلید | پیش‌فرض | کنترل می‌کند |
|---|---|---|
| `maxFlattenNodes` | ۳۰۰۰ | سقف ردیف نمای جدولی |
| `maxDepth` | ۱۵ | سقف عمق |
| `previewChars` | ۴۰۰ | بریدن مقدار طولانی |
| `largeValueBytes` | ۲۰۰۰ | بالاتر از این «سنگین» است |
| `maxDocumentBytes` | ۴ مگابایت | بالاتر از این فقط خلاصه |

---

## عیب‌یابی پیکربندی

```bash
curl -s http://localhost:8080/api/v1/meta/health | python3 -m json.tool
```

سه چیز را ببینید:

| کلید | مقدار درست |
|---|---|
| `labelWarnings` | خالی |
| `configWarnings` | خالی |
| `searchFields.problems` | خالی — یعنی هیچ ادعای ایندکس دروغی نیست |

اگر برچسبی اعمال نشد، احتمالاً فایل خراب بوده و سرویس عمداً نسخهٔ قبلی را
نگه داشته. `labelWarnings` دلیلش را می‌گوید.

← قبلی: [۲ — معماری](02-architecture.md) · ادامه: [۴ — تضمین‌های ایمنی](04-readonly.md)
