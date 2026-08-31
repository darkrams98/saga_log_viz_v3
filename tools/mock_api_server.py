#!/usr/bin/env python3
"""
سرور API ساختگی برای توسعهٔ رابط کاربری — بدون نیاز به MongoDB واقعی.

روی mongomock اجرا می‌شود و دقیقاً همان قراردادهای نسخهٔ جاوا را رعایت می‌کند:

  • جستجوی عادی فقط روی فیلدهایی که در config.json هم enabled و هم indexed اند
  • یافتن یک لاگ = دقیقاً یک find_one، و همین عدد در پاسخ برمی‌گردد
  • جستجوی پیشرفته: بدون sort، با سقف تعداد و سقف زمان
  • هیچ endpointی برای فهرست، آمار یا aggregation وجود ندارد

اجرا:
    python3 tools/build_fixtures.py && python3 tools/mock_api_server.py 8080
"""

import json
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reference_engine as R  # noqa: E402

import mongomock  # noqa: E402

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURES = os.path.join(BASE, "data", "fixtures.json")

# شمارندهٔ پرس‌وجو در هر درخواست — معادل OperationCounter.java
_local = threading.local()


def _record(op):
    ops = getattr(_local, "ops", None)
    if ops is not None:
        ops.append(op)


def _start():
    _local.ops = []


def _stop():
    ops = getattr(_local, "ops", []) or []
    _local.ops = None
    return list(ops)


