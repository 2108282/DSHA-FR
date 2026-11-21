import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'

const bundle = readFileSync(new URL('../lib/client.js', import.meta.url), 'utf8')

function mount({ initialState, hasConnection = true } = {}) {
  let state = initialState
  let reconnects = 0
  const window = new EventTarget()
  const connection = {
    state: { getSnapshot: () => state },
    reconnect() {
      reconnects++
      state = 'connecting' // Mirrors the synchronous transition in ConnectionController.
    },
  }
  const cleanups = []
  const ctx = {
    effect(install) { cleanups.push(install()) },
    inject(keys, install) {
      assert.deepEqual(Array.from(keys), ['connection'])
      if (hasConnection) install(this)
    },
    get(key) {
      assert.equal(key, 'connection')
      return connection
    },
  }
  window.__ModuleLoader__ = {
    load({ factory }) {
      factory(() => { throw new Error('unexpected external runtime import') }).apply(ctx)
    },
  }
  runInNewContext(bundle, { window })
  return {
    window,
    setState: next => { state = next },
    dispatch: type => window.dispatchEvent(new Event(type)),
    get reconnects() { return reconnects },
    dispose() { for (const cleanup of cleanups.reverse()) cleanup?.() },
  }
}

test('foreground does not interrupt healthy, connecting or not-yet-started connections', () => {
  for (const initialState of ['connected', 'connecting', undefined]) {
    const client = mount({ initialState })
    client.dispatch('dsha:foreground')
    assert.equal(client.reconnects, 0)
    client.dispose()
  }
})

test('a disconnected connection requests one reconnect without forging network events', () => {
  const client = mount({ initialState: 'disconnected' })
  const syntheticEvents = []
  for (const type of ['offline', 'online', 'focus', 'visibilitychange']) {
    client.window.addEventListener(type, () => { syntheticEvents.push(type) })
  }
  client.dispatch('dsha:background')
  assert.equal(client.reconnects, 0)
  client.dispatch('dsha:foreground')
  client.dispatch('dsha:foreground')
  assert.equal(client.reconnects, 1)
  assert.deepEqual(syntheticEvents, [])
  client.setState('disconnected')
  client.dispose()
  client.dispatch('dsha:foreground')
  assert.equal(client.reconnects, 1)
})

test('a client without the connection service still installs the back dispatcher', () => {
  const client = mount({ hasConnection: false })
  assert.equal(typeof client.window.triggerMobileBack, 'function')
  client.dispatch('dsha:foreground')
  assert.equal(client.reconnects, 0)
  client.dispose()
  assert.equal(client.window.triggerMobileBack, undefined)
})
