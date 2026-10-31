import { useEffect } from 'react'
import type { MotionValue } from 'motion/react'

let deadline = 0, timer: ReturnType<typeof setTimeout> | undefined
// SVG displacement falls back to expensive raster passes on moving backdrops.
// During motion, keep native blur/reflection and composite the elastic surface;
// restore the unchanged library filter once movement has settled.
export function markGlassMotion(duration = 160) {
  deadline = Math.max(deadline, performance.now() + duration)
  if (timer !== undefined) return
  document.documentElement.dataset.glassMotion = 'active'
  const settle = () => {
    const remaining = deadline - performance.now()
    if (remaining > 0) { timer = setTimeout(settle, remaining + 1); return }
    timer = undefined; delete document.documentElement.dataset.glassMotion
  }
  timer = setTimeout(settle, duration + 1)
}

export function useGlassMotion(values: readonly MotionValue<number>[], reduced: boolean) {
  useEffect(() => {
    if (reduced) return
    const stop = values.map(value => value.on('change', () => markGlassMotion()))
    return () => stop.forEach(unsubscribe => unsubscribe())
    // Individual motion values are stable; callers pass a fixed-length tuple.
  }, [...values, reduced])
}

export function useGlassInputBudget() {
  useEffect(() => {
    const start = () => markGlassMotion(800)
    document.addEventListener('pointerdown', start, { capture: true, passive: true })
    document.addEventListener('keydown', start, { capture: true })
    return () => {
      document.removeEventListener('pointerdown', start, true); document.removeEventListener('keydown', start, true)
      clearTimeout(timer); timer = undefined; deadline = 0; delete document.documentElement.dataset.glassMotion
    }
  }, [])
}
