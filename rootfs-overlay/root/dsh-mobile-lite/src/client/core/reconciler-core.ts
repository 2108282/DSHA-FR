/**
 * 最小化 ReconcilerTask 任务契约，供 stats-line 任务声明调度生命周期。
 */
export interface ReconcilerTask {
  readonly name: string
  readonly scopes?: readonly string[]
  ensure(): void
  dispose(): void
}
