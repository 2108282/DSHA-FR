type Listener = () => void

interface TurnRelayState {
  reasoningActive: boolean
  textActive: boolean
}

/**
 * Universal Baton Relay Queue for DSH Smooth Stream.
 * Guarantees strict sequential execution across all Agent loop iterations:
 * Phase 1: Reasoning stream -> Phase 2: Text response stream -> Phase 3: Tool execution.
 * Only ONE active animator exists at any given instant.
 *
 * Designed for mobile 60/120fps with zero DOM polling, zero MutationObserver overhead.
 */
class StreamRelay {
  private readonly turns = new Map<string, TurnRelayState>()
  private readonly listeners = new Set<Listener>()

  private getOrCreate(turnKey: string): TurnRelayState {
    const key = turnKey || 'active'
    let state = this.turns.get(key)
    if (!state) {
      state = { reasoningActive: false, textActive: false }
      this.turns.set(key, state)
    }
    return state
  }

  setReasoningActive(turnKey: string | undefined, active: boolean): void {
    const key = turnKey || 'active'
    const state = this.getOrCreate(key)
    if (state.reasoningActive === active) return
    state.reasoningActive = active
    this.notify()
  }

  setTextActive(turnKey: string | undefined, active: boolean): void {
    const key = turnKey || 'active'
    const state = this.getOrCreate(key)
    if (state.textActive === active) return
    state.textActive = active
    this.notify()
  }

  isReasoningActive(turnKey?: string | null): boolean {
    if (turnKey) {
      const state = this.turns.get(turnKey)
      if (state?.reasoningActive) return true
    }
    for (const state of this.turns.values()) {
      if (state.reasoningActive) return true
    }
    return false
  }

  isTextActive(turnKey?: string | null): boolean {
    if (turnKey) {
      const state = this.turns.get(turnKey)
      if (state?.textActive) return true
    }
    for (const state of this.turns.values()) {
      if (state.textActive) return true
    }
    return false
  }

  /**
   * Reset a turn's state immediately (e.g. upon turn settle or interruption).
   */
  resetTurn(turnKey?: string | null): void {
    const key = turnKey || 'active'
    if (this.turns.has(key)) {
      this.turns.delete(key)
      this.notify()
    }
  }

  clearAll(): void {
    if (this.turns.size > 0) {
      this.turns.clear()
      this.notify()
    }
  }

  /**
   * Whether a tool call in this step/turn must wait.
   * Blocked as long as any preceding reasoning or text is actively revealing.
   */
  isToolBlocked(turnKey?: string | null): boolean {
    return this.isReasoningActive(turnKey) || this.isTextActive(turnKey)
  }

  subscribe = (listener: Listener): () => void => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  private notify(): void {
    for (const listener of [...this.listeners]) {
      listener()
    }
  }
}

export const streamRelay = new StreamRelay()
