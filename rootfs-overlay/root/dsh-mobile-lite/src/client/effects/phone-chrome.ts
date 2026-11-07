import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'

/**
 * 移动端/触屏断点检测：
 * 视口宽度 < 1024px 且主输入为粗指针 (触屏设备 / DSHA 原生环境)
 */
export const MOBILE_QUERY = '(max-width: 1023px) and (pointer: coarse)'

/**
 * 检查当前视口是否匹配移动端
 */
export function isMobileViewport(): boolean {
  return typeof window !== 'undefined' && window.matchMedia(MOBILE_QUERY).matches
}

/**
 * 获取 AppFrame 外壳根节点：
 * 依次尝试插件打标的选择器、shell.overlay 父节点、以及带有 sidebarCol 的类名匹配。
 */
export function findFrame(): HTMLElement | null {
  const overlay = document.querySelector('[data-shell-overlay]')
  if (overlay?.parentElement) return overlay.parentElement as HTMLElement
  return document.querySelector<HTMLElement>(
    '[data-mobile-nav="frame"], [class*="pI_x6G_frame"], [class*="frame"]:has(> [class*="sidebarCol"]), [class*="frame"]:has(> [data-pane="sidebar"])'
  )
}

export function getFrame(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-mobile-nav="frame"]') ?? findFrame()
}

/**
 * 确保外壳打上移动端标记，随时可重入
 */
export function ensureFrameMarker(): HTMLElement | null {
  if (!isMobileViewport()) return null
  const frame = getFrame()
  if (frame !== null && !frame.hasAttribute('data-mobile-nav')) {
    frame.setAttribute('data-mobile-nav', 'frame')
  }
  return frame
}

/**
 * 移动端专属副作用生命周期挂载器：
 * 1. 响应式监听 matchMedia 状态变化；
 * 2. 匹配移动端时自动给 frame 注入 [data-mobile-nav="frame"]，使紧凑化 CSS 生效；
 * 3. 首帧若未渲染完成则通过轮询与监听确保标记注入；
 * 4. 挂载子任务并绑定清理回调。
 */
export function installMobileEffect(
  ctx: ClientContext,
  label: string,
  install: (narrow: MediaQueryList) => (() => void) | undefined,
  query: string = MOBILE_QUERY,
): void {
  ctx.effect(() => {
    const narrow = window.matchMedia(query)
    let cleanup: (() => void) | undefined
    let pollCount = 0
    let pollId: number | null = null

    const arm = (): void => {
      cleanup?.()
      if (pollId !== null) {
        cancelAnimationFrame(pollId)
        pollId = null
      }
      if (narrow.matches) {
        ensureFrameMarker()
        // 若首帧 React 尚未挂载 frame，连续在接下来的几帧持续尝试
        const tryArmFrame = (): void => {
          if (!narrow.matches) return
          const frame = ensureFrameMarker()
          if (frame === null && pollCount < 30) {
            pollCount++
            pollId = requestAnimationFrame(tryArmFrame)
          } else {
            pollId = null
          }
        }
        tryArmFrame()
        cleanup = install(narrow)
      } else {
        getFrame()?.removeAttribute('data-mobile-nav')
        cleanup = undefined
      }
    }

    arm()
    narrow.addEventListener('change', arm)
    return () => {
      narrow.removeEventListener('change', arm)
      if (pollId !== null) cancelAnimationFrame(pollId)
      cleanup?.()
      getFrame()?.removeAttribute('data-mobile-nav')
    }
  }, label)
}
