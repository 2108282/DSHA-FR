# dsh-classic-chat

> **DeepSeek Harness（`dsh`）经典极速对话窗插件（All-in-One 终极合一版）**  
> 物理屏蔽 0.2.2+ 新版引起的全局回流与流式卡顿，完整沿用 0.2.1-rc.5 稳健的会话装配与排版视口，深度集成 `dsh-smooth-stream` 二阶阻尼弹簧与打字机平滑流引擎，并原生兼容未来协议新特性。

---

## 一、插件的作用与核心价值

随着 DeepSeek Harness 升级至 `0.2.2+`，官方 Web 前端为了实现复杂的树状折叠动效（`flow-motion` 与 `group-tree`），引入了高频的 DOM 几何重排与测量。在大模型高速输出（30~60 tokens/s）时，移动端及轻量设备上极易触发严重的**全局回流雪崩（Reflow Storm）**，导致界面严重掉帧、卡死甚至浏览器崩溃。

本插件的作用：
1. **彻底终结 0.2.2+ 对话流式卡死**：
   在 Cordis 内核层物理屏蔽官方卡顿的 `ui-conversation` 和 `ui-chat`，换上经过优化与修补的经典极速架构，杜绝 DOM 几何暴力测量；
2. **解除官方 3 帧（~50ms）人工延迟锁**：
   消除原版在 Token 分发中硬性嵌套的 3 层 `requestAnimationFrame` 死锁，恢复 60/120fps 原生单帧极速分发；
3. **全量继承 `dsh-smooth-stream` 平滑流引擎**：
   集成动态打字机揭示、二阶阻尼弹簧视口跟随与 GPU 合成层位移，带来如提词器般温润流淌的阅读质感；
4. **深度向前兼容 0.2.2+ 新协议**：
   底层原生容错 `ignorable: true` 试验性事件与 `outcome.kind === 'abandoned'` 中途废弃流，保证未来底层系统随意升级，对话窗永不报错。

---

## 二、插件构建结构

本插件遵循 DSH 标准的 Cordis 双端插件架构（Node.js 宿主端 + 浏览器前端），整体工程完全自包含：

```
dsh-classic-chat/
├── cordis.patch.yml              # 核心补丁：物理屏蔽官方卡顿组件，挂载本插件
├── package.json                  # 模块元数据、标准 exports 映射与依赖解耦
├── README.md                     # 项目核心说明文档
├── LICENSE                       # MIT 许可证
├── lib/                          # 预编译生产运行目录
│   ├── index.js                  # 宿主端入口：提供 uiConversation/conversation 服务，处理 /smooth-stream RPC
│   ├── client.js                 # 浏览器端核心：包含 0.2.1 会话装配 + 聊天视口 + 平滑流前端 (1.65 MB)
│   └── client.js.map             # 完整 SourceMap 调试映射
├── src/                          # 全套完整未压缩 TypeScript/TSX 源码 (120+ 文件)
│   ├── client/                   # 打字机视图、二阶弹簧引擎、设置面板、CSS 模块
│   │   ├── TypewriterAssistantNodeView.tsx
│   │   ├── TypewriterToolNodeView.tsx
│   │   ├── SmoothStreamCard.tsx
│   │   └── ...
│   ├── index.ts                  # Host 端源码
│   ├── plugin.ts                 # 配置与路由注册源码
│   └── settings.ts               # 设置持久化定义
├── benchmarks/                   # 性能与流式基准测试套件
├── tests/                        # 单元与集成测试用例
└── docs/                         # 演示与原理说明文档
```

### 各核心文件分工职责

1. **`cordis.patch.yml`**：
   - 将官方的 `ui-conversation` 与 `ui-chat` 显式标记为 `disabled: true`，彻底阻断其加载；
   - 插入 `classic-chat` 作为单线条目全权接管。
2. **`lib/index.js`（Host 宿主端）**：
   - 正规调用 `ctx.provide("uiConversation")` 和 `ctx.provide("conversation")`，为全系统提供核心会话数据总线；
   - 在 `httpCtx.webServer` 上完整注册 `STREAM_SETTINGS_RPC_CHANNEL`（即 `/smooth-stream`）HTTP POST 路由，处理设置读取、持久化写入与实时调试面板数据；
   - 挂载 `tapIndex`，在 HTML 首页加载时注入 `window.__DSH_SMOOTH_STREAM_BOOT__` 物理引擎配置。
