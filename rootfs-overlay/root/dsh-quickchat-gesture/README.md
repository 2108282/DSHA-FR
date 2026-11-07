# DSH QuickChat Gesture (全面屏手势与侧滑返回分发中枢插件)

本项目是一个专为移动端与 Android 宿主打造的极简、轻量、高灵敏 DSH 客户端手势分发插件（编译后 `lib/client.js` 仅 16KB）。

核心解决在移动端全面屏边缘侧滑返回（或系统 `onBackPressed`）时，网页内各层级浮层（设置弹窗、抽屉侧边栏、文件栏、插件市场与详情页、自动化任务看板等）无法按照用户直觉层层退回，甚至导致系统直接退出应用或刷新页面的核心痛点。

---

## 一、 核心特性与设计哲学

1. **全面屏返回分发中枢（`triggerMobileBack`）**：
   - 开放全局函数 `window.triggerMobileBack()`（兼容别名 `window.__dshTriggerMobileBack()`），专供手机系统侧滑手势或原生 Android 容器通过 `WebView.evaluateJavascript` 高频调用；
2. **零门控无条件直绑全局**：
   - 严禁套在响应式媒体查询（如 `MOBILE_QUERY`）或延迟初始化函数中；无论在竖屏、横屏或折叠屏任意视口下，插件在浏览器端加载的第一毫秒立即直绑 `window`，确保 Native 随时可调不丢事件；
3. **六层智能优先级侦测（Cascade Priority）**：
   - 深度感知 DSH 宿主内部多层 DOM 树与组件状态，自动识别各浮层当前层级，自顶向下精准关闭；
4. **插件二级子页面精准回退（Subpage Prioritized Back）**：
   - 特别针对插件市场、插件管理器、设置项的二级页面（如面包屑、配置详情、更新日志、确认弹窗）做优先捕获，先退回插件主列表，再次返回才退回会话区；
5. **硬件加速平滑退出动效（280ms Smooth Exit）**：
   - 触发抽屉与右侧文件栏退出时，预先执行硬件加速平滑滑出动画，避免生硬瞬切带来的视觉撕裂感；
6. **严密的返回值契约**：
   - 消费并关闭上层界面统一返回 `true`（Native 拦截不退出）；处于最底层普通对话页返回 `'none'`（通知 Native 执行系统级退出或退回后台）。

---

## 二、 目录结构

```text
dsh-quickchat-gesture/
├── package.json                         # 插件清单（声明依赖、导出与客户端注入）
├── cordis.patch.yml                     # 插件注册元信息
├── .gitignore                           # Git 忽略配置
├── tsconfig.json                        # 宿主端 TypeScript 配置
├── tsconfig.client.json                 # 客户端 TypeScript 编译配置
├── scripts/
│   └── build-client.mjs                 # 打包组装脚本（生成 DSH ModuleLoader 格式）
├── lib/
│   ├── index.js                         # 宿主端编译产物
│   ├── types/index.d.ts                 # 类型导出
│   └── client.js                        # 浏览器端独立打包产物（仅 16KB，最终交付件）
└── src/
    ├── index.ts                         # 宿主端入口（纯客户端插件标准空入口）
    └── client/
        ├── index.ts                     # 客户端入口（声明 inject: ['layout'] 并挂载）
        ├── runtime.d.ts                 # 运行时与 Context 类型声明
        ├── core/
        │   └── frame.ts                 # 宿主外壳 DOM 节点寻址定位工具
        └── effects/
            └── mobile-back-dispatcher.ts # 全面屏侧滑返回分发核心状态机
```

---

## 三、 六层返回层级识别状态机

