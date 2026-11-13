import { createElement, useSyncExternalStore, type ComponentType } from 'react'
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import type {} from '@deepseek-ai/dsh-client-locale/client'
// Type-only: the connection Context merge and the plugins section's SlotMap
// entry ('settings.plugin.item').
import type { ConnectionHandle } from '@deepseek-ai/dsh-client-connection/client'
import type {} from '@deepseek-ai/dsh-client-ui-settings-plugins/client'
import type { ChatNodeViewProps } from '@deepseek-ai/dsh-client-ui-conversation/client'
import { TypewriterAssistantNodeView } from './TypewriterAssistantNodeView.tsx'
import { wrapFollowNodeView, type FollowWrapProps } from './TypewriterToolNodeView.tsx'
import { SmoothStreamCard } from './SmoothStreamCard.tsx'
import { SmoothStreamCardController } from './smooth-stream-card-controller.ts'
import { createSmoothStreamSettingsApi } from './smooth-stream-settings-api.ts'
import { DebugPanel } from './DebugPanel.tsx'
import { debugRuntime } from './debugRuntime.ts'
import { NS as SETTINGS_NS, en, zh } from './locales.ts'
import { DEFAULT_STREAM_CONFIG, STREAM_BOOT_GLOBAL, type StreamConfig } from '../config.ts'
import { DEFAULT_STREAM_SETTINGS, STREAM_SETTINGS_NS, type StreamSettings } from '../settings.ts'

/**
 * Cordis services required by the browser half. Only `slots` is load-bearing
 * for the stream itself; locale and Connection power the configuration card
 * and are wired through `ctx.inject` below so a deployment without them still
 * streams with defaults.
 */
export const inject = ['slots']

type AssistantProps = ChatNodeViewProps<'assistant-step'>

const STREAM_MODES: readonly string[] = ['typewriter', 'teleprompter']
const STREAM_PRESETS: readonly string[] = ['realtime', 'balanced', 'silky']

/**
 * The assistant renderer owns its own character queue and conversation
 * follower, so wrapping it again would create two scroll owners. Human input
 * stays immediate; every Agent-owned output renderer goes through the same
 * generic follow boundary. This is deliberately keyed by the owner that
 * provides the renderer, not by individual tool names, so new Context,
 * Command, and Tool rows are covered automatically.
 */
const SKIP_WRAP = new Set(['assistant-step', 'user', 'steering', 'command-input'])

/** React function/class or an exotic component such as memo/forwardRef/lazy. */
function isWrappableComponent(value: unknown): value is ComponentType<FollowWrapProps> {
  return typeof value === 'function'
    || (value !== null && typeof value === 'object' && '$$typeof' in value)
}

/**
 * Read the Host-bridged boot config. The inline script is produced by this
 * plugin's Host half from a schema-validated value, so only the structural
 * guarantees that could break between the two halves are re-checked: the
 * global is absent when the client runs without its Host entry (defaults
 * apply), and any present-but-malformed value fails loudly instead of
 * rendering a half-configured view.
 * @returns The resolved configuration for the assistant node view.
 */
function readBootConfig(): StreamConfig {
  const raw = (globalThis as Record<string, unknown>)[STREAM_BOOT_GLOBAL]
  if (raw === undefined) {
    console.info('[dsh-smooth-stream] no host config bridge; using defaults')
    return DEFAULT_STREAM_CONFIG
  }
  if (
    typeof raw !== 'object' || raw === null
    || !STREAM_MODES.includes((raw as StreamConfig).mode)
    || !STREAM_PRESETS.includes((raw as StreamConfig).preset)
    || typeof (raw as StreamConfig).revealCharsPerSec !== 'number'
    || typeof (raw as StreamConfig).scrollSpeedPxPerSec !== 'number'
    || typeof (raw as StreamConfig).maxScrollSpeedPxPerSec !== 'number'
  ) {
    throw new Error(`[dsh-smooth-stream] malformed ${STREAM_BOOT_GLOBAL} boot global: ${JSON.stringify(raw)}`)
  }
  return raw as StreamConfig
}

