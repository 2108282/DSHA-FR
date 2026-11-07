import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from './effects/phone-chrome.ts'
import { createStatsLineTask } from './effects/stats-line.ts'
import { installSidebarSwipe } from './effects/sidebar-swipe.ts'
import { installFileUpload } from './effects/file-upload.ts'
import { openFilesPanel } from './components/open-files-panel.ts'
import { COMPOSER_AND_STATS_CSS } from './styles/styles.ts'

export const inject = ['layout']

export function apply(ctx: ClientContext): void {
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

  installMobileEffect(ctx, 'dsh-mobile-lite: stats-line task', () => {
    const statsTask = createStatsLineTask()
    statsTask.ensure()
    return () => {
      statsTask.dispose()
    }
  })

  installSidebarSwipe(ctx, () => openFilesPanel())
  
  installFileUpload(ctx)
}
