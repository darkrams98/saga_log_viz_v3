import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import SearchPanel from '../components/SearchPanel.jsx'
import AdvancedSearch from '../components/AdvancedSearch.jsx'
import FlowGraph from '../components/FlowGraph.jsx'
import CommandDetail from '../components/CommandDetail.jsx'
import FieldTable from '../components/FieldTable.jsx'
import RawJson from '../components/RawJson.jsx'
import { CopyButton, EmptyState, ErrorBox, Loading, Tabs } from '../components/ui'
import { formatDateTime, formatBytes, dirFor } from '../lib/format'

/**
 * تنها صفحهٔ برنامه: نمایش یک لاگ.
 *
 * شناسه در آدرس صفحه است (`/log/:id`) تا پشتیبان بتواند لینک را برای
 * همکارش بفرستد — کاری که در عمل مدام لازم می‌شود.
 */
export default function LogViewerPage({ ui }) {
  const { id: routeId } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()

  const [fields, setFields] = useState(null)
  const [fieldsNote, setFieldsNote] = useState('')
  const [advConfig, setAdvConfig] = useState(null)

  const [field, setField] = useState(params.get('field') || '_id')
  const [input, setInput] = useState(routeId || '')
  const [state, setState] = useState({ busy: false, error: null, data: null, notFound: null })
  const [tab, setTab] = useState('graph')
  const [selectedNode, setSelectedNode] = useState(null)
  const [advHasResults, setAdvHasResults] = useState(false)

  useEffect(() => {
    api.searchFields()
      .then((r) => { setFields(r.fields); setFieldsNote(r.note) })
      .catch(() => setFields([]))
    api.advancedConfig().then(setAdvConfig).catch(() => setAdvConfig(null))
  }, [])

  const load = useCallback(async (value, searchField) => {
    if (!value) return
    setState({ busy: true, error: null, data: null, notFound: null })
    setSelectedNode(null)
    try {
      const data = await api.byId(value, searchField)
      if (data?.found === false) {
        setState({ busy: false, error: null, data: null, notFound: data })
        return
      }
      setState({ busy: false, error: null, data, notFound: null })
      setTab('graph')
      // اگر مرحله‌ای شکست خورده، همان را از اول باز می‌کنیم
      const failed = data?.graph?.summary?.failedNodeId
      if (failed) setSelectedNode(failed)
    } catch (err) {
      setState({ busy: false, error: err, data: null, notFound: null })
    }
  }, [])

  useEffect(() => {
    if (routeId) {
      setInput(routeId)
      load(routeId, params.get('field') || undefined)
    }
  }, [routeId, params, load])

  function submit() {
    const value = input.trim()
    if (!value) return
    navigate(`/log/${encodeURIComponent(value)}${field && field !== '_id' ? `?field=${field}` : ''}`)
  }

  const data = state.data
  const node = data?.graph?.nodes?.find((n) => n.id === selectedNode) || null

  return (
    <div className="viewer">
      <header className="viewer-head">
        <div>
          <h1>نمایش یک لاگ</h1>
          <p className="viewer-sub">
            شناسه را از ELK کپی کنید و اینجا بچسبانید. برای هر نمایش دقیقاً یک
            پرس‌وجو روی MongoDB اجرا می‌شود.
          </p>
        </div>
      </header>

      <SearchPanel
        fields={fields}
        note={fieldsNote}
        value={input}
        field={field}
        onChange={setInput}
        onFieldChange={setField}
        onSubmit={submit}
        busy={state.busy}
      />

      <AdvancedSearch
        config={advConfig}
        onPick={(id) => navigate(`/log/${encodeURIComponent(id)}`)}
        onHasResults={setAdvHasResults}
      />

      {state.busy && <Loading rows={5} label="در حال خواندن لاگ…" />}

      {state.error && (
        <ErrorBox message={state.error.message} onRetry={() => load(input, field)} />
      )}

      {state.notFound && (
        <EmptyState
          icon="🔎"
          title={state.notFound.message}
          text={state.notFound.hint}
        />
      )}

      {!state.busy && !state.error && !state.notFound && !data && !advHasResults && (
        <EmptyState
          icon="◂"
          title="هنوز لاگی انتخاب نشده"
          text="این ابزار برای عیب‌یابی یک فرایند مشخص است، نه پایش کلی. پایش با Grafana و جستجوی گسترده با ELK انجام می‌شود."
        />
      )}

      {data && (
        <>
          <SummaryCard data={data} />

          <Tabs
            active={tab}
            onChange={setTab}
            tabs={[
              { key: 'graph', label: 'نمای شماتیک' },
              { key: 'table', label: 'نمای جدولی' },
              { key: 'raw', label: 'JSON خام' },
            ]}
          />

          <div className="viewer-body">
            {tab === 'graph' && (
              <>
                <FlowGraph
                  graph={data.graph}
                  colors={ui?.graph?.colors}
                  selectedId={selectedNode}
                  onSelect={(id) => setSelectedNode((cur) => (cur === id ? null : id))}
                />
                <CommandDetail node={node} onClose={() => setSelectedNode(null)} />
              </>
            )}

            {tab === 'table' && (
              <FieldTable rows={data.table} truncated={data.tableTruncated} />
            )}

            {tab === 'raw' && (
              <RawJson json={data.rawJson} sizeBytes={data.rawSizeBytes}
                       maskingProfile={data.maskingProfile} />
            )}
          </div>
        </>
      )}
    </div>
  )
}

