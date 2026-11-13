import { createElement, useCallback, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type ComponentType } from 'react'
import { FollowHost } from './FollowHost.tsx'
import { useProgressiveDomText } from './useProgressiveDomText.ts'
import { streamRelay } from './streamRelay.ts'
import { hasRecentConversationFollow } from './teleprompterGlide.ts'
import entranceCss from './AgentRowEntrance.module.css'

/** Props forwarded through a follow wrap; extra kit seats pass through. */
export type FollowWrapProps = {
  node?: unknown
  renderSlot?: unknown
} & Record<string, unknown>

/**
 * 严格判定卡片是否属于历史卡片或已结算卡片（含未成功加载、中断、执行完毕的卡片）。
 * 历史卡片与已结算卡片必须保持纯静态展示，绝不触发清空打字机或占用流式接力棒。
 */
export function isSettledChatNode(node: unknown): boolean {
  if (node === null || typeof node !== 'object') return false
  const n = node as Record<string, unknown>
  const data = n.data as Record<string, unknown> | undefined
  if (data !== undefined && typeof data === 'object') {
    // 助手卡片已结算或因错误/停止而中断
    if (data.status === 'settled' || data.status === 'interrupted') return true
    // 工具卡片已产出最终执行结果
    if (data.root !== null && typeof data.root === 'object') {
      const root = data.root as Record<string, unknown>
      if (root.kind === 'tool-result' || ('result' in root && root.result !== undefined)) return true
      if ('state' in root && (root.state === 'ok' || root.state === 'error' || root.state === 'stopped')) return true
    }
    // 命令卡片已执行完毕（无论是 ok 还是 error）
    if (data.command !== null && typeof data.command === 'object') {
      const cmd = data.command as Record<string, unknown>
      if (cmd.outcome !== null && cmd.outcome !== undefined) return true
    }
    if ('outcome' in data && data.outcome !== null && data.outcome !== undefined) return true
    // 模型重试已结束
    if (data.current !== null && typeof data.current === 'object') {
      const cur = data.current as Record<string, unknown>
      if (cur.retryState !== undefined && cur.retryState !== 'scheduled') return true
    }
    if (data.state === 'ok' || data.state === 'error' || data.state === 'stopped') return true
  }
  const location = n.location as Record<string, unknown> | undefined
  if (location !== undefined && typeof location === 'object') {
    if (location.kind === 'step' && location.step !== null && typeof location.step === 'object') {
      if ((location.step as { status?: string }).status === 'closed') return true
    }
    if (location.kind === 'turn' && location.turn !== null && typeof location.turn === 'object') {
      if ((location.turn as { status?: string }).status === 'closed') return true
    }
  }
  return false
}

function openAgentLocation(node: unknown): boolean {
  if (node === null || typeof node !== 'object' || !('location' in node)) return false
  const location = (node as { location: unknown }).location
  if (location === null || typeof location !== 'object' || !('kind' in location)) return false
  const kind = (location as { kind: unknown }).kind
  if (!('turn' in location)) return false
  const turn = (location as { turn: unknown }).turn
  if (turn === null || typeof turn !== 'object' || !('status' in turn)) return false
  if (kind === 'turn') return (turn as { status: unknown }).status === 'open'
  if (kind !== 'step' || !('step' in location)) return false
  const step = (location as { step: unknown }).step
  return step !== null
    && typeof step === 'object'
    && 'status' in step
    && (step as { status: unknown }).status === 'open'
}

/**
 * True while a Chat node has an explicitly unfinished lifecycle: an
 * assistant/workflow `status: 'running'` payload, a Tool root that has not
 * settled (`kind` absent), or a model-retry whose current attempt is still
 * `scheduled`.
 */
export function isGrowingChatNode(node: unknown): boolean {
  if (isSettledChatNode(node)) return false
  if (node === null || typeof node !== 'object' || !('data' in node)) return false
  const data = (node as { data: unknown }).data
  if (data === null || typeof data !== 'object') return false
  if ('status' in data && (data as { status: unknown }).status === 'running') return true
  if (
    'kind' in data
    && (data as { kind: unknown }).kind === 'command'
    && 'outcome' in data
    && (data as { outcome: unknown }).outcome === null
  ) return true
  if ('command' in data) {
    const command = (data as { command: unknown }).command
    if (
      command !== null
      && typeof command === 'object'
      && 'outcome' in command
      && (command as { outcome: unknown }).outcome === null
    ) return true
  }
  if ('root' in data) {
    const root = (data as { root: unknown }).root
    if (root !== null && typeof root === 'object' && !('kind' in root)) return true
  }
  if ('current' in data) {
    const current = (data as { current: unknown }).current
    if (
      current !== null
      && typeof current === 'object'
      && 'retryState' in current
      && (current as { retryState: unknown }).retryState === 'scheduled'
    ) return true
  }
  return false
}

/**
 * True for any Agent-owned Chat row in the currently open turn/step.
 * Settled historical cards are unconditionally excluded.
 */
