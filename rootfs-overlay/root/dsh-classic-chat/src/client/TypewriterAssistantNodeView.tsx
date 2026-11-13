import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type ComponentProps, type ReactNode, type RefObject } from 'react'
import { IconThinkOutlineRegular, JsonBlock, MarkdownText } from '@deepseek-ai/dsh-client-ui-primitives'
import { ImageGallery, type ImageLoader, type MessageImageLabels } from '@deepseek-ai/dsh-client-ui-attachment'
import type { ChatNodeViewProps, TurnTailOwnerProps } from '@deepseek-ai/dsh-client-ui-conversation/client'
import { AnimatedDisclosure } from './AnimatedDisclosure.tsx'
import { notifyFollowCommit } from './teleprompterGlide.ts'
import { streamRelay } from './streamRelay.ts'
import { useSmoothStreamContent, type StreamSmoothingPreset } from './useSmoothStreamContent.ts'
import { useFpsGuard } from './useFpsGuard.ts'
import { useLogarithmicFade } from './useLogarithmicFade.ts'
import { FollowHost } from './FollowHost.tsx'
import { DEFAULT_STREAM_CONFIG, type StreamMode } from '../config.ts'
import { DEFAULT_STREAM_SETTINGS, type StreamMotionPreference } from '../settings.ts'
import css from './TypewriterAssistantNodeView.module.css'

type AssistantProps = ChatNodeViewProps<'assistant-step'>
type MarkdownProps = Pick<ComponentProps<typeof MarkdownText>, 'labels' | 'fileMentions' | 'text'>

function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(
    () => typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true,
  )
  useEffect(() => {
    if (typeof window === 'undefined' || window.matchMedia === undefined) return
    const query = window.matchMedia('(prefers-reduced-motion: reduce)')
    const onChange = () => setReduced(query.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])
  return reduced
}

/**
 * Resolve whether the reveal engine should stay off for this view. The OS
 * preference wins only in `auto` mode: `force-smooth` keeps the engine on
 * machines where a system-wide reduce-motion switch (or a forced browser
 * flag) would otherwise silently bypass smoothing, and `force-reduced`
 * disables it even when the OS asks for motion. Credit: three-state design
 * proposed by @Zn-Dk in #21/#22.
 */
function useMotionReduced(preference: StreamMotionPreference): boolean {
  const system = usePrefersReducedMotion()
  if (preference === 'force-smooth') return false
  if (preference === 'force-reduced') return true
  return system
}

interface AnimatedMarkdownTextProps extends MarkdownProps {
  streaming: boolean
  isHistorical?: boolean
  hasToolCallBelow?: boolean
  isTurnOpen?: boolean
  turnKey?: string
  keepStreamOnToolCall?: boolean
  logarithmicFade: boolean
  /** Whether the resolved reduced-motion gate keeps the reveal engine off. */
  motionReduced: boolean
  /** True on the last text block: that block owns conversation follow. */
  ownFollow: boolean
  followSpeedCpsRef?: { current: number } | undefined
  followRevealedCharsRef?: { current: number } | undefined
  followRevealScaleRef?: { current: number } | undefined
  onPredictiveChange?: ((predictive: boolean) => void) | undefined
  onTypingChange?: ((isTyping: boolean) => void) | undefined
  preset: StreamSmoothingPreset
  shouldHoldBack: () => boolean
  controlScroll?: boolean
}

/** Conservative fallback before the streaming Markdown tail has geometry. */
const PREDICTIVE_WRAP_FALLBACK_CHARS = 32
const STREAM_ANNOUNCEMENT_INTERVAL_MS = 800
const STREAM_ANNOUNCEMENT_MAX_CHARS = 320

interface PendingTextGeometry {
  root: HTMLElement
  visibleText: string
  fontSize: number
  wrapThresholdWidth: number | null
}

function approximateInlineWidth(text: string, emPx: number): number {
  let width = 0
  for (const char of text) {
    if (/\s/u.test(char)) width += emPx * 0.33
    else if (/^[\x00-\x7f]$/u.test(char)) width += emPx * 0.56
    else width += emPx
  }
  return width
}

function measurePendingTextGeometry(root: HTMLElement, visibleText: string): PendingTextGeometry {
  return { root, visibleText, fontSize: 14, wrapThresholdWidth: null }
}

/** Whether buffered source can reach a new visual line before it drains. */
function pendingTextCanGrow(
  root: HTMLElement | null,
  visibleText: string,
  pending: string,
  geometryRef: { current: PendingTextGeometry | null },
): boolean {
  if (pending === '') return false
  if (/[\r\n]/u.test(pending)) return true
  return [...pending].length >= PREDICTIVE_WRAP_FALLBACK_CHARS
}

