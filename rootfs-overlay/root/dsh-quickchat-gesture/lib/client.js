window.__ModuleLoader__.load({ id: "dsh-quickchat-gesture", factory: (require) => {
var __modules = {};
__modules["core/frame.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.findFrame = findFrame;
exports.getFrame = getFrame;
/**
 * 获取 AppFrame 外壳根节点：
 * 依次尝试插件打标的选择器、shell.overlay 父节点、以及带有 sidebarCol 的类名匹配。
 */
function findFrame() {
    if (typeof document === 'undefined')
        return null;
    const overlay = document.querySelector('[data-shell-overlay]');
    if (overlay?.parentElement)
        return overlay.parentElement;
    return document.querySelector('[data-mobile-nav="frame"], [class*="pI_x6G_frame"], [class*="frame"]:has(> [class*="sidebarCol"])');
}
function getFrame() {
    if (typeof document === 'undefined')
        return null;
    return document.querySelector('[data-mobile-nav="frame"]') ?? findFrame();
}

};
__modules["effects/mobile-back-dispatcher.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.closeLeftDrawerAnimated = closeLeftDrawerAnimated;
exports.closeRightSidebarAnimated = closeRightSidebarAnimated;
exports.triggerMobileBack = triggerMobileBack;
exports.installMobileBackDispatcher = installMobileBackDispatcher;
const frame_ts_1 = require("./core/frame.js");
/**
 * 带有平滑退出动画关闭左侧抽屉
 */
function closeLeftDrawerAnimated(ctx) {
    const frame = (0, frame_ts_1.getFrame)();
    const drawer = frame?.querySelector(':scope > [class*="sidebarCol"], [class*="sidebarCol"]');
    const mask = frame?.querySelector('[class*="drawerMask"], [data-sidebar-mask]');
    if (!drawer) {
        if (ctx)
            ctx.layout.toggleSidebar();
        return;
    }
    // 1. 抽屉平滑滑出屏幕外
    drawer.style.transition = 'transform 0.28s var(--ds-ease-in-out, ease-in-out)';
    drawer.style.transform = 'translateX(-110%)';
    if (mask) {
        mask.style.transition = 'opacity 0.28s var(--ds-ease-in-out, ease-in-out)';
        mask.style.opacity = '0';
    }
    // 2. 动画结束（280ms）后正式提交宿主折叠状态
    setTimeout(() => {
        drawer.style.transition = '';
        drawer.style.transform = '';
        if (mask) {
            mask.style.transition = '';
            mask.style.opacity = '';
        }
        if (frame && !frame.hasAttribute('data-sidebar-collapsed')) {
            if (ctx) {
                ctx.layout.toggleSidebar();
            }
            else {
                const toggleBtn = document.querySelector('[data-mobile-nav="toggle"], [data-sidebar-toggle]');
                toggleBtn?.click();
            }
        }
    }, 280);
}
/**
 * 关闭右侧 Files 面板：直接触发官方原生平滑收起过渡，杜绝多重平移与闪烁
 */
function closeRightSidebarAnimated() {
    const panel = document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open], [data-sidebar-right-open]');
    const closer = panel?.querySelector('[data-sidebar-right-toggle]') ||
        document.querySelector('[data-sidebar-right-toggle], button[aria-label*="收起右侧边栏"], button[aria-label*="Collapse right sidebar"]');
    if (typeof closer?.click === 'function') {
        closer.click();
    }
    else {
        dispatchEscape();
    }
}
/**
 * 安全派发 Escape 键盘事件
 */
function dispatchEscape() {
    if (typeof document === 'undefined')
        return;
    const event = typeof KeyboardEvent !== 'undefined'
        ? new KeyboardEvent('keydown', {
            key: 'Escape',
            code: 'Escape',
            keyCode: 27,
            which: 27,
            bubbles: true,
            cancelable: true,
        })
        : { key: 'Escape', bubbles: true };
    document.dispatchEvent(event);
}
/**
 * 关闭设置模态弹窗
 */
function closeSettingsModal() {
    dispatchEscape();
    const closeBtn = document.querySelector('[data-shortcut-modal="settings"] button:has(svg), [role="dialog"][data-shortcut-modal="settings"] button:has(svg)');
    closeBtn?.click();
}
/**
 * 全面屏手势响应核心：根据当前顶层页面自动触发对应退出动作
 *
 * @param ctx 插件上下文（可选）
 * @returns
 *  - true: 消费了返回手势（关闭了设置/抽屉/文件栏/插件页/自动化任务等）
 *  - 'none': 处于底层的【普通对话页】，不工作
 */
function triggerMobileBack(ctx) {
    // =========================================================================
    // 1. 检测【设置页 / 模态框】是否处于最上层
    // =========================================================================
    const settingsModal = document.querySelector('[data-shortcut-modal="settings"], [role="dialog"][data-shortcut-modal="settings"], [data-shortcut-modal="shortcuts"]');
    if (settingsModal && getComputedStyle(settingsModal).display !== 'none') {
        // 优先检测设置弹窗内部是否处于二级子页面（如具体插件的配置表单、面包屑返回）
        const settingsSubBack = settingsModal.querySelector('button[class*="crumb"], button[class*="back"], button[aria-label*="返回"], button[aria-label*="Back"], [class*="detailTop"] > button');
        if (settingsSubBack && getComputedStyle(settingsSubBack).display !== 'none' && getComputedStyle(settingsSubBack).visibility !== 'hidden') {
            settingsSubBack.click();
            return true;
        }
        closeSettingsModal();
        return true;
    }
    const genericModal = document.querySelector('[role="dialog"][aria-modal="true"]');
    if (genericModal && getComputedStyle(genericModal).display !== 'none' && !genericModal.querySelector('[data-dsh-market-root]')) {
        dispatchEscape();
        return true;
    }
    // =========================================================================
    // 2. 检测【左侧会话抽屉】是否处于最上层
    // （同时兼顾 frame 属性与原生 sidebarCol 可见性）
    // =========================================================================
    const frame = (0, frame_ts_1.getFrame)();
    const hasCollapsedAttr = frame ? frame.hasAttribute('data-sidebar-collapsed') : true;
    const drawerEl = document.querySelector('[class*="sidebarCol"]');
    let drawerVisible = false;
    if (drawerEl) {
        const cs = getComputedStyle(drawerEl);
        drawerVisible = cs.display !== 'none' && cs.visibility !== 'hidden' && cs.transform === 'none';
    }
    if ((frame && !hasCollapsedAttr) || drawerVisible) {
        closeLeftDrawerAnimated(ctx);
        return true;
    }
    // =========================================================================
    // 3. 检测【右侧文件栏 / 侧边栏】是否处于最上层
    // =========================================================================
    const openRightPanel = document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open], [data-sidebar-right-open]');
    if (openRightPanel) {
        const cs = getComputedStyle(openRightPanel);
        const rect = openRightPanel.getBoundingClientRect();
        const inViewport = (rect.width > 0 && rect.right > 0 && rect.left < window.innerWidth) ||
            openRightPanel.offsetWidth > 0;
        const isVisible = cs.display !== 'none' && cs.visibility !== 'hidden';
        if (isVisible && inViewport) {
            closeRightSidebarAnimated();
            return true;
        }
    }
    if (frame?.hasAttribute('data-aionui-explorer-open')) {
        frame.removeAttribute('data-aionui-explorer-open');
        return true;
    }
    // =========================================================================
    // 4. 检测【插件市场 / 插件管理 / 全局主面板】是否处于最上层
    // =========================================================================
    // 4.1 独立弹层型插件市场 (dsh-market)
    const marketRoot = document.querySelector('[data-dsh-market-root]');
    if (marketRoot && getComputedStyle(marketRoot).display !== 'none') {
        // 检查市场内部是否有打开的模态子弹层 (如评论、更新日志、确认弹窗)
        const marketModal = document.querySelector('[data-dsh-market-portal] [role="dialog"], [data-dsh-market-root] [role="dialog"], [class*="confirmModal"], [class*="notesModal"], [role="dialog"]:has([class*="groupAddModal"])');
        if (marketModal && getComputedStyle(marketModal).display !== 'none') {
            const modalClose = marketModal.querySelector('button[aria-label*="关闭"], button[aria-label*="Close"], button[class*="close"], button:has(svg)');
            if (modalClose) {
                modalClose.click();
            }
            else {
                dispatchEscape();
            }
            return true;
        }
        // 检查市场内部是否有二级返回按钮
        const detailBackBtn = marketRoot.querySelector('button[class*="back"], button[aria-label*="返回"], button[title*="返回"], [class*="detail"] button:has(svg)');
        if (detailBackBtn && getComputedStyle(detailBackBtn).display !== 'none') {
            detailBackBtn.click();
            return true;
        }
        const marketCloseBtn = marketRoot.querySelector('button:has(svg)');
        if (marketCloseBtn) {
            marketCloseBtn.click();
        }
        else {
            dispatchEscape();
        }
        return true;
    }
    // 4.2 插件管理器面板（包含官方 [data-plugin-panel] 及侧边栏激活的全局面板）
    const pluginPanel = document.querySelector('section[data-plugin-panel], [data-plugin-panel]');
    const hasGlobalPanel = document.querySelector('[class*="panelRow"][aria-current="page"]') !== null
        || document.querySelector('[data-global-panel]') !== null;
    if (pluginPanel || hasGlobalPanel) {
        // 【关键修复点】：优先检测是否处于插件的二级/下级页面！
        // 
        // 下级页面判定特征：
        // a) 官方插件详情页 (BundleDetail / ItemDetail / RowDetail)：[data-plugin-detail]、[data-plugin-item-detail]、[data-plugin-row-detail]
        // b) 更多实验性插件页：header[class*="moreHead"]
        // c) 面包屑返回按钮：button[class*="crumb"]、button[class*="moreBack"]、[class*="detailTop"] > button
        // d) 带明确返回文案或无障碍标签的按钮
        const subpageBackBtn = document.querySelector(
        // 官方插件详情面包屑按钮 (DetailTop 的 .crumb 按钮)
        '[data-plugin-panel] button[class*="crumb"], [data-plugin-detail] [class*="crumb"], [data-plugin-item-detail] [class*="crumb"], [data-plugin-row-detail] [class*="crumb"], [class*="detailTop"] > button, ' +
            // 更多实验性插件列表顶部的返回按钮
            '[data-plugin-panel] button[class*="moreBack"], header[class*="moreHead"] button, ' +
            // 带明确返回文案或无障碍标签的按钮
            '[data-plugin-panel] button[aria-label*="返回"], [data-plugin-panel] button[aria-label*="Back"], [data-plugin-panel] button[title*="返回"], ' +
            // 全局主面板通用子级返回按钮
            '[data-global-panel] [class*="crumb"], [data-global-panel] button[class*="back"], [data-global-panel] button[aria-label*="返回"], [data-global-panel] button[aria-label*="Back"], ' +
            // Git 等第三方面板子级关闭/返回
            '[class*="dsgDetailMask"] [class*="dsgDetailClose"], [class*="dsgDetailClose"]');
        if (subpageBackBtn && getComputedStyle(subpageBackBtn).display !== 'none' && getComputedStyle(subpageBackBtn).visibility !== 'hidden') {
            subpageBackBtn.click();
            return true;
        }
        // 只有在确认没有二级返回按钮（确实处于插件列表主页）时，才退出整个插件面板返回会话
        if (ctx && typeof ctx.layout?.selectPanel === 'function') {
            ;
            ctx.layout.selectPanel(null);
        }
        else {
            const activePanelRow = document.querySelector('[class*="panelRow"][aria-current="page"]');
            activePanelRow?.click();
        }
        return true;
    }
    // =========================================================================
    // 5. 检测【自动化任务页 / Taskboard】是否处于最上层
    // =========================================================================
    const isTaskboardActive = document.documentElement.hasAttribute('data-dsh-taskboard-active')
        || document.querySelector('[data-dsh-taskboard-board]') !== null;
    if (isTaskboardActive) {
        const taskboardExitBtn = document.querySelector('[data-dsh-taskboard-board] button[class*="close"], [data-dsh-taskboard-board] button[class*="exit"], [data-mobile-nav="fab"]');
        if (taskboardExitBtn) {
            taskboardExitBtn.click();
        }
        else {
            window.history.back();
        }
        return true;
    }
    // =========================================================================
    // 6. 处于底层的【普通对话页】
    // =========================================================================
    return 'none';
}
/**
 * 安装全面屏手势分发器
 * 注意：必须无条件直绑 window，严禁套在 MOBILE_QUERY 媒体查询门控中！
 */
function installMobileBackDispatcher(ctx) {
    const boundTrigger = () => triggerMobileBack(ctx);
    // 1. 无条件立即挂载在 window 上，保证 WebView 随时可调
    if (typeof window !== 'undefined') {
        ;
        window.triggerMobileBack = boundTrigger;
        window.__dshTriggerMobileBack = boundTrigger;
    }
    // 2. 挂载到 ctx.effect 生命周期中进行卸载管理
    ctx.effect(() => {
        return () => {
            if (typeof window !== 'undefined') {
                if (window.triggerMobileBack === boundTrigger) {
                    delete window.triggerMobileBack;
                }
                if (window.__dshTriggerMobileBack === boundTrigger) {
                    delete window.__dshTriggerMobileBack;
                }
            }
        };
    }, 'dsh-quickchat-gesture: mobile back dispatcher');
}

};
__modules["index.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installMobileBackDispatcher = exports.triggerMobileBack = exports.inject = void 0;
exports.apply = apply;
const mobile_back_dispatcher_ts_1 = require("./effects/mobile-back-dispatcher.js");
/** 声明 Cordis 客户端服务依赖（侧边栏开关需要调用 ctx.layout.toggleSidebar） */
exports.inject = ['layout'];
/**
 * 移动端全面屏手势与侧滑返回分发中枢入口：
 * 挂载 triggerMobileBack 全局调度函数（无门控直绑 window，WebView 可随时调用）
 */
function apply(ctx) {
    (0, mobile_back_dispatcher_ts_1.installMobileBackDispatcher)(ctx);
}
var mobile_back_dispatcher_ts_2 = require("./effects/mobile-back-dispatcher.js");
Object.defineProperty(exports, "triggerMobileBack", { enumerable: true, get: function () { return mobile_back_dispatcher_ts_2.triggerMobileBack; } });
Object.defineProperty(exports, "installMobileBackDispatcher", { enumerable: true, get: function () { return mobile_back_dispatcher_ts_2.installMobileBackDispatcher; } });

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
