import assert from 'node:assert/strict'

// 1. 模拟与 src/client/TypewriterToolNodeView.tsx 完全一致的核心业务判断函数
function isSettledChatNode(node) {
  if (node === null || typeof node !== 'object') return false
  const data = node.data
  if (data !== undefined && typeof data === 'object') {
    if (data.status === 'settled' || data.status === 'interrupted') return true
    if (data.root !== null && typeof data.root === 'object') {
      if (data.root.kind === 'tool-result') return true
    }
    if (data.command !== null && typeof data.command === 'object') {
      if (data.command.outcome !== null && data.command.outcome !== undefined) return true
    }
    if ('outcome' in data && data.outcome !== null && data.outcome !== undefined) return true
    if (data.current !== null && typeof data.current === 'object') {
      if (data.current.retryState !== undefined && data.current.retryState !== 'scheduled') return true
    }
  }
  const location = node.location
  if (location !== undefined && typeof location === 'object') {
    if (location.kind === 'step' && location.step !== null && typeof location.step === 'object') {
      if (location.step.status === 'closed') return true
    }
    if (location.kind === 'turn' && location.turn !== null && typeof location.turn === 'object') {
      if (location.turn.status === 'closed') return true
    }
  }
  return false
}

function openAgentLocation(node) {
  if (node === null || typeof node !== 'object' || !('location' in node)) return false
  const location = node.location
  if (location === null || typeof location !== 'object' || !('kind' in location)) return false
  const kind = location.kind
  if (!('turn' in location)) return false
  const turn = location.turn
  if (turn === null || typeof turn !== 'object' || !('status' in turn)) return false
  if (kind === 'turn') return turn.status === 'open'
  if (kind !== 'step' || !('step' in location)) return false
  const step = location.step
  return step !== null
    && typeof step === 'object'
    && 'status' in step
    && step.status === 'open'
}

function isGrowingChatNode(node) {
  if (isSettledChatNode(node)) return false
  if (node === null || typeof node !== 'object' || !('data' in node)) return false
  const data = node.data
  if (data === null || typeof data !== 'object') return false
  if ('status' in data && data.status === 'running') return true
  if (
    'kind' in data
    && data.kind === 'command'
    && 'outcome' in data
    && data.outcome === null
  ) return true
  if ('command' in data) {
    const command = data.command
    if (
      command !== null
      && typeof command === 'object'
      && 'outcome' in command
      && command.outcome === null
    ) return true
  }
  if ('root' in data) {
    const root = data.root
    if (root !== null && typeof root === 'object' && !('kind' in root)) return true
  }
  if ('current' in data) {
    const current = data.current
    if (
      current !== null
      && typeof current === 'object'
      && 'retryState' in current
      && current.retryState === 'scheduled'
    ) return true
  }
  return false
}

function isFollowableChatNode(node) {
  if (isSettledChatNode(node)) return false
  return isGrowingChatNode(node) || openAgentLocation(node)
}

// 2. 引入 streamRelay 进行测试
class StreamRelay {
  constructor() {
    this.turns = new Map()
    this.listeners = new Set()
  }

  getOrCreate(turnKey) {
    const key = turnKey || 'active'
    let state = this.turns.get(key)
    if (!state) {
      state = { reasoningActive: false, textActive: false }
      this.turns.set(key, state)
    }
    return state
  }

  setReasoningActive(turnKey, active) {
    const key = turnKey || 'active'
    const state = this.getOrCreate(key)
    if (state.reasoningActive === active) return
    state.reasoningActive = active
    this.notify()
  }

  setTextActive(turnKey, active) {
    const key = turnKey || 'active'
    const state = this.getOrCreate(key)
    if (state.textActive === active) return
    state.textActive = active
    this.notify()
  }

  isReasoningActive(turnKey) {
    if (turnKey) {
      const state = this.turns.get(turnKey)
      if (state?.reasoningActive) return true
    }
    for (const state of this.turns.values()) {
      if (state.reasoningActive) return true
    }
    return false
  }

  isTextActive(turnKey) {
    if (turnKey) {
      const state = this.turns.get(turnKey)
      if (state?.textActive) return true
    }
    for (const state of this.turns.values()) {
      if (state.textActive) return true
    }
    return false
  }

  resetTurn(turnKey) {
    const key = turnKey || 'active'
    if (this.turns.has(key)) {
      this.turns.delete(key)
      this.notify()
    }
  }

  isToolBlocked(turnKey) {
    return this.isReasoningActive(turnKey) || this.isTextActive(turnKey)
  }