function announcementChunkEnd(source: string, start: number): number {
  const hardEnd = Math.min(source.length, start + STREAM_ANNOUNCEMENT_MAX_CHARS)
  if (hardEnd === source.length) return hardEnd
  const softStart = start + Math.floor(STREAM_ANNOUNCEMENT_MAX_CHARS * 0.6)
  for (let index = hardEnd - 1; index >= softStart; index -= 1) {
    if (/[\s.,;:!?]/u.test(source[index] ?? '')) return index + 1
  }
  return hardEnd
}

/** Pace screen-reader updates independently from visual reveal frames. */
interface StreamAnnouncementState {
  text: string
  revision: number
  present: boolean
}

/** A commit-driven live region isolated from the visible Markdown subtree. */
const StreamAnnouncement = memo(function StreamAnnouncement({
  text,
  active,
}: {
  text: string
  active: boolean
}) {
  const [announcement, setAnnouncement] = useState<StreamAnnouncementState>({
    text: '',
    revision: 0,
    present: active,
  })
  const sourceRef = useRef(text)
  const activeRef = useRef(active)
  const announcedOffsetRef = useRef(active ? 0 : text.length)
  const drainSourceRef = useRef<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    const clearTimer = (): void => {
      if (timerRef.current === null) return
      clearTimeout(timerRef.current)
      timerRef.current = null
    }

    const publishNext = (source: string): boolean => {
      const start = Math.min(announcedOffsetRef.current, source.length)
      const end = announcementChunkEnd(source, start)
      if (end <= start) return false
      announcedOffsetRef.current = end
      setAnnouncement(previous => ({
        text: source.slice(start, end),
        revision: previous.revision + 1,
        present: true,
      }))
      return end < source.length
    }

    const hideAfterLinger = (): void => {
      timerRef.current = setTimeout(() => {
        timerRef.current = null
        if (activeRef.current) return
        drainSourceRef.current = null
        setAnnouncement(previous => ({ ...previous, present: false }))
      }, STREAM_ANNOUNCEMENT_INTERVAL_MS)
    }

    const drainNext = (): void => {
      const source = drainSourceRef.current
      if (source === null) return
      if (!publishNext(source)) {
        hideAfterLinger()
        return
      }
      timerRef.current = setTimeout(() => {
        timerRef.current = null
        if (activeRef.current) return
        drainNext()
      }, STREAM_ANNOUNCEMENT_INTERVAL_MS)
    }

    const scheduleLive = (): void => {
      if (timerRef.current !== null || announcedOffsetRef.current >= sourceRef.current.length) return
      timerRef.current = setTimeout(() => {
        timerRef.current = null
        if (!activeRef.current) return
        const source = sourceRef.current
        if (publishNext(source)) scheduleLive()
      }, STREAM_ANNOUNCEMENT_INTERVAL_MS)
    }

    const wasActive = activeRef.current
    const previousSource = sourceRef.current
    const continuingDrain = !active && !wasActive && drainSourceRef.current !== null
    if (
      !continuingDrain
      && (!text.startsWith(previousSource) || announcedOffsetRef.current > text.length)
    ) {
      announcedOffsetRef.current = 0
    }
    sourceRef.current = text
    activeRef.current = active

    if (active) {
      if (!wasActive) {
        clearTimer()
        drainSourceRef.current = null
        setAnnouncement(previous => ({
          text: '',
          revision: previous.revision + 1,
          present: true,
        }))
      }
      scheduleLive()
      return
    }

    if (!wasActive) {
      if (drainSourceRef.current === null) announcedOffsetRef.current = text.length
      return
    }

    clearTimer()
    drainSourceRef.current = text
    drainNext()
  }, [active, text])

  useEffect(() => () => {
    if (timerRef.current !== null) clearTimeout(timerRef.current)
    timerRef.current = null
  }, [])

  if (!active && !announcement.present) return null
  const activating = active && !activeRef.current
  return (
    <span
      className={css.visuallyHidden}
      aria-live="polite"
      aria-atomic="true"
    >
      <span key={announcement.revision}>{activating ? '' : announcement.text}</span>
    </span>
  )
})

