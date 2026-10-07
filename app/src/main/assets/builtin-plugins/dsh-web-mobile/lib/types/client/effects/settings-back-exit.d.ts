import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/**
 * Android system back gesture (popstate) integration for the settings modal.
 *
 * When the settings sheet mounts (either by tapping the drawer trigger or via
 * shortcuts), this effect pushes one poppable history entry without altering
 * the URL. When the user performs Android's system-level edge swipe-back gesture
 * or presses the back button, the browser emits popstate; we catch it, close
 * the settings sheet via its native close button or Escape key, and keep the user
 * on the conversation page instead of exiting the web app.
 */
export declare function installSettingsBackExit(ctx: ClientContext): void;
//# sourceMappingURL=settings-back-exit.d.ts.map