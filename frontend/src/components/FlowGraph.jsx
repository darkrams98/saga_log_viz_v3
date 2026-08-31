import { useMemo } from 'react'

/**
 * شماتیک جریان اجرا بین میکروسرویس‌ها.
 *
 * چیدمان افقی **راست به چپ** است، هم‌راستا با خود رابط کاربری: نگاه کاربر
 * فارسی‌زبان از راست شروع می‌شود، پس «درخواست کاربر» هم باید آنجا باشد.
 *
 * چرا SVG دستی و نه یک کتابخانهٔ گراف؟
 *  - جریان ما یک زنجیرهٔ خطی است، نه گراف دلخواه. یک کتابخانهٔ layout
 *    (dagre/elk) صدها کیلوبایت اضافه می‌کرد برای مسئله‌ای که چند سطر
 *    حساب مختصات است.
 *  - کنترل کامل روی جهت RTL، فونت فارسی و رنگ‌های config.
 *  - هیچ وابستگی خارجی یعنی هیچ CDNی لازم نیست — مهم برای شبکهٔ بستهٔ بانک.
 */

const NODE_W = 194
const NODE_H = 94
const GAP_X = 48
const MARKER_W = 104
const PAD = 20
const ROW_Y = 34

const fa = new Intl.NumberFormat('fa-IR')

export default function FlowGraph({ graph, colors, selectedId, onSelect }) {
  const layout = useMemo(() => buildLayout(graph), [graph])

  // نکته: موقعیت اولیهٔ اسکرول دستی تنظیم نمی‌شود. در ظرف RTL، مرورگر
  // خودش از سمت راست شروع می‌کند — یعنی همان «شروع فرایند».
  // مقداردهی دستی scrollLeft در RTL بین مرورگرها ناسازگار است و
  // نتیجه‌اش پریدن به انتهای زنجیره بود.

  if (!graph || !graph.nodes?.length) {
    return (
      <div className="graph-empty">
        <div className="graph-empty-icon" aria-hidden="true">◇</div>
        <p>{graph?.notes?.[0] || 'این لاگ مرحله‌ای برای رسم ندارد.'}</p>
        <p className="graph-empty-hint">
          نمای جدولی و JSON خام همچنان کامل‌اند — از زبانه‌های بالا استفاده کنید.
        </p>
      </div>
    )
  }

  const palette = {
    success: colors?.success || '#0ca30c',
    error: colors?.error || '#d03b3b',
    unknown: colors?.unknown || '#8a8f98',
  }

  return (
    <div className="graph-wrap">
      <div className="graph-scroller">
        <svg
          width={layout.width}
          height={layout.height}
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          className="flow-graph"
          role="img"
          aria-label={`جریان اجرا در ${fa.format(layout.steps.length)} مرحله`}
        >
          <defs>
            <marker id="fg-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                    markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--border-strong, #b9bec7)" />
            </marker>
          </defs>

          {layout.edges.map((e) => (
            <line key={`${e.from}-${e.to}`}
                  x1={e.x1} y1={e.y} x2={e.x2} y2={e.y}
                  className="fg-edge" markerEnd="url(#fg-arrow)" />
          ))}

          {layout.markers.map((m) => (
            <g key={m.id} className="fg-marker">
              <rect x={m.x} y={m.y} width={MARKER_W} height={40} rx={20} />
              <text x={m.x + MARKER_W / 2} y={m.y + 24} textAnchor="middle">{m.label}</text>
            </g>
          ))}

          {layout.steps.map((n) => (
            <StepNode key={n.id} node={n} palette={palette}
                      selected={selectedId === n.id}
                      onSelect={onSelect} />
          ))}
        </svg>
      </div>

      <Legend palette={palette} summary={graph.summary} />

      {graph.notes?.length > 0 && (
        <ul className="graph-notes">
          {graph.notes.map((n, i) => <li key={i}>{n}</li>)}
        </ul>
      )}
    </div>
  )
}

