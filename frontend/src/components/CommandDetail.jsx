import { useState } from 'react'
import { CopyButton } from './ui'
import { formatBytes, formatDateTime, dirFor } from '../lib/format'

/**
 * جزئیات یک مرحله — همان چیزی که با کلیک روی گره باز می‌شود.
 *
 * ترتیب نمایش عمدی است: اول «چه شد» (وضعیت و خطا)، بعد «چه فرستادیم»
 * (commandContent)، بعد «چه گرفتیم» (response). این همان ترتیبی است که
 * پشتیبان هنگام عیب‌یابی دنبالش می‌گردد.
 */
export default function CommandDetail({ node, onClose }) {
  if (!node) return null

  const detail = node.detail || {}
  // فیلدهایی که بالاتر در «واقعیت‌ها» یا در جعبهٔ خطا نشان داده شده‌اند
  const shownAbove = ['title', 'status', 'commandType', 'routingKey',
                      'rollbackDescription', 'StartDate', 'startDate']
  const contentKeys = Object.keys(detail).filter((k) => !shownAbove.includes(k))
  // فقط محتوای واقعاً بزرگ لایق پنل بازشونده است؛ یک تاریخِ ۳۷ بایتی نه.
  const big = contentKeys.filter((k) => detail[k]?.json && detail[k]?.sizeBytes > 200)
  const small = contentKeys.filter((k) => !big.includes(k))

  return (
    <div className={`cmd-detail ${node.severity === 'error' ? 'is-error' : ''}`}>
      <div className="cmd-head">
        <div>
          <span className={`cmd-dot cmd-${node.severity}`} aria-hidden="true" />
          <strong>{node.title}</strong>
          <span className="cmd-sub">{node.service}</span>
        </div>
        <button type="button" className="chip" onClick={onClose}>بستن</button>
      </div>

      <div className="cmd-facts">
        <Fact label="وضعیت" value={node.status} raw={node.rawStatus}
              tone={node.severity} />
        <Fact label="نوع دستور" value={node.commandType} raw={node.rawCommandType} copy />
        <Fact label="میکروسرویس" value={node.service} raw={node.routingKey} copy
              note={node.serviceSource === 'fallback'
                ? 'این routingKey هنوز در config.json ترجمه نشده است.'
                : null} />
        {node.startedAt && (
          <Fact label="زمان شروع" value={formatDateTime(node.startedAt)} raw={node.startedAt} />
        )}
      </div>

      {node.errorText && (
        <div className="cmd-error">
          <span className="cmd-error-label">متن خطا</span>
          <code dir="ltr">{node.errorText}</code>
          <CopyButton value={node.errorText} label="کپی خطا" />
        </div>
      )}

      {small.length > 0 && (
        <div className="cmd-facts">
          {small.map((k) => (
            <Fact key={k} label={detail[k].label} value={detail[k].value}
                  raw={detail[k].value} copy />
          ))}
        </div>
      )}

      {big.map((k) => (
        <Payload key={k} name={k} field={detail[k]} />
      ))}

      {node.truncated && (
        <p className="cmd-note">
          بخشی از محتوا به‌دلیل حجم زیاد بریده شده است. برای دیدن کامل، از زبانهٔ
          «JSON خام» استفاده کنید.
        </p>
      )}
    </div>
  )
}

function Fact({ label, value, raw, copy, tone, note }) {
  if (!value) return null
  return (
    <div className="cmd-fact">
      <span className="cmd-fact-label">{label}</span>
      <span className={`cmd-fact-value ${tone ? `tone-${tone}` : ''}`} dir={dirFor(value)}>
        {value}
      </span>
      {raw && raw !== value && (
        <span className="cmd-fact-raw mono" dir="ltr" title="مقدار خام برای جستجو در ELK">
          {raw}
        </span>
      )}
      {copy && raw && <CopyButton value={raw} label="کپی" />}
      {note && <span className="cmd-fact-note">{note}</span>}
    </div>
  )
}

/**
 * ورودی و خروجی مرحله.
 *
 * این‌ها رشته‌های JSON چندکیلوبایتی‌اند. پیش‌فرض بسته است — باز کردن همه‌شان
 * صفحه را غیرقابل استفاده می‌کند و کاربر معمولاً فقط یکی‌شان را می‌خواهد.
 */
function Payload({ name, field }) {
  const [open, setOpen] = useState(false)
  const [pretty, setPretty] = useState(true)
  const body = pretty && field.json ? field.json : field.value

  return (
    <div className="payload">
      <button type="button" className="payload-head" onClick={() => setOpen((o) => !o)}
              aria-expanded={open}>
        <span className="payload-caret" aria-hidden="true">{open ? '▾' : '◂'}</span>
        <span className="payload-label">{field.label || name}</span>
        <span className="payload-size">{formatBytes(field.sizeBytes)}</span>
      </button>

      {open && (
        <div className="payload-body">
          <div className="payload-actions">
            {field.json && (
              <button type="button" className={`chip ${pretty ? 'active' : ''}`}
                      onClick={() => setPretty((p) => !p)}>
                {pretty ? 'نمایش خام' : 'نمایش مرتب'}
              </button>
            )}
            <CopyButton value={body} label="کپی" />
          </div>
          <pre className="payload-pre" dir="ltr">{body}</pre>
          {field.truncated && (
            <p className="cmd-note">این مقدار بریده شده است.</p>
          )}
        </div>
      )}
    </div>
  )
}
