/**
 * 获取 AppFrame 外壳根节点：
 * 依次尝试插件打标的选择器、shell.overlay 父节点、以及带有 sidebarCol 的类名匹配。
 */
export function findFrame(): HTMLElement | null {
  if (typeof document === 'undefined') return null
  const overlay = document.querySelector('[data-shell-overlay]')
  if (overlay?.parentElement) return overlay.parentElement as HTMLElement
  return document.querySelector<HTMLElement>(
    '[data-mobile-nav="frame"], [class*="pI_x6G_frame"], [class*="frame"]:has(> [class*="sidebarCol"])'
  )
}

export function getFrame(): HTMLElement | null {
  if (typeof document === 'undefined') return null
  return document.querySelector<HTMLElement>('[data-mobile-nav="frame"]') ?? findFrame()
}