function StepNode({ node, palette, selected, onSelect }) {
  const color = palette[node.severity] || palette.unknown
  const cls = [
    'fg-node',
    `fg-${node.severity}`,
    selected ? 'selected' : '',
  ].join(' ')

  return (
    <g
      className={cls}
      transform={`translate(${node.x}, ${node.y})`}
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      aria-label={`مرحلهٔ ${fa.format(node.index + 1)}: ${node.service} — ${node.status || 'نامشخص'}`}
      onClick={() => onSelect(node.id)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(node.id) }
      }}
    >
      <rect className="fg-box" width={NODE_W} height={NODE_H} rx={12}
            style={{ stroke: color }} />
      {/* نوار رنگی وضعیت در لبهٔ راست — در RTL اول دیده می‌شود */}
      <rect className="fg-stripe" x={NODE_W - 5} y={1} width={4} height={NODE_H - 2}
            rx={2} style={{ fill: color }} />

      {/*
        همهٔ متن‌ها با textAnchor="middle" چیده می‌شوند.
        دلیل: در SVG معنای start/end به جهت متن وابسته است و صفحه RTL است،
        پس "end" لبهٔ چپ می‌شود نه راست و متن از جعبه بیرون می‌زند.
        "middle" تنها لنگری است که مستقل از جهت، همان‌جایی می‌ماند که انتظار داریم.
      */}
      <text className="fg-step-no" x={NODE_W / 2} y={19} textAnchor="middle">
        مرحلهٔ {fa.format(node.index + 1)}
      </text>

      <text className="fg-service" x={NODE_W / 2} y={42} textAnchor="middle">
        {clip(node.service, 20)}
      </text>

      <text className="fg-type" x={NODE_W / 2} y={60} textAnchor="middle">
        {clip(node.commandType, 24)}
      </text>

      <text className="fg-status" x={NODE_W / 2} y={79} textAnchor="middle">
        {node.status || 'نامشخص'}
      </text>

      {node.severity === 'error' && (
        <text className="fg-warn" x={18} y={22} textAnchor="middle">⚠</text>
      )}
      {node.serviceSource === 'fallback' && (
        <title>{`این routingKey در config.json ترجمه نشده: ${node.routingKey}`}</title>
      )}
    </g>
  )
}

function Legend({ palette, summary }) {
  if (!summary) return null
  const items = [
    { key: 'success', label: 'موفق', count: summary.successCount },
    { key: 'error', label: 'ناموفق', count: summary.errorCount },
    { key: 'unknown', label: 'نامشخص', count: summary.unknownCount },
  ].filter((i) => i.count > 0)

  return (
    <div className="graph-legend">
      {items.map((i) => (
        <span key={i.key} className="legend-item">
          <span className="legend-dot" style={{ background: palette[i.key] }} />
          {i.label}: {fa.format(i.count)}
        </span>
      ))}
      <span className="legend-hint">برای دیدن ورودی و خروجی هر مرحله، روی آن کلیک کنید.</span>
    </div>
  )
}

/**
 * محاسبهٔ مختصات.
 *
 * مبدأ در RTL سمت راست است: مرحلهٔ اول بیشترین x را دارد و هر مرحلهٔ بعدی
 * به چپ می‌رود. عرض کل از قبل حساب می‌شود تا بتوانیم از راست بچینیم.
 */
function buildLayout(graph) {
  const steps = (graph?.nodes || []).filter((n) => n.kind === 'step')
  const hasStart = (graph?.nodes || []).some((n) => n.kind === 'start')
  const hasEnd = (graph?.nodes || []).some((n) => n.kind === 'end')
  const startNode = (graph?.nodes || []).find((n) => n.kind === 'start')
  const endNode = (graph?.nodes || []).find((n) => n.kind === 'end')

  const slots = steps.length + (hasStart ? 1 : 0) + (hasEnd ? 1 : 0)
  const widths = []
  if (hasStart) widths.push(MARKER_W)
  steps.forEach(() => widths.push(NODE_W))
  if (hasEnd) widths.push(MARKER_W)

  const width = PAD * 2 + widths.reduce((a, b) => a + b, 0) + GAP_X * Math.max(0, slots - 1)
  const height = ROW_Y + NODE_H + 28

  // چیدن از راست به چپ
  let cursor = width - PAD
  const placed = []
  widths.forEach((w, i) => {
    cursor -= w
    placed.push({ x: cursor, w, i })
    cursor -= GAP_X
  })

  let p = 0
  const markers = []
  const laid = []
  if (hasStart) {
    markers.push({ id: 'start', x: placed[p].x, y: ROW_Y + (NODE_H - 40) / 2, label: startNode.service })
    laid.push({ id: 'start', x: placed[p].x, w: MARKER_W, cy: ROW_Y + NODE_H / 2 })
    p++
  }
  const stepNodes = steps.map((n) => {
    const slot = placed[p++]
    laid.push({ id: n.id, x: slot.x, w: NODE_W, cy: ROW_Y + NODE_H / 2 })
    return { ...n, x: slot.x, y: ROW_Y }
  })
  if (hasEnd) {
    markers.push({ id: 'end', x: placed[p].x, y: ROW_Y + (NODE_H - 40) / 2, label: endNode.service })
    laid.push({ id: 'end', x: placed[p].x, w: MARKER_W, cy: ROW_Y + NODE_H / 2 })
  }

  const byId = Object.fromEntries(laid.map((n) => [n.id, n]))
  const edges = (graph?.edges || []).map((e) => {
    const from = byId[e.from]
    const to = byId[e.to]
    if (!from || !to) return null
    // در RTL مبدأ سمت راست است، پس خط از لبهٔ چپِ مبدأ به لبهٔ راستِ مقصد می‌رود
    return { from: e.from, to: e.to, x1: from.x - 6, x2: to.x + to.w + 6, y: from.cy }
  }).filter(Boolean)

  return { width, height, steps: stepNodes, markers, edges }
}

function clip(text, max) {
  if (!text) return '—'
  return text.length > max ? `${text.slice(0, max - 1)}…` : text
}