/**
 * Smooth streaming text arm. While the reply runs, the accumulated source is
 * revealed through the smoother at a rate that tracks the model's arrival
 * and rendered by the Harness `MarkdownText`
 * streaming arm (incremental parse, frozen non-tail blocks), so there is no
 * raw-text tail and no text-to-markdown swap: the tree stays markdown
 * throughout. The last text block owns conversation-port follow so wraps
 * glide instead of snapping. Once the stream closes and the reveal queue
 * drains, the settled full parse (KaTeX math, fence highlighting, file
 * mentions) swaps in exactly once.
 */
function AnimatedMarkdownText({
  text,
  labels,
  fileMentions,
  streaming,
  isHistorical = false,
  hasToolCallBelow = false,
  isTurnOpen = false,
  turnKey,
  keepStreamOnToolCall = true,
  logarithmicFade,
  motionReduced,
  ownFollow,
  followSpeedCpsRef,
  followRevealedCharsRef,
  followRevealScaleRef,
  onPredictiveChange,
  onTypingChange,
  preset,
  shouldHoldBack,
  controlScroll = true,
}: AnimatedMarkdownTextProps) {
  const reduced = motionReduced
  // 核心守卫：准确记录该卡片是否曾真正处于 streaming 活跃流中
  const wasStreamingRef = useRef(streaming)
  if (streaming) wasStreamingRef.current = true

  // 历史卡片与未加载成功（中断/已结算）卡片判定：
  // 若挂载时非 streaming 且从未经历活跃流，则绝对属于已完成的历史卡片，杜绝二次从 0 字符打字
  const isHistoryOrSettled = !streaming && !wasStreamingRef.current
  const keepStreaming = !isHistoryOrSettled && keepStreamOnToolCall && isTurnOpen && wasStreamingRef.current
  const [typing, setTyping] = useState(() => streaming)
  const localSpeedCpsRef = useRef(35)
  const followRootRef = useRef<HTMLDivElement>(null)
  const predictionSourceRef = useRef<string | null>(null)
  const predictionStateRef = useRef(false)
  const predictionGeometryRef = useRef<PendingTextGeometry | null>(null)
  const speedCpsRef = followSpeedCpsRef ?? localSpeedCpsRef

  // 串行接力：只要前序思考块仍在活跃吐字，正文保持静止等待
  const reasoningActive = useSyncExternalStore(
    streamRelay.subscribe,
    () => streamRelay.isReasoningActive(turnKey),
    () => false,
  )
  const waitingForReasoning = isTurnOpen && reasoningActive
  const canType = typing && !waitingForReasoning && !isHistoryOrSettled

  const [shownLength, setShownLength] = useState(() => (streaming ? 0 : text.length))
  const effectiveStreaming = (streaming || (keepStreaming && shownLength < text.length)) && !waitingForReasoning && !isHistoryOrSettled
  const feedingText = waitingForReasoning ? '' : text

  const displayed = useSmoothStreamContent(feedingText, {
    enabled: canType && !reduced,
    inputComplete: !effectiveStreaming,
    preset,
    shouldHoldBack,
    speedCpsRef,
    revealedCharsRef: followRevealedCharsRef,
    revealScaleRef: followRevealScaleRef,
    onRevealCommit: () => { notifyFollowCommit(followRootRef.current) },
  })
  const shown = reduced || isHistoryOrSettled ? text : (waitingForReasoning ? '' : displayed)

  useEffect(() => {
    setShownLength(shown.length)
  }, [shown.length])

  const live = canType && !reduced
  const isRevealing = live && shown.length < text.length && !waitingForReasoning

  // 严格向 Relay 报告本正文块的吐字活跃状态（供后续工具调用排队使用）
  useEffect(() => {
    if (isHistoryOrSettled) {
      streamRelay.setTextActive(turnKey, false)
      onTypingChange?.(false)
      return
    }
    const isActivelyTyping = !waitingForReasoning && shownLength < text.length && text.length > 0 && !reduced
    const active = isActivelyTyping || (effectiveStreaming && !waitingForReasoning)
    streamRelay.setTextActive(turnKey, active)
    onTypingChange?.(active)
    return () => {
      streamRelay.setTextActive(turnKey, false)
      onTypingChange?.(false)
    }
  }, [isHistoryOrSettled, waitingForReasoning, shownLength, text.length, effectiveStreaming, reduced, turnKey, onTypingChange])

  useLogarithmicFade(followRootRef, logarithmicFade && !reduced, live, speedCpsRef)

  useEffect(() => {
    const root = followRootRef.current
    if (root === null || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      if (predictionGeometryRef.current?.root === root) predictionGeometryRef.current = null
    })
    observer.observe(root)
    return () => { observer.disconnect() }
  }, [])

  useLayoutEffect(() => {
    if (onPredictiveChange === undefined) return
    // 流式生成期间保持预测跑道稳定开启，避免因分块到达间隙而发生上下剧烈抽搐
    const isStreamActive = live && effectiveStreaming && text.length > 0
    const next = isStreamActive
    predictionSourceRef.current = text
    predictionStateRef.current = next
    onPredictiveChange(next)
  }, [live, onPredictiveChange, effectiveStreaming, text])

  // The stream closed: keep revealing the remaining queue, then swap to the
  // settled parse exactly once. The markdown tree stays mounted until then.
  useEffect(() => {
    if (typing && !effectiveStreaming && shown.length === text.length) setTyping(false)
  }, [shown, effectiveStreaming, text, typing])

  // A row can mount before the projection flips its assistant step to
  // `running`, which would freeze `typing` at false forever and leave the
  // reveal engine off for the whole reply. Re-arm on the rising edge so late
  // stream starts are still smoothed. (Adopted from #22 by @Zn-Dk.)
  useEffect(() => {
    if (isHistoryOrSettled) {
      setTyping(false)
      return
    }
    if (streaming || (keepStreaming && shown.length < text.length)) setTyping(true)
  }, [isHistoryOrSettled, streaming, keepStreaming, shown.length, text.length])

  return (
    <FollowHost
      active={live && ownFollow}
      speedCpsRef={speedCpsRef}
      revealedCharsRef={followRevealedCharsRef}
      revealScaleRef={followRevealScaleRef}
      predictive={effectiveStreaming}
      controlScroll={controlScroll}
      hostRef={followRootRef}
    >
      <MarkdownText
        text={live ? shown : (waitingForReasoning ? '' : text)}
        streaming={live}
        labels={labels}
        fileMentions={live || waitingForReasoning ? undefined : fileMentions}
      />
    </FollowHost>
  )
}