3. **`lib/client.js`（浏览器前端）**：
   - 顺序注册并激活 0.2.1 会话装配器（`UiConversation`）、聊天视口（`AssistantNodeView`）与平滑流引擎（`dsh-smooth-stream`）；
   - 内存动态解除 3 帧 rAF 嵌套为单帧调度；
   - 拦截事件分发器，遇到 0.2.2 的 `ignorable: true` 自动吞掉，遇到 `abandoned` 流废弃自动清空瞬态草稿；
   - 注入 CSS 严格局部隔离（`contain: layout paint style !important`），切断回流蔓延。

---

## 三、开发与构建踩坑实录（关键避坑复盘）

在从“想法”落地为“真实开箱即用插件”的过程中，经历了一系列硬核排错，以下是关键踩坑复盘：

### 坑 1：缺少 `exports["./client"]` 导致前端打包器暴毙
- **报错现象**：
  ```
  AggregateError: client-modules: 1 client package failed to compose:
  - client-modules: dsh-classic-chat declares dsh.client but exports no "./client" bundle
  at ClientModuleRegistry.resolveMeta (dsh-client-modules/lib/index.js:738)
  ```
- **原因剖析**：
  DSH 的 `@deepseek-ai/dsh-client-modules` 在 Node.js 启动时会解析所有声明了 `dsh.client` 的插件。它要求 `package.json` 中必须显式声明 Node 标准的 `exports["./client"]`。若缺失该字段，整个 `clientModules` 服务直接当场 Crash，连带所有前端包全军覆没。
- **解决手段**：
  在 `package.json` 中严格补充标准导出映射：
  ```json
  "exports": {
    ".": { "default": "./lib/index.js" },
    "./client": { "default": "./lib/client.js" },
    "./package.json": "./package.json"
  }
  ```

---

### 坑 2：物理屏蔽底层组件导致 10 个业务插件连坐挂起
- **报错现象**：
  ```
  web boot: 10 entries did not activate
  @deepseek-ai/dsh-client-ui-workflow-run: pending (waiting for service: uiConversation)
  @deepseek-ai/dsh-client-ui-skill: pending (waiting for service: uiConversation)
  @deepseek-ai/dsh-client-ui-schedule: pending (waiting for services: conversation, uiConversation)
  ...
  ```
- **原因剖析**：
  `ui-conversation` 是全系统公用的数据总线服务提供方。系统中有 10 个核心业务插件（工作流、定时任务、审批、技能等）在依赖中声明了必须等待 `uiConversation` 与 `conversation` 服务。当我们在补丁中将其设为 `disabled: true` 时，如果不自己在 Host 端接管提供该服务，这 10 个插件就会全部因等待服务而永久挂起。
- **解决手段**：
  在插件的 `lib/index.js`（Host 侧）中，使用 Cordis 原生 API 正式提供这两个服务：
  ```javascript
  ctx.provide("uiConversation", { name: "classic-ui-conversation", active: true });
  ctx.provide("conversation", { name: "classic-conversation", active: true });
  ```

---

### 坑 3：前端聚合依赖自环引起的死锁（Deadlock）
- **报错现象**：
  ```
  web boot: 11 entries did not activate
  ...
  dsh-classic-chat: pending (waiting for service: uiConversation)
  ```
- **原因剖析**：
  在前端聚合打包时，由于将原版 `ui-chat` 的 `inject` 列表（包含 `"uiConversation"`）无脑放入了 `dsh-classic-chat` 的导出 `inject` 数组中。这导致 Cordis 认为：*“在激活 `dsh-classic-chat` 之前，必须先有外部提供 `uiConversation`”*；但 `uiConversation` 偏偏就在 `dsh-classic-chat` 的 `apply` 函数内生成！从而触发“自己等待自己”的循环死锁。
- **解决手段**：
  在 `lib/client.js` 导出声明中，彻底过滤掉由自己负责初始化的内部服务：
  ```javascript
  const inject = [...new Set(rawInject)].filter(s => s !== "uiConversation" && s !== "conversation");
  ```

---

### 坑 4：遗漏 Host RPC 路由导致设置无法保存、调试面板调不出
- **报错现象**：
  设置面板显示“未保存 / 本部署没有接受这些值，已保留供你修改”；勾选“显示渲染调试面板”置灰提示“当前 Host 版本不支持实时调试，请先更新插件 Host”；平滑流未能完全生效。
- **原因剖析**：
  `dsh-smooth-stream` 的前端设置卡片在点击“保存”或读取调试数据时，是通过 HTTP POST 向后台发送 `/smooth-stream/write` 和 `/smooth-stream/debugRead` 的。如果服务端只写了一个空 `apply`，未挂载完整的 570 行 Host 路由逻辑，请求就会全部 404；且前端因无法确认配置有效性，导致平滑流引擎处于安全回退状态。
