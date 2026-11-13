#!/usr/bin/env node
/**
 * Restore rc.2's failed-turn retry in the vendored 0.2.1 engine.
 *
 * rc.2 lets the primary composer button continue a failed generation: the
 * `conversationContinuation` projection names the failure, `InputHub.retry`
 * validates that the failure is still displayed, and the composer submits one
 * empty message with `allowEmpty` so the Host retries the turn. The vendored
 * engine predates both the projection and the empty-submit path.
 *
 * Idempotent. Usage: node scripts/patch-feature-retry.mjs [--check]
 */
import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const TARGET = resolve(ROOT, 'lib/client.js')
const MARKER = 'Submit empty input to retry the displayed failed generation'

const PROPS_ANCHOR = 'retryFileUpload, registerFileIntake, occurrence,'
const GOAL_ANCHOR = '\t\t\tconst hasGoal = useProjection("goal", (goal) => goal != null);\n'
const REMOVED_ANCHOR = '\t\t\tconst removed = useSession((s) => s.removed) ?? false;\n'
const PRIMARY_ANCHOR = '\t\t\tconst primaryStops = running && subagent === null && (empty || blocked !== void 0);\n\t\t\tconst primaryDisabled = primaryStops ? stop === void 0 : empty || disabled || machineBusy || uploadsPending;\n\t\t\tconst interruptible = running && continuable;\n\t\t\tconst primarySubmitMode = resolveSubmitMode(busyEnter, running, "enter", steeringAvailable);\n\t\t\tconst plainMessageDraft = !empty && input?.phase === "plain" && !draft.trimStart().startsWith("/");\n\t\t\tconst primaryLabel = primaryStops ? t("input.stop") : running && steeringAvailable && !disabled && !uploadsPending && plainMessageDraft ? t(primarySubmitMode === "steer" ? "input.send.steer" : "input.send.queue") : t("input.send");\n\t\t\tconst onPrimary = () => {\n\t\t\t\tif (primaryStops) {\n\t\t\t\t\tstop?.();\n\t\t\t\t\treturn;\n\t\t\t\t}\n\t\t\t\tif (keyboard === void 0) return;\n'
const DISPATCH_ANCHOR = 'case "enter": return this.onEnter(ev.mode, ev.draft, ev.submission);'
const ENTER_ANCHOR = 'onEnter(mode, draft, submission) {'
const EMPTY_ANCHOR = '\t\t\t\tconst trimmed = draft.trim();\n\t\t\t\tif (trimmed === "") return [];\n'
const SUBMIT_ANCHOR = 'submit(mode = "queue", source) {'
const RUN_ANCHOR = '\t\t\t\tthis.dispatchRun({\n\t\t\t\t\ttype: "enter",\n\t\t\t\t\tmode,\n'
const CANPICK_ANCHOR = '\t\t\tcanPickFiles(id) {'
const INERT_INJECT_ANCHOR = '\t\t\t\t\t\tregisterFileIntake: fileIntake,\n\t\t\t\t\t\tkeyboard: void 0,'
const LIVE_INJECT_ANCHOR = '\t\t\t\t\t\tregisterFileIntake: fileIntake,\n\t\t\t\t\t\tkeyboard: shell,'
const ZH_ANCHOR = '"input.stop": "停止生成",'
const EN_ANCHOR = '"input.stop": "Stop generating",'

const RETRY_METHOD = `\t\t\t/**
\t\t\t* Submit empty input to retry the displayed failed generation. Stale
\t\t\t* failures, active work, and drafts keep their current input unchanged.
\t\t\t* @param id - retained ordinary Session identity.
\t\t\t* @param failureSeq - failed turn/end sequence the displayed button addresses.
\t\t\t*/
\t\t\tretry(id, failureSeq) {
\t\t\t\tconst binding = this.sessions().binding(id);
\t\t\t\tif (binding === void 0 || binding.session.projections.faceOf("conversationContinuation").getSnapshot() !== failureSeq) return;
\t\t\t\tconst session = binding.session.getSnapshot();
\t\t\t\tconst shell = this.shellFor(binding);
\t\t\t\tconst input = shell.snapshot;
\t\t\t\tconst inbox = binding.session.projections.faceOf("inbox").getSnapshot();
\t\t\t\tif (session.running || session.removed || session.openState !== "open" || session.subagent !== null || (session.pendingSubmissions?.length ?? 0) > 0 || input.phase !== "plain" || input.draft.trim() !== "" || input.attachmentIds.length > 0 || (inbox?.["next-turn"].length ?? 0) > 0 || inbox?.["next-step"].some((message) => message.source.kind === "user") || this.conversation().blocks.storeFor(id).getSnapshot() !== void 0) return;
\t\t\t\tshell.submit("queue", "click", true);
\t\t\t}
`

