import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from './effects/phone-chrome.ts'
import { createStatsLineTask } from './effects/stats-line.ts'
import { installSidebarSwipe } from './effects/sidebar-swipe.ts'
import { openFilesPanel } from './components/open-files-panel.ts'
import { COMPOSER_AND_STATS_CSS } from './styles/styles.ts'

/** 声明 Cordis 客户端服务依赖（侧边栏开关需要调用 ctx.layout.toggleSidebar） */
export const inject = ['layout']

/**
 * 移动端轻量功能总装入口：
 * 1. 注入输入框紧凑化与用量环浮层 CSS；
 * 2. 挂载 stats-line 动态计算与局部 MutationObserver 调度任务；
 * 3. 挂载左滑/右滑调出侧边栏的双向手势引擎。
 */
export function apply(ctx: ClientContext): void {
  // 1. 挂载紧凑化与用量环样式表
  ctx.effect(() => {
    const tagId = 'dsh-mobile-lite-styles'
    for (const stale of document.querySelectorAll(`style[data-plugin-css="${tagId}"]`)) {
      stale.remove()
    }
    const style = document.createElement('style')
    style.dataset.pluginCss = tagId
    style.textContent = COMPOSER_AND_STATS_CSS
    document.head.appendChild(style)
    return () => {
      style.remove()
    }
  }, 'dsh-mobile-lite: inject styles')

  // 2. 挂载功能 1：输入框与底部性能/用量组件布局优化 (Stats-line)
  installMobileEffect(ctx, 'dsh-mobile-lite: stats-line task', () => {
    const statsTask = createStatsLineTask()
    statsTask.ensure()
    return () => {
      statsTask.dispose()
    }
  })

  // 3. 挂载功能 2：左右两侧侧边栏滑动手势 (Sidebar & Files Swipe)
  installSidebarSwipe(ctx, () => openFilesPanel())
}