export function isFollowableChatNode(node: unknown): boolean {
  if (isSettledChatNode(node)) return false
  return isGrowingChatNode(node) || openAgentLocation(node)
}

/**
 * True when a newly mounted Agent-owned row belongs to the current open
 * Turn/Step, or is itself an unresolved growing lifecycle.
 */
export function shouldAnimateChatNodeEntrance(node: unknown): boolean {
  return isFollowableChatNode(node)
}

function getTurnKey(node: unknown): string {
  if (node === null || typeof node !== 'object' || !('location' in node)) return 'active'
  const location = (node as { location: unknown }).location
  if (location === null || typeof location !== 'object') return 'active'
  if ('turn' in location && location.turn !== null && typeof location.turn === 'object') {
    const turn = location.turn as { turn?: unknown }
    if (turn.turn !== undefined) return String(turn.turn)
  }
  return 'active'
}

/** Runtime-only fallback for unknown or terminal rows at the active flow tip. */
function liveAgentTailMode(root: HTMLElement): 'turn' | 'handoff' | null {
  const port = root.closest<HTMLElement>('[data-conversation-scroll]')
  if (port === null) return null
  const row = root.closest<HTMLElement>('[data-chat-flow-key]')
  if (row !== null) {
    let sibling = row.nextElementSibling
    while (sibling !== null) {
      if (sibling instanceof HTMLElement && sibling.hasAttribute('data-chat-flow-key')) return null
      sibling = sibling.nextElementSibling
    }
  }
  const flow = root.closest<HTMLElement>('[data-chat-flow]')
  const hasTurnStatus = flow !== null && [...flow.children].some(child =>
    child instanceof HTMLElement
    && child.getAttribute('role') === 'status'
    && !child.hasAttribute('data-chat-flow-key'))
  if (hasTurnStatus) return 'turn'
  return hasRecentConversationFollow(port) ? 'handoff' : null
}

/**
 * Wrap a prior Agent Chat renderer so its entrance and later growth share
 * conversation follow. Presentation stays with the wrapped component; kit
 * seats (`renderSlot`, locale, inject) pass through unchanged.
 */