class Store:
    def __init__(self):
        self.config = R.load_config()
        self.labels = R.Labels(R.load_labels(), self.config)
        self.engine = R.Engine(self.config, self._profile())
        client = mongomock.MongoClient()
        self.col = client["saga"]["sagaSequence"]
        with open(FIXTURES, encoding="utf-8") as f:
            fixtures = json.load(f)
        # هر fixture یک پوشش {_source, doc} دارد؛ سند واقعی داخل doc است.
        # نشانهٔ __source نگه داشته می‌شود تا در نمای جدولی معلوم باشد
        # این سند از کدام schema آمده — دقیقاً همان حالتی که موتور باید
        # با فیلد ناشناخته کنار بیاید.
        docs = [dict(f["doc"], __source=f["_source"]) for f in fixtures]
        self.col.insert_many(docs)
        print(f"  {len(docs)} سند در mongomock بارگذاری شد")

    def _profile(self):
        return (self.labels.l.get("privacy") or {}).get("maskingProfile", "secretsOnly")

    def reload(self):
        self.config = R.load_config()
        self.labels = R.Labels(R.load_labels(), self.config)
        self.engine = R.Engine(self.config, self._profile())

    # ------------------------------------------------------ جستجوی عادی

    def normal_fields(self):
        return (self.labels.l.get("search") or {}).get("normalFields") or []

    def allowed_field(self, name):
        for f in self.normal_fields():
            if f.get("field") == name and f.get("enabled", True) and f.get("indexed", False):
                return f
        return None

    def default_field(self):
        usable = [f for f in self.normal_fields()
                  if f.get("enabled", True) and f.get("indexed", False)]
        for f in usable:
            if f.get("default"):
                return f
        return usable[0] if usable else None

    def find_by_field(self, field, value):
        _record("findOne")
        return self.col.find_one({field: value})

    # -------------------------------------------------- جستجوی پیشرفته

    def advanced_cfg(self):
        return ((self.labels.l.get("search") or {}).get("advanced")) or {}

    def advanced(self, filters):
        cfg = self.advanced_cfg()
        if not cfg.get("enabled", True):
            raise ValueError("جستجوی پیشرفته در config.json غیرفعال است.")
        if not filters:
            raise ValueError("حداقل یک فیلتر لازم است. جستجوی بدون فیلتر یعنی خواندن "
                             "کل مجموعه، که مجاز نیست.")
        if len(filters) > 10:
            raise ValueError("حداکثر ۱۰ فیلتر پشتیبانی می‌شود.")

        ops = {o["op"]: o for o in (cfg.get("operators") or [])}
        conditions = []
        for f in filters:
            conditions.append(self._condition(f, ops))
        query = conditions[0] if len(conditions) == 1 else {"$and": conditions}

        limit = int(cfg.get("maxResults", 20))
        _record("find")
        # بدون sort — همان قاعدهٔ نسخهٔ جاوا
        docs = list(self.col.find(query).limit(limit + 1))
        capped = len(docs) > limit
        return docs[:limit], capped, limit

    def _condition(self, f, ops):
        field = (f.get("field") or "").strip()
        if not field:
            raise ValueError("نام فیلد در یکی از فیلترها خالی است.")
        if field.startswith("$"):
            raise ValueError("نام فیلد نمی‌تواند با $ شروع شود.")
        op = f.get("op") or "eq"
        if op not in ops:
            raise ValueError(f"عملگر «{op}» شناخته نشد.")
        raw = (f.get("value") or "").strip()

        def need():
            if not raw:
                raise ValueError("مقدار یکی از فیلترها خالی است.")
            return raw

        if op == "exists":
            return {field: {"$exists": raw.lower() != "false"}}
        if op == "contains":
            return {field: {"$regex": re.escape(need()), "$options": "i"}}
        if op == "prefix":
            return {field: {"$regex": "^" + re.escape(need()), "$options": "i"}}
        if op == "in":
            values = [self._guess(v.strip()) for v in need().split(",") if v.strip()]
            return {field: {"$in": values}}
        if op == "eq":
            return {field: self._guess(need())}
        return {field: {ops[op]["mongo"]: self._guess(need())}}

    @staticmethod
    def _guess(raw):
        if raw.lower() in ("true", "false"):
            return raw.lower() == "true"
        if re.fullmatch(r"-?\d{1,15}", raw):
            return int(raw)
        if re.fullmatch(r"-?\d+\.\d+", raw):
            return float(raw)
        return raw

    # --------------------------------------------------- آماده‌سازی نما

    def present(self, doc):
        warnings = []
        raw = dict(doc)
        log_id = R.to_text(raw.get("_id"))

        try:
            graph = R.build_flow_graph(raw, self.labels, self.engine)
        except Exception as e:                                    # noqa: BLE001
            warnings.append(f"گراف ساخته نشد: {type(e).__name__}")
            graph = R.build_flow_graph(None, self.labels, self.engine)

        table, truncated = [], False
        try:
            nodes, cut = self.engine.flatten(raw)
            truncated = cut
            for n in nodes:
                self._rows(n, 0, table)
        except Exception as e:                                    # noqa: BLE001
            warnings.append(f"نمای جدولی کامل ساخته نشد: {type(e).__name__}")

        try:
            masked = self.engine.masker.mask_object(raw, "")
            raw_json = json.dumps(masked, ensure_ascii=False, indent=2, default=str)
            size = len(json.dumps(raw, ensure_ascii=False, default=str))
        except Exception:                                          # noqa: BLE001
            raw_json, size = "{}", 0
            warnings.append("تولید JSON خام ناموفق بود.")

        return {
            "id": log_id,
            "header": self._header(raw, graph),
            "summary": self._summary(raw),
            "graph": graph,
            "table": table,
            "tableTruncated": truncated,
            "rawJson": raw_json,
            "rawSizeBytes": size,
            "maskingProfile": (self.labels.l.get("privacy") or {}).get("maskingProfile", "secretsOnly"),
            "warnings": warnings,
        }

    def _rows(self, node, depth, out):
        if not node or len(out) > 5000:
            return
        container = bool(node.get("children")) or node.get("childCount") is not None
        out.append({
            "path": node.get("path"),
            "label": self.labels.field(node.get("path")),
            "key": node.get("key"),
            "type": node.get("type"),
            "value": node.get("value"),
            "depth": depth,
            "masked": node.get("masked", False),
            "truncated": node.get("truncated", False),
            "sizeBytes": node.get("sizeBytes", 0),
            "container": container,
            "childCount": node.get("childCount"),
        })
        for child in node.get("children") or []:
            self._rows(child, depth + 1, out)

    def _header(self, raw, graph):
        raw_title = R.to_text(R.resolve_first(raw, "title"))
        raw_status = R.to_text(R.resolve_first(raw, "status"))
        time_cfg = self.config.get("time") or {}
        start = None
        for candidate in time_cfg.get("candidates") or []:
            start = R.to_instant(R.resolve_first(raw, candidate), time_cfg.get("stringFormats") or [])
            if start:
                break
        end = R.to_instant(R.resolve_first(raw, "completeDate"), time_cfg.get("stringFormats") or [])
        duration = None
        if start and end and end >= start:
            ms = (end - start).total_seconds() * 1000
            duration = f"{int(ms)} میلی‌ثانیه" if ms < 1000 else f"{ms / 1000:.1f} ثانیه"

        summary = graph["summary"]
        return {
            "title": self.labels.title(raw_title)["value"],
            "rawTitle": raw_title,
            "status": self.labels.status(raw_status)["value"],
            "rawStatus": raw_status,
            "severity": summary["overallSeverity"],
            "startedAt": start.isoformat().replace("+00:00", "Z") if start else None,
            "completedAt": end.isoformat().replace("+00:00", "Z") if end else None,
            "durationText": duration,
            "stepCount": summary["stepCount"],
            "errorCount": summary["errorCount"],
        }

    def _summary(self, raw):
        out = []
        for f in self.labels.l.get("summaryFields") or []:
            value = R.resolve_first(raw, f.get("path"))
            if R.is_empty(value):
                continue
            text = R.to_text(value, 300)
            raw_text = text
            translated = False
            if f.get("translate"):
                label = self.labels.by_map(f["translate"], text)
                text, translated = label["value"], label["source"] != "fallback"
            elif f.get("type") == "datetime":
                inst = R.to_instant(value, (self.config.get("time") or {}).get("stringFormats") or [])
                text = inst.isoformat().replace("+00:00", "Z") if inst else text
            out.append({
                "path": f["path"], "label": f.get("label", f["path"]),
                "value": self.engine.masker.mask_value(f["path"], text),
                "rawValue": raw_text, "type": f.get("type", "text"),
                "copy": bool(f.get("copy")), "translated": translated,
            })
        return out


