export const name = 'dsh-mobile-lite'

interface ClientModules {
  rebuilt(id: string): string | undefined
}
interface HostContext {
  inject(keys: string[], install: (scope: { clientModules: ClientModules }) => void): unknown
}

export function apply(ctx: HostContext): void {
  // Client modules are cached by the Host. Publish completed display patches
  // through its artifact registry without restarting DSH or its connection.
  ctx.inject(['clientModules'], (scope) => {
    for (const id of [
      '@deepseek-ai/dsh-api-session-controller',
      '@deepseek-ai/dsh-client-ui-session',
      '@deepseek-ai/dsh-client-ui-model-selection',
      '@deepseek-ai/dsh-client-ui-permission-presets',
    ]) scope.clientModules.rebuilt(id)
  })
}
