# نمایشگر لاگ Saga

نمایش **یک** لاگ از MongoDB بر اساس شناسه — برای اینکه کارشناس پشتیبانی
با یک نگاه بفهمد فرایند کجا شکست خورده.

```
شناسه (از ELK)  →  find({_id})  →  گراف جریان + جدول + JSON خام
```

**این یک داشبورد پایش نیست، و عمداً نیست.** پایش کلی کار Grafana است و
جستجوی گسترده کار ELK. این ابزار فقط یک کار می‌کند.

---

## سه ادعا، هر سه قابل بررسی

| ادعا | چطور |
|---|---|
| **دقیقاً یک پرس‌وجو** برای هر نمایش | `mongoOperations` در پاسخ API و در UI |
| **هرگز نمی‌نویسد** | بدون Spring Data Mongo، دروازه‌ای بدون متد نوشتن، نگهبان درایور، کاربر `read` |
| **هرگز کل مجموعه را نمی‌خواند** | `aggregate` و `count` از دروازهٔ داده حذف شده‌اند — خطای کامپایل، نه تصمیم زمان اجرا |

```bash
curl -s localhost:8080/api/v1/log/<id> | python3 -c \
  'import json,sys;d=json.load(sys.stdin);print(d["mongoOperations"],d["operations"])'
# 1 ['findOne']
```

---

## شروع سریع

```bash
# ۱) ایندکس‌ها — احتمالاً کاری لازم نیست
mongosh "mongodb://…/saga" ops/indexes.js

# ۲) backend
export MONGO_URI="mongodb://log_viewer_ro:***@host:27017/saga?authSource=admin"
cd backend && mvn spring-boot:run

# ۳) frontend
cd frontend && npm install && npm run dev
```

سپس http://localhost:5173 — شناسه را از ELK کپی کنید و بچسبانید.

### بدون MongoDB، فقط برای دیدن رابط کاربری

```bash
python3 tools/build_fixtures.py
python3 tools/mock_api_server.py 8080      # روی mongomock
cd frontend && npm run dev
```

---

## ⭐ افزودن یک میکروسرویس جدید

سه گام، بدون تغییر کد، بدون استقرار مجدد:

**۱.** در گراف، گرهی که ترجمه نشده مقدار خام را نشان می‌دهد. کلیک کنید و
از پنل جزئیات کپی‌اش کنید:

```
orchestration26.wallet.service.routing.key
```

**۲.** یک سطر به `config/config.json` اضافه کنید:

```json
"routingKeys": {
  "orchestration26.wallet.service.routing.key": "کیف پول"
}
```

**۳.** بازخوانی کنید:

```bash
curl -X POST http://localhost:8080/api/v1/meta/config/reload
```

همین. نوع دستور جدید هم دقیقاً همین‌طور، در `commandTypes`.

> اگر سرویس چند نسخه دارد (`yaghoot25`، `yaghoot26`، …) به‌جای یک سطر
> برای هر نسخه، یک الگو در `routingKeyPatterns` بنویسید تا نسخه‌های آینده
> خودبه‌خود شناخته شوند.

جزئیات کامل: [۳ — راهنمای پیکربندی](docs/03-configuration.md)

---

## چه چیزی نمایش داده می‌شود

**نمای شماتیک** — گراف افقی راست‌به‌چپ: هر گره یک میکروسرویس با نام فارسی،
هر یال یک گام از `commandList`. سبز/قرمز/خاکستری. کلیک روی گره →
`title`، `commandType`، `commandContent`، `response`، متن خطا.

**نمای جدولی** — همهٔ فیلدها به‌صورت مسیر/مقدار، با جستجو و کپی. فیلد
ناشناخته هم اینجاست؛ چیزی گم نمی‌شود.

**JSON خام** — سند کامل با جستجو، حالت مرتب/فشرده، و کپی.

بالای همه یک **کارت خلاصه**: چه عملیاتی، چه شد، کِی، چند مرحله، و اگر
شکست خورده — کدام مرحله و با چه خطایی.

---

## ساختار پروژه

```
config/config.json     ← «چطور نشان بده»: برچسب فارسی، گراف، جستجو
config/config.yaml     ← «چطور بخوان»: اتصال، مسیرها، محدودیت‌ها، ماسک
backend/               ← Spring Boot 3.3 / Java 21 / درایور خام MongoDB
  …/labels             زنجیرهٔ ترجمه: دقیق → الگو → نرمال‌شده → خام
  …/flow               ساخت گراف از commandList
  …/mongo              دروازهٔ داده، نگهبان فقط-خواندن، شمارندهٔ پرس‌وجو
  …/parse              مسیرها، تبدیل نوع، تخت‌کردن سند
frontend/              ← React + Vite، فارسی/RTL، فونت محلی وزیرمتن
  …/components/FlowGraph.jsx   گراف SVG، بدون کتابخانهٔ خارجی
ops/indexes.js         ← بررسی و ساخت ایندکس‌های اختیاری (توسط DBA)
tools/                 ← راستی‌آزمایی و سرور ساختگی
docs/                  ← مستندات
```

---

## راستی‌آزمایی

```bash
python3 tools/build_fixtures.py && python3 tools/verify_generic.py   # ۴۰ بررسی
cd backend && mvn test                                               # ۶۷ تست JUnit
cd frontend && npm run build
```

روی ۱۵۴ سند از **سه schema متفاوت** اجرا می‌شود: ۱۳۵ سند واقعی تولید،
۵ سند با ساختار Elasticsearch، و ۱۴ سند عمداً خراب — آرایهٔ `null`،
عمق ۲۵ لایه، JSON ناقص، رشتهٔ ۳۰۰ کیلوبایتی، نوع‌های غیرمنتظره.

---

## مستندات

| سند | موضوع |
|---|---|
| [۱ — تحلیل](docs/01-analysis.md) | چرا از ELK به MongoDB آمدیم و چه چیزی شکسته بود |
| [۲ — معماری](docs/02-architecture.md) | جریان داده، دو حالت جستجو، گراف، و مرزهای صریح |
| [۳ — پیکربندی](docs/03-configuration.md) | افزودن میکروسرویس، همهٔ کلیدها، عیب‌یابی config |
| [۴ — تضمین‌های ایمنی](docs/04-readonly.md) | فقط-خواندن، بدون پویش، و یک اشکال واقعی که پیدا شد |
| [۵ — استقرار](docs/05-operations.md) | راه‌اندازی، استفادهٔ روزمره، مهاجرت، عیب‌یابی |
