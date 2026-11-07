window.__ModuleLoader__.load({ id: "dsh-mobile-lite", factory: (require) => {
var __modules = {};
__modules["effects/phone-chrome.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MOBILE_QUERY = void 0;
exports.findFrame = findFrame;
exports.getFrame = getFrame;
exports.installMobileEffect = installMobileEffect;
/**
 * 移动端/触屏断点检测：
 * 视口宽度 < 1024px 且主输入为粗指针 (触屏设备 / DSHA 原生环境)
 */
exports.MOBILE_QUERY = '(max-width: 1023px) and (pointer: coarse)';
/**
 * 获取 AppFrame 外壳根节点：
 * 依次尝试插件打标的选择器、shell.overlay 父节点、以及带有 sidebarCol 的类名匹配。
 */
function findFrame() {
    const overlay = document.querySelector('[data-shell-overlay]');
    if (overlay?.parentElement)
        return overlay.parentElement;
    return document.querySelector('[data-mobile-nav="frame"], [class*="pI_x6G_frame"], [class*="frame"]:has(> [class*="sidebarCol"])');
}
function getFrame() {
    return document.querySelector('[data-mobile-nav="frame"]') ?? findFrame();
}
/**
 * 移动端专属副作用生命周期挂载器：
 * 1. 响应式监听 matchMedia 状态变化；
 * 2. 匹配移动端时自动给 frame 注入 [data-mobile-nav="frame"]，使紧凑化 CSS 生效；
 * 3. 挂载子任务并绑定清理回调。
 */
function installMobileEffect(ctx, label, install, query = exports.MOBILE_QUERY) {
    ctx.effect(() => {
        const narrow = window.matchMedia(query);
        let cleanup;
        const arm = () => {
            cleanup?.();
            const frame = getFrame();
            if (narrow.matches) {
                frame?.setAttribute('data-mobile-nav', 'frame');
                cleanup = install(narrow);
            }
            else {
                frame?.removeAttribute('data-mobile-nav');
                cleanup = undefined;
            }
        };
        arm();
        narrow.addEventListener('change', arm);
        return () => {
            narrow.removeEventListener('change', arm);
            cleanup?.();
            getFrame()?.removeAttribute('data-mobile-nav');
        };
    }, label);
}

};
__modules["effects/stats-line.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.statsAnchorAlive = statsAnchorAlive;
exports.createStatsLineTask = createStatsLineTask;
// The official conversation status row (turns / steps / LLM time / TTFT /
// cache) has a hashed class, so the stylesheet cannot target it directly.
// Mark the exact row on narrow screens by text: a [class*=_root] that
// carries the metrics text and no composer input (textarea or the
// data-composer-input Lexical node; the composer card also ends in
// _root and can mention turns in its model line). The CSS then lays the
// marked row out as ONE horizontally scrolling line with every metric
// reachable.
// Fast-path predicate: is the previously marked strip still alive in place?
// Re-verifying one anchor per flush is O(1); the full-tree hunt in mark()
// grows with the conversation and runs on every streaming token.
function statsAnchorAlive(el) {
    if (el === null || !el.isConnected)
        return false;
    if (el.closest('[data-phase]') === null)
        return false;
    // The strip must stay inside the composer stack, but it need not be a
    // DIRECT child of it: 0.1.5 nests the status row (bOPqQW_root) under the
    // composer card wrapper (uV2eYG_root), so the marked element sits one level
    // deeper than on rc.2 hosts. Ancestry is still the right test — only the
    // marker's own subtree position changed, not its container relationship.
    return el.closest('[class*="_composerStack"]') !== null;
}
function createStatsLineTask() {
    // React-owned nodes must never be relocated (issue #104): on unmount React
    // calls parent.removeChild(child) against the parent it rendered the node
    // into, so a node this task moved makes that throw NotFoundError and the
    // SlotErrorBoundary blanks the whole composer slot until a reload. The
    // offline-reconnect rebuild hits exactly this path. Both folded readouts
    // (context ring, TPS text) therefore STAY where React rendered them; the
    // visible slot is held by a plugin-owned placeholder element React does not
    // track, and the host node is absolutely positioned on top of it.
    // Coordinates refresh on every flush and on viewport resizes — the keyboard
    // changes layout without any DOM mutation to wake the reconciler.
    // The overlay must resolve against a positioned ancestor. The host rarely
    // positions these containers, so mark the expected one (CSS sets
    // position: relative for the marker, without !important so host styles stay
    // in charge) and let placeOverlay walk to whichever ancestor actually ends
    // up positioned — the math is self-consistent with any container.
    const ensurePositioned = (el, marker) => {
        if (getComputedStyle(el).position === 'static')
            el.setAttribute('data-mobile-nav', marker);
    };
    const positionedAncestor = (el) => {
        for (let node = el.parentElement; node !== null; node = node.parentElement) {
            if (getComputedStyle(node).position !== 'static')
                return node;
        }
        return null;
    };
    const placeOverlay = (host, reserve) => {
        const container = positionedAncestor(host);
        if (container === null)
            return;
        const box = reserve.getBoundingClientRect();
        const base = container.getBoundingClientRect();
        const left = box.left - base.left - container.clientLeft;
        // Center the host on its slot vertically, not top-align it. Measured
        // 2026-09-29 (issue #140 acceptance): the 20px ring top-aligned on its
        // 16px reserve hung its center at y=793 while the neighbouring keys sit
        // at 789-791 — reported as「不与其他小UI对齐」. Centering is a no-op for
        // same-height overlays (the 0.1.5/0.1.6 TPS text) and aligns the ring
        // with the cluster. hostRect is read BEFORE the style write below; its
        // height does not depend on top/left, so the math is stable across flushes.
        const hostRect = host.getBoundingClientRect();
        const top = box.top - base.top - container.clientTop - (hostRect.height - box.height) / 2;
        const styled = host;
        if (styled.style.left !== `${left}px`)
            styled.style.left = `${left}px`;
        if (styled.style.top !== `${top}px`)
            styled.style.top = `${top}px`;
    };
    // The composer root renders the TPS readout ("TPS 89.4 tok/s") as its own
    // row BELOW the status strip; fold it into the strip so every metric sits
    // on one line. Idempotent: the placeholder's text mirrors the readout and
    // the readout itself is overlaid on the placeholder's box.
    const moveTps = (stats) => {
        const stack = stats.closest('[class*="_composerStack"]');
        if (stack === null)
            return;
        let reserve = stats.querySelector(':scope > [data-mobile-nav="stats-tps-reserve"]');
        for (const el of stack.querySelectorAll('div')) {
            const text = (el.textContent ?? '').trim();
            if (!/^TPS\s+\d/.test(text))
                continue;
            if (el.children.length > 0)
                continue;
            if (el.getAttribute('data-mobile-nav') === 'stats-tps')
                continue;
            if (reserve === null) {
                reserve = document.createElement('span');
                reserve.setAttribute('data-mobile-nav', 'stats-tps-reserve');
                reserve.setAttribute('aria-hidden', 'true');
                stats.appendChild(reserve);
            }
            const live = el.textContent ?? '';
            if (reserve.textContent !== live)
                reserve.textContent = live;
            el.setAttribute('data-mobile-nav', 'stats-tps');
            const tpsRow = el.parentElement;
            if (tpsRow === null)
                continue;
            ensurePositioned(tpsRow, 'stats-tps-row');
            placeOverlay(el, reserve);
            // The strip's last child is the flex shrink group: mirror whatever
            // width the placeholder settled on so the overlay clips with the same
            // ellipsis instead of overlapping the neighbouring group.
            const width = reserve.getBoundingClientRect().width;
            const styled = el;
            if (styled.style.maxWidth !== `${width}px`)
                styled.style.maxWidth = `${width}px`;
            return;
        }
    };
    // 2026-09-23（店主最终确认）：**环要、百分比数字不要** —— 环显示在输入框行
    // 的右簇（模型/麦克风旁），CSS 用 font-size:0 只留环、隐掉 "45%" 文本；统计条
    // 拿满整宽。与 moveTps 同款 overlay：环留在 React 渲染的 dock 原位，插件自建
    // 占位 span 顶住右簇槽位（16px 环 + 2px 边距，行 gap 补足余量）。
    const moveRing = (statsOrDock) => {
        let dock = null;
        if (statsOrDock !== null && statsOrDock !== undefined) {
            if (statsOrDock.matches?.('[data-composer-dock], [class*="_dock"]')) {
                dock = statsOrDock;
            }
            else {
                const holder = statsOrDock.parentElement;
                dock = holder?.parentElement ?? holder;
            }
        }
        if (dock === null) {
            dock = document.querySelector('[data-composer-dock], [class*="_dock"]');
        }
        if (dock === null)
            return;
        // Search the composer dock for the real ContextMeter.
        // 1. MUST NOT match dsh-api-dashboard (.dshadb_barwrap or [class*="dshadb_"]).
        // 2. MUST contain an svg circle and percentage text (real context meter).
        const pool = [...dock.children];
        if (dock.parentElement !== null)
            pool.push(...dock.parentElement.children);
        const ring = pool.find((child) => {
            if (child.querySelector('textarea, [data-composer-input]') !== null)
                return false;
            if (child.matches('[class*="dshadb_"], .dshadb_barwrap') ||
                child.querySelector('[class*="dshadb_"]') !== null) {
                return false;
            }
            const hasCircle = child.querySelector('svg circle') !== null;
            const hasPercent = /\d\s*%/.test(child.textContent ?? '');
            return hasCircle && hasPercent;
        });
        if (ring === undefined)
            return;
        const row = document.querySelector('[data-composer-card] [class*="_row"] [class*="_trailing"]');
        if (row === null)
            return;
        let reserve = row.querySelector(':scope > [data-mobile-nav="stats-ring-reserve"]');
        // Position reserve STRICTLY before the send / activity button (never behind/underneath).
        let insertBeforeNode = null;
        const activity = row.querySelector('[class*="_activity"], [class*="_primary"]');
        if (activity !== null) {
            let n = activity;
            while (n && n.parentElement !== row)
                n = n.parentElement;
            insertBeforeNode = n;
        }
        if (reserve === null) {
            reserve = document.createElement('span');
            reserve.setAttribute('data-mobile-nav', 'stats-ring-reserve');
            row.insertBefore(reserve, insertBeforeNode);
        }
        else if (reserve.nextSibling !== insertBeforeNode) {
            row.insertBefore(reserve, insertBeforeNode);
        }
        if (ring.getAttribute('data-mobile-nav') !== 'stats-ring') {
            ring.setAttribute('data-mobile-nav', 'stats-ring');
        }
        const ringParent = ring.parentElement ?? dock;
        if (ringParent !== null)
            ensurePositioned(ringParent, 'stats-ring-dock');
        placeOverlay(ring, reserve);
    };
    let viewportHandler = null;
    const relayout = () => {
        const anchor = document.querySelector('[data-mobile-nav="stats"]');
        if (anchor !== null)
            moveTps(anchor);
        moveRing();
    };
    // Wake-up channel. Upstream's reconciler observes documentElement with
    // subtree:true and notes the '*' tree key on every mutation, so this task
    // re-runs constantly. This fork deliberately made that observer lean
    // (container attributes only, no subtree — streaming tokens never wake it),
    // so stats-line owns a NARROWLY scoped observer on the composer stack
    // instead. The message list lives outside the composer stack, so per-token
    // appends never fire it; only composer-internal changes (the strip mounting,
    // pill/TPS text updates) do. Self-healing: when the marked strip leaves the
    // tree, the fast path fails, the dead path re-attaches to the current stack.
    let scopeObserver = null;
    let rafId = null;
    const SELF_SELECTOR = '[data-mobile-nav="stats-ring"], [data-mobile-nav="stats-tps"], [data-mobile-nav="stats-ring-reserve"], [data-mobile-nav="stats-tps-reserve"]';
    const scheduleMark = () => {
        if (rafId !== null)
            return;
        rafId = requestAnimationFrame(() => {
            rafId = null;
            mark();
        });
    };
    const attachScope = () => {
        const stack = document.querySelector('[class*="_composerStack"]');
        if (stack === null)
            return;
        if (scopeObserver === null) {
            scopeObserver = new MutationObserver((records) => {
                // Ignore our own overlay/reserve mutations: placeOverlay writes style
                // (not observed) and moveTps/moveRing write text/append placeholders,
                // which would otherwise echo straight back into mark().
                for (const record of records) {
                    const el = record.target instanceof Element ? record.target : record.target.parentElement;
                    if (el !== null && el.closest(SELF_SELECTOR) !== null)
                        continue;
                    scheduleMark();
                    return;
                }
            });
        }
        scopeObserver.disconnect();
        scopeObserver.observe(stack, { childList: true, subtree: true, characterData: true });
    };
    let reanchorId = null;
    // The strip renders asynchronously (session projections resolve after the
    // mobile effect installs), and the stack itself can remount on session
    // switches. A cheap idle-speed re-anchor covers both without the subtree
    // cost: while the anchor is alive this is one querySelector plus two
    // ancestor walks, ~3x/sec.
    const reanchor = () => {
        const anchor = document.querySelector('[data-mobile-nav="stats"]');
        if (anchor !== null && statsAnchorAlive(anchor))
            return;
        mark();
    };
    const mark = () => {
        // Keyboard open/close and viewport rotations relayout the composer without
        // any DOM mutation, so the overlays need their own re-layout channel.
        if (viewportHandler === null) {
            viewportHandler = relayout;
            window.addEventListener('resize', relayout);
            window.visualViewport?.addEventListener('resize', relayout);
        }
        // Independent ContextMeter overlay: as long as the dock and meter exist,
        // position the context ring in the composer's trailing cluster right away.
        // Never gate moveRing on discovering a stats strip.
        moveRing();
        // Fast path: the marked strip usually survives React rebuilds between
        // tokens; re-verifying the anchor is O(1) while the full-tree hunt below
        // grows with the conversation. moveTps still re-runs so a rebuilt TPS
        // readout is re-folded.
        const anchor = document.querySelector('[data-mobile-nav="stats"]');
        if (anchor !== null && statsAnchorAlive(anchor)) {
            moveTps(anchor);
            return;
        }
        // Stale marker on a node that left the composer stack/phase context:
        // drop it so the slow path can re-anchor cleanly.
        anchor?.removeAttribute('data-mobile-nav');
        // The stack may have (re)mounted since activation — re-aim the scoped
        // observer before hunting in it.
        attachScope();
        // Scope decision: the status row is a DESCENDANT of the composer stack,
        // not necessarily its child. On rc.2 hosts it is a direct child (its own
        // `_root`); on 0.1.5 the composer card wrapper (uV2eYG_root) sits between
        // the stack and the row (bOPqQW_root), so requiring a direct child made
        // the hunt permanently miss and the strip was never marked (measured: row
        // present at 16,814 carrying "8 turns 582 steps · 103 tok/s" while
        // [data-mobile-nav="stats"] was absent). Body blocks outside the stack are
        // still skipped by the containment test below.
        const stack = document.querySelector('[class*="_composerStack"]');
        if (stack === null)
            return;
        for (const root of stack.querySelectorAll('[data-composer-stats], [class*="_root"]')) {
            // The status row lives inside the composer stack. The query is already
            // scoped to the stack, so every candidate is inside it by construction —
            // message-area blocks that mention turns/steps never enter this loop. (A
            // `stack.contains(root)` guard stood here and its comment claimed to skip
            // those blocks; it was unreachable.)
            // The todo plan strip also lives in the composer stack and its root
            // ends in _root. Its items may legitimately contain "步"/"steps" in
            // their text, so never mistake it (or any interactive dock panel)
            // for the stats strip.
            if (root.matches('[data-testid="todo-panel"]'))
                continue;
            // Dock panels are skipped by never marking a candidate whose buttons are
            // actionable controls. 0.1.5 renders the status row ITSELF as two
            // popover buttons (bOPqQW_pill, aria-haspopup="dialog"), so an
            // "any button" guard excluded the one row this task exists to mark
            // (measured: bOPqQW_root rejected solely by hasButton, marker count 0).
            // Every popover button counts as a status widget: the composer's real
            // controls (model bar, context meter) carry no metrics text and are
            // filtered by the text test above, and a panel with an actionable button
            // still fails here.
            const buttons = root.querySelectorAll('button');
            if (buttons.length > 0 && ![...buttons].every((button) => button.getAttribute('aria-haspopup') !== null))
                continue;
            const text = root.textContent ?? '';
            if (!/(turns|steps|\bLLM\b|轮|步)/.test(text))
                continue;
            // Composer card must never be mistaken for the status strip; exclude
            // its input region across both composer DOMs (textarea / Lexical
            // contentEditable marked data-composer-input).
            if (root.querySelector('textarea, [data-composer-input]') !== null)
                continue;
            root.setAttribute('data-mobile-nav', 'stats');
            moveTps(root);
            return;
        }
    };
    // Scope decision: the TPS readout updates are childList/characterData text
    // mutations inside the composer stack, so this task can only wake on the
    // tree key. This fork's reconciler never notes '*' (see attachScope above),
    // so the task carries its own scoped observer plus an idle re-anchor; the
    // expensive composer-stack scan still only runs when the anchor is stale.
    let started = false;
    return {
        name: 'stats-line',
        scopes: ['*'],
        ensure: () => {
            attachScope();
            if (!started) {
                started = true;
                reanchorId = setInterval(reanchor, 350);
            }
            mark();
        },
        dispose: () => {
            // Hand the official layout back: drop every marker (the strip loses its
            // one-line layout, ring/TPS overlays return to static flow) and remove
            // the plugin-owned placeholders.
            if (viewportHandler !== null) {
                window.removeEventListener('resize', viewportHandler);
                window.visualViewport?.removeEventListener('resize', viewportHandler);
                viewportHandler = null;
            }
            if (rafId !== null) {
                cancelAnimationFrame(rafId);
                rafId = null;
            }
            if (reanchorId !== null) {
                clearInterval(reanchorId);
                reanchorId = null;
            }
            scopeObserver?.disconnect();
            scopeObserver = null;
            started = false;
            for (const el of document.querySelectorAll('[data-mobile-nav="stats-ring"], [data-mobile-nav="stats-tps"]')) {
                const styled = el;
                styled.style.left = '';
                styled.style.top = '';
                styled.style.maxWidth = '';
            }
            for (const key of ['stats', 'stats-ring', 'stats-ring-dock', 'stats-tps', 'stats-tps-row']) {
                for (const el of document.querySelectorAll(`[data-mobile-nav="${key}"]`)) {
                    el.removeAttribute('data-mobile-nav');
                }
            }
            for (const el of document.querySelectorAll('[data-mobile-nav="stats-ring-reserve"], [data-mobile-nav="stats-tps-reserve"]')) {
                el.remove();
            }
        },
    };
}

};
__modules["effects/gesture-guard.js"] = function (require, module, exports) {
"use strict";
/**
 * Gesture-consumption contract between the sidebar swipe layer and every
 * other document-level listener that would otherwise treat the release as a
 * plain tap (the overlay's drawer-close click/pointerup handlers and the
 * FAB / backdrop element listeners).
 *
 * Two independent signals, because they answer questions at different times:
 * - the axis-lock flag (`markStrokeLocked` at tryLock, i.e. during
 *   pointermove) tells a host handler running EARLIER in the same release
 *   event's capture phase that this pointerup is a swipe release, not a tap;
 * - the consume marks (`markGestureConsumed` at the gesture layer's own
 *   pointerup, after classification) cover the events that come AFTER the
 *   release.
 *
 * When the gesture layer classifies a stroke as a real swipe it calls
 * `markGestureConsumed(target, windowMs, upTo)`; any later listener that
 * calls `consumeIfGestured(event)` on the same stroke returns true and bails
 * out, so a swipe can never toggle the drawer twice or navigate a session
 * row — including the synthetic click the browser dispatches after the
 * stroke (its target is the release point or an ancestor of it, which is why
 * the mark walks the ancestor chain up to `upTo`).
 *
 * Non-gesture taps leave both signals clear, so the host's own close / tap /
 * nav-arm logic keeps working untouched.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.markStrokeLocked = markStrokeLocked;
exports.clearStrokeLocked = clearStrokeLocked;
exports.isStrokeLocked = isStrokeLocked;
exports.markGestureConsumed = markGestureConsumed;
exports.consumeIfGestured = consumeIfGestured;
exports.isGestureConsumed = isGestureConsumed;
/** Marked targets with their expiry timestamp (monotonic performance.now). */
const consumed = new Map();
/**
 * True while the live stroke is axis-locked horizontal. Unlike the consume
 * marks (written at the gesture layer's OWN pointerup, after
 * classification), this flag is written at tryLock time — during
 * pointermove, strictly before any pointerup can fire — so a host handler
 * registered earlier in the capture phase can consult it on the same
 * release event without losing the race (audit S0/S1, 2026-08-27): while
 * the flag is up, the pointerup it is seeing is a swipe release, never a
 * tap, classified or not.
 */
let strokeLocked = false;
/** Flag the live stroke as axis-locked horizontal (called by tryLock). */
function markStrokeLocked() {
    strokeLocked = true;
}
/** Clear the axis-lock flag (called by reset and on a new pointer epoch). */
function clearStrokeLocked() {
    strokeLocked = false;
}
/** True while a stroke is axis-locked horizontal (host handlers yield). */
function isStrokeLocked() {
    return strokeLocked;
}
/**
 * True when the value looks like a DOM node that can carry an ancestor
 * chain. Feature-detected (no `instanceof Element`) so the guard stays
 * importable and testable in non-DOM environments (node:test).
 */
function isElementLike(value) {
    return (typeof value === 'object' &&
        value !== null &&
        'parentElement' in value &&
        value.parentElement !== undefined);
}
/**
 * Register that the stroke ending on `target` is a gesture. The mark covers
 * `target` itself and every ancestor up to and including `upTo` (when given
 * and present in the chain), so a follow-up synthetic click — whose target
 * is usually an ancestor of the release point — is reported as consumed
 * too. Multiple marks accumulate independently and expire after `windowMs`.
 */
function markGestureConsumed(target, windowMs, upTo) {
    const until = performance.now() + windowMs;
    if (!isElementLike(target)) {
        consumed.set(target, until);
        return;
    }
    let el = target;
    while (el !== null) {
        consumed.set(el, until);
        if (el === upTo)
            break;
        el = isElementLike(el.parentElement) ? el.parentElement : null;
    }
}
/**
 * True when the event belongs to a stroke already marked as a gesture.
 * Matches the event target itself or any of its ancestors. Stale marks are
 * dropped lazily.
 */
function consumeIfGestured(event) {
    const now = performance.now();
    const target = event.target;
    if (!isElementLike(target)) {
        for (const [t, until] of consumed) {
            if (until <= now)
                consumed.delete(t);
        }
        return false;
    }
    let el = target;
    while (el !== null) {
        const until = consumed.get(el);
        if (until !== undefined) {
            if (until <= now) {
                consumed.delete(el);
            }
            else {
                return true;
            }
        }
        el = isElementLike(el.parentElement) ? el.parentElement : null;
    }
    return false;
}
/**
 * Test-only probe: true when a gesture mark is still live for the given
 * element (tests/sidebar-swipe.test.ts asserts expiry with it; production
 * code never calls it — the swipe layer gates on its own consumedEl
 * instead). Returns false for stale marks.
 */
function isGestureConsumed(target) {
    const until = consumed.get(target);
    if (until === undefined)
        return false;
    if (until <= performance.now()) {
        consumed.delete(target);
        return false;
    }
    return true;
}

};
__modules["effects/sidebar-swipe.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.startZonePxFor = startZonePxFor;
exports.classifySwipe = classifySwipe;
exports.classifyFilesSwipe = classifyFilesSwipe;
exports.slidingVelocity = slidingVelocity;
exports.hitTestStart = hitTestStart;
exports.filesZoneHit = filesZoneHit;
exports.openStateStartMode = openStateStartMode;
exports.followTranslate = followTranslate;
exports.followOpenTransform = followOpenTransform;
exports.findHorizontalScroller = findHorizontalScroller;
exports.selectionOwnsStroke = selectionOwnsStroke;
exports.closeDrawerAnimated = closeDrawerAnimated;
exports.installSidebarSwipe = installSidebarSwipe;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
const gesture_guard_ts_1 = require("./effects/gesture-guard.js");
/**
 * Sidebar drawer swipe gestures (B 档 hybrid follow, per the 2026-08-29
 * controlled upgrade of docs/specs/2026-08-27-sidebar-swipe-gestures.md).
 *
 * Three gestures:
 * - edge swipe-in: the pointer goes down within the start zone (45% of the
 *   left edge) and the drawer is closed → the host state is flipped AT
 *   AXIS-LOCK (early commit) while the drawer is pinned in its closed slot,
 *   so the REAL open subtree mounts off-screen and then follows the finger
 *   out of the slot (see startFollow for why the flip has to come first);
 * - content swipe-toward-slot: the pointer goes down inside the open drawer
 *   and drags LEFT (LTR) → the drawer FOLLOWS the finger (inline translateX,
 *   transition:none) and releases into the host's transition;
 * - content swipe-out (legacy): drag RIGHT inside the open drawer → no
 *   follow (A 档 semantics preserved verbatim), release classifies.
 *
 * The release decision is UNCHANGED from A 档: classifySwipe (distance ratio
 * OR recent-window velocity) — after a follow stroke, dx IS the followed
 * position, so the same function decides complete vs spring-back. The commit
 * is still just `ctx.layout.toggleSidebar()`. The follow mechanics ride the
 * host transition instead of fighting it: during the stroke the drawer gets
 * inline `transition: none` + translateX; on release the inline styles are
 * dropped and the commit retargets the host transition IN THE SAME TASK (no
 * paint in between), so the drawer animates from the finger position to the
 * final state with zero custom animation code.
 *
 * Review constraints honored (spec 2026-08-27 second review): the backdrop
 * stays binary (appears at commit — never opacity-followed, 缺陷 2); a modal
 * rising mid-stroke reverts the drawer every move event (缺陷 1's per-frame
 * guard); the OPEN final state must end with transform:none (the containing
 * block invariant for fixed descendants) — a transitionend-free cleanup pair
 * (inline clear + host value) guarantees it because the host open rule is
 * transform:none. No gesture-layer DOM, no setPointerCapture. Zero transform
 * writes remain true for the LEGACY rightward-close path.
 *
 * Coexistence with the host's overlay interactions (document capture click /
 * pointerup) is two-layered via gesture-guard.ts: (1) tryLock publishes an
 * axis-lock flag the instant the stroke locks horizontal — during
 * pointermove, strictly before any pointerup — and the host handlers yield
 * on it first, because they are registered EARLIER and the post-release
 * consume marks do not exist yet on the stroke's own release event (audit
 * S0: the host toggled first and the gesture toggled back, net zero);
 * (2) a classified swipe additionally marks its target chain consumed so
 * the synthetic click after the stroke can never toggle twice or navigate
 * a row.
 */
/**
 * Start-zone width as a FRACTION of the viewport width: the pointer counts
 * as "from the left edge" anywhere inside the left (RTL: right) strip this
 * wide. The zone STAYS at 45% (2026-09-11 user decision): a brief seventh
 * pass shrank it to 0.25 for the draggable-widget conflict and was rolled
 * back the same day — the user keeps the "anywhere in the left half" feel
 * and the conflict is handled by yield signals instead (the
 * data-mobile-nav-dragging cooperation mark + the floating-widget positional
 * heuristic, see dragMarkYields/findFloatingWidget).
 * History of the constant: 24px (hotspot era) → 48px (third pass, fixed
 * "识别成对话内容滚动") → 96px (fourth pass — at that point the zone also
 * finally cleared Chrome Android's EDGE_WIDTH_DP=48dp history-navigation
 * trigger strip, whose strokes the browser claims and pointercancels; the
 * browser gesture itself is suppressed by the root overscroll-behavior-x:
 * none rule in layout.css.ts) → 0.45×viewport (fifth pass; brief 0.25
 * experiment rolled back) — this value.
 * Safety at this width: the release classification (0.16×w travel OR
 * 0.45px/ms velocity) still gates the commit, so widening cannot open on a
 * tap; vertical strokes reset at axis lock (≤8px of prevented movement) and
 * hand scrolling back; strokes beginning inside genuinely horizontally
 * scrollable containers are excluded from the zone entirely — see
 * findHorizontalScroller (at 45% the stats line / message code blocks sit
 * well inside the strip, so that guard is load-bearing).
 */
const START_ZONE_RATIO = 0.45;
/**
 * The zone in pixels for a given viewport width (pure, exported for the
 * decision-table tests). Rounded so the probe boundary assertions stay
 * integral (390px → Math.round(175.5) = 176).
 */
function startZonePxFor(viewportWidthPx, ratio = START_ZONE_RATIO) {
    return Math.round(viewportWidthPx * ratio);
}
/**
 * Axis-lock threshold: once the stroke's dominant axis has moved this far,
 * the axis is decided. Horizontal-dominant (|dx| > |dy|) locks the stroke
 * to X (a swipe); vertical-dominant abandons it to native scrolling.
 * Replaces the old 4px slop + 1.5× direction-bias pair — a 1.5× bias
 * rejected natural ~45° diagonal swipes (the other half of the
 * "识别成滚动" report). MUI uses a 3px uncertainty threshold; 8px is a
 * comfortable margin against tap jitter while still deciding in the first
 * ~16ms of movement.
 */
const LOCK_PX = 8;
/** Distance thresholds as a fraction of the viewport width.
 *  Second tuning pass (2026-08-27, "识别成滚动" feedback): 0.16 open = ~62px
 *  on a 390px phone, 0.13 close = ~51px. Keep the open threshold above the
 *  close threshold so an accidental reverse swipe cannot re-open. */
const OPEN_DISTANCE_RATIO = 0.16;
const CLOSE_DISTANCE_RATIO = 0.13;
/** Velocity window: most-recent-60ms instantaneous speed (end-segment slope). */
const VELOCITY_WINDOW_MS = 60;
/** px/ms speed thresholds for open / close (MUI uses 0.45). */
const OPEN_VELOCITY = 0.45;
const CLOSE_VELOCITY = 0.45;
/** Covers the .28s CSS transition; prevents reverse-gesture double-toggles. */
const COOLDOWN_MS = 350;
/** How long a consumed gesture mark stays live (covers the synthetic click).
 * Short by design: browsers dispatch the synthetic click within tens of ms,
 * while iOS shells suppress it entirely — a long window with no delivery
 * would let the marks swallow the user's next genuine tap (dead-tap bug).
 * When upTo is absent from the release chain (edge swipe-in releases over
 * the main content) the mark walk reaches the document root, so this short
 * window is also the bound on how long any tap can be suppressed. */
const CONSUME_WINDOW_MS = 300;
/**
 * Rightward travel (from the stroke start) that arms the OPEN follow, i.e.
 * flips the host state early so the real drawer subtree mounts. Slightly
 * above LOCK_PX so an 8px horizontal twitch inside the wide start zone does
 * not mount-and-unmount 389 nodes; small enough that the dead zone before
 * the drawer's edge appears is imperceptible.
 */
/** The open follow arms at the AXIS LOCK itself: tryLock already demanded
 * 8px of horizontal-dominant travel, so no extra twitch margin is needed —
 * every pixel between lock and arm was dead drag (user report 2026-08-29
 * 「右滑的过程中最开始有真空期,有一段卡的地方」). The release verdict still
 * decides the outcome, so arming early cannot commit a false open. */
const OPEN_FOLLOW_BASE_PCT = 101;
/**
 * Files-panel (host right sidebar) gesture constants — the right-edge mirror
 * of the drawer layer (spec 2026-09-13-files-swipe-gesture-design.md).
 *
 * FILES_ZONE_RATIO mirrors the drawer's START_ZONE_RATIO: a narrow strip
 * cannot be used because Chrome Android's history-nav edge strip (~48dp)
 * pointercancels strokes starting inside it — the same reason the drawer
 * zone grew to 45% of the viewport.
 */
const FILES_ZONE_RATIO = 0.45;
/**
 * Distance threshold as a fraction of the viewport width for BOTH files
 * directions (≈62px on a 390px phone). The drawer's separate close ratio
 * (0.13) exists because the close stroke follows into its slot; files
 * strokes have no follow, so one ratio serves both directions.
 */
const FILES_DISTANCE_RATIO = 0.16;
/** px/ms velocity threshold for both files directions (drawer parity). */
const FILES_VELOCITY = 0.45;
/** Pointer id we are tracking (multi-touch is ignored). */
let trackingPointer = 0;
/** True once the stroke is axis-locked (direction bias passed). */
let tracking = false;
/** Stroke samples (x + timestamp) for the recent-window velocity. */
let samples = [];
/** Stroke origin (for the direction-bias check). */
let startX = 0;
let startY = 0;
/** Drawer visibility at lock time. */
let lockDrawerOpen = false;
/** Expiry of the post-release cooldown (performance.now()). */
let cooldownUntil = 0;
/** Element whose stroke was marked consumed (null = no live mark). */
let consumedEl = null;
/**
 * Which gesture family owns the current stroke: 'drawer' = the sidebar
 * gestures (every pre-existing behavior, untouched); 'files' = the
 * right-edge files-panel gesture (no follow painting, host-panel commit).
 * Written by beginStroke only; a fresh beginStroke always routes it.
 */
let strokeMode = 'drawer';
/** Files-panel visibility at lock time (the mirror of lockDrawerOpen). */
let lockFilesOpen = false;
/**
 * The files-panel toggle injected at install (openFilesPanel: it toggles by
 * the host's own control state, so open and close share one function).
 * Module-level because endStroke is a module-level function; the default is
 * a no-op so the node:test suite can import the module without a DOM.
 */
let filesToggleFn = () => false;
/**
 * Pure decision: what does this stroke do, given the drawer state?
 * `dx`/`dy` are raw pointer deltas (RTL mirrors X through `rtl`), `velX` is
 * the raw recent-window X velocity. The stroke must be locked horizontal
 * (|dx| > |dy| and past the lock slop) and direction-consistent; then
 * distance OR velocity wins, with the drawer-state-specific threshold.
 */
function classifySwipe(t, m, rtl) {
    // RTL mirrors the X axis: a rightward stroke (positive dx in LTR) is
    // leftward in RTL. Normalize to the logical direction before judging.
    const dx = rtl ? -m.dx : m.dx;
    if (Math.abs(dx) <= t.lockPx)
        return 'none';
    if (Math.abs(dx) <= Math.abs(m.dy))
        return 'none';
    if (t.drawerOpen) {
        // BOTH horizontal directions close (2026-08-29 sixth round, user report
        // 「根本没法左滑关闭」). Leftward is the natural "push it back into its
        // slot" gesture — and the only one the follow animation actually paints
        // (followTranslate's close branch follows leftward), so refusing it made
        // the drawer track the finger and then spring back, i.e. the animation
        // promised a close the classifier would not honor. Rightward stays
        // accepted verbatim: four tuning rounds of muscle memory ride on it and
        // failure scenarios B0/B1/B2 assert it. Nothing else competes for a
        // horizontal stroke while the drawer is open, so accepting both costs no
        // ambiguity.
        const travel = Math.abs(dx);
        if (travel / t.viewportWidthPx >= t.closeDistanceRatio)
            return 'close';
        const velX = rtl ? -m.velX : m.velX;
        // A fling only counts when it agrees with the stroke's own direction
        // (same contradiction guard the open branch applies).
        if (velX > 0 !== dx > 0)
            return 'none';
        return Math.abs(velX) >= t.closeVelocity ? 'close' : 'none';
    }
    if (dx <= 0)
        return 'none';
    if (dx / t.viewportWidthPx >= t.openDistanceRatio)
        return 'open';
    const velX = rtl ? -m.velX : m.velX;
    return velX >= t.openVelocity ? 'open' : 'none';
}
/**
 * Pure decision for the FILES gesture (right-edge zone), the mirror twin of
 * classifySwipe. RTL mirrors the X axis exactly like classifySwipe. The
 * verdict space extends the drawer's with `files` (the files-panel commit:
 * open the panel on a leftward stroke when everything is closed, close it on
 * a rightward stroke when it is open):
 * - leftward-logical strokes only ever mean "open the panel" and fire ONLY
 *   when panel and drawer are BOTH closed — the panel would mount under the
 *   open drawer (z-1100) and be invisible, so the stroke is 'none' (the
 *   2026-09-13 narrowing: a leftward stroke NEVER collapses anything);
 * - rightward-logical strokes close the VISIBLE TOP: drawer open → 'close'
 *   (the animated commitFollowClose path, gated on the drawer's own
 *   distance/velocity thresholds so both families judge a stroke alike);
 *   else panel open → 'files'; else 'none'.
 */
function classifyFilesSwipe(t, m, rtl) {
    const dx = rtl ? -m.dx : m.dx;
    if (Math.abs(dx) <= t.lockPx)
        return 'none';
    if (Math.abs(dx) <= Math.abs(m.dy))
        return 'none';
    const velX = rtl ? -m.velX : m.velX;
    if (dx < 0) {
        if (t.panelOpen || t.drawerOpen)
            return 'none';
        if (-dx / t.viewportWidthPx >= t.distanceRatio)
            return 'files';
        // A fling only counts when it agrees with the stroke's own direction
        // (the same contradiction guard classifySwipe applies).
        if (velX > 0 !== dx > 0)
            return 'none';
        return -velX >= t.velocity ? 'files' : 'none';
    }
    if (t.drawerOpen) {
        // Same gates as the drawer family's close (classifySwipe), including its
        // CLOSE_DISTANCE_RATIO: this cell IS the drawer-close commit path, so the
        // two families must judge one physical stroke alike. Without the gate the
        // files zone's 45% reaches ~66px into the open drawer column at 390px,
        // where a thumb resting on a row drifts ~8px sideways while scrolling —
        // that closed the drawer AND consumed the tap. The leftward cell beside the
        // drawer is already 'none', so this is what makes the families agree.
        const closeRatio = t.drawerCloseDistanceRatio ?? t.distanceRatio;
        if (dx / t.viewportWidthPx >= closeRatio)
            return 'close';
        if (velX <= 0)
            return 'none';
        return velX >= t.velocity ? 'close' : 'none';
    }
    if (t.panelOpen) {
        if (dx / t.viewportWidthPx >= t.distanceRatio)
            return 'files';
        if (velX > 0 !== dx > 0)
            return 'none';
        return velX >= t.velocity ? 'files' : 'none';
    }
    return 'none';
}
/**
 * Recent-window instantaneous velocity (px/ms) from the tail of the last
 * `windowMs` milliseconds of samples, up to `now`. Sliding X per ms between
 * the LAST TWO in-window samples — the end-of-stroke slope — so a long slow
 * drag then a quick flick reports the flick, not the drag average. Samples
 * older than the window are ignored. Fewer than two in-window samples → 0.
 */
function slidingVelocity(samples, windowMs, now) {
    const cutoff = now - windowMs;
    const inWindow = samples.filter((s) => s.t >= cutoff);
    if (inWindow.length < 2)
        return 0;
    const a = inWindow[inWindow.length - 2];
    const b = inWindow[inWindow.length - 1];
    const dt = b.t - a.t;
    if (dt <= 0)
        return 0;
    return (b.x - a.x) / dt;
}
/**
 * Geometric start-hit test: the pointer went down in the left edge start
 * zone (when the drawer is closed) or inside the drawer content area (when
 * open). Pure and viewport-relative so it is unit-testable; the runtime
 * variant additionally checks the drawer geometry via the DOM.
 */
function hitTestStart(clientX, viewportWidthPx, rtl, t) {
    const edge = rtl ? viewportWidthPx - clientX : clientX;
    return edge >= 0 && edge <= t.startZonePx;
}
/**
 * Geometric start-hit test for the FILES gesture: the pointer went down in
 * the RIGHT edge zone (RTL: LEFT) — the exact mirror of hitTestStart. Pure
 * and viewport-relative.
 */
function filesZoneHit(clientX, viewportWidthPx, rtl, zonePx) {
    const edge = rtl ? clientX : viewportWidthPx - clientX;
    return edge >= 0 && edge <= zonePx;
}
/** Which gesture family owns a stroke that begins while the drawer is OPEN.
 * Inside the drawer body the drawer family always wins (owner's rule
 * 2026-09-17: the drawer's own surface must answer a leftward drag, whether or
 * not the viewport-ratio files zone overlaps it — at 390px that zone starts at
 * x=214, inside the 280px drawer); outside the body the right zone keeps its
 * files routing and its deliberate leftward 'none' verdict (2026-09-13
 * narrowing). */
function openStateStartMode(insideDrawer, inFilesZone) {
    return insideDrawer || !inFilesZone ? 'drawer' : 'files';
}
/**
 * Pure follow mapping (B 档): the translateX (px) to paint for a stroke
 * sample, or null when THIS sample has no follow. `closedTx` is the signed
 * closed-slot translateX (negative LTR, positive RTL — the drawer slides
 * off the anchored edge); `dx` is the RAW pointer delta; normalization
 * mirrors classifySwipe (`d = rtl ? -dx : dx`, rightward-logical positive =
 * toward open).
 *
 * Decision table (C3 hybrid, 2026-08-29 user decision):
 * - close stroke (drawer open): LEFTWARD-logical travel drags the drawer
 *   toward its closed slot, clamped at the slot; rightward-logical → null
 *   (the legacy A 档 close owns that direction — no follow, momentum-honest);
 * - open stroke (drawer closed): NOT used at runtime — the open direction
 *   follows through `followOpenTransform` instead, because its baseline has
 *   to stay a percentage across the subtree swap (see that function). The px
 *   mapping is kept pure and tested as the reference semantics;
 * - a zero closed slot (degenerate host without a closed transform) yields
 *   a constant 0 — the follow degrades to a no-op instead of inventing
 *   travel.
 */
function followTranslate(closedTx, dx, rtl, drawerOpen) {
    const dir = closedTx <= 0 ? -1 : 1;
    const slot = Math.abs(closedTx);
    const d = rtl ? -dx : dx;
    if (drawerOpen) {
        if (d >= 0)
            return null;
        // + 0 normalizes -0 (dir=-1 times a clamped 0) so strict equality in the
        // decision table and in probe comparisons sees a plain zero.
        return dir * Math.min(slot, -d) + 0;
    }
    if (d <= 0)
        return null;
    return dir * (slot - Math.min(slot, d)) + 0;
}
/**
 * Pure follow mapping for the OPEN direction (B 档, 2026-08-29 second pass).
 * Returns the CSS transform to paint for a stroke that has already
 * early-committed the host state, or null when this sample has no follow
 * (leftward-logical travel, i.e. pulled back past the stroke origin).
 *
 * The baseline is the host's own PERCENTAGE slot (`translateX(-110%)`), kept
 * symbolic on purpose: at arm time the element is still the ~206px collapsed
 * rail and a frame later React has swapped in the ~280px drawer. A px
 * baseline captured before the swap would leave the wider drawer 74px
 * off-position (its slot is -308px, not -227px); `-110%` re-resolves against
 * the element's current width on every frame, so the same declaration is
 * correct across the mount. `min()`/`max()` clamp the open end so overshoot
 * cannot drag the drawer past its resting position.
 */
function followOpenTransform(travelPx, rtl) {
    const t = rtl ? -travelPx : travelPx;
    if (t <= 0)
        return null;
    return rtl
        ? `translateX(max(0px, calc(${OPEN_FOLLOW_BASE_PCT}% - ${t}px)))`
        : `translateX(min(0px, calc(-${OPEN_FOLLOW_BASE_PCT}% + ${t}px)))`;
}
/**
 * Pure walk: the innermost element of the chain (self included) that is a
 * GENUINELY horizontally scrollable container — overflow-x auto/scroll AND
 * content actually overflowing (scrollWidth > clientWidth + 1; the +1
 * absorbs subpixel rounding). A stroke beginning inside one belongs to that
 * scroller: the browser claims the horizontal pan (pointercancel on real
 * devices) and the release classification must neither compete with it nor
 * preventDefault it away — prevention is what would break the strip's native
 * scrolling near the left edge once the start zone grew to 45% of the
 * viewport (the stats
 * line spans the full width; message code blocks are overflow-x:auto too).
 * CDP failure scenario C1 pins this contract. overflow-x:hidden/clip never
 * match: clipped content cannot pan, so a horizontal stroke there stays free
 * for the gesture layer.
 */
function findHorizontalScroller(node) {
    let cur = node;
    while (cur !== null) {
        if ((cur.overflowX === 'auto' || cur.overflowX === 'scroll') &&
            cur.scrollWidth > cur.clientWidth + 1) {
            return cur;
        }
        cur = cur.parent;
    }
    return null;
}
/** The open drawer element: sidebar column of the plugin frame. */
function findDrawer() {
    const frame = (0, phone_chrome_ts_1.getFrame)();
    if (frame === null)
        return null;
    return frame.querySelector('[class*="sidebarCol"]');
}
/** True when the drawer is currently open (per the collapsed marker). */
function drawerOpen() {
    const frame = (0, phone_chrome_ts_1.getFrame)();
    return frame !== null && !frame.hasAttribute('data-sidebar-collapsed');
}
/**
 * True when the host's right sidebar files panel is currently VISIBLE (any
 * form: fullscreen on phones, docked on tablets). Measured on 0.1.5: the
 * panel element is persistent — the closed fullscreen form stays in the DOM
 * at `visibility: hidden` with its rect pushed to x=viewport-width — so
 * presence alone is not the panel-state read. Detect the open state by the
 * three ways the host can hide it (visibility / display / pushed-off rect);
 * a host without this panel keeps returning false and the explorer fallback
 * applies.
 */
function filesPanelOpen() {
    const panel = document.querySelector('[data-sidebar-right-panel]');
    if (panel === null)
        return false;
    const cs = getComputedStyle(panel);
    if (cs.visibility === 'hidden' || cs.display === 'none')
        return false;
    return panel.getBoundingClientRect().left < window.innerWidth;
}
/**
 * Map the real DOM ancestor chain (target first, root last) onto the plain
 * SwipeChainNode shape findHorizontalScroller walks. Bounded by the document
 * depth (~15 nodes in this app) and run once per pointerdown, so the
 * getComputedStyle calls are not a per-frame cost.
 */
function chainFrom(target) {
    let node = null;
    let el = target;
    while (el !== null) {
        node = {
            parent: node,
            scrollWidth: el.scrollWidth,
            clientWidth: el.clientWidth,
            overflowX: getComputedStyle(el).overflowX,
        };
        el = el.parentElement;
    }
    return node;
}
/** Whether a modal dialog owns the screen (gestures must yield to it). */
function modalOpen() {
    return document.querySelector('[aria-modal="true"]') !== null;
}
/** True when a full-screen takeover (taskboard / ssh) owns the frame, or any
 *  host conversation.view overlay (trajectory tab, dsh-file-viewer, or a
 *  future third-party view — they all set the generic
 *  `data-conversation-composer-overlay` attribute on their root) is open.
 *  In both cases the drawer edge-swipe gestures yield so horizontal content
 *  scrolling (kanban columns, trajectory tables, CSV/code panes) wins the
 *  left-edge start zone; the FAB still opens the drawer. This reads the
 *  generic overlay attribute directly — deliberately decoupled from the
 *  file-viewer marker, which keys on `.dsfv-panel` only (design
 *  2026-09-06-conversation-overlay-takeover-design.md §4.2). */
function takeoverActive() {
    return (document.documentElement.hasAttribute('data-dsh-taskboard-active') ||
        document.documentElement.hasAttribute('data-dsh-ssh-active') ||
        document.querySelector('[data-conversation-composer-overlay]') !== null);
}
/**
 * Whether a live, non-collapsed text selection owns the pointer stroke.
 * A selection-handle drag (and a long-press selection that appears between
 * pointerdown and the axis lock) is horizontally dominant and geometrically
 * indistinguishable from a drawer swipe — the browser must keep it (#43,
 * iPad WebKit). Feature-detected so the node:test suite can import the
 * predicate without a DOM.
 *
 * TWO selection models must be read, because they are disjoint:
 * - the DOCUMENT selection (window.getSelection) covers message-flow text
 *   and contenteditable hosts;
 * - a selection inside a text control lives on the ELEMENT as
 *   selectionStart/selectionEnd and is INVISIBLE to window.getSelection —
 *   measured on the composer during a hijacked stroke (#44, real iPad):
 *   taStart=0 taEnd=20 while the document selection reported isCollapsed.
 *   Reading only the document selection let the swipe layer arm, lock, and
 *   collapse the composer selection the user was extending.
 * document.activeElement is the right anchor for the element model: a handle
 * drag keeps focus inside the control, and it also covers strokes whose
 * points land outside the control's own box.
 */
function selectionOwnsStroke() {
    if (typeof window === 'undefined')
        return false;
    const sel = window.getSelection();
    if (sel !== null && !sel.isCollapsed)
        return true;
    if (typeof document === 'undefined')
        return false;
    const el = document.activeElement;
    if (el === null)
        return false;
    const tag = el.tagName;
    if (tag !== 'TEXTAREA' && tag !== 'INPUT')
        return false;
    // Input types without a text selection (checkbox, number, email, …) report
    // null here — measured in Chromium — and older WebKit/Gecko throw
    // InvalidStateError instead. Both mean "no text selection is being
    // dragged", never "the control owns this stroke", so neither may be
    // allowed to escape from a pointer handler.
    try {
        const { selectionStart: start, selectionEnd: end } = el;
        return typeof start === 'number' && typeof end === 'number' && start !== end;
    }
    catch {
        return false;
    }
}
/** Whether the swipe layer is on cooldown (animation in flight). */
function onCooldown() {
    return performance.now() < cooldownUntil;
}
/**
 * Draggable-element yield mark (2026-09-11, 桌宠拖动冲突 D 方案的 C 侧):
 * a dragging component (desktop pet, floating ball, drag-to-reorder, …)
 * marks itself with `data-mobile-nav-dragging` while its drag is live — on
 * the element the pointer is holding (or any ancestor), or on
 * documentElement/body as a global mark when the dragged node moves around
 * or the dragger prefers not to touch the node tree. The gesture layer
 * reads the mark at pointerdown AND at every axis-lock attempt before the
 * stroke locks: a mark present at either point yields the whole stroke (no
 * drawer arm, no touchmove preventDefault) because the two layers would
 * otherwise both answer the same pointer stream — the exact bug the probe
 * reproduces (draggable-conflict-probe pet.t1: a 56px floating ball dragged
 * rightward inside the fifth-pass 45% zone opened the drawer mid-drag).
 * Semantics mirror selectionOwnsStroke: the mark must be up by the first
 * few move events (a pointerdown handler is the natural place); once the
 * stroke axis-locks the gesture stays committed — a mark appearing
 * mid-locked-stroke does not unwind an already-armed open follow.
 */
function dragMarkYields(event) {
    if (document.documentElement.hasAttribute('data-mobile-nav-dragging'))
        return true;
    if (document.body.hasAttribute('data-mobile-nav-dragging'))
        return true;
    return (event.target instanceof Element &&
        event.target.closest('[data-mobile-nav-dragging]') !== null);
}
/** Upper bound (px) of the "small floating widget" positional heuristic.
 * The real-world reference is dsh-pet's floating ball (kz2Bea_float,
 * position:fixed, measured 148x160 on the live profile page) — 160 would
 * sit exactly on that widget's edge; 200 leaves headroom for sibling
 * plugin widgets while a full-screen overlay (backdrop, sheets, dialogs)
 * still cannot pass. */
const FLOATING_WIDGET_MAX_PX = 200;
/**
 * Floating-widget positional yield (2026-09-11, 悬浮窗拖动冲突 B 侧): plugins
 * ship draggable floating widgets (desktop-pet / floating-ball / draggable
 * panel shapes) that carry NO standard "draggable" DOM mark, yet the user
 * presses the widget itself — so the stroke's start target sits inside that
 * widget's layer. Draggable widgets almost always live in a SMALL
 * freely-positioned layer (position: fixed | absolute, own box ≤ 160px)
 * hovering above the page, so walk the ancestor chain from the event target:
 * the first small positioned ancestor counts as a floating widget and the
 * stroke yields (no arm, no touchmove preventDefault). Pairs with
 * dragMarkYields (cooperation mark) which needs no shape guessing.
 * Excluded: anything inside our own frame subtree — the FAB / backdrop /
 * drawer content carry their own gesture semantics and must never be
 * misread as floating widgets (the closed-state FAB sits in the start zone).
 * ponytail: no DOM-standard draggable signal exists; shape ≈ draggable is an
 * approximation with a known ceiling — a STATIC small positioned element
 * (e.g. a message badge) also yields, costing a stroke start under a
 * ≤160px dot; a REAL widget that misses (bigger layer, static positioning)
 * upgrades via the data-mobile-nav-dragging mark or by raising the cap.
 */
function findFloatingWidget(target) {
    if (target.closest('[data-mobile-nav="frame"]') !== null)
        return null;
    let el = target;
    while (el !== null) {
        if (el instanceof HTMLElement) {
            const cs = getComputedStyle(el);
            if ((cs.position === 'fixed' || cs.position === 'absolute') &&
                el.offsetWidth <= FLOATING_WIDGET_MAX_PX &&
                el.offsetHeight <= FLOATING_WIDGET_MAX_PX) {
                return el;
            }
        }
        el = el.parentElement;
    }
    return null;
}
function floatingWidgetYields(event) {
    return (event.target instanceof Element &&
        findFloatingWidget(event.target) !== null);
}
/**
 * Cache the follow geometry for a freshly locked stroke. Runs ONCE per
 * stroke (one getComputedStyle, plus one getBoundingClientRect only for the
 * cold-start fallback); the per-move path afterwards is write-only.
 *
 * CLOSE strokes follow from a px baseline read here. OPEN strokes cannot:
 * the host renders TWO different subtrees in the same sidebar column —
 * collapsed it is a ~206px rail holding only Task Board / SSH / Files /
 * Session log (79 nodes, ZERO `role=treeitem`), open it is the ~280px drawer
 * with the session tree and footer (389 nodes, 15 treeitems). Dragging the
 * closed column would only reveal the rail (measured 2026-08-29, the user's
 * "完全不同的 UI、没有真实会话、位置全乱" report). The open direction therefore
 * commits FIRST and follows AFTER (armOpenFollow), which is also why its
 * baseline must stay a percentage rather than a px value cached here.
 */
function closeDrawerAnimated(ctx) {
    if (!drawerOpen())
        return false;
    ctx.layout.toggleSidebar();
    return true;
}
function abortStroke(_ctx, _immediate = false) {
    reset();
}
/** Start a stroke; returns true when it may be tracked. */
function beginStroke(event, rtl, viewportWidthPx) {
    if (onCooldown())
        return false;
    if (modalOpen())
        return false;
    if (takeoverActive())
        return false;
    // A live text selection owns the stroke (a selection-handle drag is
    // horizontally dominant and geometrically identical to a swipe — #43,
    // iPad WebKit): yield before any geometric test. This also blocks
    // swipe-open while a stale selection is alive; one tap collapses the
    // selection everywhere, and backdrop tap-to-close is unaffected (a tap
    // never reaches tryLock).
    if (selectionOwnsStroke())
        return false;
    // A live draggable (data-mobile-nav-dragging, see dragMarkYields) owns the
    // stroke: yield before any geometric test so the drawer cannot arm for a
    // drag that starts inside the start zone.
    if (dragMarkYields(event))
        return false;
    // Plugin-shipped draggable floating widgets (pet / floating-ball shapes
    // without any cooperation mark) yield the same way, via the positional
    // heuristic — the user pressed the widget itself.
    if (floatingWidgetYields(event))
        return false;
    if (!(event.target instanceof Element))
        return false;
    // A stroke beginning inside a genuinely horizontally scrollable container
    // belongs to that scroller (the stats line, a message code block, any
    // carousel): yield it so its native horizontal pan survives — and so the
    // wide 45%-of-viewport start zone cannot turn a strip scroll into a
    // drawer open (failure scenario C1). Applies to both branches: inside the
    // drawer the same "scroller owns horizontal" semantics should hold.
    if (findHorizontalScroller(chainFrom(event.target)) !== null)
        return false;
    const open = drawerOpen();
    const filesZonePx = startZonePxFor(viewportWidthPx, FILES_ZONE_RATIO);
    if (open) {
        // Close strokes may start ANYWHERE over the frame (2026-08-29 sixth
        // round, user report 「希望打开抽屉之后以外的部分可以进行左滑」). The
        // previous gate required the start point inside the drawer's own
        // geometry and explicitly rejected the backdrop, so the ~28% of the
        // screen beside the drawer swallowed every swipe — combined with the
        // leftward verdict being refused, closing felt impossible. Nothing else
        // owns a horizontal stroke while the drawer is open (the conversation is
        // behind the backdrop), so the whole frame is fair game.
        //
        // 2026-09-13 narrowing (user decision): the RIGHT zone beside the drawer
        // now belongs to the files gesture. Its leftward stroke must NOT close
        // the drawer (the files panel would mount UNDER the drawer — z-1100 —
        // and be invisible); its rightward stroke keeps the animated close via
        // classifyFilesSwipe's 'close' verdict. The left zone / drawer content
        // keeps every pre-existing drawer behavior. Drawer-mode follow painting
        // only ever maps LEFTWARD (followTranslate's close branch), which is
        // exactly the direction the narrowing removes from this zone — no close
        // follow is lost by the routing (probe swipe.close-follow-reaches-slot
        // was relocated into the drawer family accordingly).
        //
        // Tap-to-close on the backdrop is unaffected: a tap never reaches
        // tryLock, so endStroke returns on !wasTracking without writing a
        // consume mark, and the document-capture click handler passes backdrop /
        // FAB clicks through unconditionally anyway.
        const frame = (0, phone_chrome_ts_1.getFrame)();
        if (frame === null)
            return false;
        const rect = frame.getBoundingClientRect();
        if (event.clientX < rect.left || event.clientX > rect.right)
            return false;
        if (event.clientY < rect.top || event.clientY > rect.bottom)
            return false;
        // A session-row action menu (kebab) owns its own tap.
        if (event.target.closest('[class*="sessionRow"] button') !== null)
            return false;
        //
        // 2026-09-17 (owner: the judgment zone must end at the drawer's right
        // edge): inside the drawer BODY the drawer family always wins. The files
        // zone is viewport-relative (0.45 from the right edge = x >= 214 at 390px)
        // and overlaps the 280px drawer by 66px, so that sliver of the drawer's own
        // surface used to answer 'none' to a leftward drag - touching the drawer and
        // dragging left did nothing. Routing on the drawer's live rect (not a
        // hardcoded width) keeps the rule true at every viewport: at >= 509px the
        // 0.45 zone starts right of the drawer and nothing changes. Outside the
        // body the files routing, including its leftward no-op, is untouched.
        const drawer = findDrawer();
        const drawerRect = drawer === null ? null : drawer.getBoundingClientRect();
        const insideDrawer = drawerRect !== null && event.clientX >= drawerRect.left && event.clientX <= drawerRect.right;
        strokeMode = openStateStartMode(insideDrawer, filesZoneHit(event.clientX, viewportWidthPx, rtl, filesZonePx));
    }
    else if (hitTestStart(event.clientX, viewportWidthPx, rtl, { startZonePx: startZonePxFor(viewportWidthPx) })) {
        strokeMode = 'drawer';
    }
    else if (filesZoneHit(event.clientX, viewportWidthPx, rtl, filesZonePx)) {
        strokeMode = 'files';
    }
    else {
        return false;
    }
    trackingPointer = event.pointerId;
    tracking = false;
    startX = event.clientX;
    startY = event.clientY;
    samples = [{ t: event.timeStamp, x: event.clientX }];
    return true;
}
/**
 * Axis-lock the stroke once its dominant axis has moved LOCK_PX. Horizontal
 * dominance (|dx| > |dy|) locks to X and is tracked; vertical dominance
 * abandons the stroke back to native scrolling (browser takes over, no
 * further preventDefault). Once locked the axis never re-decides — matching
 * MUI's UNCERTAINTY_THRESHOLD semantics.
 */
function tryLock(event) {
    const dx = event.clientX - startX;
    const dy = event.clientY - startY;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < LOCK_PX)
        return false;
    // Second timing window for the drag mark (same pattern as the selection
    // check in onPointerMove): the dragger often raises the mark in its own
    // pointerdown/move handler, i.e. AFTER our beginStroke ran. Re-check at
    // every lock attempt so the stroke yields before the axis locks.
    if (dragMarkYields(event) || floatingWidgetYields(event)) {
        reset();
        return false;
    }
    if (Math.abs(dx) <= Math.abs(dy)) {
        // Vertical-dominant: hand the touch back to scrolling.
        reset();
        return false;
    }
    tracking = true;
    lockDrawerOpen = drawerOpen();
    if (strokeMode === 'files') {
        lockFilesOpen = filesPanelOpen();
        (0, gesture_guard_ts_1.markStrokeLocked)();
        return true;
    }
    (0, gesture_guard_ts_1.markStrokeLocked)();
    return true;
}
/** Append a sample and prune the window. */
function pushSample(event) {
    samples.push({ t: event.timeStamp, x: event.clientX });
    const cutoff = event.timeStamp - VELOCITY_WINDOW_MS;
    let i = 0;
    while (i < samples.length - 1 && samples[i].t < cutoff)
        i += 1;
    if (i > 0)
        samples = samples.slice(i);
}
/**
 * Release the stroke: classify, then either commit or spring back.
 *
 * B 档 ordering is load-bearing: the verdict is computed FIRST (the follow
 * position IS dx, so classifySwipe decides complete-vs-revert exactly as in
 * A 档), then the inline follow styles are dropped — restoring the host
 * transition and clearing the transform starts an animation toward the
 * drawer's CURRENT host state — and only then does the commit flip the host
 * state, retargeting that transition within the SAME task. No paint happens
 * between the two, so the user sees one continuous motion from the finger
 * position into the final state; a reverted stroke simply animates home.
 *
 * An ARMED OPEN follow inverts the commit: the host state was already
 * flipped at arm time, so a positive verdict must NOT toggle again (that
 * would close the drawer the user just pulled out) and a negative verdict
 * must toggle BACK. Either way the inline release comes first, so the host
 * transition animates from the finger position to whichever state wins.
 */
function endStroke(ctx, event, rtl, viewportWidthPx) {
    const wasTracking = tracking;
    const filesMode = strokeMode === 'files';
    const filesOpenAtLock = lockFilesOpen;
    const vel = slidingVelocity(samples, VELOCITY_WINDOW_MS, event.timeStamp);
    const dx = event.clientX - startX;
    const dy = event.clientY - startY;
    reset();
    if (!wasTracking)
        return;
    const modal = modalOpen();
    // An armed open follow has already flipped the marker, so classifySwipe
    // must still be asked the question the USER answered: it was a closed
    // drawer when the stroke began (lockDrawerOpen), which is what the stored
    // flag holds — never re-read drawerOpen() here.
    const verdict = modal || onCooldown()
        ? 'none'
        : filesMode
            ? classifyFilesSwipe({
                distanceRatio: FILES_DISTANCE_RATIO,
                velocity: FILES_VELOCITY,
                lockPx: LOCK_PX,
                viewportWidthPx,
                panelOpen: filesOpenAtLock,
                drawerOpen: lockDrawerOpen,
                drawerCloseDistanceRatio: CLOSE_DISTANCE_RATIO,
            }, { dx, dy, velX: vel }, rtl)
            : classifySwipe({
                openDistanceRatio: OPEN_DISTANCE_RATIO,
                closeDistanceRatio: CLOSE_DISTANCE_RATIO,
                velocityWindowMs: VELOCITY_WINDOW_MS,
                openVelocity: OPEN_VELOCITY,
                closeVelocity: CLOSE_VELOCITY,
                lockPx: LOCK_PX,
                cooldownMs: COOLDOWN_MS,
                startZonePx: startZonePxFor(viewportWidthPx),
                viewportWidthPx,
                drawerOpen: lockDrawerOpen,
            }, { dx, dy, velX: vel }, rtl);
    // Terminal styles, per verdict. CLOSE commits are LATE: animate the inline
    // transform into the closed slot and flip the host only when the drawer is
    // already off-screen (commitFollowClose → commitWithAnimation) — flipping
    // first swaps the sidebar subtree mid-animation (measured: width 280→206
    // at t≈200ms of the 280ms transition, tx jumped backward). OPEN verdicts
    // and the revert/modal/cooldown paths keep the plain release: the host
    // stays in its current state, so its own transition finishes the motion
    // and no subtree swap can be in flight. Every path either releases or
    // hands the inline pair to the pending commit — it can never leak.
    if (event.target instanceof Element) {
        if (verdict === 'close') {
            markStrokeConsumed(event.target);
            if (drawerOpen())
                ctx.layout.toggleSidebar();
            cooldownUntil = performance.now() + COOLDOWN_MS;
            return;
        }
        if (verdict === 'open') {
            markStrokeConsumed(event.target);
            if (!drawerOpen())
                ctx.layout.toggleSidebar();
            cooldownUntil = performance.now() + COOLDOWN_MS;
            return;
        }
        if (verdict === 'files') {
            filesToggleFn();
            markStrokeConsumed(event.target);
            cooldownUntil = performance.now() + COOLDOWN_MS;
            return;
        }
        if (modal || (filesMode && verdict === 'none')) {
            markStrokeConsumed(event.target);
        }
    }
}
/**
 * Mark the released stroke so its synthetic click cannot re-toggle the drawer
 * or activate a row.
 *
 * The mark walks the ancestor chain up to the DRAWER when the stroke started
 * inside it: the backdrop is a frame child, so stopping at the frame would
 * make the host treat a genuine backdrop tap within the window as consumed
 * and swallow the close (the "tap twice to close" bug). A stroke that started
 * OUTSIDE the drawer (the left-edge start zone, or — since closing accepts
 * the whole frame — the backdrop itself) has no drawer in its chain, so the
 * walk would otherwise run all the way to the document root and briefly
 * shadow every tap on the page; the frame is the tightest correct stop for
 * those, and it is what must be marked anyway, because a backdrop-started
 * close stroke needs its own overlay click consumed.
 */
function markStrokeConsumed(target) {
    const drawer = findDrawer();
    const upTo = drawer !== null && drawer.contains(target) ? drawer : (0, phone_chrome_ts_1.getFrame)() ?? null;
    (0, gesture_guard_ts_1.markGestureConsumed)(target, CONSUME_WINDOW_MS, upTo);
    consumedEl = target;
}
/** Forget stroke state (called on cancel / visibility change / blur). */
function reset() {
    trackingPointer = 0;
    tracking = false;
    samples = [];
    strokeMode = 'drawer';
    lockFilesOpen = false;
    (0, gesture_guard_ts_1.clearStrokeLocked)();
}
/** The logical reading direction of the frame (RTL support). */
function frameRtl() {
    const frame = (0, phone_chrome_ts_1.getFrame)();
    return frame !== null && getComputedStyle(frame).direction === 'rtl';
}
/** Install the gesture layer for the current mobile breakpoint. */
function installSidebarSwipe(ctx, filesToggle) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: sidebar swipe gestures', () => {
        filesToggleFn = filesToggle;
        const viewportWidth = () => window.innerWidth || document.documentElement.clientWidth || 0;
        const onPointerDown = (event) => {
            // A new pointer starts a new interaction epoch: drop the previous
            // stroke's click gate. When the browser never delivers the synthetic
            // click (iOS shells suppress it after a swipe), this — together with
            // the short CONSUME_WINDOW_MS — keeps the next genuine tap alive
            // instead of eating it at the document-capture click handler.
            consumedEl = null;
            (0, gesture_guard_ts_1.clearStrokeLocked)(); // belt-and-suspenders: a lost stroke must not leak its lock into this epoch
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            // A second finger means the browser owns this interaction (pinch zoom),
            // and a two-finger drag is never a drawer swipe. Merely ignoring the
            // extra pointer would keep the stroke alive — and with it the
            // touchmove preventDefault below, which cancels the native pinch. On
            // iOS that pinch is the only way back out of a zoom, so fighting it
            // recreates exactly the trap #45 reported. Hand the whole interaction
            // back instead.
            if (trackingPointer !== 0 && trackingPointer !== event.pointerId) {
                abortStroke(ctx);
                return;
            }
            beginStroke(event, frameRtl(), viewportWidth());
        };
        const onPointerMove = (event) => {
            if (event.pointerId !== trackingPointer)
                return;
            // A modal may rise mid-stroke (e.g. an a11y trap opening) — spec review
            // 缺陷 1's guard, now per-MOVE because B 档 paints a transform the
            // modal must not inherit: abandon and spring the drawer back.
            if (modalOpen() || takeoverActive()) {
                abortStroke(ctx);
                return;
            }
            if (!tracking) {
                if (selectionOwnsStroke()) {
                    reset();
                    return;
                }
                if (tryLock(event)) {
                    pushSample(event);
                }
            }
            else {
                pushSample(event);
            }
        };
        const onPointerUp = (event) => {
            if (event.pointerId !== trackingPointer)
                return;
            endStroke(ctx, event, frameRtl(), viewportWidth());
        };
        const onPointerCancel = (event) => {
            if (event.pointerId !== trackingPointer)
                return;
            abortStroke(ctx);
        };
        // The browser may synthesize a click a few ms after the stroke's
        // pointerup. The host overlay handlers and the FAB / backdrop element
        // listeners would treat it as a tap; swallow it at document capture so
        // a swipe can never toggle twice or navigate a row. Non-gesture taps
        // (no live mark) pass through untouched.
        //
        // A click whose target is (or is inside) the backdrop or the FAB is
        // NEVER a gesture's synthetic click: the stroke start is always the
        // left-edge start zone or the drawer content, never the backdrop (outside
        // the drawer, on the right) or the FAB. The mark chain can reach them
        // in degenerate hit-test cases (e.g. a stroke starting on a point where
        // the empty drawer does not register as the event target), and
        // swallowing that click would break the "tap the backdrop to close"
        // path — the "tap twice to close" bug. Let those clicks through.
        const onClick = (event) => {
            if (consumedEl === null)
                return;
            if (!(event.target instanceof Element))
                return;
            // A genuine backdrop / FAB tap is always let through: their own click
            // listeners toggle the drawer, and a consume mark that walked to the
            // document root would otherwise swallow it ("tap twice to close").
            // The one exception is a click on the overlay element that STARTED the
            // just-committed stroke — since close strokes may begin anywhere over
            // the frame, the backdrop can now be the stroke's own start target,
            // and letting its synthetic click through would re-toggle the drawer
            // straight back open.
            const overlay = event.target.closest('[data-sidebar-mask], [class*="drawerMask"], [data-mobile-nav="fab"]');
            if (overlay !== null && !overlay.contains(consumedEl))
                return;
            if (!(0, gesture_guard_ts_1.consumeIfGestured)(event))
                return;
            event.stopPropagation();
            event.preventDefault();
            consumedEl = null;
        };
        const onVisibility = () => {
            if (document.hidden)
                abortStroke(ctx);
        };
        // Edge-touch priority (iOS UIScreenEdgePanGestureRecognizer semantics):
        // a stroke that began inside the left-edge start zone must never be
        // claimed by native scrolling. touch-action: pan-y already forbids the
        // browser from panning it horizontally; this preventDefault (passive:
        // false) additionally stops the vertical-scroll claim, so the pointer
        // event stream reaches the gesture layer intact on browsers where the
        // scroller wins the race (iOS Safari in particular — headless cannot
        // reproduce that behavior). Vertical-dominant strokes abandon the
        // gesture (reset() clears trackingPointer), so scrolling resumes for
        // touches that were never swipes. Strokes starting inside a genuinely
        // horizontally scrollable container never reach this state at all
        // (beginStroke rejects them via findHorizontalScroller), so their
        // native horizontal pan is never prevented.
        //
        // Multi-touch is the one case that must never be prevented: two fingers
        // on the screen mean a pinch, and preventDefault on those touchmoves
        // cancels the browser's zoom gesture. The pointerdown guard above
        // already abandons the stroke when a second finger lands; this is the
        // belt-and-braces path for engines that hand the gesture to the
        // compositor without delivering a second pointerdown (#46 real-device
        // report: pinch-out zoomed but pinch-in would not zoom back).
        const onTouchMove = (event) => {
            if (trackingPointer === 0)
                return;
            if (event.touches.length > 1) {
                abortStroke(ctx);
                return;
            }
            event.preventDefault();
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('pointermove', onPointerMove, true);
        document.addEventListener('pointerup', onPointerUp, true);
        document.addEventListener('pointercancel', onPointerCancel, true);
        document.addEventListener('click', onClick, true);
        document.addEventListener('touchmove', onTouchMove, { capture: true, passive: false });
        const onBlur = () => abortStroke(ctx);
        document.addEventListener('visibilitychange', onVisibility);
        window.addEventListener('blur', onBlur);
        return () => {
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('pointermove', onPointerMove, true);
            document.removeEventListener('pointerup', onPointerUp, true);
            document.removeEventListener('pointercancel', onPointerCancel, true);
            document.removeEventListener('click', onClick, true);
            document.removeEventListener('touchmove', onTouchMove, { capture: true });
            document.removeEventListener('visibilitychange', onVisibility);
            window.removeEventListener('blur', onBlur);
            abortStroke(ctx, true);
        };
    });
}

};
__modules["components/open-files-panel.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.HOST_FILES_CLOSER = exports.HOST_FILES_OPENER = void 0;
exports.openFilesPanel = openFilesPanel;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/** The host's own right-sidebar opener (ui-sidebar-right: ExpandButton). */
exports.HOST_FILES_OPENER = '[data-sidebar-right-expand]';
/** The host's collapse control, mounted while the right sidebar is open. */
exports.HOST_FILES_CLOSER = '[data-sidebar-right-toggle]';
/**
 * Open the file browser from a mobile control, preferring the surface the user
 * actually has.
 *
 * 1. The host's own right sidebar (`data-sidebar-right-expand`) is the current
 *    file browser: on 0.1.5 it holds the workspace tree (Files tab). The host
 *    renders its opener inside `headerCorner`, which the desktop layout hides
 *    with `display: none`, so the control exists and its click handler runs
 *    while it has no painted size — acting on it programmatically is the
 *    supported path, and forcing the corner visible would fight the very
 *    layout that hides it.
 * 2. Only when that surface is absent (hosts without ui-sidebar-right) do we
 *    fall back to the third-party explorer column, which needs its
 *    `data-aionui-explorer-open` marker; the preview sheet is yielded first
 *    because compat.css gives preview precedence over explorer.
 *
 * Returns true when the official sidebar took the action, so callers can skip
 * their own layout work (the host panel covers the frame on its own).
 */
function openFilesPanel(doc = document, frame = (0, phone_chrome_ts_1.getFrame)()) {
    // Toggle semantics, because the host swaps controls with the panel state
    // (measured on 0.1.5): while the panel is CLOSED the only opener is
    // `data-sidebar-right-expand`; once it is OPEN that element is unmounted and
    // only `data-sidebar-right-toggle` (Collapse right sidebar) remains. Acting
    // on the expand button alone was a no-op whenever the panel happened to be
    // already open — the exact "tap does nothing / position looks wrong" report.
    // Closing first also keeps the control reachable: the full-screen panel
    // covers the header, so a second tap could never reach our button.
    const closer = doc.querySelector(exports.HOST_FILES_CLOSER);
    const opener = doc.querySelector(exports.HOST_FILES_OPENER);
    const hostControl = typeof opener?.click === 'function' ? opener : closer;
    // Duck-typed on purpose: any element exposing click() acts as the control.
    if (typeof hostControl?.click === 'function') {
        hostControl.click();
        return true;
    }
    if (frame === null)
        return false;
    frame.removeAttribute('data-aionui-preview-open');
    frame.setAttribute('data-aionui-explorer-open', '');
    return false;
}

};
__modules["styles/styles.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.COMPOSER_AND_STATS_CSS = void 0;
/**
 * 提取自 dsh-web-mobile 的紧凑化输入框与统计胶囊排版规则
 */
exports.COMPOSER_AND_STATS_CSS = "/* ==========================================================================\n   dsh-web-mobile: 输入框与底部性能用量组件相关样式\n   来源: src/client/styles/layout.css.ts & src/client/styles/compat.css.ts\n   ========================================================================== */\n\n/* 1. 活跃会话下的输入框紧凑化 (layout.css.ts) */\n[data-mobile-nav=\"frame\"] [data-phase=\"active\"] [data-composer-card] {\n  padding-top: 2px !important;\n  gap: 4px !important;\n}\n[data-mobile-nav=\"frame\"] [data-phase=\"active\"] [data-composer-card] [class*=\"_row\"] {\n  padding: 0 8px !important;\n}\n[data-mobile-nav=\"frame\"] [data-phase=\"active\"] [data-composer-card] [data-composer-input],\n[data-mobile-nav=\"frame\"] [data-phase=\"active\"] [data-composer-card] [class*=\"_scroll\"] {\n  min-height: 28px !important;\n  padding-top: 2px !important;\n}\n\n/* 2. 统计胶囊排版：Activity (轮次/步数/TPS) 与 Usage (Token/缓存命中) (compat.css.ts) */\n[data-composer-stat=\"activity\"] {\n  flex: 0 0 auto !important;\n  width: max-content !important;\n  min-width: max-content !important;\n  max-width: 60% !important;\n  font-size: 10.5px !important;\n  line-height: 18px !important;\n  margin-right: 4px !important;\n  white-space: nowrap !important;\n}\n[data-composer-stat=\"activity\"] button,\n[data-composer-stat=\"activity\"] span,\n[data-composer-stat=\"activity\"] svg {\n  font-size: 10.5px !important;\n}\n[data-composer-stat=\"activity\"] button {\n  padding: 1px 5px !important;\n  gap: 4px !important;\n}\n[data-composer-stat=\"activity\"] svg {\n  width: 13px !important;\n  height: 13px !important;\n  flex: 0 0 auto !important;\n}\n\n[data-composer-stat=\"usage\"] {\n  flex: 0 1 auto !important;\n  min-width: 0 !important;\n  max-width: 50% !important;\n  font-size: 10.5px !important;\n  line-height: 18px !important;\n  overflow: hidden !important;\n  white-space: nowrap !important;\n}\n[data-composer-stat=\"usage\"] button,\n[data-composer-stat=\"usage\"] span,\n[data-composer-stat=\"usage\"] svg {\n  font-size: 10.5px !important;\n}\n[data-composer-stat=\"usage\"] button {\n  padding: 1px 5px !important;\n  gap: 4px !important;\n  min-width: 0 !important;\n  max-width: 100% !important;\n  overflow: hidden !important;\n}\n[data-composer-stat=\"usage\"] svg {\n  width: 13px !important;\n  height: 13px !important;\n  flex: 0 0 auto !important;\n}\n[data-composer-stat=\"usage\"] [class*=\"_label\"],\n[data-composer-stat=\"usage\"] button > span {\n  flex: 0 1 auto !important;\n  min-width: 0 !important;\n  overflow: hidden !important;\n  text-overflow: ellipsis !important;\n  white-space: nowrap !important;\n}\n\n/* 3. 上下文用量环：浮层化绝对定位并隐藏文本（仅留16px圆环） (compat.css.ts) */\n[data-mobile-nav=\"stats-ring\"] {\n  position: absolute !important;\n  flex: 0 0 auto !important;\n  display: inline-flex !important;\n  align-items: center !important;\n  min-width: 0 !important;\n  margin: 0 2px 0 0 !important;\n  padding: 0 !important;\n  background: transparent !important;\n  box-shadow: none !important;\n  border: 0 !important;\n}\n[data-mobile-nav=\"stats-ring\"],\n[data-mobile-nav=\"stats-ring\"] * {\n  font-size: 0 !important;\n}\n[data-mobile-nav=\"stats-ring\"] button {\n  padding: 0 !important;\n  margin: 0 !important;\n  gap: 0 !important;\n  min-width: 0 !important;\n  width: auto !important;\n  background: transparent !important;\n  box-shadow: none !important;\n  border: 0 !important;\n}\n[data-mobile-nav=\"stats-ring\"] svg {\n  display: inline-block !important;\n  width: 16px !important;\n  height: 16px !important;\n  flex: 0 0 auto !important;\n}\n\n/* 4. 插件自建占位符与定位基准上下文 (compat.css.ts) */\n[data-mobile-nav=\"stats-ring-reserve\"],\n[data-mobile-nav=\"stats-tps-reserve\"] {\n  visibility: hidden !important;\n  pointer-events: none !important;\n}\n[data-mobile-nav=\"stats-ring-reserve\"] {\n  flex: 0 0 auto !important;\n  display: inline-block !important;\n  width: 16px !important;\n  height: 16px !important;\n  margin: 0 2px 0 0 !important;\n  padding: 0 !important;\n}\n[data-mobile-nav=\"stats-tps\"] {\n  display: flex !important;\n  flex-flow: row nowrap !important;\n  align-items: center !important;\n  margin: 0 !important;\n  padding: 0 !important;\n  font-size: 10px !important;\n  line-height: 18px !important;\n  white-space: nowrap !important;\n  overflow: hidden !important;\n  text-overflow: ellipsis !important;\n}\n[data-mobile-nav=\"stats-tps\"] * {\n  white-space: nowrap !important;\n}\n[data-mobile-nav=\"stats-tps\"] span {\n  overflow: hidden !important;\n  text-overflow: ellipsis !important;\n  min-width: 0 !important;\n}\n[data-mobile-nav=\"stats-ring-dock\"],\n[data-mobile-nav=\"stats-tps-row\"] {\n  position: relative;\n}\n";

};
__modules["index.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.inject = void 0;
exports.apply = apply;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
const stats_line_ts_1 = require("./effects/stats-line.js");
const sidebar_swipe_ts_1 = require("./effects/sidebar-swipe.js");
const open_files_panel_ts_1 = require("./components/open-files-panel.js");
const styles_ts_1 = require("./styles/styles.js");
/** 声明 Cordis 客户端服务依赖（侧边栏开关需要调用 ctx.layout.toggleSidebar） */
exports.inject = ['layout'];
/**
 * 移动端轻量功能总装入口：
 * 1. 注入输入框紧凑化与用量环浮层 CSS；
 * 2. 挂载 stats-line 动态计算与局部 MutationObserver 调度任务；
 * 3. 挂载左滑/右滑调出侧边栏的双向手势引擎。
 */
function apply(ctx) {
    // 1. 挂载紧凑化与用量环样式表
    ctx.effect(() => {
        const tagId = 'dsh-mobile-lite-styles';
        for (const stale of document.querySelectorAll(`style[data-plugin-css="${tagId}"]`)) {
            stale.remove();
        }
        const style = document.createElement('style');
        style.dataset.pluginCss = tagId;
        style.textContent = styles_ts_1.COMPOSER_AND_STATS_CSS;
        document.head.appendChild(style);
        return () => {
            style.remove();
        };
    }, 'dsh-mobile-lite: inject styles');
    // 2. 挂载功能 1：输入框与底部性能/用量组件布局优化 (Stats-line)
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-mobile-lite: stats-line task', () => {
        const statsTask = (0, stats_line_ts_1.createStatsLineTask)();
        statsTask.ensure();
        return () => {
            statsTask.dispose();
        };
    });
    // 3. 挂载功能 2：左右两侧侧边栏滑动手势 (Sidebar & Files Swipe)
    (0, sidebar_swipe_ts_1.installSidebarSwipe)(ctx, () => (0, open_files_panel_ts_1.openFilesPanel)());
}

};
var __cache = {};
function __localRequire(id) {
  if (id.charCodeAt(0) !== 46) return require(id);
  id = id.slice(2);
  var cached = __cache[id];
  if (cached) return cached.exports;
  var module = { exports: {} };
  __cache[id] = module;
  __modules[id](__localRequire, module, module.exports);
  return module.exports;
}
var module = { exports: {} };
__modules["index.js"](__localRequire, module, module.exports);
return module.exports; } });
