import { useState } from 'react'
import { api } from '../api/client'
import { formatDateTime } from '../lib/format'

/**
 * جستجوی پیشرفته — بخش کاملاً جدا، با هشدار صریح.
 *
 * چرا جدا؟ چون هزینه‌اش با جستجوی عادی زمین تا آسمان فرق دارد. جستجوی
 * عادی یک lookup روی ایندکس است؛ این یکی می‌تواند میلیون‌ها سند را بخواند.
 * قاطی کردن این دو در یک کادر، کاربر را به گران‌ترین مسیر هدایت می‌کند
 * بدون اینکه بداند.
 *
 * خروجی هم عمداً «فهرست کوتاه برای انتخاب» است، نه یک جدول قابل مرور:
 * این ابزار برای رسیدن به *یک* شناسه است، نه برای پایش.
 */
export default function AdvancedSearch({ config, onPick, onHasResults }) {
  const [open, setOpen] = useState(false)
  const [filters, setFilters] = useState([{ field: '', op: 'eq', value: '' }])
  const [state, setState] = useState({ busy: false, error: null, result: null })

  if (!config?.enabled) return null

  function update(index, patch) {
    setFilters((list) => list.map((f, i) => (i === index ? { ...f, ...patch } : f)))
  }

  function add() {
    if (filters.length < 10) setFilters((l) => [...l, { field: '', op: 'eq', value: '' }])
  }

  function remove(index) {
    setFilters((l) => (l.length === 1 ? l : l.filter((_, i) => i !== index)))
  }

  async function run(e) {
    e.preventDefault()
    const usable = filters.filter((f) => f.field.trim())
    if (!usable.length) {
      setState({ busy: false, error: 'حداقل یک فیلتر با نام فیلد لازم است.', result: null })
      return
    }
    setState({ busy: true, error: null, result: null })
    onHasResults?.(false)
    try {
      const result = await api.advanced(usable)
      setState({ busy: false, error: null, result })
      onHasResults?.(true)
    } catch (err) {
      setState({ busy: false, error: err.message, result: null })
    }
  }

  const needsValue = (op) => op !== 'exists'

  return (
    <section className="advanced">
      <button type="button" className="advanced-toggle" onClick={() => setOpen((o) => !o)}
              aria-expanded={open}>
        <span aria-hidden="true">{open ? '▾' : '◂'}</span>
        جستجوی پیشرفته (روی فیلدهای بدون ایندکس)
        <span className="advanced-badge">سنگین</span>
      </button>

      {open && (
        <div className="advanced-body">
          <div className="warn-box" role="alert">
            <span className="warn-icon" aria-hidden="true">⚠</span>
            <div>
              <strong>این جستجو به سرور فشار می‌آورد.</strong>
              <p>{config.warning}</p>
              <p className="warn-meta">
                حداکثر {config.maxResults?.toLocaleString('fa-IR')} نتیجه ·
                سقف زمان {Math.round((config.maxTimeMs || 0) / 1000).toLocaleString('fa-IR')} ثانیه
              </p>
            </div>
          </div>

          <form onSubmit={run}>
            {filters.map((f, i) => (
              <div className="filter-row" key={i}>
                <input
                  className="input mono" dir="ltr" list="advanced-fields"
                  placeholder="نام فیلد، مثل commandList.routingKey"
                  value={f.field}
                  onChange={(e) => update(i, { field: e.target.value })}
                  aria-label={`فیلد ${i + 1}`}
                />
                <select className="input" value={f.op}
                        onChange={(e) => update(i, { op: e.target.value })}
                        aria-label={`عملگر ${i + 1}`}>
                  {config.operators?.map((o) => (
                    <option key={o.op} value={o.op}>{o.label}</option>
                  ))}
                </select>
                <input
                  className="input" dir="auto"
                  placeholder={needsValue(f.op) ? 'مقدار' : '(لازم نیست)'}
                  disabled={!needsValue(f.op)}
                  value={f.value}
                  onChange={(e) => update(i, { value: e.target.value })}
                  aria-label={`مقدار ${i + 1}`}
                />
                <button type="button" className="chip" onClick={() => remove(i)}
                        disabled={filters.length === 1} aria-label="حذف این فیلتر">×</button>
              </div>
            ))}

            <datalist id="advanced-fields">
              {config.suggestedFields?.map((s) => (
                <option key={s.field} value={s.field}>{s.label}</option>
              ))}
            </datalist>

            <div className="filter-actions">
              <button type="button" className="chip" onClick={add}
                      disabled={filters.length >= 10}>+ فیلتر</button>
              <button type="submit" className="btn btn-warn" disabled={state.busy}>
                {state.busy ? 'در حال جستجو…' : 'اجرای جستجوی سنگین'}
              </button>
            </div>
          </form>

          {state.error && <div className="warn-box error" role="alert">{state.error}</div>}

          {state.result && <Results result={state.result} columns={config.resultFields}
                                    onPick={onPick} />}
        </div>
      )}
    </section>
  )
}

function Results({ result, columns, onPick }) {
  if (!result.hits?.length) {
    return (
      <div className="advanced-empty">
        نتیجه‌ای پیدا نشد. نام فیلد را بررسی کنید — مسیر تودرتو با نقطه نوشته می‌شود،
        مثل <code dir="ltr">commandList.routingKey</code>.
      </div>
    )
  }
  return (
    <div className="advanced-results">
      <div className="advanced-results-head">
        <strong>{result.hits.length.toLocaleString('fa-IR')} نتیجه</strong>
        <span className="ops-chip" title="تعداد پرس‌وجوهای اجراشده روی MongoDB">
          {result.mongoOperations?.toLocaleString('fa-IR')} پرس‌وجو
        </span>
      </div>

      <ul className="hit-list">
        {result.hits.map((h) => (
          <li key={h.id}>
            <button type="button" className="hit" onClick={() => onPick(h.id)}>
              {(columns || []).map((c) => (
                <span key={c.path} className={`hit-cell ${c.path === '_id' ? 'mono' : ''}`}
                      dir={c.path === '_id' ? 'ltr' : 'auto'}>
                  <span className="hit-cell-label">{c.label}</span>
                  {c.path === 'startDate' ? formatDateTime(h.fields[c.path]) : (h.fields[c.path] || '—')}
                </span>
              ))}
              <span className="hit-go" aria-hidden="true">نمایش ←</span>
            </button>
          </li>
        ))}
      </ul>

      {result.notes?.map((n, i) => <p key={i} className="table-note">{n}</p>)}
    </div>
  )
}
