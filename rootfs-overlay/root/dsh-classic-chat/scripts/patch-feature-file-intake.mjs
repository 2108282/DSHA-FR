#!/usr/bin/env node
/**
 * Restore rc.2's document-level file paste route in the vendored 0.2.1 engine.
 *
 * rc.2 lets a file pasted anywhere on the page reach the composer through
 * `ComposerBarInjected.registerFileIntake`; the vendored engine predates that
 * route, so this patch installs the same router, registers it on the injected
 * face, and teaches the vendored InputBar to join it with its `occurrence`.
 *
 * Idempotent: rerun after a bundle rebuild is a no-op while the markers exist.
 *
 * Usage: node scripts/patch-feature-file-intake.mjs [--check]
 */
import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const TARGET = resolve(ROOT, 'lib/client.js')
const SNIPPET = resolve(ROOT, 'scripts/patches/rc2-file-intake.js')
const MARKER = 'function createComposerFileIntake()'

const APPLY_ANCHOR = '\t\tfunction apply(ctx, config = Config({})) {\n\t\t\tconst sessions = ctx.sessions;\n\t\t\tconst slots = ctx.slots;\n\t\t\tconst maxConcurrentFileUploads = config.maxConcurrentFileUploads;\n'
const PROPS_ANCHOR = 'function InputBar({ useSession, useInput, inputActions, keyboard, addFiles, removeAttachment, resolveDraftAttachments, retryFileUpload, toggleCommandMenu, stop,'
const KEYMAP_ANCHOR = '\t\t\t(0, react.useEffect)(() => {\n\t\t\t\tif (editor === null || keyboard === void 0) return;\n\t\t\t\treturn installDraftKeymap(editor, keyboard, gate);\n\t\t\t}, [editor, keyboard]);\n'
const INERT_INJECT_ANCHOR = '\t\t\t\t\tif (sessionId === void 0) return {\n\t\t\t\t\t\tkeyboard: void 0,'
const LIVE_INJECT_ANCHOR = '\t\t\t\t\treturn {\n\t\t\t\t\t\tkeyboard: shell,'
const COMPOSER_BAR_ANCHOR = '\t\t\tconst inputBar = renderSlot("conversation.composer.bar", {\n\t\t\t\tvariant: hero ? "hero" : "composer",\n'

const INTAKE_EFFECT = `\t\t\t(0, react.useEffect)(() => {
\t\t\t\tif (typeof registerFileIntake !== "function" || cardRef.current === null) return;
\t\t\t\treturn registerFileIntake({
\t\t\t\t\tcard: cardRef.current,
\t\t\t\t\toccurrence: occurrence ?? "main",
\t\t\t\t\taccepts: () => gate.current.canAcceptDrop,
\t\t\t\t\tadd: (files, directories) => {
\t\t\t\t\t\tgate.current.intakeFiles(files, directories);
\t\t\t\t\t}
\t\t\t\t});
\t\t\t}, [registerFileIntake, occurrence]);
`

const check = process.argv.includes('--check')
const source = await readFile(TARGET, 'utf8')
if (source.includes(MARKER)) {
  console.log('patch-feature-file-intake: already applied')
  process.exit(0)
}
if (check) {
  console.error('patch-feature-file-intake: missing file intake route (run node scripts/patch-feature-file-intake.mjs)')
  process.exit(1)
}

const snippet = (await readFile(SNIPPET, 'utf8')).trimEnd()
let patched = source

function replaceOnce(needle, replacement, label) {
  const at = patched.indexOf(needle)
  if (at === -1) throw new Error(`patch-feature-file-intake: anchor "${label}" not found`)
  patched = patched.slice(0, at) + replacement + patched.slice(at + needle.length)
}

replaceOnce(APPLY_ANCHOR, APPLY_ANCHOR.replace('\t\tfunction apply', `${snippet}\n\n\t\tfunction apply`), 'apply')
// The router lives at apply scope: one document route per mounted engine.
replaceOnce(APPLY_ANCHOR.replace(APPLY_ANCHOR, '\t\t\tconst maxConcurrentFileUploads = config.maxConcurrentFileUploads;\n'),
  '\t\t\tconst maxConcurrentFileUploads = config.maxConcurrentFileUploads;\n\t\t\tconst fileIntake = createComposerFileIntake();\n', 'fileIntake')
replaceOnce(PROPS_ANCHOR, PROPS_ANCHOR.replace('retryFileUpload,', 'retryFileUpload, registerFileIntake, occurrence,'), 'InputBar props')
replaceOnce(KEYMAP_ANCHOR, KEYMAP_ANCHOR + INTAKE_EFFECT, 'keymap effect')
replaceOnce(INERT_INJECT_ANCHOR, INERT_INJECT_ANCHOR.replace('\t\t\t\t\t\tkeyboard: void 0,', '\t\t\t\t\t\tregisterFileIntake: fileIntake,\n\t\t\t\t\t\tkeyboard: void 0,'), 'inert inject')
replaceOnce(LIVE_INJECT_ANCHOR, LIVE_INJECT_ANCHOR.replace('\t\t\t\t\t\tkeyboard: shell,', '\t\t\t\t\t\tregisterFileIntake: fileIntake,\n\t\t\t\t\t\tkeyboard: shell,'), 'live inject')
replaceOnce(COMPOSER_BAR_ANCHOR, COMPOSER_BAR_ANCHOR.replace('\t\t\t\tvariant: hero ? "hero" : "composer",', '\t\t\t\tvariant: hero ? "hero" : "composer",\n\t\t\t\toccurrence: props.variant,'), 'composer bar occurrence')

await writeFile(TARGET, patched)
console.log('patch-feature-file-intake: installed document paste route')
