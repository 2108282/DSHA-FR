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
}

export { triggerMobileBack, installMobileBackDispatcher } from './effects/mobile-back-dispatcher.ts'