| 优先级 | 判定目标 | 页面关闭后的真实 DOM 状态（防误判关键） | 执行的关闭操作 | 函数返回值 |
| :---: | :--- | :--- | :--- | :---: |
| **1** | **设置页 / 模态框** | **真正卸载（Unmounted）**：关闭后 React 直接 `removeChild` 移除节点 | 若设置内部有二级配置子页面（带面包屑返回键）优先退回设置主项；否则派发 Escape 退出设置弹窗 | `true` |
| **2** | **左侧会话抽屉** | **依然常驻 DOM**：关闭后节点仍在，但外壳挂有 `data-sidebar-collapsed`，以属性为准 | 播放平滑滑出动画（`translateX(-110%)`），280ms 后切换折叠 | `true` |
| **3** | **右侧文件栏** | **依然常驻 DOM**：关闭后节点仍在，但推到 `100vw` 外或隐藏，以屏幕坐标为准 | 播放平滑滑出动画（`translateX(100%)`），280ms 后触发收起 | `true` |
| **4** | **插件管理器 / 插件市场** | **二级详情 ➔ 主列表 ➔ 对话页**：分层逐级退回 | ① **处于二级/下级页面**（如插件详情页 `[data-plugin-detail]`、配置子页、更多列表 `moreHead`、或带 `button[class*="crumb"]` 面包屑）：**精准点击返回键退回插件主列表**；<br>② **处于插件主列表页**（无二级返回键）：**退出整个插件页面退回对话区**；<br>③ **处于第三方插件市场**（`dshmarket`）：内部有子弹窗先关子弹窗，有返回键先点返回键，主页关闭市场 | `true` |
| **5** | **自动化任务页** | **属性卸载**：激活时根元素挂有 `html[data-dsh-taskboard-active]`，退出即移除 | 点击看板退出按钮或调用 `history.back()` | `true` |
| **6** | **普通对话页** | **最底层的常驻容器**：以上所有浮层均未打开 | **不进行任何操作** | `'none'` |

---

## 四、 Android Native 端接入指南

### 1. Java 范例代码

在宿主 Activity 的 `onBackPressed` 或侧滑手势回调中注入执行：

```java
public class MainActivity extends AppCompatActivity {
    private WebView webView;

    private static final String SCRIPT_TRIGGER_MOBILE_BACK =
            "(function() {\n" +
            "    try {\n" +
            "        if (typeof window.triggerMobileBack === 'function') {\n" +
            "            return String(window.triggerMobileBack());\n" +
            "        }\n" +
            "        if (typeof window.__dshTriggerMobileBack === 'function') {\n" +
            "            return String(window.__dshTriggerMobileBack());\n" +
            "        }\n" +
            "    } catch (e) {\n" +
            "        console.error('triggerMobileBack error:', e);\n" +
            "    }\n" +
            "    return 'none';\n" +
            "})()";

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(SCRIPT_TRIGGER_MOBILE_BACK, value -> {
                // evaluateJavascript 返回的结果为带引号的 JSON 字符串，例如 "\"true\"" 或 "\"none\""
                String clean = value != null ? value.replace("\"", "").trim() : "none";
                if ("true".equalsIgnoreCase(clean)) {
                    // 已经被前端消费（关闭了浮层、抽屉、文件栏或插件二级页），宿主无需处理
                    return;
                }
                // 返回值为 none：处于最底层普通会话界面，由系统接管（退到后台或退出）
                moveTaskToBack(true);
            });
            return;
        }
        super.onBackPressed();
    }
}
```

### 2. Kotlin 范例代码

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    private val scriptTriggerMobileBack = """
        (function() {
            try {
                if (typeof window.triggerMobileBack === 'function') {
                    return String(window.triggerMobileBack());
                }
                if (typeof window.__dshTriggerMobileBack === 'function') {
                    return String(window.__dshTriggerMobileBack());
                }
            } catch (e) {
                console.error('triggerMobileBack error:', e);
            }
            return 'none';
        })()
    """.trimIndent()

    override fun onBackPressed() {
        webView.evaluateJavascript(scriptTriggerMobileBack) { rawValue ->
            val result = rawValue?.replace("\"", "")?.trim() ?: "none"
            if (result.equals("true", ignoreCase = true)) {
                // 已被前端消费，忽略本次返回动作
                return@evaluateJavascript
            }
            // 处于最底层普通会话，退至后台
            moveTaskToBack(true)
        }
    }
}
```

---

## 五、 构建与安装调试

### 1. 本地重新编译构建
在项目根目录下执行（免额外依赖的极简编译链路）：
```bash
npm run build
```
产物将直接输出至 `lib/client.js` 与 `lib/index.js`。

### 2. 在 DSH 中加载运行
将 `dsh-quickchat-gesture` 拷贝到 DSH 的插件工作目录或作为本地依赖引入。DSH 启动时会自动扫描并在前端加载 `lib/client.js`。

### 3. 浏览器端自测方法
在手机或 PC 浏览器的 DevTools 控制台中直接执行：
```js
window.triggerMobileBack()
```
- 若打开了左侧抽屉，抽屉将平滑滑出并折叠，返回 `true`；
- 若进入了插件详情页，将退回插件列表页，返回 `true`；
- 若处于普通对话页，控制台将返回 `'none'`。
