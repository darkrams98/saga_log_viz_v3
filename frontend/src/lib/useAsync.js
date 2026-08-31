import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * هوک کوچک برای مدیریت سه حالت لازم هر صفحه: در حال بارگذاری / خطا / داده.
 * درخواست‌های قدیمی نادیده گرفته می‌شوند تا نتیجهٔ کند، نتیجهٔ تازه را خراب نکند.
 */
export function useAsync(fn, deps = [], { skip = false } = {}) {
  const [state, setState] = useState({ loading: !skip, error: null, data: null })
  const requestId = useRef(0)

  const run = useCallback(() => {
    if (skip) {
      setState({ loading: false, error: null, data: null })
      return
    }
    const id = ++requestId.current
    setState((s) => ({ ...s, loading: true, error: null }))
    Promise.resolve()
      .then(fn)
      .then((data) => {
        if (id === requestId.current) setState({ loading: false, error: null, data })
      })
      .catch((error) => {
        if (id === requestId.current) {
          setState({ loading: false, error: error.message || 'خطای نامشخص', data: null })
        }
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    run()
  }, [run])

  return { ...state, reload: run }
}

/** تأخیر در اعمال مقدار — برای اینکه با هر حرف تایپ‌شده به سرور درخواست نرود */
export function useDebounced(value, delay = 350) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(t)
  }, [value, delay])
  return debounced
}