const check = process.argv.includes('--check')
const source = await readFile(TARGET, 'utf8')
if (source.includes(MARKER)) {
  console.log('patch-feature-retry: already applied')
  process.exit(0)
}
if (check) {
  console.error('patch-feature-retry: missing failed-turn retry (run node scripts/patch-feature-retry.mjs)')
  process.exit(1)
}

let patched = source
function replaceOnce(needle, replacement, label) {
  const at = patched.indexOf(needle)
  if (at === -1) throw new Error(`patch-feature-retry: anchor "${label}" not found`)
  patched = patched.slice(0, at) + replacement + patched.slice(at + needle.length)
}

replaceOnce(PROPS_ANCHOR, 'retryFileUpload, registerFileIntake, occurrence, retry,', 'InputBar props')
replaceOnce(GOAL_ANCHOR, GOAL_ANCHOR
  + '\t\t\tconst retryableFailure = useProjection("conversationContinuation") ?? void 0;\n'
  + '\t\t\tconst pendingHumanInput = useProjection("inbox", (inbox) => inbox?.["next-step"].some((message) => typeof message === "object" && message !== null && "source" in message && typeof message.source === "object" && message.source !== null && "kind" in message.source && message.source.kind === "user") ?? false);\n', 'retry projections')
replaceOnce(REMOVED_ANCHOR, REMOVED_ANCHOR
  + '\t\t\tconst historyOpen = useSession((s) => s.openState === "open") ?? false;\n'
  + '\t\t\tconst submissionPending = useSession((s) => (s.pendingSubmissions?.length ?? 0) > 0) ?? false;\n', 'session flags')
replaceOnce(PRIMARY_ANCHOR, PRIMARY_ANCHOR
  .replace('const primaryDisabled = primaryStops ? stop === void 0 : empty || disabled || machineBusy || uploadsPending;',
    'const primaryRetries = !running && subagent === null && empty && retryableFailure !== void 0 && !pendingHumanInput && historyOpen && !submissionPending && !disabled && input.queue.length === 0 && input.phase === "plain" && retry !== void 0;\n\t\t\tconst primaryDisabled = primaryStops ? stop === void 0 : !primaryRetries && empty || disabled || machineBusy || uploadsPending;')
  .replace('const primaryLabel = primaryStops ? t("input.stop") : running',
    'const primaryLabel = primaryStops ? t("input.stop") : primaryRetries ? t("input.continue") : running')
  .replace('\t\t\t\tif (keyboard === void 0) return;\n',
    '\t\t\t\tif (primaryRetries) {\n\t\t\t\t\tretry(retryableFailure);\n\t\t\t\t\treturn;\n\t\t\t\t}\n\t\t\t\tif (keyboard === void 0) return;\n'), 'primary button')
replaceOnce(DISPATCH_ANCHOR, 'case "enter": return this.onEnter(ev.mode, ev.draft, ev.submission, ev.allowEmpty === true);', 'machine dispatch')
replaceOnce(ENTER_ANCHOR, 'onEnter(mode, draft, submission, allowEmpty = false) {', 'machine onEnter')
replaceOnce(EMPTY_ANCHOR, '\t\t\t\tconst trimmed = draft.trim();\n\t\t\t\tif (trimmed === "") {\n\t\t\t\t\tif (allowEmpty !== true) return [];\n\t\t\t\t\treturn this.detachedEffects(this.beginDetached(mode, draft, submission));\n\t\t\t\t}\n', 'empty submit')
replaceOnce(SUBMIT_ANCHOR, 'submit(mode = "queue", source, allowEmpty = false) {', 'facade submit')
replaceOnce(RUN_ANCHOR, RUN_ANCHOR + '\t\t\t\t\t...allowEmpty ? { allowEmpty: true } : {},\n', 'submit action')
replaceOnce(CANPICK_ANCHOR, RETRY_METHOD + CANPICK_ANCHOR, 'InputHub.retry')
replaceOnce(INERT_INJECT_ANCHOR, '\t\t\t\t\t\tregisterFileIntake: fileIntake,\n\t\t\t\t\t\tretry: void 0,\n\t\t\t\t\t\tkeyboard: void 0,', 'inert inject')
replaceOnce(LIVE_INJECT_ANCHOR, '\t\t\t\t\t\tregisterFileIntake: fileIntake,\n\t\t\t\t\t\tretry: (failureSeq) => {\n\t\t\t\t\t\t\tinputHub.retry(sessionId, failureSeq);\n\t\t\t\t\t\t},\n\t\t\t\t\t\tkeyboard: shell,', 'live inject')
replaceOnce(ZH_ANCHOR, ZH_ANCHOR + '\n\t\t\t"input.continue": "继续生成",', 'zh locale')
replaceOnce(EN_ANCHOR, EN_ANCHOR + '\n\t\t\t"input.continue": "Continue generating",', 'en locale')

await writeFile(TARGET, patched)
console.log('patch-feature-retry: installed failed-turn retry')
