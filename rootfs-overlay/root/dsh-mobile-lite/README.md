# DSH Mobile Lite (移动端轻量极简复用插件)

本项目将从 `dsh-web-mobile` 提取出的两大核心功能以**100% 原样复用、零算法修改**的方式整合为独立、干净、零额外依赖的微型 DSH 客户端插件（编译后 `lib/client.js` 仅 90KB）。

---

## 一、 功能特性总览

1. **输入框紧凑化与留白优化**：消除原生输入框在手机端高达 29px 的无效纵向留白，确保单行输入卡片高度控制在 78px，扩大消息流可视区域。
2. **底部性能与用量单行紧凑自适应**：
   - 保持 `activity`（轮次/步数/速度）与 `usage`（Token用量/缓存命中）在第一排单行自适应，防止数字增大折成两排挤压界面；
   - 采用**绝对定位浮层重叠（Overlay）**方案，将原生上下文用量环（ContextMeter）无缝移到输入框右侧工具簇，**隐藏冗余文字、只留圆环**，同时**绝对不挪动 React DOM 节点层级**，彻底杜绝 React 重连/卸载时的白屏崩溃（规避 #104 陷阱）。
3. **对话页双向滑动手势引擎**：
   - **左边缘右滑**（屏幕左侧 45% 区域）：滑出/收起左侧抽屉式会话列表；
   - **右边缘左滑**（屏幕右侧 45% 区域）：调出/收起右侧 Files 工作区文件树；
   - **8px 轴锁定**、**轻拂速度惯性判定（Fling）**、**代码块横滑避让**与**释放后防点击穿透守卫（Click Swallowing）**。

---

## 二、 核心文件清单与作用位置深度剖析

本项目严格按照提取代码的原生相对引用组织目录结构，保证提取出的核心文件**一行代码都不需要改动**：

```text
dsh-mobile-lite/
├── package.json                          # DSH 插件规范清单
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
        │   ├── composer-and-stats.css    # 【提取件】纯 CSS 样式定义
        │   └── styles.ts                 # 将 CSS 包装为 TS 字符串导出
        ├── components/
        │   └── open-files-panel.ts       # 【提取件】右侧 Files 触发器
        └── effects/
            ├── stats-line.ts             # 【提取件】性能用量定位与 DOM 观察器
            ├── sidebar-swipe.ts          # 【提取件】双向滑动手势引擎
            ├── gesture-guard.ts          # 【提取件】手势冲突避让与点击吞噬守卫
            └── phone-chrome.ts           # 轻量桥接层（断点检测、外壳打标与清理）
```

---

### 详细文件作用解析表

