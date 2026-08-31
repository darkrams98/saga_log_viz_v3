/**
 * قالب‌بندی مقادیر برای نمایش فارسی.
 *
 * چالش خاص این پروژه: محتوای فنی (JSON، URL، IP، UUID، مسیر کلاس، stack trace)
 * باید داخل رابط راست‌چین *خوانا* بماند. راه‌حل: هر مقدار فنی با dir="ltr"
 * یا dir="auto" رندر می‌شود و اعداد با tabular-nums هم‌تراز می‌مانند.
 */

const TZ = 'Asia/Tehran'

const dateTimeFmt = new Intl.DateTimeFormat('fa-IR-u-ca-persian', {
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  timeZone: TZ, hour12: false,
})
const timeOnlyFmt = new Intl.DateTimeFormat('fa-IR', {
  hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: TZ, hour12: false,
})
const numberFmt = new Intl.NumberFormat('fa-IR')

function toDate(value) {
  if (value === null || value === undefined || value === '') return null
  const d = typeof value === 'number' ? new Date(value) : new Date(String(value))
  return Number.isNaN(d.getTime()) ? null : d
}

export function formatDateTime(value, fallback = 'نامشخص') {
  const d = toDate(value)
  return d ? dateTimeFmt.format(d) : fallback
}

export function formatTimeOnly(value) {
  const d = toDate(value)
  return d ? timeOnlyFmt.format(d) : '—'
}

export function formatRelative(value) {
  const d = toDate(value)
  if (!d) return 'نامشخص'
  const abs = Math.abs(Date.now() - d.getTime())
  const min = 60_000, hour = 3_600_000, day = 86_400_000
  if (abs < min) return 'همین الان'
  if (abs < hour) return `${numberFmt.format(Math.floor(abs / min))} دقیقه پیش`
  if (abs < day) return `${numberFmt.format(Math.floor(abs / hour))} ساعت پیش`
  if (abs < 30 * day) return `${numberFmt.format(Math.floor(abs / day))} روز پیش`
  return formatDateTime(d)
}

export function formatNumber(value) {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return numberFmt.format(value)
}

/** شمارش سقف‌دار: ۱۰۰۰۰ با سقف ۱۰۰۰۰ یعنی «بیش از ۱۰٬۰۰۰» */
export function formatCount(value, capped) {
  if (value === null || value === undefined || value < 0) return '—'
  return capped ? `بیش از ${numberFmt.format(value)}` : numberFmt.format(value)
}

export function formatPercent(value) {
  if (value === null || value === undefined) return '—'
  return `${numberFmt.format(Math.round(value * 10) / 10)}٪`
}

export function formatBytes(bytes) {
  if (!bytes) return '۰'
  if (bytes < 1024) return `${numberFmt.format(bytes)} بایت`
  if (bytes < 1024 * 1024) return `${numberFmt.format(Math.round(bytes / 102.4) / 10)} کیلوبایت`
  return `${numberFmt.format(Math.round(bytes / 104857.6) / 10)} مگابایت`
}

/** برچسب باکت نمودار زمانی */
export function formatBucket(bucket, interval) {
  if (!bucket) return ''
  const iso = bucket.length <= 10 ? `${bucket}T00:00:00` : `${bucket}:00`
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return bucket
  if (interval === 'day') {
    return new Intl.DateTimeFormat('fa-IR-u-ca-persian', { month: 'short', day: 'numeric' }).format(d)
  }
  if (interval === 'minute') {
    return new Intl.DateTimeFormat('fa-IR', { hour: '2-digit', minute: '2-digit', hour12: false }).format(d)
  }
  return new Intl.DateTimeFormat('fa-IR-u-ca-persian', {
    month: 'short', day: 'numeric', hour: '2-digit', hour12: false,
  }).format(d)
}

/** رنگ و آیکن سطح لاگ — همیشه همراه برچسب متنی */
export const LEVEL_STYLE = {
  ERROR: { cls: 'badge-critical', color: 'var(--status-critical)', icon: '✕' },
  FATAL: { cls: 'badge-critical', color: 'var(--status-critical)', icon: '✕' },
  WARN: { cls: 'badge-warning', color: 'var(--status-warning)', icon: '!' },
  INFO: { cls: 'badge-good', color: 'var(--status-good)', icon: 'i' },
  DEBUG: { cls: 'badge-muted', color: 'var(--text-muted)', icon: '·' },
}

export function levelStyle(level) {
  return LEVEL_STYLE[String(level || '').toUpperCase()] || LEVEL_STYLE.DEBUG
}

/**
 * آیا این متن «فنی» است و باید چپ‌چین شود؟
 * UUID، IP، JSON، مسیر کلاس، URL، شناسهٔ hex — همه در RTL ناخوانا می‌شوند.
 */
const TECHNICAL = /^[\x20-\x7E]+$/
export function isTechnical(text) {
  if (typeof text !== 'string' || !text) return false
  return TECHNICAL.test(text.trim())
}

/** جهت مناسب برای یک مقدار — کلید خوانایی محتوای فنی در رابط راست‌چین */
export function dirFor(text) {
  return isTechnical(text) ? 'ltr' : 'auto'
}
