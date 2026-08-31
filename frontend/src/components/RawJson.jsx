import { useMemo, useState } from 'react'
import { CopyButton } from './ui'
import { formatBytes } from '../lib/format'

/**
 * JSON خام سند، همان‌طور که در MongoDB است (پس از پوشاندن دادهٔ حساس).
 *
 * دو حالت دارد چون دو کاربرد دارد:
 *   مرتب  → برای خواندن با چشم
 *   فشرده → برای کپی و چسباندن در ابزار دیگر
 */
export default function RawJson({ json, sizeBytes, maskingProfile }) {
  const [compact, setCompact] = useState(false)
  const [query, setQuery] = useState('')

  const body = useMemo(() => {
    if (!json) return '{}'
    if (!compact) return json
    try {
      return JSON.stringify(JSON.parse(json))
    } catch {
      return json
    }
  }, [json, compact])

  const highlighted = useMemo(() => {
    const q = query.trim()
    if (!q) return [{ text: body, hit: false }]
    const parts = []
    let index = 0
    const lower = body.toLowerCase()
    const needle = q.toLowerCase()
    while (index < body.length) {
      const found = lower.indexOf(needle, index)
      if (found < 0) {
        parts.push({ text: body.slice(index), hit: false })
        break
      }
      if (found > index) parts.push({ text: body.slice(index, found), hit: false })
      parts.push({ text: body.slice(found, found + q.length), hit: true })
      index = found + q.length
    }
    return parts
  }, [body, query])

  const hits = highlighted.filter((p) => p.hit).length

  return (
    <div className="raw-wrap">
      <div className="raw-bar">
        <input
          type="search"
          className="input"
          placeholder="جستجو در JSON…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="جستجو در JSON"
        />
        {query && <span className="field-count">{hits.toLocaleString('fa-IR')} مورد</span>}
        <button type="button" className={`chip ${compact ? 'active' : ''}`}
                onClick={() => setCompact((c) => !c)}>
          {compact ? 'نمایش مرتب' : 'نمایش فشرده'}
        </button>
        <span className="field-count">{formatBytes(sizeBytes)}</span>
        <CopyButton value={body} label="کپی JSON" />
      </div>

      {maskingProfile !== 'off' && (
        <p className="table-note">
          {maskingProfile === 'secretsOnly'
            ? 'رمز، توکن و رمز یک‌بارمصرف حذف شده‌اند؛ بقیهٔ مقادیر کامل‌اند. '
            : 'دادهٔ حساس (کد ملی، موبایل، شمارهٔ حساب) ماسک شده است. '}
          این رفتار با کلید <code dir="ltr">privacy.maskingProfile</code> در
          <code dir="ltr"> config.json</code> قابل تغییر است.
        </p>
      )}

      <pre className="raw-pre" dir="ltr">
        {highlighted.map((p, i) =>
          p.hit ? <mark key={i}>{p.text}</mark> : <span key={i}>{p.text}</span>)}
      </pre>
    </div>
  )
}