function imageLabels(t: AssistantProps['t']): MessageImageLabels {
  return {
    image: t('image.label'),
    open: t('image.openOriginal'),
    openNamed: label => t('image.openOriginalLabel', { label }),
    loading: t('image.loading'),
    loadFailed: t('image.loadFailed'),
    lightbox: {
      dialog: t('image.preview'),
      close: t('image.closePreview'),
    },
  }
}

/**
 * Apply searchable hidden state without unmounting a stable subtree — the
 * Host's completion fold hides the answer-inline reasoning this way, so the
 * takeover renderer must reproduce it to stay inside the contract: the row
 * disappears into the Host's process summary and comes back on find-in-page.
 * (Mirrors the Harness chat kit's `useSearchableHidden`.)
 */
function useSearchableHidden(
  hidden: boolean,
  reveal: () => void,
): RefObject<HTMLDivElement> {
  const ref = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const element = ref.current
    if (element === null) return
    if (hidden && element.contains(element.ownerDocument.activeElement)) {
      reveal()
      return
    }
    if (hidden) element.setAttribute('hidden', 'until-found')
    else element.removeAttribute('hidden')
  }, [hidden, reveal])
  useEffect(() => {
    const element = ref.current
    if (element === null) return
    element.addEventListener('beforematch', reveal)
    return () => { element.removeEventListener('beforematch', reveal) }
  }, [reveal])
  return ref
}

/**
 * One answer-inline reasoning block wrapped in the Host's fold contract. The
 * Host computes the fold decision (turn closed, compact transcript, this node
 * is the answer) and delivers it through the `turnProcess` owner prop; when
 * folded the block hides into the Host's "thought" summary row instead of
 * staying mounted above the answer.
 */
function FoldableReasoning({
  hidden,
  reveal,
  children,
}: {
  hidden: boolean
  reveal: () => void
  children: ReactNode
}) {
  const ref = useSearchableHidden(hidden, reveal)
  return (
    <div ref={ref} data-turn-process-inline={hidden || undefined}>
      {children}
    </div>
  )
}

function firstLine(text: string): string {
  const newline = text.indexOf('\n')
  return newline === -1 ? text : text.slice(0, newline)
}

function latestLine(text: string): string {
  const visible = text.trimEnd()
  const newline = visible.lastIndexOf('\n')
  return newline === -1 ? visible : visible.slice(newline + 1)
}

/**
 * Built-in Think disclosure with a smoothed `text` feed. Chevron and row
 * click stay on the disclosure chrome, which the plugin's AnimatedDisclosure
 * renders with a height-animated body (the harness primitive would mount and
 * unmount it, which cannot glide). The row opens only while this block is
 * the streaming tail and closes as soon as thinking ends — a later block,
 * or the assistant node settling — not when the rest of the reply is
 * still streaming.
 */
