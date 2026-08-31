// =====================================================================
//  ایندکس‌های «نمایشگر لاگ»
// =====================================================================
//  خبر خوب: این نسخه در حالت عادی **هیچ ایندکس تازه‌ای لازم ندارد**.
//
//  چرا؟ چون جستجوی عادی فقط `find({_id: ...})` است و MongoDB خودش روی
//  `_id` یک ایندکس یکتا می‌سازد. پشتیبان شناسه را از قبل از ELK گرفته،
//  پس نیازی به اسکن نیست.
//
//  این فایل دو کار می‌کند:
//    ۱) بررسی می‌کند ایندکس‌های ادعاشده در config.json واقعاً وجود دارند
//    ۲) ایندکس‌های *اختیاری* را می‌سازد، اگر تصمیم گرفتید فیلد دیگری را
//       به «جستجوی عادی» اضافه کنید
//
//  اجرا:
//      mongosh "mongodb://…/saga" ops/indexes.js                    # فقط بررسی
//      mongosh "mongodb://…/saga" --eval "var APPLY=true" ops/indexes.js   # ساخت
//
//  ⚠️  روی مجموعه‌ای با میلیون‌ها سند، ساخت ایندکس زمان‌بر است و هزینهٔ
//      نوشتن را هم بالا می‌برد. فقط ایندکسی بسازید که واقعاً استفاده می‌شود.
// =====================================================================

const COLLECTION = "sagaSequence";           // = mongo.collection در config.yaml
const APPLY_CHANGES = typeof APPLY !== "undefined" && APPLY;

// ---------------------------------------------------------------------
//  ایندکس‌های اختیاری
//
//  هر کدام را که ساختید، در config.json هم فیلد متناظرش را با
//  `"indexed": true, "enabled": true` فعال کنید — وگرنه در UI ظاهر نمی‌شود.
//  اگر برعکس عمل کنید (فعال کنید ولی ایندکس نسازید)، سرویس هنگام
//  راه‌اندازی هشدار می‌دهد و در /api/v1/meta/health دیده می‌شود.
// ---------------------------------------------------------------------
const OPTIONAL = [
  {
    name: "ix_registerId",
    keys: { registerId: 1 },
    configField: "registerId",
    why: "جستجوی مستقیم با شناسهٔ مشتری، وقتی پشتیبان شناسهٔ لاگ را ندارد.",
  },
  {
    name: "ix_deviceId",
    keys: { deviceId: 1 },
    configField: "deviceId",
    why: "پیگیری همهٔ فرایندهای یک دستگاه.",
  },
  {
    name: "ix_command_id",
    keys: { "commandList._id": 1 },
    configField: "commandList._id",
    why: "وقتی فقط شناسهٔ یک *مرحله* در دست است، نه شناسهٔ کل فرایند.",
  },
  {
    name: "ix_startDate",
    keys: { startDate: -1 },
    configField: null,
    why: "فقط اگر خواستید جستجوی پیشرفته را با فیلتر بازهٔ زمانی سریع‌تر کنید. " +
         "این سرویس خودش بازهٔ زمانی نمی‌سازد.",
  },
];

// ---------------------------------------------------------------------
//  ایندکس‌هایی که عمداً پیشنهاد نمی‌شوند
// ---------------------------------------------------------------------
//  • commandList.commandContent / commandList.response
//      رشته‌های JSON چندکیلوبایتی. ایندکس B-tree روی این‌ها بی‌فایده است
//      چون جستجو زیررشته‌ای است. اگر جستجوی متنی سریع لازم شد، جای درستش
//      ELK است — که همین حالا هم دارید.
//
//  • status / title / platform و مانند آن
//      کاردینالیتی پایین. ایندکس‌شان هزینهٔ نوشتن را بالا می‌برد بی‌آنکه
//      جستجوی «یک لاگ مشخص» را سریع‌تر کند. جستجوی پیشرفته عمداً کند است
//      و در UI هم همین گفته می‌شود.
//
//  • ایندکس TTL
//      این سرویس فقط می‌خواند و دربارهٔ نگهداشت داده تصمیمی نمی‌گیرد.
// ---------------------------------------------------------------------

const col = db.getCollection(COLLECTION);
const existing = col.getIndexes();
const signatures = existing.map((ix) => JSON.stringify(ix.key));

print("");
print("مجموعه: " + db.getName() + "." + COLLECTION);
print("تعداد تقریبی اسناد: " + col.estimatedDocumentCount().toLocaleString());
print("");
print("ایندکس‌های موجود:");
existing.forEach((ix) => print("   " + ix.name + "  " + JSON.stringify(ix.key)));
print("");

const hasId = signatures.indexOf('{"_id":1}') !== -1;
print(hasId
  ? "✔ ایندکس _id موجود است — جستجوی عادی سریع کار می‌کند. کار دیگری لازم نیست."
  : "✘ ایندکس _id پیدا نشد! این غیرعادی است؛ با تیم پایگاه داده بررسی کنید.");
print("");

print("ایندکس‌های اختیاری:");
let created = 0;
OPTIONAL.forEach((ix) => {
  const signature = JSON.stringify(ix.keys);
  if (signatures.indexOf(signature) !== -1) {
    print("   [هست]     " + ix.name + "  " + signature);
    if (ix.configField) {
      print("             └─ در config.json فیلد «" + ix.configField +
            "» را می‌توانید enabled و indexed کنید.");
    }
    return;
  }
  if (!APPLY_CHANGES) {
    print("   [نیست]    " + ix.name + "  " + signature);
    print("             └─ " + ix.why);
    return;
  }
  try {
    col.createIndex(ix.keys, { name: ix.name, background: true });
    print("   [ساخته شد] " + ix.name + "  " + signature);
    created++;
  } catch (e) {
    print("   [خطا]     " + ix.name + " → " + e.message);
  }
});

print("");
if (!APPLY_CHANGES) {
  print("حالت بررسی. برای ساخت: mongosh … --eval \"var APPLY=true\" ops/indexes.js");
} else {
  print(created + " ایندکس ساخته شد.");
  print("یادتان باشد فیلد متناظر را در config.json فعال کنید و بعد:");
  print("   curl -X POST http://localhost:8080/api/v1/meta/config/reload");
}
print("");

// ---------------------------------------------------------------------
//  کاربر فقط-خواندنی سرویس
// ---------------------------------------------------------------------
//  آخرین لایهٔ دفاع؛ تنها لایه‌ای که به کد ما اعتماد نمی‌کند.
//  عمداً کامنت است تا کسی سهواً کاربر نسازد — با DBA هماهنگ کنید.
//
//  db.getSiblingDB("admin").createUser({
//    user: "log_viewer_ro",
//    pwd: passwordPrompt(),
//    roles: [ { role: "read", db: "saga" } ]
//  })
//
//  بررسی اینکه واقعاً فقط-خواندنی است:
//    mongosh "$MONGO_URI" --eval 'db.sagaSequence.insertOne({x:1})'
//    → باید با «not authorized on saga to execute command insert» رد شود.
// ---------------------------------------------------------------------
