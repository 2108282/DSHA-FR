/**
 * 提取自 dsh-web-mobile 的紧凑化输入框与统计胶囊排版规则
 */
export const COMPOSER_AND_STATS_CSS = `/* ==========================================================================
   dsh-web-mobile: 移动端紧凑化样式（严格遵循上游 @media 媒体查询契约）
   ========================================================================== */

@media (max-width: 1023px) and (pointer: coarse) {
  /* 1. 活跃会话下的输入框紧凑化 */
  [data-phase="active"] [data-composer-card] {
    padding-top: 2px !important;
    gap: 4px !important;
  }
  [data-phase="active"] [data-composer-card] [class*="_row"] {
    padding: 0 8px !important;
  }
  [data-phase="active"] [data-composer-card] [data-composer-input],
  [data-phase="active"] [data-composer-card] [class*="_scroll"] {
    min-height: 28px !important;
    padding-top: 2px !important;
  }

  /* 2. 底部 Dock 容器单排紧凑化排布 */
  [data-composer-dock],
  [class*="_dock"] {
    gap: 4px !important;
    justify-content: center !important;
    align-items: center !important;
  }
  [data-slot="conversation.composer.dock"] {
    display: contents !important;
  }

  /* 3. 统计胶囊排版：Activity (轮次/步数/TPS) 与 Usage (Token/缓存命中) */
  [data-composer-stat="activity"] {
    flex: 0 0 auto !important;
    width: max-content !important;
    min-width: max-content !important;
    max-width: 50% !important;
    font-size: 10.5px !important;
    line-height: 18px !important;
    margin-right: 4px !important;
    white-space: nowrap !important;
  }
  [data-composer-stat="activity"] button,
  [data-composer-stat="activity"] span,
  [data-composer-stat="activity"] svg {
    font-size: 10.5px !important;
  }
  [data-composer-stat="activity"] button {
    padding: 1px 5px !important;
    gap: 4px !important;
  }
  [data-composer-stat="activity"] svg {
    width: 13px !important;
    height: 13px !important;
    flex: 0 0 auto !important;
  }

  [data-composer-stat="usage"] {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    max-width: 45% !important;
    font-size: 10.5px !important;
    line-height: 18px !important;
    margin-right: 4px !important;
    overflow: hidden !important;
    white-space: nowrap !important;
  }
  [data-composer-stat="usage"] button,
  [data-composer-stat="usage"] span,
  [data-composer-stat="usage"] svg {
    font-size: 10.5px !important;
  }
  [data-composer-stat="usage"] button {
    padding: 1px 5px !important;
    gap: 4px !important;
    min-width: 0 !important;
    max-width: 100% !important;
    overflow: hidden !important;
  }
  [data-composer-stat="usage"] svg {
    width: 13px !important;
    height: 13px !important;
    flex: 0 0 auto !important;
  }
  [data-composer-stat="usage"] [class*="_label"],
  [data-composer-stat="usage"] button > span {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
  }

  /* 4. 彻底隐藏移动端的 · 分隔符 */
  [data-composer-stat] [class*="_sep"] {
    display: none !important;
  }

  /* 5. 上下文用量环：同排流式紧凑排布（保留 10.5px 文本与 13px 圆环） */
  [data-mobile-nav="stats-ring"] {
    position: static !important;
    flex: 0 0 auto !important;
    display: inline-flex !important;
    align-items: center !important;
    min-width: 0 !important;
    margin: 0 !important;
    padding: 0 !important;
    background: transparent !important;
    box-shadow: none !important;
    border: 0 !important;
    font-size: 10.5px !important;
    line-height: 18px !important;
  }
  [data-mobile-nav="stats-ring"] button,
  [data-mobile-nav="stats-ring"] span {
    font-size: 10.5px !important;
    line-height: 18px !important;
  }
  [data-mobile-nav="stats-ring"] button {
    padding: 1px 5px !important;
    gap: 3px !important;
    min-width: 0 !important;
    width: auto !important;
  }
  [data-mobile-nav="stats-ring"] svg {
    display: inline-block !important;
    width: 13px !important;
    height: 13px !important;
    flex: 0 0 auto !important;
  }

  /* 6. 文件上传按钮移动端适配 */
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

  [data-composer-card] [data-mobile-nav="file-upload"]:hover {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06)) !important;
  }

  [data-composer-card] [data-mobile-nav="file-upload"]:active {
    background: var(--dsw-alias-interactive-bg-active, rgba(0, 0, 0, .12)) !important;
  }

  [data-composer-card] [data-mobile-nav="file-upload"] svg {
    width: 14px !important;
    height: 14px !important;
    display: block !important;
    flex: none !important;
    stroke: currentColor !important;
  }

  [data-composer-card] [data-mobile-nav="file-upload"]:disabled {
    opacity: 0.38;
    cursor: default;
  }

  [data-composer-card]:not(:has(input[type=file])) [data-mobile-nav="file-upload"] {
    display: none !important;
  }

  /* ==========================================================================
     7. 输入框内部控件：尺寸 / 间距 / 收缩
        逐条移植自 dsh-web-mobile 的 layout.css.ts 中「Composer bottom row on
        mobile」与「Composer file entry」两节，按需求剔除全部上下文圆环与 TPS
        相关规则（aria-haspopup="dialog" 与 JObwrW_ 家族一律不搬）。
        本段在文件末尾：同特异性下覆盖上面第 1、6 段对 _row / 附件按钮的定义。
        宿主 0.2.2-rc.1 实测类名族：uV2eYG_（card/row/tools/modes/trailing/
        add/primary/standardControls）、iWlSmW_（权限 trigger/triggerIcon/
        triggerLabel）、7KE1Ra_（模型座位 root/trigger/triggerLabel）。
        本文件是模板字符串：注释里禁止出现反引号与美元花括号。
     ========================================================================== */

  /* 7.1 底行容器：强制单排 + 紧凑间距（宿主原生 gap 是 12px） */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) {
    box-sizing: border-box;
    container-type: inline-size;
    container-name: dsh-mobile-composer;
    flex-wrap: nowrap;
    gap: 3px;
    padding-left: 6px;
    padding-right: 6px;
    /* 行内挂着绝对定位的下拉菜单，这里裁剪会把菜单切掉 */
    overflow: visible;
  }
  /* 子代理视图的 [停止][发送] 双主键，单排必溢出：只对这一形态恢复官方换行 */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) {
    flex-wrap: wrap;
  }

  /* 7.2 左侧工具道 [+][权限][📎]：间距单独放宽到 8px（右簇仍 3px） */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] {
    flex: 0 1 auto;
    min-width: 0;
    /* 盒间距 0：三个控件都是 28×28 盒 + 16px 图标，ink 两侧各内缩 6px，
       所以 ink 间距 = 盒间距 + 12。盒间距 0 ⇒ ink 间距 12px，
       即原版「权限 ↔ 上传附件」那一档（店主 2026-11-11：按它对齐，别更宽）。
       dsh-web-mobile 用 8px 是因为它左侧控件只有两个、且权限钮还带 ⌄，
       搬进三连排会整体发散。要更松只动这一个数值：2px ⇒ ink 14px。 */
    gap: 0;
    /* 权限下拉向上弹，裁剪会切掉菜单 */
    overflow: visible;
  }

  /* 7.3 权限模式容器：gap 4 → 0，把宽度让给 trigger */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] {
    flex: 0 1 auto;
    min-width: 0;
    max-width: none;
    gap: 0;
    overflow: visible;
  }

  /* 7.4 权限触发器：宿主自带 padding:0 4px 0 8px + gap:4px，图标化后全是浪费，
         归零后 44px 盒收成约 34px。宿主的 _trigger 外面套了一层 display:contents
         包装，直接子代锚点会漏，所以这里用后代锚点。 */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] button[class*="_trigger"] {
    /* 必须是 0 0 auto：给 flex-grow 会把按钮拉宽，图标与 ⌄ 被推到两端，
       中间凭空多出一道空当（真机 2026-11-11 实测约 18px）。 */
    flex: 0 0 auto;
    min-width: 28px;
    max-width: 100%;
    display: flex !important;
    justify-content: center;
    overflow: hidden;
    padding: 0 !important;
    gap: 0 !important;
  }

  /* 7.5 权限文字可省略：屏幕变窄时先掉字，再把宽度让给模型胶囊 */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] [class*="_trigger"] [class*="_triggerLabel"] {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }

  /* 7.6 权限图标 14 → 16px，与 + / 📎 视觉对齐。箭头不在 triggerIcon 内，
         不会被一起放大；盒子 28×28 不变（16 仍有余量）。 */
  [data-composer-card] [class*="_modes"] [class*="_triggerIcon"] svg {
    width: 16px !important;
    height: 16px !important;
    flex: 0 0 auto;
  }

  /* 7.7 极窄屏（< 360px）：只留盾牌图标，文字整体隐藏 */
  @container dsh-mobile-composer (max-width: 359px) {
    [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] [class*="_trigger"] [class*="_triggerLabel"] {
      display: none !important;
    }
  }

  /* 7.8 非 trigger 座位（如实时计划 chip）：保留固有宽度，别被压成图标大小 */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > :not([class*="_trigger"]) {
    flex: 0 1 auto;
    min-width: 34px;
    max-width: max-content;
    overflow: visible;
  }
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_wrap"] > [class*="_chip"] {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }

  /* 7.9 收缩豁免：+ 与发送键是固定命中区，永不参与挤压；其余成员先收缩 */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > :not([class*="_add"]) {
    flex-shrink: 1;
    min-width: 0;
  }
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > :not([class*="_primary"]) {
    flex-shrink: 1;
    min-width: 0;
  }
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_add"] {
    flex: none;
  }
  /* + 号图标宿主写死 14px（IconPlusOutlineMedium size:14），而权限与 📎 都是 16px。
     盒子都是 28×28 时，ink 宽度差 2px 会让 ink 间距差 1~2px（21 vs 20），
     统一到 16 之后左簇三点等距。盒子尺寸不变，触控区不受影响。 */
  [data-composer-card] [class*="_add"] svg {
    width: 16px !important;
    height: 16px !important;
  }
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_primary"] {
    flex: none;
  }

  /* 7.10 右簇（模型胶囊 + 发送键）焊在右缘：整簇 flex-end 兜底，
          余量由模型根节点的 margin-left:auto 吸收（自适应留白永远落在工具道与胶囊之间） */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] {
    /* 必须是 none（宿主原值），不能用 dsh-web-mobile 的 1 1 auto：
       宿主 0.2.2 的 observeControlRow 用「子元素真实宽度之和 vs 可用宽度」来判定
       是否切 data-model-compact（模型文字塌成图标）。车道一旦可增长/可压缩，
       量出来的 needed 永远约等于 available，判定恒为"装得下"，文字改用省略号收场，
       于是出现「有时图标、有时完整、有时半截」的抖动。flex:none 让判定回到干净的二分。
       贴右由行的 space-between（宿主）+ margin-left:auto 保证，不依赖 flex-grow。 */
    flex: none;
    min-width: 0;
    gap: 3px;
    overflow: visible;
    justify-content: flex-end;
  }
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"]) > :first-child {
    margin-left: auto;
  }
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) {
    flex: 0 1 auto;
    min-width: 0;
    margin-left: auto;
    /* 原版的 -4px 是为紧贴上下文环做的补偿；本插件行内没有环，回到 0，
       与发送键之间只留车道 gap 3px */
    margin-right: 0;
    overflow: visible;
  }
  @container dsh-mobile-composer (max-width: 359px) {
    [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) {
      flex-basis: auto;
    }
  }
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) > [class*="_trigger"] {
    display: flex !important;
    width: 100%;
    max-width: 100%;
    min-width: 0;
    overflow: hidden;
  }
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) > [class*="_trigger"] > [class*="_triggerLabel"] {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  /* 右簇里非模型的座位（如麦克风）保持固有尺寸 */
  [data-phase] [data-composer-card]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"]):not(:has(> [class*="_trigger"][aria-haspopup="menu"])) {
    flex: 0 0 auto;
  }

  /* 7.11 附件按钮 📎：与左簇另外两个控件统一到宿主的 28×28 节奏。
         历史：原本照搬 dsh-web-mobile 的 34×34 触控盒 + margin-left:-11px。
         那套数值在它那里成立，是因为它左侧只有 📎 自己、且同一行的发送键
         本身是 34×34；搬进「[+][权限][📎]」三连排后立刻暴露两个问题：
           · 盒子 34 而 + 与权限都是 28，_tools 是 align-items:center，
             于是 📎 上下各多出 3px（高度看着不齐）；
           · -11px 是给「权限钮还带 ⌄、盒宽约 34-36」准备的补偿，
             权限钮收成 28 且去掉 ⌄ 之后它过量，把 📎 拽近 9px
             （ink 间距：+ → 权限 21px，权限 → 📎 只有 12px）。
         现在：盒 28×28、margin 归零、视觉胶囊铺满 28（与 + 同大），
         触控区由 ::after 外扩 4px 保住 36×36 —— 视觉小、手指好按，原意不丢。 */
  [data-composer-card] [data-mobile-nav="file-upload"] {
    flex: 0 0 auto !important;
    width: 28px !important;
    min-width: 28px !important;
    max-width: 28px !important;
    height: 28px !important;
    min-height: 28px !important;
    max-height: 28px !important;
    padding: 0 !important;
    margin: 0 !important;
    position: relative !important;
    display: grid !important;
    place-items: center;
    border: 0 !important;
    border-radius: 8px;
    background: transparent;
    color: inherit;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }
  [data-composer-card] [data-mobile-nav="file-upload"] svg {
    width: 16px !important;
    height: 16px !important;
    display: block !important;
    flex: none !important;
    stroke: currentColor !important;
  }
  /* 视觉胶囊铺满 28×28，与 + 号的悬停胶囊同尺寸（inset 0，不再缩 3px） */
  [data-composer-card] [data-mobile-nav="file-upload"]::before {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: 999px;
    background: transparent;
    transition: background .12s ease;
  }
  [data-composer-card] [data-mobile-nav="file-upload"]:hover::before,
  [data-composer-card] [data-mobile-nav="file-upload"]:active::before {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
  }
  /* 命中区外扩：::after 属于按钮本身，一起参与命中测试，视觉完全不变。
     盒间距已经是 0，所以只外扩 2px（28 → 32px），再多就会盖住左侧权限钮的点击区。 */
  [data-composer-card] [data-mobile-nav="file-upload"]::after {
    content: '';
    position: absolute;
    inset: -2px;
    border-radius: 12px;
  }

  /* 7.12 输入区宿主自己渲染的功能键不再叠灰胶囊（宿主已有 :hover 底色），
          只压掉浏览器默认的淡蓝 tap 高亮 */
  [data-composer-card] button,
  [data-composer-card] [role="button"],
  [data-composer-card] [tabindex] {
    -webkit-tap-highlight-color: transparent;
  }

  /* 7.13 权限控件收成「一个图标」。
         宿主结构（dsh-client-ui-permission-presets 0.2.2-rc.1）是
           button.iWlSmW_trigger > span[triggerIcon 14px] + span[triggerLabel] + span[chevron]
         文字标签宿主自己在 @container (width<=460px) 里已经隐藏，剩下的 ⌄ 只是视觉噪音。
         这里把触发器钉成 28×28 方形（与 + 同尺寸）、内容居中、⌄ 隐藏。
         作用域刻意跟着宿主的 460px 门槛：更宽的屏幕上文字标签还在，箭头要留着当提示。
         锚点用「_modes 里的 button[class*=_trigger]」，不用 iWlSmW_ 哈希；
         模型触发器的 class 里也有 _trigger，但它带 aria-haspopup=menu，这里排除掉，
         两者不会互相误伤（权限触发器没有 aria-haspopup，实测 2026-11-11）。 */
  @container dsh-mobile-composer (max-width: 460px) {
    [data-composer-card] [class*="_modes"] button[class*="_trigger"]:not([aria-haspopup="menu"]) {
      flex: 0 0 auto !important;
      width: 28px !important;
      min-width: 28px !important;
      max-width: 28px !important;
      height: 28px !important;
      padding: 0 !important;
      gap: 0 !important;
      justify-content: center;
    }
    [data-composer-card] [class*="_modes"] button[class*="_trigger"]:not([aria-haspopup="menu"]) > [class*="_chevron"] {
      display: none !important;
    }
  }
}
`