function AnimatedReasoning({
  text,
  running,
  preset,
  thinkAutoExpand,
  motionReduced,
  logarithmicFade,
  shouldHoldBack,
  followSpeedCpsRef,
  followRevealScaleRef,
  t,
  turnKey,
  onTypingChange,
}: {
  text: string
  running: boolean
  preset: StreamSmoothingPreset
  thinkAutoExpand: boolean
  motionReduced: boolean
  logarithmicFade: boolean
  shouldHoldBack: () => boolean
  followSpeedCpsRef?: { current: number } | undefined
  followRevealScaleRef?: { current: number } | undefined
  t: AssistantProps['t']
  turnKey?: string
  onTypingChange?: ((isTyping: boolean) => void) | undefined
}) {
  const reduced = motionReduced
  const wasRunningRef = useRef(running)
  if (running) wasRunningRef.current = true

  const [shownLength, setShownLength] = useState(() => (running ? 0 : text.length))
  // 只有真正曾经处于 running 状态的思考过程在转入静止后才需要 draining 泄压；初次挂载非 running 的卡片属于历史卡片，绝不处于 draining 阶段
  const stillDraining = wasRunningRef.current && shownLength < text.length
  const activeRunning = running || (stillDraining && text.length > 0)
  const [expanded, setExpanded] = useState(() => activeRunning && thinkAutoExpand)
  const [userToggled, setUserToggled] = useState(false)
  const [autoClosed, setAutoClosed] = useState(false)
  const summaryRef = useRef<HTMLSpanElement>(null)
  const fadeRootRef = useRef<HTMLDivElement>(null)
  const localFadeSpeedRef = useRef(35)
  const fadeSpeedRef = followSpeedCpsRef ?? localFadeSpeedRef
  const commitAnchorRef = useRef<HTMLDivElement>(null)
  const displayed = useSmoothStreamContent(text, {
    enabled: activeRunning && !reduced,
    inputComplete: !running && stillDraining,
    preset,
    shouldHoldBack,
    speedCpsRef: fadeSpeedRef,
    revealScaleRef: followRevealScaleRef,
    onRevealCommit: () => { notifyFollowCommit(commitAnchorRef.current) },
  })
  const shown = activeRunning && !reduced ? displayed : text
  const summary = activeRunning ? latestLine(shown) : firstLine(text)
  useLogarithmicFade(fadeRootRef, logarithmicFade && !reduced && expanded, activeRunning, fadeSpeedRef)

  useEffect(() => {
    setShownLength(shown.length)
  }, [shown.length])

  // 严格向 Relay 报告本思考块的活跃状态（正文与工具依赖此状态排队）
  useEffect(() => {
    const isActivelyReasoning = activeRunning && !reduced
    streamRelay.setReasoningActive(turnKey, isActivelyReasoning)
    onTypingChange?.(isActivelyReasoning)
    return () => {
      streamRelay.setReasoningActive(turnKey, false)
      onTypingChange?.(false)
    }
  }, [activeRunning, reduced, turnKey, onTypingChange])

  useLayoutEffect(() => {
    if (thinkAutoExpand && !userToggled) {
      if (activeRunning) {
        setExpanded(true)
        setAutoClosed(false)
      } else {
        setExpanded(false)
        setAutoClosed(true)
      }
    }
    notifyFollowCommit(commitAnchorRef.current)
  }, [activeRunning, thinkAutoExpand, userToggled])

  useEffect(() => {
    const element = summaryRef.current
    if (element === null) return
    element.scrollLeft = activeRunning ? element.scrollWidth - element.clientWidth : 0
  }, [activeRunning, summary])

  // Preserve FollowHost's layout wrapper without mounting a second scroll owner.
  return (
    <div className={css.follow} ref={commitAnchorRef}>
      <div className={css.think} data-variant="think" data-state={activeRunning ? 'running' : 'ok'}>
        {activeRunning && <span className={css.visuallyHidden}>{t('row.running')}</span>}
        <AnimatedDisclosure
          rowClassName={css.thinkRow}
          leadingClassName={css.thinkLeading}
          titleClassName={css.thinkTitle}
          chevronClassName={css.thinkChevron}
          icon={<IconThinkOutlineRegular size={14} />}
          title="Think"
          open={expanded}
          onToggle={() => {
            setUserToggled(true)
            setAutoClosed(false)
            setExpanded(value => !value)
          }}
          bodyTransition={true}
          collapsedContent={(
            <>
              <span className={css.thinkSeparator} aria-hidden />
              <span ref={summaryRef} className={css.thinkSummary} data-follow-end={activeRunning || undefined}>{summary}</span>
            </>
          )}
        >
          <div ref={fadeRootRef} className={css.thinkBody}>{shown}</div>
        </AnimatedDisclosure>
      </div>
    </div>
  )
}

