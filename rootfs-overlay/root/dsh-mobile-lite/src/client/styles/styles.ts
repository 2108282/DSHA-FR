/**
 * 提取自 dsh-web-mobile 的紧凑化输入框与统计胶囊排版规则
 */
export const COMPOSER_AND_STATS_CSS = `/* ==========================================================================
   dsh-web-mobile: 移动端紧凑化样式（遵循原生 @media 契约）
   ========================================================================== */

/* 移动端专属规则：严格限制在小屏触屏设备 */
@media (max-width: 1023px) and (pointer: coarse) {
  /* ... (其他紧凑化样式不变) ... */

  /* 文件上传按钮移动端适配 */
  [data-composer-card] [data-mobile-nav="file-upload"] {
    display: inline-flex !important;
    align-items: center !important;
    justify-content: center !important;
    width: 28px !important;
    height: 28px !important;
    min-width: 28px !important;
    min-height: 28px !important;
    padding: 0 !important;
    margin: 0 !important;
    border: 0 !important;
    border-radius: var(--dsw-radius-sm, 6px) !important;
    background: transparent !important;
    color: var(--dsw-alias-label-secondary, currentColor) !important;
    cursor: pointer !important;
    flex: none !important;
    -webkit-tap-highlight-color: transparent !important;
  }
}

/* 桌面端/非移动端：显式禁用移动端特有样式，恢复原生 */
@media not (max-width: 1023px) and (pointer: coarse) {
  [data-composer-card] [data-mobile-nav="file-upload"] {
    /* 移除移动端强制属性，恢复 auto */
    width: auto !important;
    height: auto !important;
  }
}
`
