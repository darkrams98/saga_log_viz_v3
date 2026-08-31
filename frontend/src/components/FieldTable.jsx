import { useMemo, useState } from 'react'
import { CopyButton } from './ui'
import { formatBytes, dirFor } from '../lib/format'

/**
 * نمای جدولی سند — هر فیلد یک ردیف.
 *
 * چرا جدول تخت و نه درخت؟ چون کار پشتیبان «پیدا کردن یک مقدار» است، نه
 * «کاوش ساختار». در جدول می‌شود جستجو کرد، مرتب کرد و کپی گرفت؛ در درخت نه.
 * تودرتویی با تورفتگی و مسیر کامل نشان داده می‌شود، پس چیزی از دست نمی‌رود.
 *
 * ستون «مسیر» عمداً مقدار خام و LTR است: همان رشته‌ای که در ELK یا در
 * mongosh قابل استفاده است.
 */
export default function FieldTable({ rows, truncated }) {
  const [query, setQuery] = useState('')
  const [onlyLeaves, setOnlyLeaves] = useState(true)

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (rows || []).filter((r) => {
      if (onlyLeaves && r.container && !r.value) return false
      if (!q) return true
      return (r.path || '').toLowerCase().includes(q)
        || (r.label || '').toLowerCase().includes(q)
        || (r.value || '').toLowerCase().includes(q)
    })
  }, [rows, query, onlyLeaves])

  const asText = useMemo(
    () => filtered.map((r) => `${r.path}\t${r.value ?? ''}`).join('\n'),
    [filtered],
  )

  return (
    <div className="field-table-wrap">
      <div className="field-table-bar">
        <input
          type="search"
          className="input"
          placeholder="جستجو در مسیر یا مقدار…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="جستجو در فیلدها"
        />
        <label className="check">
          <input type="checkbox" checked={onlyLeaves}
                 onChange={(e) => setOnlyLeaves(e.target.checked)} />
          فقط فیلدهای دارای مقدار
        </label>
        <span className="field-count">
          {filtered.length.toLocaleString('fa-IR')} از {(rows || []).length.toLocaleString('fa-IR')}
        </span>
        <CopyButton value={asText} label="کپی جدول" />
      </div>

      {truncated && (
        <p className="table-note">
          این سند بزرگ است و بخشی از فیلدها در جدول نیامده. JSON خام کامل است.
        </p>
      )}

      <div className="field-table-scroll">
        <table className="field-table">
          <thead>
            <tr>
              <th style={{ width: '32%' }}>فیلد</th>
              <th style={{ width: '10%' }}>نوع</th>
              <th>مقدار</th>
              <th style={{ width: 64 }} aria-label="کپی" />
            </tr>
          </thead>
          <tbody>
            {filtered.map((r) => (
              <tr key={r.path} className={r.container ? 'is-container' : ''}>
                <td>
                  <div style={{ paddingInlineStart: Math.min(r.depth, 8) * 12 }}>
                    <span className="field-label">{r.label !== r.path ? r.label : r.key}</span>
                    <span className="field-path mono" dir="ltr">{r.path}</span>
                  </div>
                </td>
                <td>
                  <span className="type-chip">{r.type}</span>
                  {r.childCount != null && (
                    <span className="child-count">{r.childCount.toLocaleString('fa-IR')} مورد</span>
                  )}
                </td>
                <td>
                  {r.value ? (
                    <span className="field-value" dir={dirFor(r.value)}>{r.value}</span>
                  ) : (
                    <span className="field-empty">—</span>
                  )}
                  {r.masked && <span className="masked-chip">ماسک‌شده</span>}
                  {r.sizeBytes > 2000 && (
                    <span className="size-chip">{formatBytes(r.sizeBytes)}</span>
                  )}
                </td>
                <td>{r.value && <CopyButton value={r.value} label="" />}</td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <tr>
                <td colSpan={4} className="field-empty" style={{ padding: 24, textAlign: 'center' }}>
                  فیلدی با این جستجو پیدا نشد.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