/**
 * Assistant node renderer for the typewriter overlay. Text observed while
 * streaming is revealed by the smoother through the Harness Markdown
 * renderer at a rate that tracks arrival. Reasoning blocks keep the
 * built-in Think disclosure and only receive a smoothed text feed; the
 * outer node owns conversation-port follow while streaming; the final text
 * block keeps ownership while its settled reveal queue drains. The FPS guard
 * holds offscreen reveals when the frame rate is degraded. Settled text
 * renders with the full Markdown pipeline.
 */
export const TypewriterAssistantNodeView = memo(function TypewriterAssistantNodeView({
  mode: _mode = DEFAULT_STREAM_CONFIG.mode,
  preset = DEFAULT_STREAM_CONFIG.preset,
  revealCharsPerSec: _revealCharsPerSec = DEFAULT_STREAM_CONFIG.revealCharsPerSec,
  scrollSpeedPxPerSec: _scrollSpeedPxPerSec = DEFAULT_STREAM_CONFIG.scrollSpeedPxPerSec,
  maxScrollSpeedPxPerSec: _maxScrollSpeedPxPerSec = DEFAULT_STREAM_CONFIG.maxScrollSpeedPxPerSec,
  thinkAutoExpand = DEFAULT_STREAM_SETTINGS.thinkAutoExpand,
  logarithmicFade = DEFAULT_STREAM_SETTINGS.logarithmicFade,
  keepStreamOnToolCall = DEFAULT_STREAM_SETTINGS.keepStreamOnToolCall,
  controlScroll = true,
  motionPreference = DEFAULT_STREAM_SETTINGS.motionPreference,
  node,
  groupPart,
  useTurnData,
  openFile,
  loadImage,
  fileMentions,
  turnProcess,
  t,
}: AssistantProps & {
  groupPart?: 'reasoning' | 'response'
  mode?: StreamMode
  preset?: StreamSmoothingPreset
  revealCharsPerSec?: number
  scrollSpeedPxPerSec?: number
  maxScrollSpeedPxPerSec?: number
  thinkAutoExpand?: boolean
  logarithmicFade?: boolean
  keepStreamOnToolCall?: boolean
  controlScroll?: boolean
  motionPreference?: StreamMotionPreference
}) {
  const data = node.data
  // 核心铁律：初次挂载时若非 running 状态，说明属于历史卡片或前序已完成步骤，永不二次流式打字！
  const wasMountRunningRef = useRef(data.status === 'running')
  const isHistorical = !wasMountRunningRef.current && data.status !== 'running'
  const streaming = !isHistorical && data.status === 'running'
  const reduced = useMotionReduced(motionPreference)
  // The Host's completion-fold decision for THIS node's inline reasoning:
  // only the answer step folds, only in compact-transcript mode, and only
  // once the turn has closed (foldable implies it). Mirrors the built-in
  // assistant renderer so the takeover keeps the official behavior.
  const reasoningHidden = turnProcess !== undefined
    && turnProcess.foldable
    && turnProcess.spec.answerStep === data.step
    && turnProcess.spec.inlineReasoning
    && !turnProcess.open
  const revealProcess = useCallback(() => { turnProcess?.setOpen(true) }, [turnProcess])
  const { ref: guardRef, shouldHoldBack } = useFpsGuard(streaming)
  const rootSpeedRef = useRef(35)
  const rootRevealedCharsRef = useRef(0)
  const rootRevealScaleRef = useRef(1)

  // 检查在指定索引之后是否已经产生实质性的正文回复或工具调用
  const hasSubsequentContent = (fromIndex: number): boolean => {
    for (let i = fromIndex + 1; i < data.blocks.length; i++) {
      const b = data.blocks[i]
      if (b === undefined) continue
      if (b.kind === 'tool-call') return true
      if (b.kind === 'text' && b.text.trim().length > 0) return true
    }
    return false
  }

  // 只要后续未产生实质性回复正文或工具，速度控制器就安全归思考块所有
  const hasAnyReplyOrTool = data.blocks.some(b => b.kind === 'tool-call' || (b.kind === 'text' && b.text.trim().length > 0))
  const reasoningOwnsSpeed = streaming && !hasAnyReplyOrTool
  const reasoningTailIndex = reasoningOwnsSpeed ? data.blocks.findIndex(b => b.kind === 'reasoning') : -1
  const rootPredictiveRef = useRef(false)
  const previousReasoningTailRef = useRef(-1)
  if (reasoningTailIndex !== previousReasoningTailRef.current) {
    // Think growth is already paced by its own text reveal. Opening additional
    // speculative runway here exposes that runway as an empty gap above the
    // fixed turn status, especially when reasoning arrives in fast bursts.
    // The follower still smooths real height growth within the measured gap
    // and catches up any unsafe remainder in the same frame.
    rootPredictiveRef.current = false
    if (!reasoningOwnsSpeed) rootSpeedRef.current = 35
    previousReasoningTailRef.current = reasoningTailIndex
  }
  const updateTextPrediction = useMemo(
    () => (predictive: boolean): void => { rootPredictiveRef.current = predictive },
    [],
  )
  const turn = node.location.kind === 'turn' || node.location.kind === 'step'
    ? node.location.turn
    : undefined
  const step = node.location.kind === 'step' ? node.location.step : undefined
  const isStepOpen = streaming && turn !== undefined && turn.status === 'open' && (step === undefined || step.status === 'open')
  const turnKey = turn !== undefined && typeof turn === 'object' && 'turn' in turn && turn.turn !== undefined
    ? String(turn.turn)
    : 'active'

  // 未加载成功（interrupted）或已结算时，无条件清理该轮次接力棒阻塞，杜绝后续卡片死锁
  useEffect(() => {
    if (data.status === 'interrupted' || data.status === 'settled') {
      streamRelay.resetTurn(turnKey)
    }
  }, [data.status, turnKey])
  const tail = useTurnData('turn-tail')
  const owner = useMemo<TurnTailOwnerProps | undefined>(() => {
    if (turn?.status !== 'closed' || data.finalNode === undefined) return undefined
    if (tail?.closing?.finalNode.seq !== data.finalNode.seq) return undefined
    return { turn, seq: data.finalNode.seq, openFile }
  }, [data.finalNode, openFile, tail, turn])
  const mentions = useMemo(
    () => owner === undefined ? undefined : fileMentions(owner),
    [fileMentions, owner],
  )
  const markdownLabels = useMemo(() => ({
    code: { copyLabel: t('copy'), copiedLabel: t('copied') },
    footnotes: t('markdown.footnotes'),
  }), [t])
  const imageLoader: ImageLoader = loadImage ?? (async () => {
    throw new Error(t('image.serviceUnavailable'))
  })
  const hasVisible = streaming
    || data.status === 'interrupted'
    || data.blocks.some(block => {
      if (block.kind === 'tool-call') return false
      if (groupPart === 'reasoning' && block.kind !== 'reasoning') return false
      if (groupPart === 'response' && block.kind === 'reasoning') return false
      return true
    })
  if (!hasVisible) return null
  const announcementText = data.blocks
    .filter(block => block.kind === 'text' && (groupPart === undefined || groupPart === 'response'))
    .map(block => block.text)
    .join('\n')

  const [reasoningActive, setReasoningActive] = useState(false)
  const [childBlockTyping, setChildBlockTyping] = useState(false)
  const isTyping = streaming || reasoningActive || childBlockTyping

  useLayoutEffect(() => {
    const el = guardRef.current
    if (!el) return
    const card = el.closest('[data-chat-flow-key]')
    if (card instanceof HTMLElement) {
      if (isHistorical || (!streaming && !isTyping)) {
        card.setAttribute('data-smooth-stream-settled', 'true')
      } else {
        card.removeAttribute('data-smooth-stream-settled')
      }
    }
  }, [isHistorical, streaming, isTyping])

  const rendered: ReactNode[] = []
  const last = data.blocks.length - 1
  let lastFollow = -1
  for (let index = 0; index < data.blocks.length; index += 1) {
    const kind = data.blocks[index]?.kind
    if (groupPart === 'reasoning' && kind !== 'reasoning') continue
    if (groupPart === 'response' && kind === 'reasoning') continue
    if (kind === 'text' || kind === 'reasoning') lastFollow = index
  }
  for (let index = 0; index < data.blocks.length; index += 1) {
    const block = data.blocks[index]
    if (block === undefined) continue
    if (groupPart === 'reasoning' && block.kind !== 'reasoning') continue
    if (groupPart === 'response' && block.kind === 'reasoning') continue
    switch (block.kind) {
      case 'text': {
        const hasPrecedingReasoning = data.blocks.slice(0, index).some(b => b.kind === 'reasoning')
        const shouldWaitForReasoning = hasPrecedingReasoning && reasoningActive
        const textContent = shouldWaitForReasoning ? '' : block.text
        const hasToolCallBelow = data.blocks.slice(index + 1).some(b => b.kind === 'tool-call')
          || data.blocks.some(b => b.kind === 'tool-call')
        const turnKey = turn !== undefined && typeof turn === 'object' && 'turn' in turn && turn.turn !== undefined
          ? String(turn.turn)
          : 'active'
        rendered.push(
          <AnimatedMarkdownText
            key={index}
            text={block.text}
            labels={markdownLabels}
            fileMentions={mentions}
            streaming={streaming}
            isHistorical={isHistorical}
            hasToolCallBelow={hasToolCallBelow}
            isTurnOpen={isStepOpen}
            turnKey={turnKey}
            keepStreamOnToolCall={keepStreamOnToolCall}
            logarithmicFade={logarithmicFade && data.status !== 'interrupted'}
            motionReduced={reduced}
            ownFollow={!streaming && index === lastFollow}
            followSpeedCpsRef={index === lastFollow ? rootSpeedRef : undefined}
            followRevealedCharsRef={index === lastFollow ? rootRevealedCharsRef : undefined}
            followRevealScaleRef={index === lastFollow ? rootRevealScaleRef : undefined}
            onPredictiveChange={index === lastFollow ? updateTextPrediction : undefined}
            onTypingChange={setChildBlockTyping}
            preset={preset}
            shouldHoldBack={shouldHoldBack}
            controlScroll={controlScroll}
          />,
        )
        break
      }
      case 'reasoning': {
        const turnKey = turn !== undefined && typeof turn === 'object' && 'turn' in turn && turn.turn !== undefined
          ? String(turn.turn)
          : 'active'
        const isThinkingActive = streaming && !hasSubsequentContent(index)
        rendered.push(
          <FoldableReasoning key={index} hidden={reasoningHidden} reveal={revealProcess}>
            <AnimatedReasoning
              text={block.text}
              running={isThinkingActive && !isHistorical}
              preset={preset}
              thinkAutoExpand={thinkAutoExpand}
              logarithmicFade={logarithmicFade && data.status !== 'interrupted'}
              motionReduced={reduced}
              shouldHoldBack={shouldHoldBack}
              followSpeedCpsRef={reasoningOwnsSpeed && isThinkingActive ? rootSpeedRef : undefined}
              followRevealScaleRef={reasoningOwnsSpeed && isThinkingActive ? rootRevealScaleRef : undefined}
              t={t}
              turnKey={turnKey}
              onTypingChange={setReasoningActive}
            />
          </FoldableReasoning>,
        )
        break
      }
      case 'image': {
        const start = index
        const group = [block]
        while (index + 1 < data.blocks.length) {
          const next = data.blocks[index + 1]
          if (next === undefined || next.kind !== 'image') break
          group.push(next)
          index += 1
        }
        rendered.push(
          <ImageGallery key={start} images={group} load={imageLoader} align="start" labels={imageLabels(t)} />,
        )
        break
      }
      case 'tool-call':
        break
      case 'other':
        rendered.push(
          <JsonBlock
            key={index}
            label={t('message.unknownBlock')}
            payload={block.block}
            truncatedLabel={total => t('json.truncated', { total })}
          />,
        )
        break
    }
  }

  return (
    <div
      ref={guardRef}
      className={css.root}
      data-streaming={streaming || undefined}
      data-smooth-stream-typing={isTyping ? 'true' : undefined}
    >
      <StreamAnnouncement text={announcementText} active={streaming && !reduced} />
      <FollowHost
        active={streaming && !reduced}
        speedCpsRef={rootSpeedRef}
        revealedCharsRef={rootRevealedCharsRef}
        revealScaleRef={rootRevealScaleRef}
        predictiveRef={rootPredictiveRef}
        controlScroll={controlScroll}
      >
        <div className={css.body}>
          {rendered}
          {data.status === 'interrupted' && (groupPart === undefined || groupPart === 'response' || !data.blocks.some(b => b.kind !== 'reasoning' && b.kind !== 'tool-call')) && <span className={css.stopped}>{t('message.stopped')}</span>}
        </div>
      </FollowHost>
    </div>
  )
})
