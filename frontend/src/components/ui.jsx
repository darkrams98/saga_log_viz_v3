import { useState } from 'react'
import { formatNumber } from '../lib/format'

/** کارت پایه با عنوان و یادداشت اختیاری */
export function Card({ title, note, action, children, bodyClass = 'card-body' }) {
  return (
    <section className="card">
      {(title || action) && (
        <header className="card-head">
          <div>
            <h2 className="card-title">{title}</h2>
            {note && <div className="card-note">{note}</div>}
          </div>
          {action}
        </header>
      )}
      <div className={bodyClass}>{children}</div>
    </section>
  )
}

export function StatTile({ label, value, hint, tone }) {
  const color = tone ? `var(--status-${tone})` : undefined
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value" style={color ? { color } : undefined}>{value}</div>
      {hint && <div className="stat-hint">{hint}</div>}
    </div>
  )
}

export function Badge({ children, cls = 'badge-muted', dotColor, title }) {
  return (
    <span className={`badge ${cls}`} title={title}>
      {dotColor && <span className="badge-dot" style={{ background: dotColor }} />}
      {children}
    </span>
  )
}

/** کپی سریع شناسه‌ها — یکی از خواسته‌های اصلی پشتیبانی */
export function CopyButton({ value, label = 'کپی' }) {
  const [copied, setCopied] = useState(false)
  if (!value) return null

  async function copy(event) {
    event.stopPropagation()
    try {
      await navigator.clipboard.writeText(String(value))
    } catch {
      // مرورگرهای قدیمی یا context ناامن
      const ta = document.createElement('textarea')
      ta.value = String(value)
      document.body.appendChild(ta)
      ta.select()
      try { document.execCommand('copy') } catch { /* بی‌خیال */ }
      document.body.removeChild(ta)
    }
    setCopied(true)
    setTimeout(() => setCopied(false), 1400)
  }

  return (
    <button type="button" className="copy-btn" onClick={copy} title={`${label}: ${value}`}>
      {copied ? '✓ کپی شد' : '⧉'}
    </button>
  )
}

/** مقدار قابل کپی با نمایش تک‌فاصله‌ای */
export function CopyableValue({ value, fallback = 'نامشخص', mono = true }) {
  if (!value) return <span style={{ color: 'var(--text-muted)' }}>{fallback}</span>
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <span className={mono ? 'mono' : undefined}>{value}</span>
      <CopyButton value={value} />
    </span>
  )
}

export function EmptyState({ icon = '🔍', title, text, action }) {
  return (
    <div className="state">
      <div className="state-icon" aria-hidden="true">{icon}</div>
      <div className="state-title">{title}</div>
      {text && <div className="state-text">{text}</div>}
      {action}
    </div>
  )
}

export function Loading({ rows = 4, label = 'در حال بارگذاری…' }) {
  return (
    <div aria-busy="true" aria-live="polite">
      <span className="sr-only">{label}</span>
      <div style={{ display: 'grid', gap: 8 }}>
        {Array.from({ length: rows }).map((_, i) => (
          <div key={i} className="skeleton" style={{ height: i === 0 ? 22 : 15, width: i === 0 ? '45%' : '100%' }} />
        ))}
      </div>
    </div>
  )
}

export function ErrorBox({ message, onRetry }) {
  return (
    <div className="alert" role="alert">
      <strong>مشکلی پیش آمد</strong>
      <div style={{ marginTop: 4 }}>{message}</div>
      {onRetry && (
        <button type="button" className="btn btn-sm" style={{ marginTop: 10 }} onClick={onRetry}>
          تلاش دوباره
        </button>
      )}
    </div>
  )
}

export function Pager({ page, totalPages, totalItems, onChange }) {
  if (totalPages <= 1) {
    return (
      <div className="pager">
        <span style={{ color: 'var(--text-muted)' }}>{formatNumber(totalItems)} نتیجه</span>
      </div>
    )
  }
  return (
    <div className="pager">
      <span style={{ color: 'var(--text-muted)' }}>
        {formatNumber(totalItems)} نتیجه — صفحهٔ {formatNumber(page + 1)} از {formatNumber(totalPages)}
      </span>
      <span style={{ display: 'flex', gap: 6 }}>
        <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => onChange(page - 1)}>
          قبلی
        </button>
        <button
          type="button"
          className="btn btn-sm"
          disabled={page + 1 >= totalPages}
          onClick={() => onChange(page + 1)}
        >
          بعدی
        </button>
      </span>
    </div>
  )
}

export function Tabs({ tabs, active, onChange }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button
          key={t.key}
          type="button"
          role="tab"
          aria-selected={active === t.key}
          className={`tab ${active === t.key ? 'active' : ''}`}
          onClick={() => onChange(t.key)}
        >
          {t.label}
          {t.count !== undefined && t.count !== null && (
            <span style={{ color: 'var(--text-muted)', fontSize: 11, marginInlineStart: 5 }}>
              {formatNumber(t.count)}
            </span>
          )}
        </button>
      ))}
    </div>
  )
}