STORE = None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):                                          # noqa: N802
        self._send(204, {})

    def do_GET(self):                                              # noqa: N802
        url = urlparse(self.path)
        path = url.path
        query = parse_qs(url.query)
        _start()
        try:
            if path == "/api/v1/meta/ui":
                return self._send(200, self._ui())
            if path == "/api/v1/meta/health":
                return self._send(200, self._health())
            if path == "/api/v1/log/search/fields":
                return self._send(200, self._search_fields())
            if path == "/api/v1/log/advanced/config":
                return self._send(200, self._advanced_cfg())
            if path.startswith("/api/v1/log/"):
                return self._by_id(path.rsplit("/", 1)[-1], query)
            return self._send(404, {"message": "مسیر پیدا نشد"})
        except Exception as e:                                     # noqa: BLE001
            return self._send(500, {"message": str(e)})
        finally:
            _stop()

    def do_POST(self):                                             # noqa: N802
        url = urlparse(self.path)
        _start()
        try:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}") if length else {}
            if url.path == "/api/v1/log/advanced":
                return self._advanced(body)
            if url.path == "/api/v1/meta/config/reload":
                STORE.reload()
                return self._send(200, {"reloaded": True,
                                        "routingKeys": len(STORE.labels.routing),
                                        "commandTypes": len(STORE.labels.command_types)})
            if url.path == "/api/v1/meta/indexes/inspect":
                return self._send(200, self._index_report())
            return self._send(404, {"message": "مسیر پیدا نشد"})
        except ValueError as e:
            return self._send(400, {"message": str(e)})
        except Exception as e:                                     # noqa: BLE001
            return self._send(500, {"message": str(e)})
        finally:
            _stop()

    # ------------------------------------------------------------ مسیرها

    def _by_id(self, log_id, query):
        from urllib.parse import unquote
        log_id = unquote(log_id)
        field = (query.get("field") or [None])[0]
        if not field:
            default = STORE.default_field()
            if not default:
                return self._send(400, {"message": "هیچ فیلد جستجوی ایندکس‌شده‌ای فعال نیست."})
            field = default["field"]
        if not STORE.allowed_field(field):
            return self._send(400, {"message":
                f"جستجو روی «{field}» در حالت عادی مجاز نیست، چون این فیلد در config.json "
                "به‌عنوان فیلد ایندکس‌شده معرفی نشده است."})

        doc = STORE.find_by_field(field, log_id)
        ops = list(getattr(_local, "ops", []) or [])
        if not doc:
            return self._send(404, {
                "found": False, "searchedField": field,
                "message": "لاگی با این شناسه پیدا نشد.",
                "hint": "شناسه را از ELK دوباره کپی کنید؛ فاصله یا نویسهٔ اضافه شایع‌ترین "
                        "علت است. اگر مطمئنید شناسه درست است، ممکن است لاگ هنوز به MongoDB "
                        "نرسیده یا بر اساس سیاست نگهداشت حذف شده باشد.",
                "mongoOperations": len(ops), "operations": ops})

        view = STORE.present(doc)
        view["found"] = True
        view["searchedField"] = field
        view["mongoOperations"] = len(ops)
        view["operations"] = ops
        return self._send(200, view)

    def _advanced(self, body):
        docs, capped, limit = STORE.advanced(body.get("filters") or [])
        cfg = STORE.advanced_cfg()
        columns = cfg.get("resultFields") or []
        hits = []
        for d in docs:
            fields = {}
            for c in columns:
                value = R.resolve_first(d, c["path"])
                text = R.to_text(value, 200)
                if c.get("translate"):
                    text = STORE.labels.by_map(c["translate"], text)["value"]
                elif c.get("type") == "datetime":
                    inst = R.to_instant(value, (STORE.config.get("time") or {}).get("stringFormats") or [])
                    text = inst.isoformat().replace("+00:00", "Z") if inst else text
                fields[c["path"]] = STORE.engine.masker.mask_free_text(text or "")
            hits.append({"id": R.to_text(d.get("_id")), "fields": fields})

        notes = []
        if capped:
            notes.append(f"بیش از {limit} نتیجه وجود دارد؛ فقط {limit} مورد اول نمایش داده شد. "
                         "فیلتر را دقیق‌تر کنید.")
        notes.append("نتایج به ترتیب زمانی نیستند — مرتب‌سازی روی فیلد بدون ایندکس سنگین است "
                     "و عمداً انجام نشده.")
        ops = list(getattr(_local, "ops", []) or [])
        return self._send(200, {
            "hits": hits, "capped": capped, "limit": limit,
            "columns": [{"path": c["path"], "label": c.get("label", c["path"])} for c in columns],
            "notes": notes, "mongoOperations": len(ops), "operations": ops})

    def _search_fields(self):
        fields = []
        for f in STORE.normal_fields():
            fields.append({
                "field": f.get("field"), "label": f.get("label", f.get("field")),
                "type": f.get("type", "string"), "indexed": f.get("indexed", False),
                "enabled": f.get("enabled", True),
                "usable": f.get("enabled", True) and f.get("indexed", False),
                "default": f.get("default", False),
                "placeholder": f.get("placeholder", ""), "hint": f.get("hint", "")})
        return {"fields": fields,
                "note": "در حالت عادی فقط فیلدهای ایندکس‌شده قابل جستجو هستند. "
                        "برای افزودن فیلد تازه: اول ایندکس بسازید، بعد در config.json فعالش کنید."}

    def _advanced_cfg(self):
        cfg = STORE.advanced_cfg()
        return {
            "enabled": cfg.get("enabled", True),
            "maxResults": cfg.get("maxResults", 20),
            "maxTimeMs": cfg.get("maxTimeMs", 15000),
            "warning": cfg.get("warning", ""),
            "operators": [{"op": o["op"], "label": o.get("label", o["op"])}
                          for o in cfg.get("operators") or []],
            "suggestedFields": cfg.get("suggestedFields") or [],
            "resultFields": [{"path": c["path"], "label": c.get("label", c["path"])}
                             for c in cfg.get("resultFields") or []]}

    def _ui(self):
        g = STORE.labels.l.get("graph") or {}
        return {
            "graph": {"layout": g.get("layout", "horizontal-rtl"),
                      "colors": g.get("colors") or {},
                      "showStartEnd": g.get("showStartEnd", True),
                      "startLabel": g.get("startLabel"), "endLabel": g.get("endLabel"),
                      "detailFields": g.get("detailFields") or []},
            "timezone": (STORE.config.get("time") or {}).get("displayTimezone", "Asia/Tehran"),
            "maskingProfile": (STORE.labels.l.get("privacy") or {}).get("maskingProfile"),
            "counts": {"routingKeys": len(STORE.labels.routing),
                       "commandTypes": len(STORE.labels.command_types),
                       "titles": len(STORE.labels.titles),
                       "statuses": len(STORE.labels.statuses)},
            "warnings": [], "labelsPath": "config/config.json",
            "loadedAt": "2026-08-31T00:00:00Z"}

    def _index_report(self):
        # mongomock ایندکس واقعی ندارد؛ فقط _id را موجود فرض می‌کنیم
        fields = []
        for f in STORE.normal_fields():
            actual = f.get("field") == "_id"
            if not f.get("enabled", True):
                status = "disabled"
            elif f.get("indexed") and actual:
                status = "ok"
            elif f.get("indexed"):
                status = "missing-index"
            else:
                status = "not-claimed"
            fields.append({"field": f.get("field"), "label": f.get("label"),
                           "enabled": f.get("enabled", True), "claimed": f.get("indexed", False),
                           "actual": actual, "status": status})
        return {"reachable": True, "existingIndexes": ["_id:1"], "fields": fields,
                "problems": [f["field"] for f in fields if f["status"] == "missing-index"],
                "note": "این سرویس ایندکس نمی‌سازد؛ ساخت آن‌ها با DBA است (ops/indexes.js)."}

    def _health(self):
        return {
            "readOnly": {"enforced": True, "blockedWriteAttempts": 0,
                         "readCommandsExecuted": 0, "clean": True, "recentViolations": []},
            "mongo": {"database": "saga", "collection": "sagaSequence",
                      "readPreference": "secondaryPreferred", "reachable": True,
                      "estimatedDocuments": STORE.col.count_documents({})},
            "searchFields": self._index_report(),
            "configWarnings": [], "labelWarnings": [],
            "maskingProfile": (STORE.labels.l.get("privacy") or {}).get("maskingProfile")}


def main():
    global STORE
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print("راه‌اندازی سرور ساختگی…")
    STORE = Store()
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"  آمادهٔ سرویس روی http://127.0.0.1:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
