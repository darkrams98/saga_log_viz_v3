import { useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import LogViewerPage from './pages/LogViewerPage.jsx'
import { api } from './api/client'

/**
 * پوستهٔ برنامه.
 *
 * یک صفحه، یک کار. نوار کناری و منوی چندصفحه‌ای عمداً حذف شده‌اند:
 * وقتی برنامه فقط یک کار می‌کند، منو فقط فضا می‌گیرد.
 *
 * پیکربندی نمایش (رنگ گراف، برچسب‌ها) یک بار خوانده می‌شود. اگر خوانده
 * نشد، برنامه باز هم کار می‌کند — فقط با رنگ‌های پیش‌فرض.
 */
export default function App() {
  const [ui, setUi] = useState(null)
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme || 'system')

  useEffect(() => {
    api.ui().then(setUi).catch(() => setUi(null))
  }, [])

  useEffect(() => {
    if (theme === 'system') delete document.documentElement.dataset.theme
    else document.documentElement.dataset.theme = theme
  }, [theme])

  return (
    <div className="shell">
      <TopBar ui={ui} theme={theme} setTheme={setTheme} />
      <main className="shell-main">
        <Routes>
          <Route path="/" element={<Navigate to="/log" replace />} />
          <Route path="/log" element={<LogViewerPage ui={ui} />} />
          <Route path="/log/:id" element={<LogViewerPage ui={ui} />} />
          <Route path="*" element={<Navigate to="/log" replace />} />
        </Routes>
      </main>
    </div>
  )
}

function TopBar({ ui, theme, setTheme }) {
  const warnings = ui?.warnings?.length || 0

  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand-mark" aria-hidden="true">L</span>
        <span>
          <span className="brand-title">نمایشگر لاگ Saga</span>
          <span className="brand-sub">فقط‌خواندنی · یک لاگ در هر بار</span>
        </span>
      </div>

      <div className="topbar-meta">
        {ui?.counts && (
          <span className="topbar-chip" title="از config.json خوانده شده">
            {ui.counts.routingKeys.toLocaleString('fa-IR')} میکروسرویس ·
            {' '}{ui.counts.commandTypes.toLocaleString('fa-IR')} نوع دستور
          </span>
        )}
        {warnings > 0 && (
          <span className="topbar-chip warn">{warnings.toLocaleString('fa-IR')} هشدار پیکربندی</span>
        )}
        <div className="chip-row">
          {[{ key: 'system', label: 'سیستم' },
            { key: 'light', label: 'روشن' },
            { key: 'dark', label: 'تیره' }].map((t) => (
            <button key={t.key} type="button"
                    className={`chip ${theme === t.key ? 'active' : ''}`}
                    onClick={() => setTheme(t.key)}>
              {t.label}
            </button>
          ))}
        </div>
      </div>
    </header>
  )
}
