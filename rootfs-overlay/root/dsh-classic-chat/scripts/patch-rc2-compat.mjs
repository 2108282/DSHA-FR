#!/usr/bin/env node
/**
 * Splice the rc.2 compatibility shim for `useAnchoredPosition` into
 * lib/client.js.
 *
 * rc.2 removed that hook from `@deepseek-ai/dsh-client-ui-primitives`. The
 * vendored 0.2.1 conversation engine calls it from the composer's ContextMeter
 * and from the chat statistics dialog; the missing export throws inside the
 * `conversation.composer.bar` slot entry, so the whole composer (and its input
 * box) disappears. The shim prefers a host export when one exists and falls
 * back to the rc.1 implementation.
 *
 * lib/client.js is a vendored artifact: `pnpm build` would regenerate it from
 * src/client, which does not contain the vendored conversation engine. Apply
 * this patch to the committed artifact instead, and keep it idempotent.
 *
 * Usage: node scripts/patch-rc2-compat.mjs [--check]
 */
import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const TARGET = resolve(ROOT, 'lib/client.js')
const SNIPPET = resolve(ROOT, 'scripts/patches/rc2-anchored-position-compat.js')
const MARKER = '_dshCreateAnchoredPositionHook'
const BINDING = 'const _dshUseAnchoredPosition = _dshCreateAnchoredPositionHook(react, _deepseek_ai_dsh_client_ui_primitives);'
const MODULES = [
  '@deepseek-ai/dsh-client-ui-conversation',
  '@deepseek-ai/dsh-client-ui-chat',
]
const REQUIRE_LINE = '\t\tlet react = require("react");'
const CALL = '(0, _deepseek_ai_dsh_client_ui_primitives.useAnchoredPosition)('
const REPLACEMENT = '_dshUseAnchoredPosition('

/**
 * Locate the factory body of one registered client module.
 * @param source - lib/client.js contents.
 * @param id - the registered module id.
 * @returns the start offset of the factory body, or -1.
 */
function factoryStart(source, id) {
  const head = `\tid: "${id}",\n\tfactory: (require) => {`
  const index = source.indexOf(head)
  return index === -1 ? -1 : index + head.length
}

/**
 * Patch one module's binding and call sites.
 * @param source - lib/client.js contents.
 * @param id - the registered module id.
 * @returns the patched contents.
 */
function patchModule(source, id) {
  const start = factoryStart(source, id)
  if (start === -1) throw new Error(`patch-rc2-compat: module ${id} not found in lib/client.js`)
  const end = source.indexOf('window.__ModuleLoader__.load({', start)
  const stop = end === -1 ? source.length : end
  const body = source.slice(start, stop)

  let next = body
  if (!next.includes(BINDING)) {
    // Insert after the module's whole require header: the two dependency
    // declarations appear in different orders in the vendored modules, and
    // binding before either one is a temporal-dead-zone error at import time.
    const header = /\n\t\tlet [A-Za-z_$][\w$]* = require\("[^"]+"\);\n/g
    header.lastIndex = 0
    let at = -1
    for (const match of body.matchAll(header)) {
      if (match.index > 2000) break
      at = match.index + match[0].length
    }
    if (at === -1) throw new Error(`patch-rc2-compat: require header not found in ${id}`)
    next = next.slice(0, at) + '\t\t' + BINDING + '\n' + next.slice(at)
  }
  const calls = next.split(CALL).length - 1
  next = next.split(CALL).join(REPLACEMENT)
  return { source: source.slice(0, start) + next + source.slice(stop), calls }
}

const check = process.argv.includes('--check')
const source = await readFile(TARGET, 'utf8')
if (source.includes(MARKER)) {
  console.log('patch-rc2-compat: already applied')
  process.exit(0)
}

if (check) {
  console.error('patch-rc2-compat: missing compatibility shim (run node scripts/patch-rc2-compat.mjs)')
  process.exit(1)
}

const snippet = (await readFile(SNIPPET, 'utf8')).trimEnd()
const loader = source.indexOf('window.__ModuleLoader__.load({')
if (loader === -1) throw new Error('patch-rc2-compat: module loader not found in lib/client.js')

// A second delivery of the same bundle (rc.2 application batch + plugin row)
// must not register the same module ids twice: the loader rejects duplicates
// and the failed import leaves the composer and every uiConversation consumer
// waiting. Run the whole bundle once per page; HMR still uses a page reload.
const head = [
  '// rc.2 delivers this bundle through the application batch and again through',
  '// the plugin row; only the first execution may register its module ids.',
  'if (globalThis.__dshClassicChatBundleLoaded !== true) {',
  'globalThis.__dshClassicChatBundleLoaded = true;',
  '',
  snippet,
  '',
].join('\n')
let patched = head + source + '\n}\n'
let calls = 0
for (const id of MODULES) {
  const result = patchModule(patched, id)
  patched = result.source
  calls += result.calls
}
if (calls === 0) throw new Error('patch-rc2-compat: no useAnchoredPosition call site replaced')

await writeFile(TARGET, patched)
console.log(`patch-rc2-compat: inserted shim, replaced ${String(calls)} call site(s)`)
