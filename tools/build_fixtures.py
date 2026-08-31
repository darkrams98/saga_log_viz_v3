#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ساخت اسناد نمونه به شکلی که *واقعاً در MongoDB* هستند.

سه منبع:
  ۱) data/sagasample.csv     → ساختار واقعی MongoDB (ستون‌های commandList.N.* دوباره
                                به آرایه تبدیل می‌شوند، تاریخ‌ها به BSON Date)
  ۲) data/es-samples/*.json  → ساختار قدیمی Elasticsearch (کاملاً متفاوت!)
  ۳) اسناد عمداً خراب        → برای اثبات اینکه برنامه نمی‌شکند

خروجی: data/fixtures.json  (فهرستی از اسناد، با نشانهٔ منبع)

نکتهٔ مهم: این اسکریپت *فقط ابزار تست* است. برنامه به آن وابسته نیست.
"""

import csv
import json
import os
import re
import sys
from datetime import datetime, timezone

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(BASE, "data", "sagasample.csv")
ES_DIR = os.path.join(BASE, "data", "es-samples")
OUT = os.path.join(BASE, "data", "fixtures.json")

csv.field_size_limit(10 ** 8)
ISO_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z?$")


def nz(v):
    if v is None:
        return None
    s = v.strip()
    if not s or s.lower() in ("null", "none"):
        return None
    return s


def coerce(value):
    """CSV همه‌چیز را رشته می‌کند؛ نوع‌های اصلی را برمی‌گردانیم."""
    if value is None:
        return None
    if ISO_RE.match(value):
        return {"$date": value}          # نمایندهٔ BSON Date
    if value in ("true", "false"):
        return value == "true"
    if re.fullmatch(r"-?\d{1,15}", value):
        return int(value)
    if re.fullmatch(r"-?\d+\.\d+", value):
        return float(value)
    return value


def csv_to_documents(path):
    """ستون‌های تخت commandList.N.field را دوباره به آرایه تبدیل می‌کند."""
    docs = []
    with open(path, encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            doc = {}
            steps = {}
            for col, raw in row.items():
                value = nz(raw)
                if value is None:
                    continue
                m = re.match(r"^commandList\.(\d+)\.(.+)$", col)
                if m:
                    idx = int(m.group(1))
                    steps.setdefault(idx, {})[m.group(2)] = coerce(value)
                else:
                    doc[col] = coerce(value)
            if steps:
                doc["commandList"] = [steps[i] for i in sorted(steps)]
            docs.append({"_source": "mongo-csv", "doc": doc})
    return docs


def es_to_documents(directory):
    """
    اسناد Elasticsearch. بدنه داخل _source است و شکلش با MongoDB فرق دارد:
    id به‌جای _id، startDate عدد epoch به‌جای Date، commandList[].id به‌جای _id،
    startDate به‌جای StartDate، و rollbackException که در Mongo اصلاً نیست.
    """
    out = []
    if not os.path.isdir(directory):
        return out
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as fh:
            hit = json.load(fh)
        body = hit.get("_source", hit)
        # همان‌طور که هست نگه می‌داریم: هدف اثبات این است که برنامه
        # بدون تغییر کد، *هر دو* شکل را نمایش می‌دهد.
        out.append({"_source": "elasticsearch", "doc": body})
    return out


def _deeply_nested(levels=25):
    """سندی که عمق تودرتویی‌اش از سقف پیمایش بیشتر است."""
    node = {"bottom": "خیلی عمیق"}
    for i in range(levels):
        node = {f"level{levels - i}": node}
    node["_id"] = "deeply-nested"
    node["startDate"] = {"$date": "2026-08-24T09:55:00Z"}
    return node


def broken_documents():
    """اسنادی که عمداً هر فرض ممکنی را نقض می‌کنند."""
    return [
        {"_source": "edge", "doc": {}},
        {"_source": "edge", "doc": {"_id": "only-id"}},
        {"_source": "edge", "doc": {"_id": "wrong-types", "status": 42, "title": ["a", "b"],
                                    "startDate": "نه یک تاریخ", "commandList": "رشته نه آرایه"}},
        {"_source": "edge", "doc": {"_id": "null-everywhere", "status": None, "title": None,
                                    "startDate": None, "commandList": [None, None]}},
        {"_source": "edge", "doc": _deeply_nested()},
        {"_source": "edge", "doc": {"_id": "array-of-arrays",
                                    "matrix": [[1, 2], [3, [4, [5, [6]]]]],
                                    "startDate": {"$date": "2026-08-24T10:00:00Z"}}},
        {"_source": "edge", "doc": {"_id": "unknown-fields", "startDate": {"$date": "2026-08-24T10:05:00Z"},
                                    "brandNewField": "فیلدی که هنگام توسعه وجود نداشت",
                                    "nested": {"anotherNew": {"deep": [1, "two", {"three": 3}]}}}},
        {"_source": "edge", "doc": {"_id": "epoch-millis", "startDate": 1787050099053,
                                    "status": "COMPLETED"}},
        {"_source": "edge", "doc": {"_id": "epoch-seconds", "startDate": 1787050099,
                                    "status": "FAILED"}},
        {"_source": "edge", "doc": {"_id": "iso-string-time", "startDate": "2026-08-24T11:00:00.000Z",
                                    "status": "ROLL_BACKED"}},
        {"_source": "edge", "doc": {"_id": "broken-json-string",
                                    "startDate": {"$date": "2026-08-24T11:05:00Z"},
                                    "payload": '{"unclosed": "json'}},
        {"_source": "edge", "doc": {"_id": "huge-string",
                                    "startDate": {"$date": "2026-08-24T11:10:00Z"},
                                    "blob": "x" * 300_000}},
        {"_source": "edge", "doc": {"_id": "secret-inside",
                                    "startDate": {"$date": "2026-08-24T11:15:00Z"},
                                    "auth": {"password": "9acb8b418297064680b5c2bac83cb98f",
                                             "otp": "590778",
                                             "nationalCode": "1273368304",
                                             "mobile": "09018917308"}}},
        {"_source": "edge", "doc": {"_id": "different-schema-entirely",
                                    "ts": "2026-08-24T11:20:00Z",
                                    "severity": "WARN",
                                    "msg": "سرویسی با ساختار کاملاً متفاوت",
                                    "labels": {"app": "payment-gateway", "pod": "pg-7d9"},
                                    "durationMs": 1234}},
    ]


def main():
    docs = []
    if os.path.exists(CSV_PATH):
        docs.extend(csv_to_documents(CSV_PATH))
    docs.extend(es_to_documents(ES_DIR))
    docs.extend(broken_documents())

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(docs, fh, ensure_ascii=False)

    counts = {}
    for d in docs:
        counts[d["_source"]] = counts.get(d["_source"], 0) + 1
    print(f"نوشته شد: {OUT}")
    print("تعداد اسناد بر اساس منبع:", counts)
    return 0


if __name__ == "__main__":
    sys.exit(main())