- **解决手段**：
  将原版 `dsh-smooth-stream/lib/index.js` 的全量 Host 端逻辑（包含 `/smooth-stream` 路由注册、`saveSettings` 磁盘持久化、`injectStreamConfig` 页面全局变量注入）完整并入 `lib/index.js`。

---

## 四、`dsh-smooth-stream` 平滑流全量功能详解

本插件完整集成了 `dsh-smooth-stream`（v0.6.2）的全部物理渲染特性，将**内容呈现**与**视口运动**拆分为两个独立的物理状态机，在每一帧动画回调（rAF）中连续积分：

```
[ 模型 SSE 分块流 ]
         │
         ▼
 ┌─────────────────┐       背压阻尼 (0.55x ~ 1.0x)       ┌─────────────────┐
 │   揭示节奏引擎   │ ◄──────────────────────────────── │   弹簧跟随引擎   │
 │ (Reveal Engine) │                                   │ (Follow Engine) │
 └────────┬────────┘                                   └────────┬────────┘
          │ 字符积压与分数积分                                      │ 二阶阻尼弹簧积分 (k=130, c=24)
          ▼                                                     ▼
 [ 逐帧平滑展开 DOM ] ───────────────────────────────► [ 合成层 Transform 补偿 ]
  (单帧视觉位移 ≤ 8px)                                   (0 Reflow / 纯 GPU 合成)
```

### 1. 动态自适应的揭示引擎（Reveal Engine）
- **分数级字符积分**：根据积压队列深度自适应调节速度（$v = 90 + \text{backlog}^{1.25} \times P$），低负载时从容自然，高积压时平稳追赶，绝不倾泻整段文本；
- **折行限幅平滑**：长文本和代码块发生换行时，单帧视觉位移被限制在 8px 以内，将原本 24~28px 的单帧跳跃平摊至数帧完成；
- **尾部平稳收束**：回合结束标记到达后，残余缓冲区以恒定速率释放，正文、思考链与工具调用之间自然衔接。

### 2. 纯合成层驱动的跟随引擎（Follow Engine）
- **二阶阻尼物理系统**：采用 $k=130, c=24, m=1$ 的亚步进物理弹簧，将内容高度的变化转化为平滑连续的速度与位移轨迹；
- **零重排（Zero-Reflow）位移补偿**：真实滚动容器始终锚定在底部，剩余的视觉滞后完全由外层 DOM 的 `transform: translate3d` 吸收。跟随过程不读写任何触发 Layout 的属性，彻底杜绝重排风暴；
- **闭环背压控制（Closed-Loop Backpressure）**：当跟随滞后接近预留空间时，反向向揭示引擎施加阻尼（最低降至 0.55 倍速），防止文本增长超出视口弹簧范围；
- **掉帧自愈与跨刷新率一致**：主线程卡顿（Stall）时自动钳位物理时间（$\le 32\text{ms}$），避免画面恢复后的突进瞬移；在 60Hz 与 120Hz 屏幕下保持一致的物理收敛时间。

### 3. 会话生命周期自动折叠（Turn Auto-Collapse）
- 对话进行时，思考链与工具调用保持实时展开；
- 回合结算完成并稳定后，自动将执行过程收敛为一行极简的耗时条，保持工作区专注；支持随时点击无缝展开。

### 4. 串行接力架构（Baton Relay Queue）
大模型在复杂 Agent 循环中经历的三个阶段：**思考链（Reasoning） → 前置正文回复（Response Text） → 工具调用与执行（Tool Execution）**。引擎以统一的物理阻尼在三个阶段之间无缝交接，避免不同阶段交接时视口上下跳动。

---

## 五、安装与启用指南

### 1. 克隆与链接
```bash
# 克隆到本地 plugin-src 目录
git clone https://github.com/2108282/dsh-classic-chat.git /root/.dsh/plugin-src/dsh-classic-chat

# 链接至 web profile
cd /root/.dsh/profiles/web
pnpm add link:/root/.dsh/plugin-src/dsh-classic-chat
```

### 2. 在 Profile Bundles 中声明
在 `$DSH_HOME/profiles/web/package.json` 的 `dsh.profile.bundles` 数组中加入本插件：
```json
"bundles": [
  "@deepseek-ai/dsh-base",
  "@deepseek-ai/dsh-web-app",
  ... 其他插件 ...
  "dsh-classic-chat"
]
```
> **注意**：由于本插件已自包含完整的 `dsh-smooth-stream`，**无需再在 bundles 中单独声明 `dsh-smooth-stream`**。

### 3. 重启生效
重新启动 DSH 服务并刷新浏览器，设置即可正常保存，调试面板可自由调出，对话输出体验彻底恢复丝滑稳定！

---

## 许可证

[MIT License](LICENSE)