export function wrapFollowNodeView(
  Inner: ComponentType<FollowWrapProps>,
  useControlScroll?: () => boolean,
) {
  return function TypewriterFollowNodeView(props: FollowWrapProps) {
    const controlScroll = useControlScroll?.() ?? true
    const speedCpsRef = useRef(35)
    const hostRef = useRef<HTMLDivElement>(null)
    const isSettled = isSettledChatNode(props.node)
    const growing = !isSettled && isGrowingChatNode(props.node)
    const structurallyFollowable = !isSettled && isFollowableChatNode(props.node)
    const structuralRef = useRef(structurallyFollowable)
    const [runtimeFollowable, setRuntimeFollowable] = useState(false)
    const runtimePersistentRef = useRef(false)
    const runtimeHandledRef = useRef(false)
    const followable = !isSettled && (structurallyFollowable || runtimeFollowable)
    const revealInitialRef = useRef(true)
    const [entering, setEntering] = useState(false)
    const [growthPulse, setGrowthPulse] = useState(false)
    const followableRef = useRef(false)
    const growingRef = useRef(growing)
    const entranceActiveRef = useRef(entering || growthPulse)
    const growthExtentRef = useRef<number | null>(null)
    const mountedRef = useRef(true)
    const pulseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

    structuralRef.current = structurallyFollowable
    followableRef.current = followable
    growingRef.current = growing
    entranceActiveRef.current = entering || growthPulse

    // 内存接力棒门禁：检查前序思考或正文是否仍在活跃输出中
    const turnKey = getTurnKey(props.node)
    const relayBlocked = useSyncExternalStore(
      streamRelay.subscribe,
      () => streamRelay.isToolBlocked(turnKey),
      () => false,
    )

    // DOM 前驱时序门禁：仅检查并监听紧邻的前驱卡片，切断全容器广播风暴
    const [precedingTyping, setPrecedingTyping] = useState(false)
    const isUnsettled = !isSettled && (growing || structurallyFollowable)

    useLayoutEffect(() => {
      if (!isUnsettled) {
        setPrecedingTyping(false)
        return
      }
      const root = hostRef.current
      if (!root) return
      const flowItem = root.closest('[data-chat-flow-key]')
      if (!flowItem) return

      // 仅定位紧邻的上一张卡片（O(1) 局部定位）
      let prev: HTMLElement | null = null
      let cur = flowItem.previousElementSibling
      while (cur instanceof HTMLElement) {
        if (cur.hasAttribute('data-chat-flow-key')) {
          prev = cur
          break
        }
        cur = cur.previousElementSibling
      }

      if (!prev) {
        setPrecedingTyping(false)
        return
      }

      const checkPreceding = () => {
        if (!prev) return false
        return prev.querySelector('[data-streaming], [data-smooth-stream-typing="true"]') !== null
      }

      const isTyping = checkPreceding()
      setPrecedingTyping(isTyping)
      if (!isTyping || typeof MutationObserver === 'undefined') return

      // 仅监听紧邻前驱，且一旦前驱完成立即销毁 observer，彻底杜绝 N^2 全局监听
      const observer = new MutationObserver(() => {
        const stillTyping = checkPreceding()
        setPrecedingTyping(stillTyping)
        if (!stillTyping) {
          observer.disconnect()
        }
      })
      observer.observe(prev, {
        attributes: true,
        attributeFilter: ['data-streaming', 'data-smooth-stream-typing'],
        subtree: true,
      })
      return () => observer.disconnect()
    }, [isUnsettled])

    // 超时自愈保护：若前序卡片因异常未能释放锁，6 秒后自动放行，绝不永久卡死
    const [timedOut, setTimedOut] = useState(false)
    const waiting = isUnsettled && (relayBlocked || precedingTyping) && !timedOut

    useEffect(() => {
      if (waiting) {
        const timer = setTimeout(() => { setTimedOut(true) }, 6000)
        return () => clearTimeout(timer)
      } else {
        setTimedOut(false)
      }
    }, [waiting])

    // 前序完成后顺畅触发入场动画
    const wasWaitingRef = useRef(waiting)
    useEffect(() => {
      if (wasWaitingRef.current && !waiting && !isSettled) {
        setEntering(true)
      }
      wasWaitingRef.current = waiting
    }, [waiting, isSettled])

    const finishRuntimeReveal = useCallback(() => {
      if (structuralRef.current || runtimePersistentRef.current) return
      runtimeHandledRef.current = true
      setRuntimeFollowable(false)
    }, [])

    useProgressiveDomText(
      hostRef,
      !waiting && followable,
      revealInitialRef.current,
      speedCpsRef,
      runtimeFollowable ? finishRuntimeReveal : undefined,
    )

    useLayoutEffect(() => {
      if (structurallyFollowable || isSettled) return
      const root = hostRef.current
      if (root === null) return
      const mode = liveAgentTailMode(root)
      if (runtimeFollowable) {
        if (runtimePersistentRef.current && mode !== 'turn') {
          runtimePersistentRef.current = false
          runtimeHandledRef.current = true
          setRuntimeFollowable(false)
        }
        return
      }
      if (runtimeHandledRef.current || mode === null) return
      runtimePersistentRef.current = mode === 'turn'
      setRuntimeFollowable(true)
      setEntering(false)
    }, [runtimeFollowable, structurallyFollowable, isSettled])

    const finishEntrance = useCallback(() => {
      growthExtentRef.current = null
      if (pulseTimerRef.current !== null) {
        clearTimeout(pulseTimerRef.current)
        pulseTimerRef.current = null
      }
      setEntering(false)
      setGrowthPulse(false)
    }, [])

    const onGrowth = useCallback((deltaPx: number) => {
      if (
        !mountedRef.current
        || !followableRef.current
        || growingRef.current
        || entranceActiveRef.current
        || isSettled
      ) return
      growthExtentRef.current = deltaPx
      setGrowthPulse(true)
      if (pulseTimerRef.current !== null) clearTimeout(pulseTimerRef.current)
      pulseTimerRef.current = setTimeout(() => {
        pulseTimerRef.current = null
        setGrowthPulse(false)
      }, 1200)
    }, [isSettled])

    useEffect(() => {
      mountedRef.current = true
      return () => {
        mountedRef.current = false
        if (pulseTimerRef.current !== null) clearTimeout(pulseTimerRef.current)
      }
    }, [])

    useLayoutEffect(() => {
      const el = hostRef.current
      if (!el) return
      const card = el.closest('[data-chat-flow-key]')
      const settled = isSettled || (!growing && !entering && !growthPulse && !waiting)
      if (settled) {
        el.setAttribute('data-smooth-stream-settled', 'true')
        if (card instanceof HTMLElement) card.setAttribute('data-smooth-stream-settled', 'true')
      } else {
        el.removeAttribute('data-smooth-stream-settled')
        if (card instanceof HTMLElement) card.removeAttribute('data-smooth-stream-settled')
      }
    }, [isSettled, growing, entering, growthPulse, waiting])

    return (
      <div style={waiting ? { opacity: 0, pointerEvents: 'none', height: 0, overflow: 'hidden' } : { transition: 'opacity 0.15s ease' }}>
        <FollowHost
          active={!waiting && growing}
          entrance={!waiting && (entering || growthPulse)}
          onEntranceSettled={finishEntrance}
          onGrowth={followable && !waiting ? onGrowth : undefined}
          entranceExtentRef={growthExtentRef}
          speedCpsRef={speedCpsRef}
          controlScroll={controlScroll}
          predictive={false}
          hostRef={hostRef}
          className={entranceCss.surface}
          entranceActive={!waiting && (entering || growthPulse)}
        >
          {createElement(Inner, props)}
        </FollowHost>
      </div>
    )
  }
}
