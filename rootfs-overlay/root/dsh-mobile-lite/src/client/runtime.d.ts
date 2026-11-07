declare module '@deepseek-ai/dsh-client-runtime/client' {
  export interface ClientContext {
    effect(install: () => unknown, label?: string): unknown
    layout: {
      toggleSidebar(): void
      [key: string]: unknown
    }
    [key: string]: unknown
  }
}
