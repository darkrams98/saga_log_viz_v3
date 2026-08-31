/**
 * لایهٔ ارتباط با API.
 *
 * این نسخه عمداً کوچک است: سه کار بیشتر وجود ندارد —
 * یافتن یک لاگ با شناسه، جستجوی پیشرفته، و خواندن فراداده.
 *
 * هیچ endpointی برای فهرست یا آمار وجود ندارد، چون سرور هم ندارد.
 */

const BASE = import.meta.env.VITE_API_BASE || '/api/v1'

export class ApiError extends Error {
  constructor(message, status, hint, traceId) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.hint = hint
    this.traceId = traceId
  }
}

async function request(path, params, options = {}) {
  const url = new URL(BASE + path, window.location.origin)
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value === undefined || value === null || value === '' || value === false) return
      url.searchParams.set(key, value)
    })
  }

  let response
  try {
    response = await fetch(url.toString(), {
      method: options.method || 'GET',
      headers: options.body
        ? { Accept: 'application/json', 'Content-Type': 'application/json' }
        : { Accept: 'application/json' },
      body: options.body ? JSON.stringify(options.body) : undefined,
    })
  } catch {
    throw new ApiError('ارتباط با سرور برقرار نشد. از اجرا بودن سرویس مطمئن شوید.', 0)
  }

  // ۴۰۴ اینجا خطا نیست: «پیدا نشد» یک پاسخ معتبر با بدنهٔ کامل است
  if (response.status === 404) {
    try {
      return await response.json()
    } catch {
      throw new ApiError('لاگی با این شناسه پیدا نشد.', 404)
    }
  }

  if (!response.ok) {
    let message = `درخواست ناموفق بود (کد ${response.status})`
    let hint, traceId
    try {
      const body = await response.json()
      if (body?.message) message = body.message
      hint = body?.hint
      traceId = body?.traceId
    } catch { /* بدنهٔ غیر-JSON */ }
    throw new ApiError(message, response.status, hint, traceId)
  }
  return response.json()
}

export const api = {
  ui: () => request('/meta/ui'),
  health: () => request('/meta/health'),
  reload: () => request('/meta/config/reload', null, { method: 'POST' }),

  searchFields: () => request('/log/search/fields'),
  byId: (id, field) => request(`/log/${encodeURIComponent(id)}`, { field }),

  advancedConfig: () => request('/log/advanced/config'),
  advanced: (filters) => request('/log/advanced', null, { method: 'POST', body: { filters } }),
}
