import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileBackDispatcher } from './effects/mobile-back-dispatcher.ts'

/** 声明 Cordis 客户端服务依赖（侧边栏开关需要调用 ctx.layout.toggleSidebar） */
export const inject = ['layout']

/**
 * 移动端全面屏手势与侧滑返回分发中枢入口：
 * 挂载 triggerMobileBack 全局调度函数（无门控直绑 window，WebView 可随时调用）
 */
export function apply(ctx: ClientContext): void {
  installMobileBackDispatcher(ctx)

  // 连接服务缺失时仍保留手势分发；由旧版网页自己的连接循环恢复。
  ctx.inject(['connection'], (scope) => {
    const connection = scope.get('connection') as {
      state: { getSnapshot(): string | undefined }
      reconnect(): void
    }
    scope.effect(() => {
      if (typeof window === 'undefined') return
      const onForeground = (): void => {
        // connecting 包含正在握手和退避重试；不要打断已有连接或重试。
        if (connection.state.getSnapshot() === 'disconnected') connection.reconnect()
      }
      window.addEventListener('dsha:foreground', onForeground)
      return () => window.removeEventListener('dsha:foreground', onForeground)
    }, 'dsh-quickchat-gesture: foreground reconnect')
  })
}

export { triggerMobileBack, installMobileBackDispatcher } from './effects/mobile-back-dispatcher.ts'
