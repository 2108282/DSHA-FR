#!/usr/bin/env node
/**
 * Restore rc.2's `conversation.hero.frame` layer in the vendored 0.2.1 engine.
 *
 * rc.2 wraps the Conversation scroll body in a `hoc` slot so the official brand
 * plugin can paint the home whale behind it. The vendored engine never declares
 * that child and renders the scroll body directly, so the official layer stays
 * pending. Declare the child and pass the body as the layer's children.
 *
 * rc.2-only: `hoc` slots do not exist before 0.2.2-rc.2.
 *
 * Idempotent. Usage: node scripts/patch-feature-hero-frame.mjs [--check]
 */
import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const TARGET = resolve(ROOT, 'lib/client.js')
const MARKER = 'renderSlot("conversation.hero.frame"'

const CHILDREN_ANCHOR = '\t\t\t\t\t"conversation.hero.brand.mark": {\n'
const BODY_ANCHOR = '\t\t\t\tchildren: [(0, react_jsx_runtime.jsxs)("div", {\n\t\t\t\t\tclassName: ConversationRoot_module_css_default.scrollBody,\n\t\t\t\t\t"data-conversation-scroll": "",\n\t\t\t\t\tchildren: [sessionId === void 0 ? null : (0, react_jsx_runtime.jsx)(Views, {}), composerSeat]\n\t\t\t\t}), (0, react_jsx_runtime.jsx)(WidthControls, {\n'

const check = process.argv.includes('--check')
const source = await readFile(TARGET, 'utf8')
if (source.includes(MARKER)) {
  console.log('patch-feature-hero-frame: already applied')
  process.exit(0)
}
if (check) {
  console.error('patch-feature-hero-frame: missing hero frame layer (run node scripts/patch-feature-hero-frame.mjs)')
  process.exit(1)
}

let patched = source
function replaceOnce(needle, replacement, label) {
  const at = patched.indexOf(needle)
  if (at === -1) throw new Error(`patch-feature-hero-frame: anchor "${label}" not found`)
  patched = patched.slice(0, at) + replacement + patched.slice(at + needle.length)
}

replaceOnce(CHILDREN_ANCHOR, '\t\t\t\t\t"conversation.hero.frame": {\n\t\t\t\t\t\tkind: "hoc",\n\t\t\t\t\t\tscope: "root"\n\t\t\t\t\t},\n' + CHILDREN_ANCHOR, 'children')
replaceOnce(BODY_ANCHOR, '\t\t\t\tchildren: [renderSlot("conversation.hero.frame", {\n\t\t\t\t\thero: hero && props.variant !== "embedded"\n\t\t\t\t}, {\n\t\t\t\t\tchildren: (0, react_jsx_runtime.jsxs)("div", {\n\t\t\t\t\t\tclassName: ConversationRoot_module_css_default.scrollBody,\n\t\t\t\t\t\t"data-conversation-scroll": "",\n\t\t\t\t\t\tchildren: [sessionId === void 0 ? null : (0, react_jsx_runtime.jsx)(Views, {}), composerSeat]\n\t\t\t\t\t})\n\t\t\t\t}), (0, react_jsx_runtime.jsx)(WidthControls, {\n', 'scroll body')

await writeFile(TARGET, patched)
console.log('patch-feature-hero-frame: installed hero frame layer')
