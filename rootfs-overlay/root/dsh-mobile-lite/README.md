# DSH Mobile Lite (移动端轻量极简复用插件)

本项目将从 `dsh-web-mobile` 提取出的核心功能以**极简、干净、零额外依赖**的方式整合为独立的微型 DSH 客户端插件（编译后 `lib/client.js` 仅 90KB）。

---

## 一、 功能特性总览

1. **输入框紧凑化与留白优化**：消除原生输入框在手机端高达 29px 的无效纵向留白，确保单行输入卡片高度控制在 78px，扩大消息流可视区域。
2. **底部性能与用量单排紧凑自适应 (v1.0.1)**：
   - **会话统计组件**（`activity`）：保留轮数、步数与速度的字间正常空格，彻底移除冗余的 `·` 分隔符，展示格式为：`[图标] x轮 y步 ztok/s`；
   - **Token 用量组件**（`usage`）：精简无意义文字（将 `缓存命中` 简化为 `命中`），去除 `·` 分隔符，保留正常空格，展示格式为：`[图标] xMtok 命中y%`；
   - **上下文用量环**（`ContextMeter`）：取消原绝对定位浮层外移与文字隐藏方案，**同排流式回流至底部 Dock 第一排**，展示格式为：`[圆环] x%`；
   - 三者在 360px 极限窄屏下自适应并列一排，彻底杜绝换行挤占下方模型看板。
3. **原生线条风格文件上传触发器 (v1.0.1)**：
   - 在输入框左侧工具行（`_modes` 权限按钮右侧）就地注入轻量回形针上传按钮；
   - 样式与尺寸（28×28px、`var(--dsw-radius-sm, 6px)` 圆角、无边框、14px SVG）严格对齐官方权限盾牌按钮；
   - 点击直通宿主原生隐藏的 `input[type=file]`，保留完整的系统级文件选择与校验机制。
4. **对话页双向滑动手势引擎**：
   - **左边缘右滑**（屏幕左侧 45% 区域）：滑出/收起左侧抽屉式会话列表；
   - **右边缘左滑**（屏幕右侧 45% 区域）：调出/收起右侧 Files 工作区文件树；
   - **8px 轴锁定**、**轻拂速度惯性判定（Fling）**、**代码块横滑避让**与**释放后防点击穿透守卫（Click Swallowing）**。
5. **双向环境隔离与防桌面污染**：
   - 样式层通过 `@media (max-width: 1023px) and (pointer: coarse)` 原生媒体查询严格约束，保证桌面端与大屏设备完全不被移动端规则污染。

---

## 二、 核心文件清单与作用位置深度剖析

```text
dsh-mobile-lite/
├── package.json                          # DSH 插件规范清单 (v1.0.1)
├── cordis.patch.yml                      # DSH 插件注册元信息
├── tsconfig.json                         # 宿主端 TypeScript 配置
├── tsconfig.client.json                  # 客户端 TypeScript 编译配置
├── scripts/
│   └── build-client.mjs                  # 打包打包脚本（生成 DSH ModuleLoader 格式）
├── lib/
│   ├── index.js                          # 宿主端编译产物
│   └── client.js                         # 浏览器端打包产物（90KB 最终交付件）
└── src/
    ├── index.ts                          # 宿主端入口（最小空插件）
    └── client/
        ├── index.ts                      # 客户端总装入口（挂载样式、手势与任务）
        ├── runtime.d.ts                  # 本地 ClientContext 类型垫片
        ├── core/
        │   └── reconciler-core.ts        # 极简任务契约接口声明（ReconcilerTask）
        ├── styles/
        │   ├── composer-and-stats.css    # 纯 CSS 样式定义（含输入框紧凑化、单排 Dock、文件上传样式）
        │   ├── file-upload.css           # 独立文件上传按钮样式定义
        │   └── styles.ts                 # 将 CSS 包装为 TS 字符串导出
        ├── components/
        │   └── open-files-panel.ts       # 右侧 Files 触发器
        └── effects/
            ├── stats-line.ts             # 性能用量单排自适应、文字精简与 DOM 局部观察器
            ├── file-upload.ts            # 原生文件上传触发按钮注入器
            ├── sidebar-swipe.ts          # 双向滑动手势引擎
            ├── gesture-guard.ts          # 手势冲突避让与点击吞噬守卫
            └── phone-chrome.ts           # 轻量桥接层（断点检测、外壳打标与清理）
```

---

### 详细文件作用解析表