/**
 * Wrap every Agent-owned keyed Chat row except the assistant renderer in
 * place. A second
 * register with the same `children` table throws because the child slot is
 * already declared, and only the winning entry receives `renderSlot`;
 * swapping `entry.component` keeps the original children, locale, and inject
 * seats. `assistant-step` is replaced below so text and Think use the
 * typewriter reveal. The wrapper owns only the shared layout-growth/follow
 * lifecycle; the Harness keeps each renderer's controls, disclosures, and
 * cards intact.
 * @param ctx - Browser context carrying the slot registry.
 * @returns Restorer that puts the original components back.
 */
function wrapAgentChatRows(
  ctx: ClientContext,
  useControlScroll: () => boolean,
): () => void {
  const restores: Array<() => void> = []
  const wrapped = new WeakSet<object>()

  const wrapAll = (): void => {
    for (const entry of ctx.slots.entries('conversation.chat.node')) {
      const key = entry.options.key
      if (key === undefined || SKIP_WRAP.has(key)) continue
      const current = entry.component
      if (!isWrappableComponent(current) || wrapped.has(current)) continue
      const inner = current as ComponentType<FollowWrapProps>
      const next = wrapFollowNodeView(inner, useControlScroll)
      wrapped.add(next)
      entry.component = next
      restores.push(() => {
        if (entry.component === next) entry.component = inner
      })
    }
  }

  wrapAll()
  const off = ctx.on('slots/changed', (key: string) => {
    if (key === 'conversation.chat.node') wrapAll()
  })
  return () => {
    off()
    for (const restore of restores) restore()
  }
}

/**
 * A live settings cell shared by the renderer lifecycle and React views. It
 * starts on the shared defaults and follows the plugin-owned controller once
 * the optional settings services arrive.
 */
class SettingsCell {
  private readonly listeners = new Set<() => void>()
  private card: SmoothStreamCardController | undefined
  private value: StreamSettings = (() => {
    const val = { ...DEFAULT_STREAM_SETTINGS }
    try {
      const saved = typeof localStorage !== 'undefined' ? localStorage.getItem('dsh-smooth-stream:settings') : null
      if (saved) Object.assign(val, JSON.parse(saved))
    } catch {}
    return val
  })()
  private pending = false

  /** Re-point the cell at the plugin-owned settings controller. */
  attach(card: SmoothStreamCardController): () => void {
    this.card = card
    this.refresh()
    const unsubscribe = card.subscribe(() => { this.refresh() })
    return () => {
      unsubscribe()
      if (this.card !== card) return
      this.card = undefined
      this.refresh()
    }
  }

  private read(): StreamSettings {
    const snapshot = this.card?.getSnapshot()
    if (snapshot === undefined || snapshot.status !== 'ready') return this.value
    return this.card?.values() ?? this.value
  }

  private refresh(): void {
    const next = this.read()
    const pending = this.card?.getSnapshot().status === 'loading'
    if (
      pending === this.pending
      && next.enabled === this.value.enabled
      && next.controlScroll === this.value.controlScroll
      && next.motionPreference === this.value.motionPreference
      && next.thinkAutoExpand === this.value.thinkAutoExpand
      && next.logarithmicFade === this.value.logarithmicFade
      && next.fastFold === this.value.fastFold
      && next.fastPipeline === this.value.fastPipeline
      && next.keepStreamOnToolCall === this.value.keepStreamOnToolCall
      && next.debugEnabled === this.value.debugEnabled
      && next.debugTuning === this.value.debugTuning
    ) return
    this.pending = pending
    this.value = next
    for (const listener of this.listeners) listener()
  }

  /** Always respect the user's enabled preference immediately. */
  takeoverEnabled(): boolean {
    return this.value.enabled
  }

  readonly getSnapshot = (): StreamSettings => this.value

  readonly subscribe = (listener: () => void): () => void => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
}

const FAST_FOLD_STYLE_ID = 'smooth-stream-fast-fold-style'

