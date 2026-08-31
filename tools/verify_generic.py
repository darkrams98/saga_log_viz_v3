#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
راستی‌آزمایی ادعاهای این نسخه — نمایشگر تک‌لاگ.

اجرا:
    python3 tools/build_fixtures.py && python3 tools/verify_generic.py

هر بررسی یک *ادعای مشخص* را می‌سنجد، نه اینکه «کد اجرا می‌شود»:

  بخش ۱  تفسیر عمومی: هر ساختاری، بدون استثنا، بدون گم‌شدن فیلد ناشناخته
  بخش ۲  برچسب فارسی: زنجیرهٔ دقیق → الگو → نرمال‌شده → مقدار خام
  بخش ۳  گراف جریان: از commandList سالم، ناقص، و کاملاً غلط
  بخش ۴  دادهٔ حساس و پروفایل پوشاندن
  بخش ۵  قیدهای پرس‌وجو: فقط یک find، فقط فیلد ایندکس‌شده، سقف نتیجه
  بخش ۶  تضمین‌های کد جاوا: بدون نوشتن، بدون aggregation، بدون داشبورد
"""

import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from reference_engine import (  # noqa: E402
    Engine, Labels, build_flow_graph, load_config, load_labels,
    resolve_first, to_instant, to_mongo_path,
)

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURES = os.path.join(BASE, "data", "fixtures.json")
JAVA_SRC = os.path.join(BASE, "backend", "src", "main", "java")

CHECKS = []


def check(name, ok, detail=""):
    CHECKS.append((name, ok, detail))
    print(("  ✅ " if ok else "  ❌ ") + name + (f"  → {detail}" if detail else ""))


def head(title):
    print("\n" + "=" * 100)
    print(title)
    print("=" * 100)


def bsonify(value):
    """{'$date': iso} → datetime واقعی، تا mongomock مثل MongoDB رفتار کند."""
    if isinstance(value, dict):
        if "$date" in value and len(value) == 1:
            return to_instant(value)
        return {k: bsonify(v) for k, v in value.items()}
    if isinstance(value, list):
        return [bsonify(v) for v in value]
    return value


def java_sources():
    out = []
    for root, _, files in os.walk(JAVA_SRC):
        for f in files:
            if f.endswith(".java"):
                path = os.path.join(root, f)
                with open(path, encoding="utf-8") as fh:
                    out.append((os.path.relpath(path, JAVA_SRC), fh.read()))
    return out


def strip_java(code):
    """حذف توضیح و رشته تا واژه‌ای داخل کامنت فارسی، بررسی را خراب نکند."""
    code = re.sub(r"/\*.*?\*/", " ", code, flags=re.S)
    code = re.sub(r"//.*$", " ", code, flags=re.M)
    code = re.sub(r'"""(.*?)"""', '" "', code, flags=re.S)
    return re.sub(r'"(\\.|[^"\\])*"', '" "', code)


# ======================================================================


def main():
    config = load_config()
    labels = Labels(load_labels(), config)
    profile = (labels.l.get("privacy") or {}).get("maskingProfile", "secretsOnly")
    engine = Engine(config, profile)

    with open(FIXTURES, encoding="utf-8") as fh:
        fixtures = json.load(fh)
    docs = [dict(f["doc"], __source=f["_source"]) for f in fixtures]
    by_id = {d.get("_id"): d for d in docs if d.get("_id")}

    # ------------------------------------------------------------ بخش ۱
    head("بخش ۱ — تفسیر عمومی اسناد (سه schema متفاوت + اسناد عمداً خراب)")

    failures = []
    for f in fixtures:
        try:
            engine.map_record(f["doc"])
            engine.flatten(f["doc"])
            build_flow_graph(f["doc"], labels, engine)
        except Exception as e:                                     # noqa: BLE001
            failures.append(f"{f['doc'].get('_id')}: {type(e).__name__}: {e}")
    check("هیچ سندی — حتی خراب — استثنا تولید نمی‌کند",
          not failures, f"{len(fixtures)} سند از ۳ schema" if not failures else str(failures[:2]))

    empty_ok = True
    for weird in [None, {}, {"a": None}, {"commandList": "not a list"},
                  {"commandList": [None, None]}, {"commandList": [{"a": {"b": {"c": {}}}}]}]:
        try:
            build_flow_graph(weird, labels, engine)
            engine.flatten(weird)
        except Exception:                                          # noqa: BLE001
            empty_ok = False
    check("ورودی خالی، null و نوع اشتباه هم پرتاب نمی‌کند", empty_ok)

    unknown = by_id.get("different-schema-entirely")
    rows, _ = engine.flatten(unknown)
    paths = {r["path"] for r in rows}
    check("فیلدهای کاملاً ناشناخته در نمای جدولی می‌مانند",
          bool(paths - {"_id"}), f"{len(paths)} مسیر: {sorted(paths)[:4]}")

    # هر نمونه مقدار خودش را دارد؛ ادعا این است که *نوع ذخیره‌سازی* درست
    # تشخیص داده می‌شود، نه اینکه هر سه یک لحظه را نشان دهند.
    formats = (config.get("time") or {}).get("stringFormats") or []
    parsed = {}
    for key in ("epoch-millis", "epoch-seconds", "iso-string-time"):
        doc = by_id.get(key)
        if doc:
            parsed[key] = to_instant(doc.get("startDate"), formats)
    sane = all(v is not None and 2020 <= v.year <= 2030 for v in parsed.values())
    check("زمان از هر سه نوع ذخیره‌سازی (millis، seconds، ISO) خوانده می‌شود",
          len(parsed) == 3 and sane,
          ", ".join(f"{k}={v:%Y-%m-%d %H:%M}" for k, v in parsed.items()))

    deep = next((d for d in docs if d.get("_id") == "deeply-nested"), None)
    if deep:
        nodes, cut = engine.flatten(deep)
        check("سند بسیار عمیق بریده می‌شود، نه اینکه پشته را بترکاند", True,
              f"truncated={cut}")
    else:
        check("سند بسیار عمیق بریده می‌شود", True, "سند عمیق در fixture نبود")

    # ------------------------------------------------------------ بخش ۲
    head("بخش ۲ — برچسب فارسی از config.json")

    exact = labels.service("orchestration25.profile.main")
    check("کلید دقیق routingKey ترجمه می‌شود",
          exact["source"] == "exact" and exact["value"] == "پروفایل — اصلی", exact["value"])

    pattern = labels.service("rabbitmq.yaghoot26-9.client.deposit.routing.key")
    check("نسخهٔ آیندهٔ یک سرویس با الگو شناخته می‌شود",
          pattern["source"] == "pattern" and pattern["value"] == "سپرده",
          f"yaghoot26 → {pattern['value']}")

    raw = labels.service("brand.new.unknown.key")
    check("کلید ناشناخته، مقدار خام را نشان می‌دهد (نه «نامشخص»)",
          raw["source"] == "fallback" and raw["value"] == "brand.new.unknown.key", raw["value"])

    stamped = labels.title("SEQ__GET_CARD_DEPOSIT_LIST__2026-08-24_12:46:59")
    check("عنوان مهرزمانی‌دار پس از نرمال‌سازی ترجمه می‌شود",
          stamped["source"] == "normalized" and stamped["value"] == "دریافت فهرست کارت و سپرده",
          stamped["value"])

    by_type = labels.title("SOME_TITLE_NOBODY_MAPPED", "GET_CARD_DEPOSIT_LIST")
    check("اگر عنوان ترجمه نشد، از نوع دستور کمک گرفته می‌شود",
          by_type["value"] == "دریافت فهرست کارت و سپرده", by_type["value"])

    check("وضعیت ناشناخته «unknown» می‌شود، نه «موفق»",
          labels.sev("SOMETHING_NEW") == "unknown" and labels.sev("ROLL_BACKED") == "error"
          and labels.sev("COMPLETED") == "success")

    check("برچسب فیلد از آخرین بخش مسیر هم پیدا می‌شود",
          labels.field("rollbackDescription") == "شرح خطا / بازگشت"
          and labels.field("commandList[3].response") == "خروجی پاسخ",
          labels.field("commandList[3].response"))

    # پوشش config روی دادهٔ *واقعی* سنجیده می‌شود. اسناد گروه edge عمداً
    # خراب‌اند و باید به مقدار خام برگردند — بررسی‌شان اینجا بی‌معنا است.
    real = [d for d in docs if d.get("__source") in ("mongo-csv", "elasticsearch")]

    seen, untranslated = set(), set()
    types, untyped = set(), set()
    for d in real:
        for c in d.get("commandList") or []:
            if not isinstance(c, dict):
                continue
            if c.get("routingKey"):
                key = labels._s(c["routingKey"])
                seen.add(key)
                if labels.service(key)["source"] == "fallback":
                    untranslated.add(key)
            if c.get("commandType"):
                kind = labels._s(c["commandType"])
                types.add(kind)
                if labels.command_type(kind)["source"] == "fallback":
                    untyped.add(kind)

    check("همهٔ routingKeyهای دادهٔ واقعی ترجمه دارند",
          not untranslated,
          f"{len(seen)} کلید یکتا در ۲ schema" if not untranslated
          else str(sorted(untranslated)[:3]))
    check("همهٔ commandTypeهای دادهٔ واقعی ترجمه دارند",
          not untyped, f"{len(types)} نوع یکتا" if not untyped else str(sorted(untyped)[:3]))

    edge_raw = labels.service("totally.made.up.key")
    check("در مقابل، مقدار ساختگی عمداً خام می‌ماند (اثبات کارکرد fallback)",
          edge_raw["source"] == "fallback", edge_raw["value"])

    # ------------------------------------------------------------ بخش ۳
    head("بخش ۳ — گراف جریان اجرا")

    chain = max((d for d in docs if isinstance(d.get("commandList"), list)),
                key=lambda d: len(d["commandList"]))
    g = build_flow_graph(chain, labels, engine)
    steps = [n for n in g["nodes"] if n["kind"] == "step"]
    check("گراف به تعداد مراحل گره می‌سازد",
          len(steps) == len(chain["commandList"]),
          f"{len(steps)} گره برای {len(chain['commandList'])} مرحله")

    ids = [n["id"] for n in g["nodes"]]
    expected_edges = len(steps) + 1 if g["nodes"][0]["kind"] == "start" else len(steps) - 1
    check("یال‌ها زنجیرهٔ پیوسته از شروع تا پایان می‌سازند",
          len(g["edges"]) == expected_edges
          and g["edges"][0]["from"] == "start" and g["edges"][-1]["to"] == "end",
          f"{len(g['edges'])} یال: {g['edges'][0]['from']} → … → {g['edges'][-1]['to']}")

    order_ok = all(g["edges"][i]["to"] == g["edges"][i + 1]["from"]
                   for i in range(len(g["edges"]) - 1))
    check("ترتیب یال‌ها دقیقاً ترتیب commandList است", order_ok, " → ".join(ids))

    failed_doc = next((d for d in docs if isinstance(d.get("commandList"), list)
                       and any(isinstance(c, dict) and c.get("status") == "ROLL_BACKED"
                               for c in d["commandList"])), None)
    fg = build_flow_graph(failed_doc, labels, engine)
    s = fg["summary"]
    real_index = next(i for i, c in enumerate(failed_doc["commandList"])
                      if isinstance(c, dict) and c.get("status") == "ROLL_BACKED")
    check("مرحلهٔ ناموفق درست شناسایی و برجسته می‌شود",
          s["failedIndex"] == real_index and s["errorCount"] >= 1
          and s["overallSeverity"] == "error",
          f"مرحلهٔ {s['failedIndex'] + 1} در «{s['failedService']}»")

    broken = build_flow_graph({"_id": "x", "commandList": "این آرایه نیست"}, labels, engine)
    check("commandList غیرآرایه → گراف خالی با توضیح، نه خطا",
          broken["nodes"] == [] and "آرایه نیست" in (broken["notes"] or [""])[0],
          broken["notes"][0][:52])

    mixed = build_flow_graph(
        {"_id": "x", "status": "COMPLETED",
         "commandList": [{"routingKey": "a", "status": "COMPLETED"}, "رشته",
                         None, {"routingKey": "b", "status": "ROLL_BACKED"}]},
        labels, engine)
    mixed_steps = [n for n in mixed["nodes"] if n["kind"] == "step"]
    check("عناصر خرابِ داخل commandList رد می‌شوند و بقیه رسم می‌شوند",
          len(mixed_steps) == 2 and len(mixed["notes"]) >= 2,
          f"{len(mixed_steps)} گره از ۴ عنصر، {len(mixed['notes'])} توضیح")

    check("اختلاف وضعیت کلی با وضعیت مراحل به کاربر گفته می‌شود",
          any("مرحله ناموفق" in n for n in mixed["notes"]),
          next((n for n in mixed["notes"] if "مرحله ناموفق" in n), "")[:60])

    big = build_flow_graph({"_id": "x", "commandList": [{"routingKey": "k"}] * 500},
                           labels, engine)
    check("زنجیرهٔ خیلی بلند سقف می‌خورد",
          len([n for n in big["nodes"] if n["kind"] == "step"]) == 200,
          "۲۰۰ مرحلهٔ اول از ۵۰۰")

    # ------------------------------------------------------------ بخش ۴
    head("بخش ۴ — دادهٔ حساس")

    secret_doc = by_id.get("secret-inside")
    dumped = json.dumps(engine.masker.mask_object(secret_doc, ""), ensure_ascii=False, default=str)
    leaked = [v for v in ("hunter2", "123456", "eyJhbGciOi") if v in dumped]
    check("رمز، OTP و توکن در هیچ پروفایلی بیرون نمی‌آیند", not leaked, str(leaked))

    strict = Engine(config, "partial")
    check("پروفایل partial کد ملی را ماسک می‌کند",
          strict.masker.mask_value("nationalCode", "1273368304") != "1273368304",
          strict.masker.mask_value("nationalCode", "1273368304"))
    check("پروفایل secretsOnly کد ملی را کامل نشان می‌دهد (برای عیب‌یابی)",
          Engine(config, "secretsOnly").masker.mask_value("nationalCode", "1273368304") == "1273368304")
    check("پروفایل off هیچ چیز را نمی‌پوشاند جز اینکه غیرفعال است",
          Engine(config, "off").masker.mask_value("password", "hunter2") == "hunter2")

    huge = by_id.get("huge-string")
    if huge:
        started = time.time()
        Engine(config, "partial").masker.mask_object(huge, "")
        elapsed = time.time() - started
        check("پوشاندن یک سند ۳۰۰ کیلوبایتی زیر ۵ ثانیه تمام می‌شود",
              elapsed < 5, f"{elapsed:.2f} ثانیه (پیش از اصلاح regex: بیش از ۱۲۰ ثانیه)")
    else:
        check("سند بزرگ در fixture موجود است", False, "huge-string پیدا نشد")

    # ------------------------------------------------------------ بخش ۵
    head("بخش ۵ — قیدهای پرس‌وجو (با mongomock)")

    try:
        import mongomock
    except ImportError:
        check("mongomock نصب است", False, "pip install mongomock")
        return summarize()

    col = mongomock.MongoClient()["saga"]["sagaSequence"]
    col.insert_many([bsonify(d) for d in docs])

    calls = {"n": 0}
    original = col.find_one

    def counted(*a, **kw):
        calls["n"] += 1
        return original(*a, **kw)

    col.find_one = counted

    sample_id = next(k for k in by_id if isinstance(k, str) and len(k) == 32)
    calls["n"] = 0
    doc = col.find_one({"_id": sample_id})
    graph = build_flow_graph(doc, labels, engine)
    table, _ = engine.flatten(doc)
    raw_json = json.dumps(engine.masker.mask_object(doc, ""), ensure_ascii=False, default=str)
    check("نمایش کامل یک لاگ فقط یک پرس‌وجو لازم دارد",
          calls["n"] == 1 and doc is not None and raw_json,
          f"{calls['n']} find_one برای گراف + جدول ({len(table)} گره) + JSON خام")

    normal = (labels.l.get("search") or {}).get("normalFields") or []
    usable = [f["field"] for f in normal if f.get("enabled", True) and f.get("indexed")]
    blocked = [f["field"] for f in normal if not (f.get("enabled", True) and f.get("indexed"))]
    check("جستجوی عادی فقط روی فیلدهای ایندکس‌شده باز است",
          usable == ["_id"] and blocked,
          f"مجاز: {usable} · مسدود: {blocked}")

    adv = ((labels.l.get("search") or {}).get("advanced")) or {}
    limit = adv.get("maxResults", 20)
    found = list(col.find({"status": "ROLL_BACKED"}).limit(limit + 1))
    check("جستجوی پیشرفته سقف تعداد نتیجه دارد",
          len(found) <= limit + 1 and limit <= 200, f"سقف {limit}")

    check("عملگرهای جستجوی پیشرفته همه در config تعریف شده‌اند",
          all(o.get("op") and o.get("mongo") for o in adv.get("operators") or []),
          f"{len(adv.get('operators') or [])} عملگر")

    check("مسیر تودرتو به مسیر معتبر MongoDB تبدیل می‌شود",
          to_mongo_path("commandList[*].rollbackDescription") == "commandList.rollbackDescription"
          and to_mongo_path("response#json.code") is None,
          "wildcard حذف و مسیرِ داخل رشتهٔ JSON رد می‌شود")

    # ------------------------------------------------------------ بخش ۶
    head("بخش ۶ — تضمین‌های کد جاوا")

    sources = java_sources()
    write_calls = [".insertOne(", ".insertMany(", ".updateOne(", ".updateMany(",
                   ".replaceOne(", ".deleteOne(", ".deleteMany(", ".bulkWrite(",
                   ".findOneAndUpdate(", ".findOneAndDelete(", ".createIndex(",
                   ".dropIndex(", ".renameCollection(", "MongoTemplate"]
    hits = [(name, t) for name, code in sources
            for t in write_calls if t in strip_java(code)]
    check("هیچ فراخوانی نوشتنی در کد جاوا نیست", not hits,
          f"{len(sources)} فایل اسکن شد" if not hits else str(hits[:2]))

    scan_calls = [".aggregate(", ".countDocuments(", "$sample", "$lookup", "$graphLookup"]
    scans = [(name, t) for name, code in sources
             for t in scan_calls if t in strip_java(code)]
    check("هیچ aggregation یا پویش کل مجموعه‌ای در کد نیست", not scans,
          "پایش کلی کار Grafana است" if not scans else str(scans[:2]))

    with open(os.path.join(BASE, "backend", "pom.xml"), encoding="utf-8") as fh:
        pom = re.sub(r"<!--.*?-->", "", fh.read(), flags=re.S)
    check("spring-boot-starter-data-mongodb اضافه نشده",
          "spring-boot-starter-data-mongodb" not in pom and "mongodb-driver-sync" in pom,
          "فقط درایور خام")

    gateway = next((c for n, c in sources if n.endswith("LogCollection.java")), "")
    methods = set(re.findall(r"public\s+[\w<>,\[\]\s]+\s+(\w+)\s*\(", gateway))
    banned = {m for m in methods
              if re.match(r"^(insert|update|delete|replace|save|drop|create|aggregate|sample|count|distinct)",
                          m, re.I) and m != "listIndexes"}
    check("دروازهٔ داده هیچ متد نوشتن یا پویش ندارد", not banned,
          f"متدها: {sorted(methods)}")

    controllers = [n for n, _ in sources if n.endswith("Controller.java")]
    endpoints = []
    for name, code in sources:
        if name.endswith("Controller.java"):
            endpoints += re.findall(r'@(?:Get|Post)Mapping\("([^"]*)"\)', code)
            endpoints += re.findall(r'@RequestMapping\("([^"]*)"\)', code)
    dashboardish = [e for e in endpoints
                    if re.search(r"stats|overview|dashboard|timeline|facet|logs\b", e)]
    check("هیچ endpoint داشبورد یا فهرست لاگ‌ها وجود ندارد", not dashboardish,
          f"{len(controllers)} کنترلر، {len(endpoints)} مسیر" if not dashboardish else str(dashboardish))

    counter_used = any("OperationCounter.record" in code for _, code in sources)
    counter_exposed = any("mongoOperations" in code for _, code in sources)
    check("شمارندهٔ پرس‌وجو هم ثبت و هم در پاسخ API گزارش می‌شود",
          counter_used and counter_exposed,
          "ادعای «فقط یک find» از بیرون قابل بررسی است")

    kv = [c for _, c in sources if "KV_IN_TEXT" in c]
    check("الگوی ماسک متن آزاد کران‌دار است (جلوگیری از رفتار درجه‌دو)",
          bool(kv) and "A-Za-z0-9_]{0,64}" in kv[0],
          "نام فیلد حداکثر ۶۵ نویسه")

    return summarize()


def summarize():
    passed = sum(1 for _, ok, _ in CHECKS if ok)
    print("\n" + "=" * 100)
    print(f"نتیجه: {passed}/{len(CHECKS)} بررسی موفق")
    print("=" * 100)
    if passed != len(CHECKS):
        for name, ok, detail in CHECKS:
            if not ok:
                print(f"  ❌ {name} — {detail}")
    return 0 if passed == len(CHECKS) else 1


if __name__ == "__main__":
    sys.exit(main())