| 文件路径 | 对应提取文件 | 作用的具体 DOM 位置 / 区域 | 解决的核心问题与技术实现机制 |
| :--- | :--- | :--- | :--- |
| **`src/client/effects/stats-line.ts`** | `stats-line.ts` | **输入框底部 Dock 统计区**：<br>1. 监听 `[class*="_composerStack"]`<br>2. 统计打标：`[data-mobile-nav="stats"]`<br>3. 圆环打标：`[data-mobile-nav="stats-ring"]` | **1. 单排流式排布**：解除原浮层绝对定位，将上下文用量环保留在 dock 内与会话统计、Token 用量同排并列。<br>**2. 文字精简规整**：隐藏 `·` 分隔符后确保第一段文本末尾保留自然空格；使用 `TreeWalker` 将 `缓存命中` 精简为 `命中`。<br>**3. 窄范围局部调度（`attachScope`）**：在 `[class*="_composerStack"]` 上挂载专属 `MutationObserver`，避开全局消息流高频刷新的开销。 |
| **`src/client/effects/file-upload.ts`** | 新增核心 | **输入框工具栏**：<br>`[data-composer-card] [class*="_modes"]` | **1. 就地锚定注入**：在权限按钮（`_modes`）右侧动态插入轻量级回形针按钮，点击直接派发宿主原生隐藏 `input[type=file]` 的 `click` 事件。<br>**2. 零侵入复用**：完整沿用宿主底层的文件分片上传、进度监听与类型校验逻辑。 |
| **`src/client/styles/composer-and-stats.css`**<br>（及 `styles.ts`） | `composer-and-stats.css` | **输入框与底部 Dock**：<br>1. `[data-composer-card]`<br>2. `[data-composer-stat="activity"]`<br>3. `[data-composer-stat="usage"]`<br>4. `[data-mobile-nav="stats-ring"]`<br>5. `[data-mobile-nav="file-upload"]` | **1. 输入框紧凑化**：活跃会话下强制 `padding-top: 2px !important; gap: 4px !important; min-height: 28px !important;`。<br>**2. 胶囊单排锁定**：`activity`（50%）、`usage`（45%）、`stats-ring`（auto）统一设定为 22px 高度与 11px 字号，垂直居中对齐；隐藏 `_sep`。<br>**3. 按钮规范对齐**：文件按钮设定为 28×28px、`var(--dsw-radius-sm, 6px)` 圆角与透明底色，与加号、权限按钮整齐对齐。<br>**4. 原生媒体查询隔离**：全部规则包裹在 `@media (max-width: 1023px) and (pointer: coarse)` 下，桌面端零污染。 |
| **`src/client/effects/sidebar-swipe.ts`** | `sidebar-swipe.ts` | **全页面全局手势捕捉**：<br>`document` 上的 `pointerdown`、`pointermove`、`pointerup`、`touchmove`（捕获阶段 `capture: true`） | **1. 双族手势路由（Dual Family）**：屏幕左侧 45% 归为抽屉手势，右侧 45% 归为 Files 手势；抽屉打开时内部向左拖动判定为关闭。<br>**2. 8px 轴锁定（`tryLock`）**：水平占优则锁定横滑并阻止纵向滚动；垂直占优则让位给页面原生滚动。<br>**3. 双重裁决标准**：滑动距离超过屏幕宽度的 16% 或瞬时速度达到 `0.45 px/ms`（轻拂 Fling）触发。<br>**4. 动作执行**：左侧调用 `ctx.layout.toggleSidebar()`，右侧调用 `filesToggleFn()`。 |
| **`src/client/components/open-files-panel.ts`** | `open-files-panel.ts` | **顶栏及右侧边栏控件**：<br>`[data-sidebar-right-expand]`<br>`[data-sidebar-right-toggle]` | **右侧文件树触发器**：通过 `click()` 触发宿主原生的右侧栏展开与收起按钮；旧版回退至修改外壳属性 `data-aionui-explorer-open`。 |
| **`src/client/effects/gesture-guard.ts`** | `gesture-guard.ts` | **全局手势冲突拦截器** | **1. 冲突避让**：在弹窗模态、全屏遮罩、代码块横滑、划词选择或悬浮拖拽时主动让出手势。<br>**2. 吞噬点击穿透**：手势释放后，在 300ms 窗口期内拦截接下来的合成 `click` 事件。 |
| **`src/client/effects/phone-chrome.ts`** | 轻量胶水层 | **`AppFrame` 外壳根节点**：<br>`[class*="frame"]:has(> [class*="sidebarCol"])` | **1. 响应式监听**：监听 `(max-width: 1023px) and (pointer: coarse)`。<br>**2. 外壳打标与持续保活**：在首帧及异步渲染期间确保为 frame 注入 `data-mobile-nav="frame"`。<br>**3. 节点定位**：导出 `getFrame()` 与 `isMobileViewport()` 供各组件安全寻址。 |
| **`src/client/core/reconciler-core.ts`** | 极简类型文件 | 编译期类型 | 导出 `ReconcilerTask` 接口，使任务模块保持原样而无需引入原工程的复杂调度核心。 |
| **`src/client/index.ts`** | 客户端总装入口 | DSH Client 运行时 | 声明依赖 `inject: ['layout']`，挂载样式、启动 `statsTask`、安装 `sidebarSwipe` 并武装 `fileUpload`。 |
| **`src/index.ts`** | 宿主端入口 | DSH 服务端 Loader | 纯客户端 UI 插件的标准空入口，保证 DSH 加载器安全注册。 |

---

## 三、 构建与安装使用说明

### 1. 本地重新编译构建
在项目根目录下直接运行（已配置好免依赖的打包链路）：
```bash
npm run build
```
执行过程会自动完成两步：
1. `tsc` 编译 TypeScript；
2. `scripts/build-client.mjs` 将所有子模块组装成单文件 `lib/client.js`，包含标准的 `window.__ModuleLoader__.load({ id: "dsh-mobile-lite", factory: ... })` 包裹层。

### 2. 在 DSH 中加载运行
将 `dsh-mobile-lite` 作为一个本地插件或者安装到 DSH 的插件目录中。DSH 启动时会自动扫描并在前端加载 `lib/client.js`。

### 3. 真机验证方法
1. 在手机浏览器或 DSHA 内打开 Web 页面；
2. **手势测试**：
   - 从屏幕**左侧边缘 45% 内向右滑动**，左侧会话抽屉平滑滑出；
   - 在抽屉上向左滑，抽屉平滑收起；
   - 从屏幕**右侧边缘 45% 内向左滑动**，右侧 Files 文件树面板展开；
3. **输入框与统计测试 (v1.0.1)**：
   - 打开任意会话，输入框卡片紧贴底部且上下留白明显变窄；
   - 权限盾牌按钮右侧出现原生线条风格的回形针文件按钮，点击能调起系统文件选择器；
   - 底部第一排并列显示会话统计（`8轮 58步 103tok/s`）、Token 用量（`1.2Mtok 命中85%`）与上下文圆环（`15%`），三者紧凑并列且无换行。
