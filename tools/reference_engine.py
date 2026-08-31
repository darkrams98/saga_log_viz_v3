#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
پیاده‌سازی مرجع موتور عمومی — آینهٔ منطق جاوا.

⚠️ بخشی از محصول نیست. دو کار می‌کند:
   ۱) اجازه می‌دهد قواعد پارسر و کوئری‌ساز روی دادهٔ واقعی سنجیده شوند
      بدون اینکه لازم باشد کل اپلیکیشن جاوا بالا بیاید.
   ۲) به سرور ماک سوخت می‌رساند تا رابط کاربری واقعاً رندر و دیده شود.

اگر روزی رفتار جاوا و این فایل واگرا شوند، یعنی یکی از آن دو عوض شده
و باید بررسی شود — همین ارزش اصلی داشتن یک پیاده‌سازی مرجع است.
"""

import base64
import json
import os
import re
from datetime import datetime, timezone, timedelta

import yaml

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_PATH = os.path.join(BASE, "config", "config.yaml")

MAX_RESULTS = 500
MAX_DEPTH_WALK = 15


# ----------------------------------------------------------------- config

def _resolve_env(text):
    def repl(m):
        name, default = m.group(1), (m.group(2) or "")
        return os.environ.get(name, default)
    return re.sub(r"\$\{([A-Za-z0-9_]+)(?::([^}]*))?\}", repl, text)


def load_config(path=CONFIG_PATH):
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(_resolve_env(fh.read())) or {}


# ------------------------------------------------------------ json helper

def looks_like_json(s):
    if not isinstance(s, str):
        return False
    t = s.strip()
    if not t or t[0] not in "{[":
        return False
    return (t[0] == "{" and t[-1] == "}") or (t[0] == "[" and t[-1] == "]")


def try_parse_json(s):
    if not looks_like_json(s) or len(s) > 8_000_000:
        return None
    try:
        return json.loads(s)
    except Exception:
        return None


# --------------------------------------------------------- path resolution

def compile_path(path):
    """رشتهٔ مسیر → فهرست قطعه‌ها: ('field',name) ('index',n) ('wild',) ('json',)"""
    if not path:
        return []
    segments, token, i = [], "", 0
    while i < len(path) and len(segments) < 32:
        c = path[i]
        if c == ".":
            segments += _flush(token)
            token = ""
        elif c == "[":
            segments += _flush(token)
            token = ""
            close = path.find("]", i)
            if close < 0:
                token = path[i + 1:]
                break
            inside = path[i + 1:close].strip()
            if inside == "*":
                segments.append(("wild",))
            else:
                try:
                    segments.append(("index", int(inside)))
                except ValueError:
                    segments.append(("field", inside))
            i = close
        else:
            token += c
        i += 1
    segments += _flush(token)
    return segments


def _flush(token):
    name = token.strip()
    if not name:
        return []
    if name.endswith("#json"):
        base = name[:-len("#json")]
        out = [("field", base)] if base else []
        return out + [("json",)]
    if name == "*":
        return [("wild",)]
    return [("field", name)]


def resolve_all(root, path):
    segments = compile_path(path)
    if root is None or not segments:
        return []
    current, visits = [root], [0]
    for seg in segments:
        nxt = []
        for node in current:
            if visits[0] > 20000 or len(nxt) >= MAX_RESULTS:
                break
            visits[0] += 1
            _step(node, seg, nxt)
        if not nxt:
            return []
        current = nxt
    return [v for v in current if v is not None]


def _step(node, seg, out):
    if node is None:
        return
    kind = seg[0]
    if kind == "field":
        _field(node, seg[1], out)
    elif kind == "index":
        if isinstance(node, list):
            if 0 <= seg[1] < len(node) and node[seg[1]] is not None:
                out.append(node[seg[1]])
        elif seg[1] == 0:
            out.append(node)
    elif kind == "wild":
        if isinstance(node, list):
            out.extend(v for v in node if v is not None)
        elif isinstance(node, dict):
            out.extend(v for v in node.values() if v is not None)
        else:
            out.append(node)
    elif kind == "json":
        if isinstance(node, str):
            parsed = try_parse_json(node)
            if parsed is not None:
                out.append(parsed)
        else:
            out.append(node)


def _field(node, name, out):
    if isinstance(node, dict):
        v = node.get(name)
        if v is not None:
            out.append(v)
        return
    if isinstance(node, list):
        for item in node:
            if len(out) >= MAX_RESULTS:
                return
            _field(item, name, out)
        return
    if isinstance(node, str):
        parsed = try_parse_json(node)
        if parsed is not None:
            _field(parsed, name, out)


def resolve_first(root, path):
    vals = resolve_all(root, path)
    return vals[0] if vals else None


def to_mongo_path(path):
    parts = []
    for seg in compile_path(path):
        if seg[0] == "field":
            parts.append(seg[1])
        elif seg[0] == "index":
            parts.append(str(seg[1]))
        elif seg[0] == "json":
            return None
    return ".".join(parts) or None


# ------------------------------------------------------------- coercion

MILLIS_LOWER, MILLIS_UPPER = 1_000_000_000_000, 3_700_000_000_000
SECONDS_LOWER, SECONDS_UPPER = 1_000_000_000, 3_700_000_000


def to_instant(value, formats=()):
    if value is None:
        return None
    try:
        if isinstance(value, datetime):
            return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
        if isinstance(value, bool):
            return None
        if isinstance(value, (int, float)):
            return _from_epoch(int(value))
        if isinstance(value, dict):
            for key in ("$date", "$numberLong"):
                if key in value:
                    return to_instant(value[key], formats)
            return None
        if isinstance(value, str):
            return _from_string(value.strip(), formats)
    except Exception:
        return None
    return None


def _from_epoch(n):
    if MILLIS_LOWER <= n <= MILLIS_UPPER:
        return datetime.fromtimestamp(n / 1000, timezone.utc)
    if SECONDS_LOWER <= n <= SECONDS_UPPER:
        return datetime.fromtimestamp(n, timezone.utc)
    if MILLIS_UPPER < n < MILLIS_UPPER * 1000:
        return datetime.fromtimestamp(n / 1_000_000, timezone.utc)
    if n >= MILLIS_UPPER * 1000:
        return datetime.fromtimestamp(n / 1_000_000_000, timezone.utc)
    return None


def _from_string(s, formats):
    if not s:
        return None
    if 10 <= len(s) <= 20 and s.isdigit():
        return _from_epoch(int(s))
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except Exception:
        pass
    for fmt in formats or ():
        py = (fmt.replace("yyyy", "%Y").replace("MM", "%m").replace("dd", "%d")
                 .replace("HH", "%H").replace("mm", "%M").replace("ss", "%S")
                 .replace("SSS", "%f").replace("'T'", "T").replace("'Z'", "Z")
                 .replace("XXX", "%z"))
        try:
            d = datetime.strptime(s, py)
            return d if d.tzinfo else d.replace(tzinfo=timezone.utc)
        except Exception:
            continue
    try:
        d = datetime.fromisoformat(s.replace(" ", "T"))
        return d if d.tzinfo else d.replace(tzinfo=timezone.utc)
    except Exception:
        return None


def type_name(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "bool"
    if isinstance(value, str):
        return "json-string" if looks_like_json(value) else "string"
    if isinstance(value, int):
        return "int"
    if isinstance(value, float):
        return "double"
    if isinstance(value, datetime):
        return "date"
    if isinstance(value, dict):
        return "date" if "$date" in value else "object"
    if isinstance(value, list):
        return "array"
    return type(value).__name__.lower()


def to_text(value, max_chars=0):
    if value is None:
        return None
    if isinstance(value, str):
        s = value
    elif isinstance(value, bool):
        s = "true" if value else "false"
    elif isinstance(value, (int, float)):
        s = str(value)
    elif isinstance(value, datetime):
        s = value.isoformat()
    elif isinstance(value, dict) and "$date" in value:
        s = str(value["$date"])
    elif isinstance(value, (dict, list)):
        s = json.dumps(value, ensure_ascii=False, default=str)
    else:
        # معادل String.valueOf در جاوا: هر نوع ناشناخته (مثلاً ObjectId)
        # باید متن شود، نه اینکه serialization را بشکند.
        s = str(value)
    if max_chars and len(s) > max_chars:
        return s[:max_chars] + "…"
    return s


def is_empty(value):
    if value is None:
        return True
    if isinstance(value, str):
        t = value.strip()
        return not t or t.lower() == "null"
    if isinstance(value, (dict, list)):
        return len(value) == 0
    return False


# ------------------------------------------------------------ transforms

def apply_transform(value, name, config, depth=0):
    if value is None or not name or depth > 5:
        return value
    t = (config.get("transforms") or {}).get(name)
    if not t:
        return value
    kind = t.get("type", "regexReplace")
    try:
        if kind == "regexReplace":
            out = re.sub(t.get("pattern", ""), t.get("replacement", ""), value)
            return out if out.strip() else value
        if kind == "chain":
            out = value
            for step in t.get("steps", []):
                out = apply_transform(out, step, config, depth + 1)
            return out
        if kind == "upper":
            return value.upper()
        if kind == "lower":
            return value.lower()
        if kind == "trim":
            return value.strip()
    except Exception:
        return value
    return value


# --------------------------------------------------------------- masking

PERSIAN_DIGITS = {ord(c): str(i) for i, c in enumerate("۰۱۲۳۴۵۶۷۸۹")}
ARABIC_DIGITS = {ord(c): str(i) for i, c in enumerate("٠١٢٣٤٥٦٧٨٩")}


def normalize_digits(s):
    if not isinstance(s, str):
        return s
    return s.translate(PERSIAN_DIGITS).translate(ARABIC_DIGITS).replace("‌", "")


def _norm_field(name):
    f = str(name).lower().replace("_", "").replace("-", "")
    if "[" in f:
        f = f.split("[")[0]
    if "." in f:
        f = f.rsplit(".", 1)[-1]
    return f


def _matches(field, rule):
    return field == rule if len(rule) <= 4 else (
        field == rule or field.endswith(rule) or field.startswith(rule))


class Masker:
    """
    آینهٔ MaskingService.java، شامل پروفایل پوشاندن از config.json:
      off         → هیچ پوشاندنی
      secretsOnly → فقط راز‌ها حذف؛ ماسک جزئی و ماسک متن آزاد خاموش
      partial     → همهٔ قواعد config.yaml
    """

    def __init__(self, config, profile="partial"):
        m = config.get("masking") or {}
        self.profile = profile or "partial"
        self.enabled = m.get("enabled", True) and self.profile != "off"
        self.placeholder = m.get("placeholder", "[حذف‌شده]")
        self.secrets = [_norm_field(x) for x in (m.get("secretFields") or [])]
        self.allow = [_norm_field(x) for x in (m.get("allowList") or [])]
        self.free_text = m.get("freeText", True) and self.profile == "partial"
        self.rules = []
        for r in ([] if self.profile != "partial" else (m.get("rules") or [])):
            self.rules.append({
                "fields": [_norm_field(x) for x in (r.get("fields") or [])],
                "strategy": r.get("strategy", "keepEdges"),
                "head": r.get("head", 2), "tail": r.get("tail", 2),
                "keep": r.get("keep", 12), "value": r.get("value", "***"),
            })

    def is_secret(self, name):
        if not self.enabled or not name:
            return False
        f = _norm_field(name)
        if f in self.allow:
            return False
        return any(_matches(f, s) for s in self.secrets)

    def rule_for(self, name):
        if not self.enabled or not name:
            return None
        f = _norm_field(name)
        if f in self.allow:
            return None
        for rule in self.rules:
            if any(_matches(f, k) for k in rule["fields"]):
                return rule
        return None

    def keep_edges(self, value, head, tail):
        v = str(value).strip()
        if len(v) <= head + tail:
            return "*" * max(3, len(v))
        return v[:head] + "*" * (len(v) - head - tail) + (v[-tail:] if tail else "")

    def mobile_ir(self, value):
        d = re.sub(r"\D", "", normalize_digits(str(value)))
        if d.startswith("0098"):
            d = d[4:]
        elif d.startswith("98") and len(d) == 12:
            d = d[2:]
        if len(d) == 10 and d.startswith("9"):
            d = "0" + d
        return self.keep_edges(d or value, 4, 3)

    def apply(self, rule, value):
        s = str(value)
        try:
            strat = rule["strategy"]
            if strat == "keepEdges":
                digits = re.sub(r"\D", "", s)
                base = digits if digits and len(digits) >= len(s) - 4 else s
                return self.keep_edges(base, rule["head"], rule["tail"])
            if strat == "mobileIR":
                return self.mobile_ir(s)
            if strat == "fixed":
                return rule["value"]
            if strat == "truncate":
                k = max(1, rule["keep"])
                return "*" * max(3, len(s)) if len(s) <= k else s[:k] + " …***"
            if strat == "initials":
                return " ".join(p[0] + "*" * max(1, len(p) - 1) for p in s.split() if p) or "***"
            if strat == "yearOnly":
                m = re.match(r"^(\d{4})[-/]\d{1,2}[-/]\d{1,2}", s)
                if m:
                    return m.group(1) + "-**-**"
                d = re.sub(r"\D", "", s)
                return d[:4] + "****" if len(d) == 8 else self.keep_edges(s, 4, 0)
            if strat == "ipPrefix":
                if s.strip().lower() == "anonymousip":
                    return s
                parts = s.split(".")
                return f"{parts[0]}.{parts[1]}.*.*" if len(parts) == 4 else self.keep_edges(s, 4, 0)
            if strat == "remove":
                return self.placeholder
            return self.keep_edges(s, rule["head"], rule["tail"])
        except Exception:
            return "***"

    def mask_value(self, name, value):
        if value is None or value == "":
            return value
        if self.is_secret(name):
            return self.placeholder
        rule = self.rule_for(name)
        return self.apply(rule, value) if rule else value

    FREE_TEXT_MAX_CHARS = 1_000_000

    def mask_free_text(self, text):
        if not text or not self.enabled or not self.free_text:
            return text
        # بالاتر از این سقف، مقدار در هیچ نمایی کامل دیده نمی‌شود؛ پویشش فقط هزینه است.
        if len(text) > self.FREE_TEXT_MAX_CHARS:
            return text
        def kv(m):
            masked = self.mask_value(m.group(1), m.group(2))
            return m.group(0) if masked == m.group(2) else m.group(0).replace(m.group(2), masked)
        # کران ۶۴ روی نام فیلد عمدی است: با * بی‌کران، این الگو روی رشته‌های
        # طولانی بدون جداکننده رفتار درجه‌دو دارد (۶٫۹ ثانیه روی ۲۰ کیلوبایت).
        out = re.sub(r'"?([A-Za-z_][A-Za-z0-9_]{0,64})"?\s*[:=]\s*"([^"]{1,64})"', kv, text)

        def digits(m):
            d = m.group(1)
            if len(d) == 10:
                return self.keep_edges(d, 3, 2)
            if len(d) == 11 and d.startswith("09"):
                return self.keep_edges(d, 4, 3)
            if len(d) >= 13 and not (len(d) == 13 and MILLIS_LOWER < int(d) < MILLIS_UPPER):
                return self.keep_edges(d, 4, 3)
            return d
        return re.sub(r"(?<![0-9])([0-9]{10,20})(?![0-9])", digits, out)

    def mask_object(self, value, name="", depth=0):
        if value is None or depth > 30:
            return value
        if isinstance(value, dict):
            return {k: (self.placeholder if self.is_secret(k)
                        else self.mask_object(v, k, depth + 1)) for k, v in value.items()}
        if isinstance(value, list):
            return [self.mask_object(v, name, depth + 1) for v in value]
        if isinstance(value, str):
            if self.is_secret(name):
                return self.placeholder
            rule = self.rule_for(name)
            if rule:
                return self.apply(rule, value)
            nested = try_parse_json(value)
            if nested is not None:
                return json.dumps(self.mask_object(nested, name, depth + 1), ensure_ascii=False)
            return self.mask_free_text(value)
        rule = self.rule_for(name)
        if rule and rule["strategy"] == "fixed":
            return rule["value"]
        return value


# ------------------------------------------------------------ log record

class Engine:
    def __init__(self, config, masking_profile="partial"):
        self.config = config
        self.masker = Masker(config, masking_profile)
        self.limits = config.get("limits") or {}
        self.time_cfg = config.get("time") or {}
        self.level_cfg = config.get("level") or {}
        self.level_map = {k.upper(): v.upper() for k, v in (self.level_cfg.get("map") or {}).items()}

    # ---- helpers
    def _preview_chars(self):
        return self.limits.get("previewChars", 400)

    def _mask_path(self, path, text):
        if text is None:
            return None
        key = _norm_field(path or "")
        if self.masker.is_secret(key):
            return self.masker.placeholder
        rule = self.masker.rule_for(key)
        return self.masker.apply(rule, text) if rule else self.masker.mask_free_text(text)

    def resolve_field(self, doc, name, warnings):
        rule = (self.config.get("fields") or {}).get(name)
        if not rule:
            return None, None
        candidates = rule.get("candidates") if isinstance(rule, dict) else rule
        transform = rule.get("transform") if isinstance(rule, dict) else None
        for cand in (candidates or []):
            try:
                value = resolve_first(doc, cand)
                if is_empty(value):
                    continue
                text = to_text(value, self._preview_chars())
                text = apply_transform(text, transform, self.config)
                text = self._mask_path(cand, text)
                if text and text.strip():
                    return text, cand
            except Exception as e:
                warnings.append(f"خواندن «{cand}» ناموفق بود: {type(e).__name__}")
        return None, None

    def resolve_time(self, doc, warnings):
        for cand in (self.time_cfg.get("candidates") or []):
            try:
                value = resolve_first(doc, cand)
                if value is None:
                    continue
                inst = to_instant(value, self.time_cfg.get("stringFormats") or [])
                if inst:
                    return inst, cand
            except Exception as e:
                warnings.append(f"تفسیر زمان از «{cand}» ناموفق بود: {type(e).__name__}")
        warnings.append("هیچ فیلد زمانی قابل تفسیری پیدا نشد")
        return None, None

    def _level_from_field(self, doc):
        for cand in (self.level_cfg.get("candidates") or []):
            v = resolve_first(doc, cand)
            if not is_empty(v):
                raw = str(v).strip().upper()
                return self.level_map.get(raw, raw)
        return None

    def _level_from_derive(self, doc):
        for cand in (self.level_cfg.get("deriveFrom") or []):
            v = resolve_first(doc, cand)
            if not is_empty(v):
                raw = str(v).strip().upper()
                if raw in self.level_map:
                    return self.level_map[raw]
        return None

    def resolve_level(self, doc, status_text):
        for stage in (self.level_cfg.get("precedence") or ["field", "derive"]):
            got = (self._level_from_derive(doc) if stage == "derive"
                   else self._level_from_field(doc))
            if got:
                return got
        if status_text:
            mapped = self.level_map.get(str(status_text).strip().upper())
            if mapped:
                return mapped
        return (self.level_cfg.get("default") or "INFO").upper()

    def map_record(self, doc):
        warnings = []
        if not doc:
            return {"id": None, "time": None, "level": "INFO", "levelLabel": "اطلاعات",
                    "error": False, "message": None, "columns": {}, "highlights": {},
                    "warnings": ["سند خالی بود"]}
        rid, id_src = self.resolve_field(doc, "id", warnings)
        tinst, tsrc = self.resolve_time(doc, warnings)
        msg, msg_src = self.resolve_field(doc, "message", warnings)
        svc, _ = self.resolve_field(doc, "service", warnings)
        status, _ = self.resolve_field(doc, "status", warnings)
        level = self.resolve_level(doc, status)
        error_levels = [x.upper() for x in (self.level_cfg.get("errorLevels") or [])]

        columns = {}
        for col in (self.config.get("columns") or []):
            try:
                key = col.get("key")
                if col.get("source") == "path" and col.get("path"):
                    raw = resolve_first(doc, col["path"])
                    val = to_text(raw, self._preview_chars())
                    val = apply_transform(val, col.get("transform"), self.config)
                    val = self._mask_path(col["path"], val)
                else:
                    val = {"time": tinst.isoformat() if tinst else None, "level": level,
                           "message": msg, "service": svc, "status": status,
                           "id": rid}.get(key)
                    if val is None and key not in ("time", "level", "message", "service", "status", "id"):
                        val, _ = self.resolve_field(doc, key, warnings)
                    val = apply_transform(val, col.get("transform"), self.config)
                if val is not None:
                    columns[key] = val
            except Exception as e:
                warnings.append(f"ساخت ستون «{col.get('key')}» ناموفق بود: {type(e).__name__}")

        highlights = {}
        for path in ((self.config.get("display") or {}).get("highlightPaths") or []):
            try:
                raw = resolve_first(doc, path)
                if is_empty(raw):
                    continue
                text = self._mask_path(path, to_text(raw, 120))
                if text and text.strip():
                    highlights[path] = text
            except Exception:
                pass

        return {
            "id": rid, "idSource": id_src,
            "time": tinst.isoformat() if tinst else None, "timeSource": tsrc,
            "level": level,
            "levelLabel": (self.level_cfg.get("labels") or {}).get(level, level),
            "error": level in error_levels,
            "message": msg, "messageSource": msg_src,
            "service": svc, "status": status,
            "columns": columns, "highlights": highlights,
            "warnings": warnings,
            "rawSizeBytes": len(json.dumps(doc, ensure_ascii=False, default=str)),
        }

    # ---------------------------------------------------------- flatten
    def flatten(self, value, base_path=""):
        budget = {"nodes": self.limits.get("maxFlattenNodes", 3000),
                  "depth": self.limits.get("maxDepth", 15),
                  "preview": self._preview_chars(),
                  "heavy": self.limits.get("largeValueBytes", 2000),
                  "cut": False}
        hidden = set(((self.config.get("display") or {}).get("hiddenPaths") or []))
        nodes = []
        try:
            if isinstance(value, dict):
                for k, v in value.items():
                    path = f"{base_path}.{k}" if base_path else k
                    if path in hidden or k in hidden:
                        continue
                    n = self._node(k, path, v, 0, budget, hidden)
                    if n:
                        nodes.append(n)
            elif isinstance(value, list):
                for i, v in enumerate(value):
                    n = self._node(f"[{i}]", f"{base_path}[{i}]", v, 0, budget, hidden)
                    if n:
                        nodes.append(n)
        except Exception:
            budget["cut"] = True
        return nodes, budget["cut"]

    def _node(self, key, path, value, depth, budget, hidden, from_json=False):
        if budget["nodes"] <= 0:
            budget["cut"] = True
            return {"path": path, "key": key, "type": "truncated", "children": [],
                    "truncated": True, "heavy": True, "parsedFromJson": from_json}
        budget["nodes"] -= 1

        if value is None:
            return {"path": path, "key": key, "type": "null", "value": None,
                    "children": [], "truncated": False, "heavy": False,
                    "parsedFromJson": from_json, "masked": False}
        if self.masker.is_secret(key):
            return {"path": path, "key": key, "type": "secret",
                    "value": self.masker.placeholder, "children": [], "truncated": False,
                    "heavy": False, "parsedFromJson": from_json, "masked": True}

        size = len(json.dumps(value, ensure_ascii=False, default=str)) if not isinstance(value, str) else len(value)

        if isinstance(value, dict) and "$date" not in value:
            if depth >= budget["depth"]:
                return {"path": path, "key": key, "type": "object", "childCount": len(value),
                        "children": [], "truncated": True, "heavy": True,
                        "parsedFromJson": from_json, "masked": False}
            children, cut = [], False
            for k, v in value.items():
                cp = f"{path}.{k}" if path else k
                if cp in hidden or k in hidden:
                    continue
                if budget["nodes"] <= 0:
                    cut = True
                    break
                c = self._node(k, cp, v, depth + 1, budget, hidden, from_json)
                if c:
                    children.append(c)
            return {"path": path, "key": key, "type": "object", "childCount": len(value),
                    "children": children, "truncated": cut, "heavy": False,
                    "parsedFromJson": from_json, "masked": False}

        if isinstance(value, list):
            if depth >= budget["depth"]:
                return {"path": path, "key": key, "type": "array", "childCount": len(value),
                        "children": [], "truncated": True, "heavy": True,
                        "parsedFromJson": from_json, "masked": False}
            children, cut = [], False
            for i, v in enumerate(value):
                if budget["nodes"] <= 0:
                    cut = True
                    break
                c = self._node(f"[{i}]", f"{path}[{i}]", v, depth + 1, budget, hidden, from_json)
                if c:
                    children.append(c)
            return {"path": path, "key": key, "type": "array", "childCount": len(value),
                    "children": children, "truncated": cut, "heavy": False,
                    "parsedFromJson": from_json, "masked": False}

        if isinstance(value, str):
            auto = (self.config.get("display") or {}).get("autoParseJsonStrings", True)
            nbytes = len(value.encode("utf-8"))
            if auto and looks_like_json(value):
                if nbytes > budget["heavy"]:
                    return {"path": path, "key": key, "type": "json-string",
                            "value": self._mask_path(key, value[:budget["preview"]] + "…"),
                            "children": [], "truncated": True, "heavy": True,
                            "sizeBytes": nbytes, "parsedFromJson": from_json, "masked": False}
                if depth < budget["depth"]:
                    parsed = try_parse_json(value)
                    if parsed is not None:
                        inner = self._node(key, path + "#json", parsed, depth + 1,
                                           budget, hidden, True)
                        return {"path": path, "key": key, "type": "json-string",
                                "childCount": inner.get("childCount"),
                                "children": inner.get("children", []),
                                "truncated": inner.get("truncated", False),
                                "heavy": False, "sizeBytes": nbytes,
                                "parsedFromJson": from_json, "masked": False}
            if nbytes > budget["heavy"]:
                return {"path": path, "key": key, "type": "string",
                        "value": self._mask_path(key, value[:budget["preview"]] + "…"),
                        "children": [], "truncated": True, "heavy": True,
                        "sizeBytes": nbytes, "parsedFromJson": from_json, "masked": False}
            return {"path": path, "key": key, "type": "string",
                    "value": self._mask_path(key, value), "children": [],
                    "truncated": False, "heavy": False, "sizeBytes": nbytes,
                    "parsedFromJson": from_json,
                    "masked": bool(self.masker.rule_for(key))}

        text = to_text(value)
        return {"path": path, "key": key, "type": type_name(value),
                "value": self._mask_path(key, text), "children": [],
                "truncated": False, "heavy": False, "sizeBytes": size,
                "parsedFromJson": from_json,
                "masked": bool(self.masker.rule_for(key))}


# ==================================================================
#  آینهٔ config.json — برچسب فارسی و گراف جریان
#  (معادل labels/LabelResolver.java و flow/FlowGraphBuilder.java)
# ==================================================================

LABELS_PATH = os.path.join(BASE, "config", "config.json")


def load_labels(path=LABELS_PATH):
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    # کلیدهای توضیحی «_…» نادیده گرفته می‌شوند
    return {k: v for k, v in raw.items() if not k.startswith("_")}


class Labels:
    """زنجیرهٔ ترجمه: کلید دقیق → الگو → نرمال‌شده → مقدار خام."""

    def __init__(self, labels, config):
        self.l = labels
        self.config = config
        self.routing = labels.get("routingKeys") or {}
        self.patterns = [(re.compile(p["match"]), p["label"])
                         for p in (labels.get("routingKeyPatterns") or [])
                         if p.get("match") and p.get("label")]
        self.command_types = labels.get("commandTypes") or {}
        self.statuses = {k.upper(): v for k, v in (labels.get("statuses") or {}).items()}
        self.severity = {k.upper(): v for k, v in (labels.get("statusSeverity") or {}).items()}
        self.titles = labels.get("titles") or {}
        self.field_labels = labels.get("fieldLabels") or {}

    @staticmethod
    def _s(value):
        """هر ورودی — حتی list یا dict — اول متن می‌شود، مثل سمت جاوا."""
        if value is None or isinstance(value, str):
            return value
        return to_text(value, 400)

    def service(self, key):
        key = self._s(key)
        if not key:
            return {"value": "بدون میکروسرویس", "raw": None, "source": "fallback"}
        if key in self.routing:
            return {"value": self.routing[key], "raw": key, "source": "exact"}
        for pattern, label in self.patterns:
            if pattern.search(key):
                return {"value": label, "raw": key, "source": "pattern"}
        return {"value": key, "raw": key, "source": "fallback"}

    def command_type(self, value):
        value = self._s(value)
        if not value:
            return {"value": "—", "raw": None, "source": "fallback"}
        hit = self.command_types.get(value)
        return {"value": hit or value, "raw": value,
                "source": "exact" if hit else "fallback"}

    def status(self, value):
        value = self._s(value)
        if not value:
            return {"value": "نامشخص", "raw": None, "source": "fallback"}
        hit = self.statuses.get(str(value).strip().upper())
        return {"value": hit or value, "raw": value,
                "source": "exact" if hit else "fallback"}

    def sev(self, value):
        value = self._s(value)
        if not value:
            return "unknown"
        return self.severity.get(str(value).strip().upper(), "unknown")

    def title(self, value, command_type=None):
        value = self._s(value)
        command_type = self._s(command_type)
        if value:
            if value in self.titles:
                return {"value": self.titles[value], "raw": value, "source": "exact"}
            normalized = apply_transform(value, "normalizeTitle", self.config)
            if normalized and normalized != value and normalized in self.titles:
                return {"value": self.titles[normalized], "raw": value, "source": "normalized"}
        if command_type and command_type in self.command_types:
            return {"value": self.command_types[command_type],
                    "raw": value or command_type, "source": "normalized"}
        if not value:
            return {"value": "بدون عنوان", "raw": None, "source": "fallback"}
        return {"value": value, "raw": value, "source": "fallback"}

    def field(self, path):
        if not path:
            return path
        if path in self.field_labels:
            return self.field_labels[path]
        generic = re.sub(r"\[\d+]|\.\d+(?=\.|$)", "", path)
        if generic in self.field_labels:
            return self.field_labels[generic]
        # آخرین بخش مسیر، هم به‌عنوان کلید و هم در برابر آخرین بخشِ کلیدهای config
        leaf = generic.rsplit(".", 1)[-1]
        if leaf in self.field_labels:
            return self.field_labels[leaf]
        for key, value in self.field_labels.items():
            if key.rsplit(".", 1)[-1] == leaf:
                return value
        return path

    def by_map(self, name, value, command_type=None):
        return {
            "titles": lambda: self.title(value, command_type),
            "statuses": lambda: self.status(value),
            "commandTypes": lambda: self.command_type(value),
            "routingKeys": lambda: self.service(value),
        }.get(name, lambda: {"value": value, "raw": value, "source": "fallback"})()


MAX_STEPS = 200
DETAIL_CHARS = 20000


def build_flow_graph(document, labels, engine):
    """معادل FlowGraphBuilder.build — هرگز استثنا پرتاب نمی‌کند."""
    g = labels.l.get("graph") or {}
    layout = g.get("layout", "horizontal-rtl")
    notes = []

    def empty(note):
        return {"nodes": [], "edges": [], "layout": layout, "notes": [note],
                "summary": {"stepCount": 0, "successCount": 0, "errorCount": 0,
                            "unknownCount": 0, "failedIndex": -1, "failedNodeId": None,
                            "failedService": None, "failedErrorText": None,
                            "overallStatus": None, "overallSeverity": "unknown"}}

    if not document:
        return empty("سند خالی است.")

    source = g.get("source", "commandList")
    try:
        raw = resolve_first(document, source)
    except Exception:
        return empty(f"خواندن مسیر «{source}» ناموفق بود.")

    if raw is None:
        return empty(f"فیلد «{source}» در این لاگ وجود ندارد.")
    if isinstance(raw, str):
        parsed = try_parse_json(raw)
        if isinstance(parsed, list):
            raw = parsed
    if not isinstance(raw, list):
        return empty(f"فیلد «{source}» آرایه نیست ({type_name(raw)})؛ گراف قابل رسم نیست، "
                     "ولی نمای جدولی و JSON خام کامل‌اند.")

    steps = raw[:MAX_STEPS]
    if len(raw) > MAX_STEPS:
        notes.append(f"این لاگ بیش از {MAX_STEPS} مرحله دارد؛ فقط {MAX_STEPS} مرحلهٔ اول رسم شد.")

    nodes, chain = [], []
    show_markers = g.get("showStartEnd", True)
    if show_markers:
        nodes.append(_marker("start", g.get("startLabel", "درخواست کاربر")))

    success = error = unknown = 0
    failed = {"index": -1, "id": None, "service": None, "error": None}

    for i, step in enumerate(steps):
        item = step if isinstance(step, dict) else try_parse_json(step) if isinstance(step, str) else None
        if not isinstance(item, dict):
            notes.append(f"مرحلهٔ {i + 1} شیء نبود و نمایش داده نشد.")
            continue
        node = _step_node(i, item, g, labels, engine)
        nodes.append(node)
        chain.append(node)
        if node["severity"] == "success":
            success += 1
        elif node["severity"] == "error":
            error += 1
            if failed["index"] < 0:
                failed = {"index": i, "id": node["id"], "service": node["service"],
                          "error": node["errorText"]}
        else:
            unknown += 1

    edges = []
    previous = "start" if show_markers else None
    for node in chain:
        if previous:
            edges.append({"from": previous, "to": node["id"], "label": None})
        previous = node["id"]
    if show_markers and previous:
        nodes.append(_marker("end", g.get("endLabel", "پایان فرایند")))
        edges.append({"from": previous, "to": "end", "label": None})

    overall_raw = to_text(document.get("status"))
    overall = labels.status(overall_raw)
    overall_sev = "error" if error else labels.sev(overall_raw)
    if error and labels.sev(overall_raw) != "error":
        notes.append(f"وضعیت کلی لاگ «{overall['value']}» است ولی {error} مرحله ناموفق بوده. "
                     "مرحله‌ها معتبرترند.")

    return {"nodes": nodes, "edges": edges, "layout": layout, "notes": notes,
            "summary": {"stepCount": len(chain), "successCount": success,
                        "errorCount": error, "unknownCount": unknown,
                        "failedIndex": failed["index"], "failedNodeId": failed["id"],
                        "failedService": failed["service"], "failedErrorText": failed["error"],
                        "overallStatus": overall["value"], "overallSeverity": overall_sev}}


def _marker(kind, label):
    return {"id": kind, "kind": kind, "index": -1, "service": label, "routingKey": None,
            "serviceSource": "exact", "title": label, "rawTitle": None, "commandType": None,
            "rawCommandType": None, "status": None, "rawStatus": None, "severity": "marker",
            "errorText": None, "startedAt": None, "detail": {}, "truncated": False}


def _step_node(index, step, g, labels, engine):
    routing = _text(step.get(g.get("nodeLabelFrom", "routingKey")))
    ctype = _text(step.get(g.get("nodeSubLabelFrom", "commandType")))
    status = _text(step.get(g.get("statusFrom", "status")))
    raw_title = _text(step.get("title"))

    service = labels.service(routing)
    error_text = None
    for path in (g.get("errorTextFrom") or ["rollbackDescription"]):
        candidate = _text(step.get(path))
        if candidate:
            error_text = engine.masker.mask_free_text(candidate)
            break

    detail, truncated = {}, False
    for field in (g.get("detailFields") or []):
        value = step.get(field)
        if is_empty(value):
            continue
        item = _detail_value(field, value, labels, engine)
        detail[field] = item
        truncated = truncated or item["truncated"]

    started = to_instant(step.get("StartDate") or step.get("startDate"),
                         (labels.config.get("time") or {}).get("stringFormats") or [])

    return {"id": f"s{index}", "kind": "step", "index": index,
            "service": service["value"], "routingKey": routing,
            "serviceSource": service["source"],
            "title": labels.title(raw_title, ctype)["value"], "rawTitle": raw_title,
            "commandType": labels.command_type(ctype)["value"], "rawCommandType": ctype,
            "status": labels.status(status)["value"], "rawStatus": status,
            "severity": labels.sev(status), "errorText": error_text,
            "startedAt": started.isoformat().replace("+00:00", "Z") if started else None,
            "detail": detail, "truncated": truncated}


def _detail_value(field, raw, labels, engine):
    kind = type_name(raw)
    size = len(json.dumps(raw, ensure_ascii=False, default=str)) if not isinstance(raw, str) else len(raw)
    masked = engine.masker.mask_object(raw, field)
    text = to_text(masked, DETAIL_CHARS)
    truncated = bool(text) and size > DETAIL_CHARS
    pretty = None
    if isinstance(masked, str):
        parsed = try_parse_json(masked)
        if parsed is not None:
            pretty = json.dumps(parsed, ensure_ascii=False, indent=2)
            kind = "json-string"
    elif isinstance(masked, (dict, list)):
        pretty = json.dumps(masked, ensure_ascii=False, indent=2, default=str)
    if pretty and len(pretty) > DETAIL_CHARS:
        pretty, truncated = pretty[:DETAIL_CHARS], True
    return {"label": labels.field(field), "value": text, "json": pretty,
            "type": kind, "sizeBytes": size, "truncated": truncated}


def _text(value):
    return None if is_empty(value) else to_text(value, 400)