function ensureFastFoldStyles(): void {
  if (typeof document === 'undefined') return
  if (document.getElementById(FAST_FOLD_STYLE_ID) !== null) return
  const style = document.createElement('style')
  style.id = FAST_FOLD_STYLE_ID
  style.textContent = `
    html[data-smooth-stream-fast-fold="true"] [data-turn-process-hidden],
    html[data-smooth-stream-fast-fold="true"] [hidden="until-found"],
    [data-disclosure-content][data-collapsed] {
      display: none !important;
    }
    /* 核心性能优化：单步已结算卡片与历史卡片均开启严格局部隔离，切断回流蔓延且彻底杜绝视口高度跳变 */
    [data-smooth-stream-settled="true"],
    [data-chat-flow] > [data-chat-flow-key][data-smooth-stream-settled="true"],
    [data-chat-flow] > [data-chat-flow-key]:not(:has([data-streaming])):not(:has([data-smooth-stream-typing="true"])) {
      contain: layout paint style;
    }
  `
  document.head.appendChild(style)
}

function syncFastFold(enabled: boolean): void {
  if (typeof document === 'undefined') return
  ensureFastFoldStyles()
  if (enabled) {
    document.documentElement.setAttribute('data-smooth-stream-fast-fold', 'true')
  } else {
    document.documentElement.removeAttribute('data-smooth-stream-fast-fold')
  }
}

/**
 * Self-healing optimizer for DSH conversation pipeline.
 *
 * DSH core (@deepseek-ai/dsh-client-ui-conversation) ships with a nested 3-frame
 * requestAnimationFrame throttle on "animation-frame" publications, introducing
 * a hard ~50ms artificial latency between streaming tokens and causing severe
 * stuttering on long conversations.
 *
 * This hook detects the active BoundConversation prototype and replaces the 3-frame
 * nest with an immediate single-frame dispatch whenever fastPipeline is active.
 * Because it hooks dynamically in browser memory, it persists across any reinstall
 * or upgrade of DSH packages.
 */
function healDshConversationPipeline(ctx: ClientContext, settings: SettingsCell): void {
  ctx.inject(['uiConversation'], (scopedCtx: any) => {
    const uiConv = scopedCtx.uiConversation ?? scopedCtx.get?.('uiConversation')
    if (!uiConv) return

    const patchPrototype = (binding: any): void => {
      if (!binding) return
      const proto = Object.getPrototypeOf(binding)
      if (proto && !proto.__dshSmoothStreamFastPublishPatched && typeof proto.publish === 'function') {
        proto.__dshSmoothStreamFastPublishPatched = true
        const originalPublish = proto.publish
        proto.publish = function (publication: string) {
          if (publication === 'animation-frame' && settings.getSnapshot().fastPipeline && typeof requestAnimationFrame === 'function') {
            if (this.frame !== undefined) return
            this.frame = requestAnimationFrame(() => {
              this.frame = undefined
              this.flush()
            })
            return
          }
          return originalPublish.call(this, publication)
        }
        console.info('[dsh-smooth-stream] Self-healed DSH 3-frame rAF throttle into native 60/120fps dispatch')
      }
    }

    try {
      if (uiConv.bindings) {
        const list = typeof uiConv.bindings.values === 'function'
          ? Array.from(uiConv.bindings.values())
          : (uiConv.bindings.values ? Array.from(uiConv.bindings.values as any) : [])
        for (const record of list as any[]) {
          if (record?.binding) {
            patchPrototype(record.binding)
            break
          }
        }
      }
    } catch {}

    // 兼容 0.2.2+ 协议：拦截 ignorable 事件，避免未知类型报错
    if (uiConv.events && typeof uiConv.events.dispatch === 'function' && !uiConv.events.__dshIgnorablePatched) {
      uiConv.events.__dshIgnorablePatched = true
      const origDispatch = uiConv.events.dispatch
      uiConv.events.dispatch = function (entry: any) {
        if (entry?.event?.ignorable === true) return null
        return origDispatch.apply(this, arguments)
      }
    }

    const origBinding = uiConv.binding
    if (typeof origBinding === 'function' && !uiConv.__dshSmoothStreamBindingPatched) {
      uiConv.__dshSmoothStreamBindingPatched = true
      uiConv.binding = function (...args: any[]) {
        const result = origBinding.apply(this, args)
        patchPrototype(result)
        return result
      }
    }
  })
}