| 文件路径 | 对应提取文件 | 作用的具体 DOM 位置 / 区域 | 解决的核心问题与技术实现机制 |
| :--- | :--- | :--- | :--- |
| **`src/client/effects/stats-line.ts`** | `feature1-stats-line.ts` | **输入框卡片堆叠区**：<br>1. 监听 `[class*="_composerStack"]`<br>2. 占位注入：`[data-composer-card] [class*="_row"] [class*="_trailing"]`<br>3. 宿主圆环打标：`[data-mobile-nav="stats-ring"]` | **1. 规避 #104 崩溃陷阱**：React 渲染的 DOM 节点绝不能通过 `appendChild` 移出原有父级，否则组件卸载或网络重连时 React 找不到节点会报 `NotFoundError` 导致输入框整块白屏。该文件在输入框右侧只注入 16px 的隐藏占位（`stats-ring-reserve`），原生圆环保留在原位置。<br>**2. 绝对定位叠放（`placeOverlay`）**：实时计算占位符与父容器的相对坐标，动态写入 `style.left` 和 `style.top`，让原生圆环精准浮在输入框右侧工具栏空位上。<br>**3. 窄范围局部调度（`attachScope`）**：在 `[class*="_composerStack"]` 上挂载专属 `MutationObserver`，避开消息流高频刷新的开销；并在 `visualViewport.resize`（软键盘弹起/旋转屏幕）时自动重算坐标。 |
| **`src/client/styles/composer-and-stats.css`**<br>（及 `styles.ts`） | `feature1-composer-and-stats.css` | **输入框与底部 Dock**：<br>1. `[data-composer-card]`<br>2. `[data-composer-stat="activity"]`<br>3. `[data-composer-stat="usage"]`<br>4. `[data-mobile-nav="stats-ring"]` | **1. 输入框紧凑化**：在 `[data-phase="active"]` 活跃会话下，强制 `padding-top: 2px !important; gap: 4px !important; min-height: 28px !important;`，横向内边距和按钮（28/34px）热区保持不变。<br>**2. 胶囊单行自适应**：`activity`（步数/速度）设为 `max-width: 60%` 自适应；`usage`（Token/缓存）设为 `max-width: 50%`，超出自动出省略号，绝不折入第二排。<br>**3. 上下文环隐藏文字**：对 `[data-mobile-nav="stats-ring"]` 全树设置 `font-size: 0 !important;`（塌掉所有百分比文字，只留 16px SVG 圆环本身），并清除宿主画的厚重灰底，只留简洁环形进度。 |
| **`src/client/effects/sidebar-swipe.ts`** | `feature2-sidebar-swipe.ts` | **全页面全局手势捕捉**：<br>`document` 上的 `pointerdown`、`pointermove`、`pointerup`、`touchmove`（捕获阶段 `capture: true`） | **1. 双族手势路由（Dual Family）**：按触摸起点 `clientX` 划分，屏幕左侧 45% 归为抽屉手势，右侧 45% 归为 Files 手势；抽屉打开时内部向左拖动判定为关闭。<br>**2. 8px 轴锁定（`tryLock`）**：移动满 8px 时裁决主轴，水平占优则锁定横滑并调用 `event.preventDefault()` 阻止纵向滚动；垂直占优则释放让位给页面原生滚动。<br>**3. 双重裁决标准**：滑动距离超过屏幕宽度的 16% 或瞬时速度达到 `0.45 px/ms`（轻拂 Fling）即判定触发。<br>**4. 动作执行**：左侧调用 `ctx.layout.toggleSidebar()`，右侧调用 `filesToggleFn()`。 |
| **`src/client/components/open-files-panel.ts`** | `feature2-open-files-panel.ts` | **顶栏及右侧边栏控件**：<br>`[data-sidebar-right-expand]`<br>`[data-sidebar-right-toggle]` | **右侧文件树触发器**：<br>通过 `click()` 触发宿主原生的右侧栏展开与收起按钮；在无按钮的旧版上自动回退至修改外壳属性 `data-aionui-explorer-open`。 |
| **`src/client/effects/gesture-guard.ts`** | `feature2-gesture-guard.ts` | **全局手势冲突拦截器** | **1. 冲突避让**：在弹窗模态（`[aria-modal="true"]`）、全屏遮罩、代码块内部横滑、划词选择文本或悬浮拖拽时主动让出手势。<br>**2. 吞噬点击穿透（`markGestureConsumed`）**：手势释放后，在 300ms 窗口期内拦截接下来的合成 `click` 事件，防止手指松开时误触底下的会话行或操作按钮。 |
| **`src/client/effects/phone-chrome.ts`** | 轻量胶水层 | **`AppFrame` 外壳根节点**：<br>`[class*="frame"]:has(> [class*="sidebarCol"])` | **1. 响应式监听**：监听 `(max-width: 1023px) and (pointer: coarse)`。<br>**2. 外壳打标与清理**：匹配移动端时自动在 frame 上设置 `data-mobile-nav="frame"`，使 CSS 选择器精准生效；退出或卸载时自动移除属性。<br>**3. 节点定位**：导出 `getFrame()` 供各组件安全寻址。 |
| **`src/client/core/reconciler-core.ts`** | 极简类型文件 | 编译期类型 | 导出 `ReconcilerTask` 接口，使 `stats-line.ts` 保持原样而无需引入原工程的调度核心。 |
| **`src/client/index.ts`** | 客户端总装入口 | DSH Client 运行时 | 声明依赖 `inject: ['layout']`，通过 `ctx.effect` 挂载样式、启动 `statsTask.ensure()` 并调用 `installSidebarSwipe()`。 |
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
3. **输入框与统计测试**：
   - 打开任意会话，输入框卡片紧贴底部且上下留白明显变窄；
   - 底部 `activity`（步数/速度）和 `usage`（Token用量）紧凑排在同一排；
   - 原生上下文环（绿色/蓝色圆环）移至输入框右侧（发送按钮旁），只留干净圆环，文字自动隐藏。
