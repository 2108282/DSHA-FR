import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';

/**
 * 原生文件上传触发器：使用 MutationObserver 锚定 composer 卡片，
 * 注入触发宿主 hidden input 的原生按钮。
 */
export function installFileUpload(ctx: ClientContext) {
  ctx.effect(() => {
    const PAPERCLIP_SVG = `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><g transform="rotate(-45 12 12)"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></g></svg>`;

    const observer = new MutationObserver(() => {
      const card = document.querySelector('[data-composer-card]');
      if (!card || card.querySelector('[data-mobile-nav="file-upload"]')) return;

      const hostInput = card.querySelector('input[type=file]') as HTMLInputElement | null;
      if (!hostInput) return;

      const btn = document.createElement('button');
      btn.type = 'button';
      btn.dataset.mobileNav = 'file-upload';
      btn.innerHTML = PAPERCLIP_SVG;
      btn.ariaLabel = '上传文件';
      btn.title = '上传文件';
      
      btn.onclick = (e) => {
        e.preventDefault();
        e.stopPropagation();
        hostInput.click();
      };

      // 寻找工具栏容器
      const tools = card.querySelector('[class*="_tools"]') || card.querySelector('[class*="_modes"]');
      if (tools) {
        tools.appendChild(btn);
      }
    });

    observer.observe(document.body, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, 'dsh-mobile-lite: file-upload injector');
}
