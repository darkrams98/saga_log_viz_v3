import { useEffect, useMemo, useState } from 'react'

/**
 * جستجوی عادی — فقط روی فیلدهای ایندکس‌شده.
 *
 * فرض کاری: پشتیبان شناسه را از قبل از ELK گرفته و اینجا فقط می‌چسباند.
 * پس مهم‌ترین چیز، سرعت همین کار است: فوکوس خودکار، تمیزکردن فاصله و
 * گیومهٔ اضافه هنگام چسباندن، و Enter برای جستجو.
 *
 * فهرست فیلدها از سرور می‌آید (config.json)، نه از کد. اگر فردا روی
 * registerId ایندکس ساخته شود، همین‌جا خودبه‌خود ظاهر می‌شود.
 */
export default function SearchPanel({ fields, note, value, field, onChange, onFieldChange,
                                      onSubmit, busy }) {
  const usable = useMemo(() => (fields || []).filter((f) => f.usable), [fields])
  const blocked = useMemo(() => (fields || []).filter((f) => !f.usable), [fields])
  const [showBlocked, setShowBlocked] = useState(false)

  const active = usable.find((f) => f.field === field) || usable[0]

  useEffect(() => {
    if (active && field !== active.field) onFieldChange(active.field)
  }, [active, field, onFieldChange])

  function handleSubmit(e) {
    e.preventDefault()
    if (!busy && value.trim()) onSubmit()
  }

  /** چسباندن از ELK اغلب گیومه، فاصله یا ObjectId(...) همراه دارد */
  function clean(raw) {
    return raw
      .trim()
      .replace(/^ObjectId\(\s*["']?/i, '')
      .replace(/["']?\s*\)$/, '')
      .replace(/^["']|["']$/g, '')
      .trim()
  }

  return (
    <form className="search-panel" onSubmit={handleSubmit}>
      <div className="search-row">
        {usable.length > 1 && (
          <select className="input search-field" value={field || ''}
                  onChange={(e) => onFieldChange(e.target.value)}
                  aria-label="فیلد جستجو">
            {usable.map((f) => (
              <option key={f.field} value={f.field}>{f.label}</option>
            ))}
          </select>
        )}

        <input
          className="input search-input mono"
          dir="ltr"
          autoFocus
          value={value}
          placeholder={active?.placeholder || 'شناسهٔ لاگ را اینجا بچسبانید'}
          onChange={(e) => onChange(e.target.value)}
          onPaste={(e) => {
            const text = e.clipboardData?.getData('text')
            if (text) { e.preventDefault(); onChange(clean(text)) }
          }}
          aria-label={active?.label || 'شناسهٔ لاگ'}
        />

        <button type="submit" className="btn" disabled={busy || !value.trim()}>
          {busy ? 'در حال جستجو…' : 'نمایش لاگ'}
        </button>
      </div>

      <div className="search-hint">
        {active?.hint && <span>{active.hint}</span>}
        {blocked.length > 0 && (
          <button type="button" className="linkish"
                  onClick={() => setShowBlocked((s) => !s)}>
            {showBlocked ? 'بستن' : `${blocked.length} فیلد دیگر غیرفعال است — چرا؟`}
          </button>
        )}
      </div>

      {showBlocked && (
        <div className="blocked-fields">
          <p>{note}</p>
          <ul>
            {blocked.map((f) => (
              <li key={f.field}>
                <code dir="ltr">{f.field}</code> — {f.label}
                <span className="blocked-why">
                  {!f.indexed ? ' ایندکس ندارد' : ' در config.json غیرفعال است'}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </form>
  )
}