/** کارت خلاصه: چه عملیاتی، چه شد، کِی، و کجا شکست */
function SummaryCard({ data }) {
  const h = data.header || {}
  const s = data.graph?.summary || {}

  return (
    <section className={`summary-card tone-${h.severity || 'unknown'}`}>
      <div className="summary-main">
        <div className="summary-title">
          <span className={`summary-dot tone-${h.severity || 'unknown'}`} aria-hidden="true" />
          <h2 dir={dirFor(h.title)}>{h.title || 'بدون عنوان'}</h2>
          <span className={`summary-status tone-${h.severity || 'unknown'}`}>{h.status}</span>
        </div>

        {h.rawTitle && h.rawTitle !== h.title && (
          <div className="summary-raw mono" dir="ltr">
            {h.rawTitle}
            <CopyButton value={h.rawTitle} label="" />
          </div>
        )}

        <div className="summary-id mono" dir="ltr">
          {data.id}
          <CopyButton value={data.id} label="کپی شناسه" />
        </div>
      </div>

      {s.errorCount > 0 && s.failedService && (
        <div className="summary-failure">
          <strong>مرحلهٔ {(s.failedIndex + 1).toLocaleString('fa-IR')} در «{s.failedService}» شکست خورد.</strong>
          {s.failedErrorText && <code dir="ltr">{s.failedErrorText}</code>}
        </div>
      )}

      <dl className="summary-grid">
        <Item label="مراحل" value={`${(s.stepCount || 0).toLocaleString('fa-IR')} مرحله`} />
        {h.startedAt && <Item label="زمان شروع" value={formatDateTime(h.startedAt)} />}
        {h.completedAt && <Item label="زمان پایان" value={formatDateTime(h.completedAt)} />}
        {h.durationText && <Item label="مدت اجرا" value={h.durationText} />}
        {data.summary?.map((item) => (
          <Item key={item.path} label={item.label}
                value={item.type === 'datetime' ? formatDateTime(item.value) : item.value}
                raw={item.copy ? item.rawValue : null} />
        ))}
        <Item label="حجم سند" value={formatBytes(data.rawSizeBytes)} />
        <Item
          label="پرس‌وجوهای MongoDB"
          value={(data.mongoOperations ?? 0).toLocaleString('fa-IR')}
          note={data.mongoOperations === 1 ? 'مطابق قید طراحی' : undefined}
        />
      </dl>

      {data.warnings?.length > 0 && (
        <ul className="summary-warnings">
          {data.warnings.map((w, i) => <li key={i}>{w}</li>)}
        </ul>
      )}
    </section>
  )
}

function Item({ label, value, raw, note }) {
  if (!value) return null
  return (
    <div className="summary-item">
      <dt>{label}</dt>
      <dd dir={dirFor(value)}>
        {value}
        {raw && <CopyButton value={raw} label="" />}
        {note && <span className="summary-note">{note}</span>}
      </dd>
    </div>
  )
}