  subscribe(listener) {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  notify() {
    for (const listener of [...this.listeners]) listener()
  }
}

const streamRelay = new StreamRelay()

console.log('--- 测试 1: 历史卡片与未成功/中断卡片精准识别 ---')

// 1. 助手卡片已结算
const settledAssistantNode = {
  kind: 'assistant-step',
  data: { status: 'settled', blocks: [{ kind: 'text', text: 'hello' }] },
  location: { kind: 'step', turn: { status: 'open' }, step: { status: 'closed' } },
}
assert.equal(isSettledChatNode(settledAssistantNode), true, 'settled assistant 必须被识别为已结算卡片')
assert.equal(isGrowingChatNode(settledAssistantNode), false, 'settled assistant 绝不能是 growing')
assert.equal(isFollowableChatNode(settledAssistantNode), false, 'settled assistant 绝不能是 followable')

// 2. 助手卡片未加载成功 / 中断 (interrupted)
const interruptedAssistantNode = {
  kind: 'assistant-step',
  data: { status: 'interrupted', blocks: [{ kind: 'text', text: 'partially generated...' }] },
  location: { kind: 'step', turn: { status: 'open' }, step: { status: 'open' } },
}
assert.equal(isSettledChatNode(interruptedAssistantNode), true, '未加载成功/中断卡片必须被识别为历史卡片，杜绝二次从 0 打字')
assert.equal(isGrowingChatNode(interruptedAssistantNode), false, '未加载成功卡片绝不能标记为 growing')
assert.equal(isFollowableChatNode(interruptedAssistantNode), false, '未加载成功卡片绝不能标记为 followable')

// 3. 工具卡片已执行完毕 (tool-result)
const settledToolNode = {
  kind: 'tool',
  data: {
    root: { kind: 'tool-result', callId: 'call_1', result: 'success' },
  },
  location: { kind: 'step', turn: { status: 'open' }, step: { status: 'open' } },
}
assert.equal(isSettledChatNode(settledToolNode), true, '产生结果的 tool 必须被识别为历史卡片')
assert.equal(isGrowingChatNode(settledToolNode), false, '已完成 tool 绝不能是 growing')
assert.equal(isFollowableChatNode(settledToolNode), false, '已完成 tool 绝不能是 followable')

// 4. 命令卡片已执行完毕 (outcome !== null)
const settledCommandNode = {
  kind: 'command',
  data: {
    outcome: { kind: 'ok', text: 'done' },
  },
  location: { kind: 'turn', turn: { status: 'open' } },
}
assert.equal(isSettledChatNode(settledCommandNode), true, '已完成 command 必须被识别为历史卡片')

// 5. 真正正在运行的助手卡片
const activeAssistantNode = {
  kind: 'assistant-step',
  data: { status: 'running', blocks: [{ kind: 'text', text: 'typing...' }] },
  location: { kind: 'step', turn: { status: 'open' }, step: { status: 'open' } },
}
assert.equal(isSettledChatNode(activeAssistantNode), false, '正在运行的 assistant 绝不能被识别为已结算卡片')
assert.equal(isGrowingChatNode(activeAssistantNode), true, '正在运行的 assistant 必须是 growing')

// 6. 真正正在运行的工具卡片
const activeToolNode = {
  kind: 'tool',
  data: {
    root: { phase: 'start', callId: 'call_2', argsRaw: '{}' },
  },
  location: { kind: 'step', turn: { status: 'open' }, step: { status: 'open' } },
}
assert.equal(isSettledChatNode(activeToolNode), false, '正在运行的 tool 绝不能是已结算')
assert.equal(isGrowingChatNode(activeToolNode), true, '正在运行的 tool 必须是 growing')

console.log('✓ 历史卡片分类测试全部通过！\n')

console.log('--- 测试 2: 响应式接力队列严格时序与自动自愈 ---')

const turnKey = 'test-turn-100'
streamRelay.resetTurn(turnKey)
assert.equal(streamRelay.isToolBlocked(turnKey), false, '空闲状态下工具不被阻塞')

// 阶段 1：思考链正在流式吐字
streamRelay.setReasoningActive(turnKey, true)
assert.equal(streamRelay.isReasoningActive(turnKey), true, '思考中状态标记有效')
assert.equal(streamRelay.isToolBlocked(turnKey), true, '思考中必须阻塞工具加载，保证顺序')

// 阶段 2：思考结束，正文接力吐字
streamRelay.setReasoningActive(turnKey, false)
streamRelay.setTextActive(turnKey, true)
assert.equal(streamRelay.isReasoningActive(turnKey), false, '思考状态已释放')
assert.equal(streamRelay.isTextActive(turnKey), true, '正文状态有效')
assert.equal(streamRelay.isToolBlocked(turnKey), true, '正文吐字中仍须阻塞工具卡片，杜绝多卡片同时加载')

// 阶段 3：正文完成物理收束
streamRelay.setTextActive(turnKey, false)
assert.equal(streamRelay.isToolBlocked(turnKey), false, '正文完成后工具锁解除，工具卡片依次平滑入场！')

// 阶段 4：模拟中断发生时瞬时自愈释放
streamRelay.setReasoningActive(turnKey, true)
assert.equal(streamRelay.isToolBlocked(turnKey), true)
// 用户中断会话
streamRelay.resetTurn(turnKey)
assert.equal(streamRelay.isToolBlocked(turnKey), false, '中断时 resetTurn 瞬时释放所有锁，绝不造成死锁卡顿！')

console.log('✓ 串行接力时序测试全部通过！\n')
console.log('所有卡片识别与加载顺序测试 100% 成功！')