/**
 * Register the typewriter renderer after the conversation package declares the
 * keyed Chat node seat. A lower priority shadows the built-in assistant row;
 * every other keyed renderer is wrapped in place so Context, commands, Tool
 * cards, retries, and workflow runs share one extensible follow boundary. The
 * Host-bridged configuration
 * selects the render direction, smoothing preset, and glide speed; the
 * plugin-owned settings RPC supplies the live auto-expand preference when the
 * settings surface is composed.
 * @param ctx - Browser context carrying the shared slot registry.
 */
export function apply(ctx: ClientContext): void {
  const config = readBootConfig()
  const settings = new SettingsCell()
  syncFastFold(settings.getSnapshot().fastFold)
  settings.subscribe(() => {
    syncFastFold(settings.getSnapshot().fastFold)
  })
  healDshConversationPipeline(ctx, settings)
  const useControlScroll = (): boolean => useSyncExternalStore(
    settings.subscribe,
    () => settings.getSnapshot().controlScroll,
    () => settings.getSnapshot().controlScroll,
  )
  const useKeepStreamOnToolCall = (): boolean => useSyncExternalStore(
    settings.subscribe,
    () => settings.getSnapshot().keepStreamOnToolCall,
    () => settings.getSnapshot().keepStreamOnToolCall,
  )

  // The card talks to the plugin-owned loopback RPC, so the core settings
  // namespace allowlist cannot make it disappear. The stream still applies
  // with defaults when the optional Settings UI or Connection is absent.
  ctx.inject(['slots', 'locale', 'connection'], (settingsCtx) => {
    const card = new SmoothStreamCardController(
      // The shared Context augmentation also carries the Host-side Connection
      // shape. This browser entry runs after the client provider installs its
      // handle, so narrow through unknown to its client contract here.
      createSmoothStreamSettingsApi(settingsCtx.get('connection') as unknown as ConnectionHandle),
    )
    const detachSettings = settings.attach(card)
    const syncDebug = (): void => {
      const snapshot = card.getSnapshot()
      debugRuntime.syncSettings({
        available: snapshot.debugAvailable,
        enabled: snapshot.debugEnabled,
        writable: snapshot.writable && !snapshot.saving,
        dirty: snapshot.dirty,
        status: snapshot.status,
        tuning: snapshot.debugTuning,
      })
    }
    const detachBinding = debugRuntime.bindSettings({
      edit: patch => { card.inject().edit(patch) },
      save: () => { card.inject().save() },
      discard: () => { card.inject().discard() },
    })
    const detachDebug = card.subscribe(syncDebug)
    syncDebug()
    card.start()
    settingsCtx.effect(() => settingsCtx.locale.register(SETTINGS_NS, { zh, en }), 'dsh-smooth-stream: settings dictionaries')

    const SmoothStreamSectionView = (props: any) => createElement(
      'div',
      { style: { width: '100%', maxWidth: '760px', minHeight: '240px', display: 'flex', flexDirection: 'column', gap: '16px', padding: '4px 0 24px 0' } },
      createElement('h2', { style: { margin: 0, fontSize: '18px', fontWeight: 600 } }, '平滑流式输出'),
      createElement('p', { style: { color: 'var(--dsw-alias-label-tertiary)', margin: 0, fontSize: '13px' } }, '流畅打字机渲染、丝滑平稳滚动与思考过程折叠配置。'),
      createElement('ul', { style: { listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column' } }, createElement(SmoothStreamCard as any, { ...props, cardController: card, defaultOpen: true })),
    )

    const SmoothStreamDetailSectionView = (props: any) => {
      const subject = props.subject
      if (subject?.kind === 'bundle' && subject?.pkg?.name === 'dsh-smooth-stream') {
        return createElement(
          'div',
          { style: { marginTop: '16px', display: 'flex', flexDirection: 'column', gap: '12px', width: '100%' } },
          createElement('h3', { style: { margin: 0, fontSize: '15px', fontWeight: 600 } }, '平滑流式参数配置'),
          createElement('ul', { style: { listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column' } }, createElement(SmoothStreamCard as any, { ...props, cardController: card, defaultOpen: true })),
        )
      }
      return null
    }

    // 1. 注册为全局设置左侧一级菜单「平滑流式」
    settingsCtx.slots.inject('settings.section', () => settingsCtx.slots.register({
      name: 'settings.section',
      id: 'smooth-stream',
      order: 25,
      label: () => '平滑流式',
      locale: SETTINGS_NS,
      inject: () => card.inject(),
    }, SmoothStreamSectionView))

    // 2. 像 dsh-agy 一样注册进官方内置插件页面下的二级 Tab（官方原生插槽，更新永不消失）
    settingsCtx.slots.inject('settings.plugins.tab', () => settingsCtx.slots.register({
      name: 'settings.plugins.tab',
      id: 'smooth-stream-tab',
      order: 15,
      label: () => '平滑流式',
      locale: SETTINGS_NS,
      inject: () => card.inject(),
    }, SmoothStreamSectionView))

    // 3. 注册为侧边栏插件管理器的详情页配置区块
    settingsCtx.slots.inject('plugins.detail.section', () => settingsCtx.slots.register({
      name: 'plugins.detail.section',
      id: 'smooth-stream-detail-settings',
      order: 10,
      locale: SETTINGS_NS,
      inject: () => card.inject(),
    }, SmoothStreamDetailSectionView))

    // 4. 保留原 settings.plugin.item 插槽注册（向下兼容）
    settingsCtx.slots.inject('settings.plugin.item', () => settingsCtx.slots.register({
      name: 'settings.plugin.item',
      id: 'smooth-stream',
      key: STREAM_SETTINGS_NS,
      order: 30,
      locale: SETTINGS_NS,
      inject: () => card.inject(),
    // Older Harness declares this slot as a list (`id`); newer Harness uses a
    // keyed slot filtered by the Host settings namespace (`key`).
    } as never, SmoothStreamCard))
    settingsCtx.slots.inject('conversation.session.header.utilities', () => settingsCtx.slots.register({
      name: 'conversation.session.header.utilities',
      id: 'smooth-stream-debug',
      order: 40,
      locale: SETTINGS_NS,
      inject: () => debugRuntime.panelFace(),
    }, DebugPanel))
    return () => {
      card.stop()
      detachDebug()
      detachSettings()
      detachBinding()
    }
  })

  const configured = function StreamConfiguredView(props: AssistantProps) {
    const preferences = useSyncExternalStore(
      settings.subscribe,
      settings.getSnapshot,
      settings.getSnapshot,
    )
    return createElement(TypewriterAssistantNodeView, {
      ...props,
      mode: config.mode,
      preset: config.preset,
      revealCharsPerSec: config.revealCharsPerSec,
      scrollSpeedPxPerSec: config.scrollSpeedPxPerSec,
      maxScrollSpeedPxPerSec: config.maxScrollSpeedPxPerSec,
      thinkAutoExpand: preferences.thinkAutoExpand,
      logarithmicFade: preferences.logarithmicFade,
      keepStreamOnToolCall: preferences.keepStreamOnToolCall,
      controlScroll: preferences.controlScroll,
      motionPreference: preferences.motionPreference,
    })
  }
  ctx.slots.inject('conversation.chat.node', () => {
    let releaseTakeover: (() => void) | undefined

    const syncTakeover = (): void => {
      if (!settings.takeoverEnabled()) {
        releaseTakeover?.()
        releaseTakeover = undefined
        return
      }
      if (releaseTakeover !== undefined) return
      const unwrap = wrapAgentChatRows(ctx, useControlScroll)
      const unshadow = ctx.slots.register({
        name: 'conversation.chat.node',
        key: 'assistant-step',
        priority: -100,
        locale: 'conversation',
        registrant: 'dsh-smooth-stream',
      }, configured)
      releaseTakeover = () => {
        // Stop observing slot changes before the shadow entry is removed.
        unwrap()
        unshadow()
      }
    }

    const unsubscribe = settings.subscribe(syncTakeover)
    syncTakeover()
    return () => {
      unsubscribe()
      releaseTakeover?.()
    }
  })
}
