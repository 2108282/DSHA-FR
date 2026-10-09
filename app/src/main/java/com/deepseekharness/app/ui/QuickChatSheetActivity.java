package com.deepseekharness.app.ui;

import com.deepseekharness.app.ConfirmReceiver;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 快捷对话底部抽屉弹层（纯代码动态构建，零外部 XML 依赖）：
 * 1. 左右 100% 铺满物理屏幕（Theme.DeepseekHarness.SheetTransparent + Decor 零边距）；
 * 2. 顶栏 4 按钮像素级规格统一（36x36dp 触摸区、1.85dp 中等线宽、圆角对齐、绝对对称居中）；
 *    - ① [ ✕ ] 关闭弹层
 *    - ② [ >_ ] 容器终端控制台
 *    - ③ [ 💬➕ ] 开启新对话（毫秒级 DOM 触发 / 路由重置）
 *    - ④ [ ⬒ ] 顺时针旋转 90° 的全屏展开聊天按钮
 * 3. 多档 15% 阶梯智能吸附停靠（35%/50%/65%/80%/95%），低于 25% 安全退出；
 * 4. 底部严格锁定在屏幕最底端，拖拽仅顶部上下伸缩；
 * 5. 全局静态 WebView 单例保活，再次弹出零转圈、零重新加载；
 * 6. 1:1 精准字体还原（移除 OverviewMode，设置 textZoom 100）；
 * 7. 注入透明全局 CSS 变量与 DOM 背景，100% 透出毛玻璃半透明卡片与桌面壁纸；
 * 8. 键盘弹出时：单次状态跃迁平滑拉升至默认高度，卡片顶部与底板绝对锁死在原位，底部全量铺满浅色底板，内部 WebView 视口等额收缩，输入框精准停靠在键盘正上方；
 * 9. 低位退出在动画完全结束后（onAnimationEnd）重置高度，彻底消除退出时的拉长闪屏。
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class QuickChatSheetActivity extends AppCompatActivity {

    public static final int ICON_CLOSE = 1;
    public static final int ICON_SETTINGS = 2;
    public static final int ICON_NEW_CHAT = 3;
    public static final int ICON_FULLSCREEN = 4;
    public static final int ICON_FILES = 5;
    public static final int ICON_BACK = 6;
    public static final int ICON_REFRESH = 7;

    // 全局静态保活双槽位，彻底解决再次进入重新转圈加载问题，并支持本机与远端 0 秒瞬间热切换
    @SuppressLint("StaticFieldLeak")
    private static WebView sCachedWebView = null;
    @SuppressLint("StaticFieldLeak")
    private static WebView sLocalWebView = null;
    @SuppressLint("StaticFieldLeak")
    private static WebView sRemoteWebView = null;
    public static boolean sIsRemoteActive = false;

    private static boolean sWebLoaded = false;
    private static boolean sLocalWebLoaded = false;
    private static boolean sRemoteWebLoaded = false;
    private static long sLoadedGeneration = -1;
    private static int sLoadedPort = 0;
    private static String sLoadedRemoteUrl = "";
    /** + 号触发：token 失效重载后自动补发新建对话动作 */
    private static volatile boolean sPendingNewChat = false;
    public static volatile String sPendingApprovalDecision = null;

    public static WebView getActiveWebView() {
        if (sIsRemoteActive && sRemoteWebView != null) {
            return sRemoteWebView;
        }
        return sLocalWebView != null ? sLocalWebView : sCachedWebView;
    }

    /**
     * 构建点击通知唤起抽屉的标准 Intent。
     * 采用标准的单任务栈拉起模式，避免 FLAG_ACTIVITY_REORDER_TO_FRONT 导致的任务栈串台误入主界面。
     */
    public static Intent createLaunchIntent(Context ctx) {
        return new Intent(ctx, QuickChatSheetActivity.class)
                .setAction("com.deepseekharness.app.OPEN_SHEET")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    public static void syncApprovalDecision(boolean allow) {
        sPendingApprovalDecision = allow ? "allowed-once" : "rejected";
        if (sCachedWebView != null) {
            sCachedWebView.post(() -> {
                try {
                    executeApprovalDecisionScript(sCachedWebView, allow);
                } catch (Throwable ignored) {}
            });
        }
        try {
            WebPreviewActivity prev = WebPreviewActivity.currentInstance;
            if (prev != null && prev.getWebView() != null) {
                WebView pv = prev.getWebView();
                pv.post(() -> {
                    try {
                        executeApprovalDecisionScript(pv, allow);
                    } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable ignored) {}
    }

    public static void executeApprovalDecisionScript(WebView webView, boolean allow) {
        if (webView == null) return;
        String keywordsJson = allow
                ? "['允许一次', '允许本次', '允许', '同意', 'Allow once', 'Allow', 'Approve', 'Yes']"
                : "['拒绝', '不允许', '取消', 'Reject', 'Deny', 'Cancel', 'No']";
        String js = "(function() {\n" +
                "  var targets = " + keywordsJson + ";\n" +
                "  function matches(t) {\n" +
                "    if (!t) return false;\n" +
                "    for (var k = 0; k < targets.length; k++) {\n" +
                "      if (t.indexOf(targets[k]) !== -1) return true;\n" +
                "    }\n" +
                "    return false;\n" +
                "  }\n" +
                "  var clicked = false;\n" +
                "  var panel = document.querySelector('[data-approval-key]') || document.querySelector('.approval-dialog') || document.querySelector('[role=\"dialog\"]');\n" +
                "  if (panel) {\n" +
                "    var pbtns = panel.querySelectorAll('button');\n" +
                "    for (var i = 0; i < pbtns.length; i++) {\n" +
                "      var txt = (pbtns[i].innerText || pbtns[i].textContent || '').trim();\n" +
                "      if (matches(txt)) {\n" +
                "        pbtns[i].click();\n" +
                "        clicked = true;\n" +
                "        break;\n" +
                "      }\n" +
                "    }\n" +
                "    if (!clicked && pbtns.length >= 2) {\n" +
                "      pbtns[" + (allow ? "pbtns.length - 1" : "0") + "].click();\n" +
                "      clicked = true;\n" +
                "    }\n" +
                "  }\n" +
                "  if (!clicked) {\n" +
                "    var allBtns = document.querySelectorAll('button');\n" +
                "    for (var j = 0; j < allBtns.length; j++) {\n" +
                "      var btxt = (allBtns[j].innerText || allBtns[j].textContent || '').trim();\n" +
                "      if (matches(btxt)) {\n" +
                "        allBtns[j].click();\n" +
                "        clicked = true;\n" +
                "        break;\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private FrameLayout rootOverlay;
    private FrameLayout currentActiveDialogMask = null;
    private LinearLayout sheetCard;
    private FrameLayout webContainer;
    private View keyboardSpacer;
    private ProgressBar progressBar;
    private TextView errorHint;
    private HarnessController controller;

    // 抽屉顶排操作栏与拖拽/分割线组件引用（用于反色时动态同步颜色）
    private View dragHandle;
    private TextView headerTitle;
    private TextView headerSubTitle;
    private HeaderIconButton btnClose;
    private HeaderIconButton btnSettings;
    private HeaderIconButton btnFiles;
    private HeaderIconButton btnRefresh;
    private HeaderIconButton btnNewChat;
    private HeaderIconButton btnFullscreen;
    private TextView btnFileSave;
    private TextView btnFileOpenExternal;
    private View headerDivider;

    // 抽屉内置万能查看器组件
    private FrameLayout fileViewerContainer;
    private File currentViewingFile;
    private long currentFileLoadEpoch = 0;
    private io.github.rosemoe.sora.widget.CodeEditor currentCodeEditor;
    private com.deepseekharness.app.viewer.SheetPdfAdapter currentPdfAdapter;

    private int screenHeight = 0;
    private int defaultHeight = 0;
    private int maxHeight = 0;
    private int minHeight = 0;
    private int currentHeight = 0;
    private boolean authRetried = false;
    private boolean isDismissing = false;
    private boolean isDarkMode = false;
    private boolean isMonetColor = false;
    private boolean isMonetInvert = false;
    private static volatile QuickChatSheetActivity sCurrentInstance;

    private float initialTouchY = 0f;
    private int initialHeightOnTouch = 0;

    // 原生安全审批横幅组件
    private LinearLayout approvalBannerView;
    private TextView approvalTitleView;
    private TextView approvalDetailView;
    private TextView btnApprovalAllow;
    private TextView btnApprovalDeny;
    private long currentBannerEpoch = -1L;

    // 键盘监听状态跃迁锁与动画控制器（彻底杜绝动画死锁）
    private boolean isKeyboardElevated = false;
    private ValueAnimator heightAnimator = null;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;

    private ValueCallback<Uri[]> fileCallback = null;
    private final ArrayList<File> uploads = new ArrayList<>();

    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                ValueCallback<Uri[]> callback = fileCallback;
                fileCallback = null;
                if (callback == null) return;

                Uri[] selected = WebChromeClient.FileChooserParams.parseResult(
                        result.getResultCode(), result.getData());
                if (selected == null && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        selected = new Uri[]{uri};
                    } else if (result.getData().getClipData() != null) {
                        int count = result.getData().getClipData().getItemCount();
                        if (count > 0) {
                            selected = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                selected[i] = result.getData().getClipData().getItemAt(i).getUri();
                            }
                        }
                    }
                }

                if (selected == null || selected.length == 0) {
                    callback.onReceiveValue(null);
                    return;
                }

                final Uri[] chosen = selected;
                final Context app = getApplicationContext();
                new Thread(() -> {
                    ArrayList<File> copied = new ArrayList<>();
                    try {
                        copied = WebUploads.copy(app, Arrays.asList(chosen));
                        Uri[] local = new Uri[copied.size()];
                        for (int i = 0; i < local.length; i++) {
                            local[i] = androidx.core.content.FileProvider.getUriForFile(
                                    app, app.getPackageName() + ".updates", copied.get(i));
                            try {
                                app.grantUriPermission(app.getPackageName(), local[i], Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Throwable ignored) {}
                        }
                        final ArrayList<File> ready = copied;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (isFinishing() || isDestroyed()) {
                                WebUploads.clean(ready);
                                callback.onReceiveValue(null);
                            } else {
                                uploads.addAll(ready);
                                callback.onReceiveValue(local);
                            }
                        });
                    } catch (Exception error) {
                        WebUploads.clean(copied);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            callback.onReceiveValue(null);
                            Toast.makeText(app, "上传失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                }, "sheet-file-import").start();
            });

    private void enforceExcludeFromRecents() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                for (android.app.ActivityManager.AppTask task : am.getAppTasks()) {
                    if (task != null && task.getTaskInfo() != null && task.getTaskInfo().taskId == getTaskId()) {
                        task.setExcludeFromRecents(true);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        overridePendingTransition(0, 0);
        enforceExcludeFromRecents();
        super.onCreate(savedInstanceState);

        // 窗口基础配置：全屏铺满、底部对齐（彻底锁死底部）、半透明遮罩、点击外部退出
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setFinishOnTouchOutside(true);

        Window window = getWindow();
        if (window != null) {
            // 显式关闭系统沉浸式框架对 DecorView 的状态栏 Padding 注入，消除顶部多余空白行
            WindowCompat.setDecorFitsSystemWindows(window, false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.42f);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            // 采用 ADJUST_NOTHING：避免 Window 整体与卡片顶边被系统向上顶飞
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            if (window.getDecorView() != null) {
                window.getDecorView().setFitsSystemWindows(false);
                window.getDecorView().setPadding(0, 0, 0, 0);
                window.getDecorView().setBackgroundColor(Color.TRANSPARENT);
                ViewCompat.setOnApplyWindowInsetsListener(window.getDecorView(), (v, insets) -> {
                    v.setPadding(0, 0, 0, 0);
                    return WindowInsetsCompat.CONSUMED;
                });
            }
        }

        sCurrentInstance = this;
        controller = HarnessController.get(this);
        isDarkMode = new ConfigStore(this).isSheetInvertColor();
        isMonetColor = new ConfigStore(this).isSheetMonetColor();
        isMonetInvert = new ConfigStore(this).isSheetMonetInvert();

        calculateDimensions();
        setContentView(buildUi());
        updateSystemBarsTheme();
        View content = findViewById(android.R.id.content);
        if (content != null) {
            content.setFitsSystemWindows(false);
            content.setBackgroundColor(Color.TRANSPARENT);
            content.setPadding(0, 0, 0, 0);
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                v.setPadding(0, 0, 0, 0);
                return WindowInsetsCompat.CONSUMED;
            });
        }
        setupGesture();
        setupKeyboardObserver();
        setupBackDispatcher();
        attachChatWeb();
        animateIn();
    }

    private void dismissActiveDialog() {
        if (currentActiveDialogMask != null) {
            if (rootOverlay != null && currentActiveDialogMask.getParent() == rootOverlay) {
                rootOverlay.removeView(currentActiveDialogMask);
            }
            currentActiveDialogMask = null;
        }
    }

    private void showDialogLayer(FrameLayout mask) {
        if (mask == null || rootOverlay == null) return;
        dismissActiveDialog();
        mask.setElevation(dpToPx(60));
        mask.setOutlineProvider(null);
        mask.setClickable(true);
        mask.setFocusable(true);
        currentActiveDialogMask = mask;
        rootOverlay.addView(mask);
        mask.bringToFront();
    }

    private static final String SCRIPT_CONSUME_WEB_BACK =
            "(function() {\n" +
            "    try {\n" +
            "        // 1. 优先消费：模态弹窗（通用设置、快捷键速查、对话框、确认框等）\n" +
            "        // 注意：后打开的弹窗（如快捷键弹窗）位于 DOM 较深层级，必须优先消费最顶层的 modal\n" +
            "        var modals = document.querySelectorAll('[aria-modal=\"true\"], [role=\"dialog\"]');\n" +
            "        if (modals && modals.length > 0) {\n" +
            "            var modal = modals[modals.length - 1];\n" +
            "            var closeBtn = modal.querySelector('button[aria-label*=\"Close\" i], button[aria-label*=\"关闭\" i], [class*=\"_close\"], [class*=\"_headerActions\"] button, [class*=\"_header\"] button:last-child');\n" +
            "            if (closeBtn) {\n" +
            "                closeBtn.click();\n" +
            "                return true;\n" +
            "            }\n" +
            "            var escEvent = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true });\n" +
            "            document.dispatchEvent(escEvent);\n" +
            "            window.dispatchEvent(escEvent);\n" +
            "            return true;\n" +
            "        }\n" +
            "        // 2. 优先消费：右侧文件树 / 面板（严格判定仅在真实展开可见时才执行收起，严禁在收起状态误触 toggle）\n" +
            "        var rightPane = document.querySelector('[data-sidebar-right-panel]');\n" +
            "        var expandBtn = document.querySelector('[data-sidebar-right-expand]');\n" +
            "        var isRightOpen = false;\n" +
            "        if (rightPane && !expandBtn) {\n" +
            "            if (document.querySelector('[data-sidebar-right-open]') !== null) {\n" +
            "                isRightOpen = true;\n" +
            "            } else {\n" +
            "                var cs = window.getComputedStyle(rightPane);\n" +
            "                if (cs.visibility !== 'hidden' && cs.display !== 'none' && rightPane.getBoundingClientRect().left < window.innerWidth) {\n" +
            "                    isRightOpen = true;\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "        if (isRightOpen) {\n" +
            "            var toggleBtn = document.querySelector('[data-sidebar-right-toggle], [data-sidebar-right-panel] button[aria-label*=\"收起\" i], [data-sidebar-right-panel] button[aria-label*=\"折叠\" i], [data-sidebar-right-panel] button[aria-label*=\"Collapse\" i]');\n" +
            "            if (toggleBtn && typeof toggleBtn.click === 'function') { toggleBtn.click(); return true; }\n" +
            "            var tabClose = rightPane.querySelector('button[aria-label*=\"关闭\" i], button[aria-label*=\"Close\" i], [class*=\"_tabClose\"], [class*=\"_closeBtn\"]');\n" +
            "            if (tabClose && typeof tabClose.click === 'function') { tabClose.click(); return true; }\n" +
            "        }\n" +
            "        var frame = document.querySelector('[data-mobile-nav=\"frame\"]');\n" +
            "        if (frame && frame.hasAttribute('data-aionui-explorer-open')) {\n" +
            "            frame.removeAttribute('data-aionui-explorer-open');\n" +
            "            return true;\n" +
            "        }\n" +
            "        if (frame && frame.hasAttribute('data-aionui-preview-open')) {\n" +
            "            frame.removeAttribute('data-aionui-preview-open');\n" +
            "            frame.removeAttribute('data-mobile-preview-full');\n" +
            "            return true;\n" +
            "        }\n" +
            "        // 3. 优先消费：左侧抽屉 / 侧边栏（仅在侧边栏真实展开时收起）\n" +
            "        if (frame && !frame.hasAttribute('data-sidebar-collapsed')) {\n" +
            "            var backdrop = document.querySelector('[data-mobile-nav=\"backdrop\"]');\n" +
            "            if (backdrop) { backdrop.click(); return true; }\n" +
            "            var escEv = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true });\n" +
            "            document.dispatchEvent(escEv);\n" +
            "            window.dispatchEvent(escEv);\n" +
            "            if (!frame.hasAttribute('data-sidebar-collapsed')) {\n" +
            "                frame.setAttribute('data-sidebar-collapsed', '');\n" +
            "            }\n" +
            "            return true;\n" +
            "        }\n" +
            "        // 4. 优先消费：删除确认卡片等浮层\n" +
            "        var deleteBackdrop = document.querySelector('[data-mobile-nav=\"delete-dialog-backdrop\"]');\n" +
            "        if (deleteBackdrop) { deleteBackdrop.click(); return true; }\n" +
            "        // 5. 优先消费：插件二级/三级配置详情页的面包屑导航返回\n" +
            "        var pluginCrumb = document.querySelector('[data-plugin-panel] button[class*=\"crumb\"], [data-plugin-panel] button[aria-label*=\"返回\" i], [data-plugin-panel] button[aria-label*=\"Back to\" i]');\n" +
            "        if (pluginCrumb) {\n" +
            "            pluginCrumb.click();\n" +
            "            return true;\n" +
            "        }\n" +
            "        // 6. 优先消费：插件管理主页面（或其它非会话全局面板），将返回手势严格绑定到左上角返回按钮\n" +
            "        var pluginPanel = document.querySelector('[data-plugin-panel]');\n" +
            "        var activePanel = document.querySelector('[class*=\"panelRow\"][class*=\"panelActive\"], [class*=\"panelRow\"][aria-current=\"page\"]');\n" +
            "        var fabBack = document.querySelector('[data-mobile-nav=\"fab\"][data-mobile-nav-fab-mode=\"exit-panel\"], [data-mobile-nav=\"fab\"][aria-label*=\"返回\" i], [data-mobile-nav=\"fab\"][title*=\"返回\" i]');\n" +
            "        if (pluginPanel || activePanel || fabBack) {\n" +
            "            if (fabBack) {\n" +
            "                fabBack.click();\n" +
            "                return true;\n" +
            "            }\n" +
            "            var anyFab = document.querySelector('[data-mobile-nav=\"fab\"]');\n" +
            "            if (anyFab && (anyFab.getAttribute('data-mobile-nav-fab-mode') === 'exit-panel' || anyFab.innerHTML.indexOf('path') !== -1)) {\n" +
            "                anyFab.click();\n" +
            "                return true;\n" +
            "            }\n" +
            "            var panelItem = document.querySelector('[class*=\"panelRow\"], button[aria-current=\"page\"]');\n" +
            "            if (panelItem) {\n" +
            "                var rKey = Object.keys(panelItem).find(function(k) { return k.startsWith('__reactProps') || k.startsWith('__reactFiber'); });\n" +
            "                if (rKey && panelItem[rKey]) {\n" +
            "                    var curr = panelItem[rKey];\n" +
            "                    for (var d = 0; curr && d < 20; d++) {\n" +
            "                        var p = curr.memoizedProps || curr.pendingProps || curr;\n" +
            "                        if (p && typeof p.selectPanel === 'function') {\n" +
            "                            p.selectPanel(null);\n" +
            "                            return true;\n" +
            "                        }\n" +
            "                        curr = curr.return;\n" +
            "                    }\n" +
            "                }\n" +
            "            }\n" +
            "            var curSession = document.querySelector('[class*=\"sessionRow\"][class*=\"selected\"], [class*=\"sessionRow\"][aria-selected=\"true\"], [class*=\"sessionRow\"][aria-current=\"true\"]');\n" +
            "            if (curSession) {\n" +
            "                curSession.click();\n" +
            "                return true;\n" +
            "            }\n" +
            "            var anySession = document.querySelector('[class*=\"sessionRow\"]');\n" +
            "            if (anySession) {\n" +
            "                anySession.click();\n" +
            "                return true;\n" +
            "            }\n" +
            "            var newChat = document.querySelector('[class*=\"newSession\"], [aria-label*=\"新会话\"], [aria-label*=\"新建\"], button[title*=\"新会话\"], button[title*=\"New session\"]');\n" +
            "            if (newChat) {\n" +
            "                newChat.click();\n" +
            "                return true;\n" +
            "            }\n" +
            "        }\n" +
            "    } catch (e) {\n" +
            "        console.error('dsha consume back error:', e);\n" +
            "    }\n" +
            "    return false;\n" +
            "})()";

    private void dispatchBackAction() {
        if (currentActiveDialogMask != null) {
            dismissActiveDialog();
            return;
        }
        if (fileViewerContainer != null && fileViewerContainer.getVisibility() == View.VISIBLE) {
            closeFileViewer();
            return;
        }
        if (sCachedWebView != null) {
            sCachedWebView.evaluateJavascript(SCRIPT_CONSUME_WEB_BACK, value -> {
                if ("true".equals(value)) {
                    return;
                }
                // 浮层消费完毕后，快捷抽屉的侧滑返回意图一律为平滑退出抽屉，严禁执行 WebView goBack() 导致 SPA 会话历史倒退刷新
                dismissSheet();
            });
            return;
        }
        dismissSheet();
    }

    private void setupBackDispatcher() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                dispatchBackAction();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        overridePendingTransition(0, 0);
        enforceExcludeFromRecents();
        super.onNewIntent(intent);
        setIntent(intent);
        sCurrentInstance = this;
        boolean dark = new ConfigStore(this).isSheetInvertColor();
        boolean monet = new ConfigStore(this).isSheetMonetColor();
        boolean monetInvert = new ConfigStore(this).isSheetMonetInvert();
        isDarkMode = dark;
        isMonetColor = monet;
        isMonetInvert = monetInvert;
        updateCardTheme();

        if (com.deepseekharness.app.HttpShellService.isApprovalWaiting && com.deepseekharness.app.HttpShellService.sCurrentApprovalInfo != null) {
            displayApprovalBanner(com.deepseekharness.app.HttpShellService.sCurrentApprovalInfo, com.deepseekharness.app.HttpShellService.sCurrentApprovalEpoch);
        } else {
            hideApprovalBanner();
        }
        if (sCachedWebView != null) {
            triggerForegroundWakeup();
            injectTransparentBackground(sCachedWebView);
            if (sPendingApprovalDecision != null) {
                boolean allow = "allowed-once".equals(sPendingApprovalDecision);
                sPendingApprovalDecision = null;
                executeApprovalDecisionScript(sCachedWebView, allow);
            }
            // 确保 WebView 100% 挂载在当前窗口的容器中，防止因生命周期波动导致 View 容器留空
            if (sCachedWebView.getParent() != webContainer) {
                if (sCachedWebView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) sCachedWebView.getParent()).removeView(sCachedWebView);
                }
                webContainer.addView(sCachedWebView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            // 若当前未在查看具体文件，确保 WebView 恢复显示，杜绝界面留空
            if (fileViewerContainer == null || fileViewerContainer.getVisibility() != View.VISIBLE) {
                sCachedWebView.setVisibility(View.VISIBLE);
            }
            // 唤醒防白屏兜底：若上次未成功加载出界面，再次唤出时自动重载有效凭证
            if (!sWebLoaded) {
                String authUrl = controller != null ? controller.getWebAuthUrl() : "";
                if (authUrl != null && !authUrl.isEmpty()) {
                    if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                    sCachedWebView.loadUrl(authUrl);
                }
            }
        }
        // 如果抽屉当前已在屏幕中且处于展开状态，不再重置平移从底部重复播放动画，平滑保持
        if (sheetCard != null && sheetCard.getVisibility() == View.VISIBLE && sheetCard.getTranslationY() == 0) {
            triggerForegroundWakeup();
        } else {
            animateIn();
        }
    }

    private void calculateDimensions() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;
        // 初始默认高度取用户设置 (默认 75%)，全屏态 95%，最低安全退出阈值 25%
        int userPercent = new ConfigStore(this).getSheetHeightPercent();
        float userRatio = Math.max(30, Math.min(95, userPercent)) / 100.0f;
        defaultHeight = (int) (screenHeight * userRatio);
        maxHeight = (int) (screenHeight * 0.95f);
        minHeight = (int) (screenHeight * 0.25f);
        currentHeight = defaultHeight;
    }

    /** 智能底角圆角计算：顶部恒为 24dp 圆角；左侧不贴边(>0)则左下切圆角，右侧不贴边(>0)则右下切圆角 */
    private float[] calculateCardCornerRadii(int marginLeftDp, int marginRightDp) {
        float topRadius = dpToPx(24);
        float bottomLeftRadius = marginLeftDp > 0 ? dpToPx(24) : 0f;
        float bottomRightRadius = marginRightDp > 0 ? dpToPx(24) : 0f;
        return new float[]{
                topRadius, topRadius,                 // 左上
                topRadius, topRadius,                 // 右上
                bottomRightRadius, bottomRightRadius, // 右下
                bottomLeftRadius, bottomLeftRadius    // 左下
        };
    }

    /** 系统状态栏与导航栏根据抽屉反色开关设置文字/图标明暗 */
    private void updateSystemBarsTheme() {
        Window window = getWindow();
        if (window != null && window.getDecorView() != null) {
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
            if (controller != null) {
                // 当 isDarkMode 为 true（深色/反色开启）时，状态栏与导航栏文字/图标为浅白色（false）
                // 当 isDarkMode 为 false（浅色模式）时，状态栏与导航栏文字/图标为深黑色（true）
                controller.setAppearanceLightStatusBars(!isDarkMode);
                controller.setAppearanceLightNavigationBars(!isDarkMode);
            }
        }
    }

    private void updateCardTheme() {
        com.deepseekharness.app.core.ConfigStore cfg = new com.deepseekharness.app.core.ConfigStore(this);
        int opacity = isDarkMode ? cfg.getSheetOpacityNight() : cfg.getSheetOpacityDay();
        MonetThemeHelper.Palette palette = MonetThemeHelper.resolve(this, isDarkMode, cfg.isSheetMonetColor(), opacity);
        int cardBgColor = palette.cardBgColor;
        int textColor = palette.textColor;
        int lineColor = palette.lineColor;
        int handleColor = palette.handleColor;
        int borderColor = palette.borderColor;

        // 1. 卡片圆角背景与描边（自适应不贴边圆角）
        if (sheetCard != null) {
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setShape(GradientDrawable.RECTANGLE);
            cardBg.setCornerRadii(calculateCardCornerRadii(cfg.getSheetMarginLeft(), cfg.getSheetMarginRight()));
            cardBg.setColor(cardBgColor);
            cardBg.setStroke(dpToPx(1), borderColor);
            sheetCard.setBackground(cardBg);

            // 动态同步左右边距
            if (sheetCard.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) sheetCard.getLayoutParams();
                int newLeft = dpToPx(cfg.getSheetMarginLeft());
                int newRight = dpToPx(cfg.getSheetMarginRight());
                if (mlp.leftMargin != newLeft || mlp.rightMargin != newRight) {
                    mlp.leftMargin = newLeft;
                    mlp.rightMargin = newRight;
                    sheetCard.setLayoutParams(mlp);
                }
            }
        }

        // 2. 顶部拖拽横条颜色
        if (dragHandle != null && dragHandle.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) dragHandle.getBackground()).setColor(handleColor);
        }

        // 3. 顶部操作栏文字颜色
        if (headerTitle != null) {
            headerTitle.setTextColor(textColor);
        }

        // 4. 顶部操作栏矢量图标按钮（图片）颜色
        if (btnClose != null) btnClose.setIconColor(textColor);
        if (btnSettings != null) btnSettings.setIconColor(textColor);
        if (btnFiles != null) btnFiles.setIconColor(textColor);
        if (btnRefresh != null) btnRefresh.setIconColor(textColor);
        if (btnNewChat != null) btnNewChat.setIconColor(textColor);
        if (btnFullscreen != null) btnFullscreen.setIconColor(textColor);

        // 5. 顶部操作栏底部分割线颜色
        if (headerDivider != null) {
            headerDivider.setBackgroundColor(lineColor);
        }

        // 6. 错误提示文字颜色
        if (errorHint != null) {
            errorHint.setTextColor(palette.textSecondaryColor);
        }

        // 6.5 原生审批横幅主题更新
        if (approvalBannerView != null) {
            applyApprovalBannerTheme(approvalBannerView);
        }

        // 7. 手机系统顶部状态栏与导航栏文字/图标颜色
        updateSystemBarsTheme();

        // 8. 注入 WebView 沉浸样式
        if (sCachedWebView != null) {
            injectTransparentBackground(sCachedWebView);
        }
    }

    private LinearLayout buildApprovalBannerView(MonetThemeHelper.Palette palette) {
        LinearLayout outer = new LinearLayout(this);
        LinearLayout.LayoutParams outerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outerLp.setMargins(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(6));
        outer.setLayoutParams(outerLp);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        outer.setVisibility(View.GONE);

        applyApprovalBannerTheme(outer);

        // 顶部标题横行：[⚠️ 图标] + [标题] + [等待审批 胶囊徽章]
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView iconView = new TextView(this);
        iconView.setText("⚠️");
        iconView.setTextSize(14);
        titleRow.addView(iconView);

        approvalTitleView = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleLp.setMarginStart(dpToPx(6));
        approvalTitleView.setLayoutParams(titleLp);
        approvalTitleView.setText("危险权限授权申请");
        approvalTitleView.setTextSize(13.5f);
        approvalTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        approvalTitleView.setTextColor(isDarkMode ? Color.parseColor("#FBBF24") : Color.parseColor("#B45309"));
        approvalTitleView.setSingleLine(true);
        approvalTitleView.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(approvalTitleView);

        TextView badgeView = new TextView(this);
        badgeView.setText("等待审批");
        badgeView.setTextSize(10);
        badgeView.setTypeface(Typeface.DEFAULT_BOLD);
        badgeView.setTextColor(Color.parseColor("#F59E0B"));
        badgeView.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(dpToPx(10));
        badgeBg.setColor(Color.parseColor("#26F59E0B"));
        badgeView.setBackground(badgeBg);
        titleRow.addView(badgeView);

        outer.addView(titleRow);

        // 中间详情文案（命令/原因）
        approvalDetailView = new TextView(this);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.setMargins(0, dpToPx(6), 0, dpToPx(8));
        approvalDetailView.setLayoutParams(detailLp);
        approvalDetailView.setText("模型申请执行敏感操作，请确认是否允许");
        approvalDetailView.setTextSize(12);
        approvalDetailView.setMaxLines(4);
        approvalDetailView.setEllipsize(TextUtils.TruncateAt.END);
        approvalDetailView.setTextIsSelectable(true);
        approvalDetailView.setTextColor(isDarkMode ? Color.parseColor("#E2E8F0") : Color.parseColor("#334155"));
        outer.addView(approvalDetailView);

        // 底部操作按钮栏（居右对齐）
        LinearLayout btnRow = new LinearLayout(this);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRow.setLayoutParams(btnRowLp);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        // [拒绝按钮]
        btnApprovalDeny = new TextView(this);
        btnApprovalDeny.setText("拒绝");
        btnApprovalDeny.setTextSize(12.5f);
        btnApprovalDeny.setTypeface(Typeface.DEFAULT_BOLD);
        btnApprovalDeny.setTextColor(Color.parseColor("#EF4444"));
        btnApprovalDeny.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));
        GradientDrawable denyBg = new GradientDrawable();
        denyBg.setShape(GradientDrawable.RECTANGLE);
        denyBg.setCornerRadius(dpToPx(8));
        denyBg.setColor(isDarkMode ? Color.parseColor("#29EF4444") : Color.parseColor("#14EF4444"));
        denyBg.setStroke(dpToPx(1), Color.parseColor("#4DEF4444"));
        btnApprovalDeny.setBackground(denyBg);
        btnApprovalDeny.setOnClickListener(v -> {
            ConfirmReceiver.triggerVibrate(this, 50);
            ConfirmReceiver.writeApprovalDecision("rejected");
            HttpShellService svc = HttpShellService.instance();
            if (svc != null) {
                svc.resolveConfirm(false, currentBannerEpoch);
            }
            syncApprovalDecision(false);
            hideApprovalBanner();
        });
        btnRow.addView(btnApprovalDeny);

        // [允许一次按钮]
        btnApprovalAllow = new TextView(this);
        LinearLayout.LayoutParams allowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        allowLp.setMarginStart(dpToPx(10));
        btnApprovalAllow.setLayoutParams(allowLp);
        btnApprovalAllow.setText("允许一次");
        btnApprovalAllow.setTextSize(12.5f);
        btnApprovalAllow.setTypeface(Typeface.DEFAULT_BOLD);
        btnApprovalAllow.setTextColor(Color.WHITE);
        btnApprovalAllow.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        GradientDrawable allowBg = new GradientDrawable();
        allowBg.setShape(GradientDrawable.RECTANGLE);
        allowBg.setCornerRadius(dpToPx(8));
        allowBg.setColor(Color.parseColor("#10B981"));
        btnApprovalAllow.setBackground(allowBg);
        btnApprovalAllow.setOnClickListener(v -> {
            ConfirmReceiver.triggerVibrate(this, 50);
            ConfirmReceiver.writeApprovalDecision("allowed-once");
            HttpShellService svc = HttpShellService.instance();
            if (svc != null) {
                svc.resolveConfirm(true, currentBannerEpoch);
            }
            syncApprovalDecision(true);
            hideApprovalBanner();
        });
        btnRow.addView(btnApprovalAllow);

        outer.addView(btnRow);
        return outer;
    }

    private void applyApprovalBannerTheme(LinearLayout outer) {
        if (outer == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(12));
        if (isDarkMode) {
            bg.setColor(Color.parseColor("#F0241A0A"));
            bg.setStroke(dpToPx(1), Color.parseColor("#80F59E0B"));
        } else {
            bg.setColor(Color.parseColor("#F5FEF9C3"));
            bg.setStroke(dpToPx(1), Color.parseColor("#B3EAB308"));
        }
        outer.setBackground(bg);
        if (approvalTitleView != null) {
            approvalTitleView.setTextColor(isDarkMode ? Color.parseColor("#FBBF24") : Color.parseColor("#B45309"));
        }
        if (approvalDetailView != null) {
            approvalDetailView.setTextColor(isDarkMode ? Color.parseColor("#E2E8F0") : Color.parseColor("#334155"));
        }
    }

    public void displayApprovalBanner(HttpShellService.AuthPromptInfo info, long epoch) {
        if (info == null) return;
        currentBannerEpoch = epoch;
        if (approvalBannerView != null) {
            if (approvalTitleView != null) {
                approvalTitleView.setText(info.title != null ? info.title : "⚠️ 安全操作审批");
            }
            if (approvalDetailView != null) {
                approvalDetailView.setText(info.detail != null ? info.detail : "模型正在请求执行敏感指令，请确认是否允许。");
            }
            if (btnApprovalAllow != null && info.primaryBtn != null && !info.primaryBtn.isEmpty()) {
                btnApprovalAllow.setText(info.primaryBtn);
            }
            if (btnApprovalDeny != null && info.secondaryBtn != null && !info.secondaryBtn.isEmpty()) {
                btnApprovalDeny.setText(info.secondaryBtn);
            }
            applyApprovalBannerTheme(approvalBannerView);
            approvalBannerView.setVisibility(View.VISIBLE);
        }
    }

    public void hideApprovalBanner() {
        if (approvalBannerView != null && approvalBannerView.getVisibility() != View.GONE) {
            approvalBannerView.setVisibility(View.GONE);
        }
    }

    public static void showNativeApprovalBanner(HttpShellService.AuthPromptInfo info, long epoch) {
        QuickChatSheetActivity inst = sCurrentInstance;
        if (inst != null && info != null) {
            inst.runOnUiThread(() -> inst.displayApprovalBanner(info, epoch));
        }
    }

    public static void dismissNativeApprovalBanner() {
        QuickChatSheetActivity inst = sCurrentInstance;
        if (inst != null) {
            inst.runOnUiThread(() -> inst.hideApprovalBanner());
        }
    }

    private View buildUi() {
        com.deepseekharness.app.core.ConfigStore cfg = new com.deepseekharness.app.core.ConfigStore(this);
        int opacity = isDarkMode ? cfg.getSheetOpacityNight() : cfg.getSheetOpacityDay();
        MonetThemeHelper.Palette palette = MonetThemeHelper.resolve(this, isDarkMode, cfg.isSheetMonetColor(), opacity);
        int cardBgColor = palette.cardBgColor;
        int textColor = palette.textColor;
        int lineColor = palette.lineColor;
        int handleColor = palette.handleColor;
        int borderColor = palette.borderColor;

        // 1. 根全屏透明遮罩容器（左右 100% 撑满，彻底消费 WindowInsets 杜绝空行）
        rootOverlay = new FrameLayout(this);
        rootOverlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootOverlay.setBackgroundColor(Color.TRANSPARENT);
        rootOverlay.setPadding(0, 0, 0, 0);
        rootOverlay.setFitsSystemWindows(false);
        ViewCompat.setOnApplyWindowInsetsListener(rootOverlay, (v, insets) -> {
            v.setPadding(0, 0, 0, 0);
            return WindowInsetsCompat.CONSUMED;
        });

        // 点击外部空白区域退出（上方、左侧留白或右侧留白）
        rootOverlay.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                int[] loc = new int[2];
                sheetCard.getLocationOnScreen(loc);
                float x = event.getRawX();
                float y = event.getRawY();
                boolean outside = x < loc[0] || x > (loc[0] + sheetCard.getWidth())
                               || y < loc[1] || y > (loc[1] + sheetCard.getHeight());
                if (outside) {
                    dismissSheet();
                    return true;
                }
            }
            return false;
        });

        // 2. 底部卡片主体（Gravity.BOTTOM 彻底锁定底部，左右边距自适应）
        int leftMarginPx = dpToPx(cfg.getSheetMarginLeft());
        int rightMarginPx = dpToPx(cfg.getSheetMarginRight());

        sheetCard = new LinearLayout(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, defaultHeight);
        cardLp.gravity = Gravity.BOTTOM;
        cardLp.leftMargin = leftMarginPx;
        cardLp.rightMargin = rightMarginPx;
        cardLp.bottomMargin = 0;
        sheetCard.setLayoutParams(cardLp);
        sheetCard.setOrientation(LinearLayout.VERTICAL);
        sheetCard.setElevation(dpToPx(16));
        sheetCard.setClipChildren(true);
        sheetCard.setFitsSystemWindows(false);

        // 顶部圆角 + 智能底角（贴边为直角，不贴边切圆角）毛玻璃半透背景 + 细微描边
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadii(calculateCardCornerRadii(cfg.getSheetMarginLeft(), cfg.getSheetMarginRight()));
        cardBg.setColor(cardBgColor);
        cardBg.setStroke(dpToPx(1), borderColor);
        sheetCard.setBackground(cardBg);

        // 3. 紧凑拖拽横条区域（Drag Handle）：压缩留白
        FrameLayout dragArea = new FrameLayout(this);
        dragArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(14)));
        dragArea.setPadding(0, dpToPx(5), 0, dpToPx(2));

        dragHandle = new View(this);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        dragHandle.setLayoutParams(handleLp);

        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setShape(GradientDrawable.RECTANGLE);
        handleBg.setCornerRadius(dpToPx(2));
        handleBg.setColor(handleColor);
        dragHandle.setBackground(handleBg);
        dragArea.addView(dragHandle);
        sheetCard.addView(dragArea);

        // 4. 顶部操作栏（RelativeLayout 保证标题绝对对称居中）
        RelativeLayout headerBar = new RelativeLayout(this);
        headerBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(42)));
        headerBar.setPadding(dpToPx(10), 0, dpToPx(10), dpToPx(2));

        // 左侧按钮组：[① ✕ 关闭] + [② >_ 容器设置] + [②+ 📁 工作区文件]
        LinearLayout leftGroup = new LinearLayout(this);
        leftGroup.setId(View.generateViewId());
        RelativeLayout.LayoutParams leftLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        leftLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        leftLp.addRule(RelativeLayout.CENTER_VERTICAL);
        leftGroup.setLayoutParams(leftLp);
        leftGroup.setOrientation(LinearLayout.HORIZONTAL);
        leftGroup.setGravity(Gravity.CENTER_VERTICAL);

        // [① ✕ 关闭按钮]
        btnClose = createHeaderIconButton(ICON_CLOSE, textColor, "关闭弹层");
        btnClose.setOnClickListener(v -> {
            if (fileViewerContainer != null && fileViewerContainer.getVisibility() == View.VISIBLE) {
                closeFileViewer();
            } else {
                dismissSheet();
            }
        });
        leftGroup.addView(btnClose);

        // [② >_ 终端按钮（保持原有图标与样式不变，点击在 本机3095桥 和 远端连接 之间无缝互换）]
        btnSettings = createHeaderIconButton(ICON_SETTINGS, textColor, "切换本机/远端 DSH 连接");
        LinearLayout.LayoutParams settingsLp = (LinearLayout.LayoutParams) btnSettings.getLayoutParams();
        settingsLp.setMarginStart(dpToPx(4));
        btnSettings.setLayoutParams(settingsLp);
        btnSettings.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            toggleDshConnectionTarget();
        });
        leftGroup.addView(btnSettings);

        // [②+ 📁 工作区文件管理按钮]
        btnFiles = createHeaderIconButton(ICON_FILES, textColor, "切换工作区文件树");
        LinearLayout.LayoutParams filesLp = (LinearLayout.LayoutParams) btnFiles.getLayoutParams();
        filesLp.setMarginStart(dpToPx(4));
        btnFiles.setLayoutParams(filesLp);
        btnFiles.setOnClickListener(v -> toggleWorkspaceFileTree());
        leftGroup.addView(btnFiles);

        headerBar.addView(leftGroup);

        // 右侧按钮组：[⓪ 🔄 刷新鉴权] + [③ 💬➕ 新建对话] + [④ ⬒ 全屏进入App] + [⑤ 💾 保存]
        LinearLayout rightGroup = new LinearLayout(this);
        rightGroup.setId(View.generateViewId());
        RelativeLayout.LayoutParams rightLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rightLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        rightLp.addRule(RelativeLayout.CENTER_VERTICAL);
        rightGroup.setLayoutParams(rightLp);
        rightGroup.setOrientation(LinearLayout.HORIZONTAL);
        rightGroup.setGravity(Gravity.CENTER_VERTICAL);

        // [⓪ 🔄 刷新鉴权并进入DSH按钮]
        btnRefresh = createHeaderIconButton(ICON_REFRESH, textColor, "重新获取Token并刷新进入DSH");
        btnRefresh.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            btnRefresh.animate().rotationBy(360f).setDuration(600)
                    .setInterpolator(new DecelerateInterpolator()).start();
            if (fileViewerContainer != null && fileViewerContainer.getVisibility() == View.VISIBLE) {
                closeFileViewer();
            }
            if (sIsRemoteActive) {
                // 远端模式刷新：刷新当前激活的远端地址
                String activeUrl = ConfigStore.get(this).getActiveRemoteDshUrl();
                if (activeUrl == null || activeUrl.isEmpty()) {
                    Toast.makeText(this, "未开启任何远端连接", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                Toast.makeText(this, "正在重新连接远端 DSH...", Toast.LENGTH_SHORT).show();
                ensureRemoteWebViewLoaded(activeUrl);
                if (sRemoteWebView != null) {
                    sRemoteWebView.loadUrl(activeUrl);
                }
            } else {
                // 本地模式刷新：重新获取Token并刷新
                Toast.makeText(this, "正在重新获取凭据并刷新本机 DSH...", Toast.LENGTH_SHORT).show();
                forceReloadWithLatestToken();
            }
        });
        rightGroup.addView(btnRefresh);

        // [③ 💬➕ 新建对话按钮]
        btnNewChat = createHeaderIconButton(ICON_NEW_CHAT, textColor, "开启新对话");
        LinearLayout.LayoutParams newChatLp = (LinearLayout.LayoutParams) btnNewChat.getLayoutParams();
        newChatLp.setMarginStart(dpToPx(4));
        btnNewChat.setLayoutParams(newChatLp);
        btnNewChat.setOnClickListener(v -> {
            WebView activeWeb = getActiveWebView();
            if (activeWeb == null) return;

            if (!sIsRemoteActive) {
                long currentGen = controller != null ? controller.getWebGeneration() : -1;
                boolean serviceRestarted = sLoadedGeneration > 0 && currentGen > 0 && sLoadedGeneration != currentGen;
                int port = controller != null ? controller.getPort() : 3080;
                String curUrl = activeWeb.getUrl();
                boolean detached = curUrl == null || (!curUrl.startsWith("http://127.0.0.1:" + port) && !curUrl.startsWith("http://localhost:" + port));

                if (serviceRestarted || detached || !sLocalWebLoaded || sLoadedPort != port) {
                    sLoadedPort = port;
                    sPendingNewChat = true;
                    forceReloadWithLatestToken();
                    return;
                }
            }

            String js = "(function() {" +
                    "  var btn = document.querySelector('[class*=\"newSession\"], [aria-label*=\"新会话\"], [aria-label*=\"新建\"], button[title*=\"新会话\"], button[title*=\"New session\"], button[title*=\"New Chat\"]');" +
                    "  if (btn) {" +
                    "    btn.click();" +
                    "  } else {" +
                    "    window.location.hash = '';" +
                    "    window.location.href = '/';" +
                    "  }" +
                    "})();";
            activeWeb.evaluateJavascript(js, null);
        });
        rightGroup.addView(btnNewChat);

        // [④ ⬒ 全屏聊天按钮]
        btnFullscreen = createHeaderIconButton(ICON_FULLSCREEN, textColor, "全屏打开聊天页面");
        LinearLayout.LayoutParams fullscreenLp = (LinearLayout.LayoutParams) btnFullscreen.getLayoutParams();
        fullscreenLp.setMarginStart(dpToPx(4));
        btnFullscreen.setLayoutParams(fullscreenLp);
        btnFullscreen.setOnClickListener(v -> {
            String currentUrl = sCachedWebView != null ? sCachedWebView.getUrl() : null;
            if (currentUrl == null || currentUrl.isEmpty() || "about:blank".equals(currentUrl)) {
                currentUrl = controller != null ? controller.getWebAuthUrl() : null;
            }
            if (currentUrl == null || currentUrl.isEmpty()) {
                int port = controller != null ? controller.getPort() : 3080;
                currentUrl = "http://127.0.0.1:" + port + "/";
            }

            final String finalUrl = currentUrl;
            new Thread(() -> {
                String cookie = controller != null ? controller.exchangeDshAuthCookie() : null;
                runOnUiThread(() -> {
                    Intent intent = WebPreviewActivity.intent(this, finalUrl, cookie);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                    overridePendingTransition(0, 0);
                });
            }, "sheet-expand-web").start();
        });
        rightGroup.addView(btnFullscreen);

        // [⑤ 💾 保存按钮] 处于文本/代码查看模式时显示
        btnFileSave = new TextView(this);
        btnFileSave.setText("保存");
        btnFileSave.setTextColor(Color.parseColor("#4C8DFF"));
        btnFileSave.setTextSize(14);
        btnFileSave.setTypeface(Typeface.DEFAULT_BOLD);
        btnFileSave.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        btnFileSave.setVisibility(View.GONE);
        btnFileSave.setOnClickListener(v -> saveCurrentEditorText());
        rightGroup.addView(btnFileSave);

        // [⑥ ↗ 外部打开按钮] 处于查看文件时显示，随时调用系统「打开方式」
        btnFileOpenExternal = new TextView(this);
        btnFileOpenExternal.setText("↗ 外部");
        btnFileOpenExternal.setTextColor(Color.parseColor("#4C8DFF"));
        btnFileOpenExternal.setTextSize(14);
        btnFileOpenExternal.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        btnFileOpenExternal.setVisibility(View.GONE);
        btnFileOpenExternal.setOnClickListener(v -> {
            if (currentViewingFile != null) {
                com.deepseekharness.app.viewer.FileOpenHelper.openWithSystem(this, currentViewingFile);
            }
        });
        rightGroup.addView(btnFileOpenExternal);

        headerBar.addView(rightGroup);

        // 中间标题容器（物理绝对对称居中，两端动态对称避让，永不挤压遮挡按钮）
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER);
        RelativeLayout.LayoutParams titleBoxLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleBoxLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        // 初始对称安全边距：对话模式下左侧为3按钮（宽116dp），预留 max(左, 右) + 8dp = 124dp 对称外边距，首帧渲染即绝对居中
        int initialMargin = dpToPx(124);
        titleBoxLp.leftMargin = initialMargin;
        titleBoxLp.rightMargin = initialMargin;
        titleBoxLp.setMarginStart(initialMargin);
        titleBoxLp.setMarginEnd(initialMargin);
        titleBox.setLayoutParams(titleBoxLp);

        // 动态对称安全边距：无论哪侧增减或显示/隐藏按钮，左右两端始终等距约束，保证标题永远在中轴线上
        Runnable updateTitleMargin = () -> {
            int leftW = leftGroup.getWidth();
            int rightW = rightGroup.getWidth();
            if (leftW <= 0 && rightW <= 0) return;
            int maxSide = Math.max(leftW, rightW);
            int safeMargin = maxSide + dpToPx(8);
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) titleBox.getLayoutParams();
            if (lp.leftMargin != safeMargin || lp.rightMargin != safeMargin) {
                lp.leftMargin = safeMargin;
                lp.rightMargin = safeMargin;
                lp.setMarginStart(safeMargin);
                lp.setMarginEnd(safeMargin);
                titleBox.setLayoutParams(lp);
            }
        };
        leftGroup.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateTitleMargin.run());
        rightGroup.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateTitleMargin.run());

        headerTitle = new TextView(this);
        headerTitle.setText("DSHA 对话");
        headerTitle.setTextColor(textColor);
        headerTitle.setTextSize(17);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        headerTitle.setGravity(Gravity.CENTER);
        titleBox.addView(headerTitle);
        updateHeaderTitleText();

        headerSubTitle = new TextView(this);
        headerSubTitle.setTextColor(Color.parseColor("#888888"));
        headerSubTitle.setTextSize(10);
        headerSubTitle.setSingleLine(true);
        headerSubTitle.setGravity(Gravity.CENTER);
        headerSubTitle.setVisibility(View.GONE);
        titleBox.addView(headerSubTitle);

        headerBar.addView(titleBox);

        sheetCard.addView(headerBar);

        // 5. 分割线
        headerDivider = new View(this);
        headerDivider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        headerDivider.setBackgroundColor(lineColor);
        sheetCard.addView(headerDivider);

        // 5.5 原生安全审批横幅卡片（当模型提权或底层高危拦截时直接在抽屉顶部显现）
        approvalBannerView = buildApprovalBannerView(palette);
        sheetCard.addView(approvalBannerView);

        // 6. WebView 主体容器（自适应伸缩，防漏字）
        webContainer = new FrameLayout(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        webContainer.setLayoutParams(webLp);
        webContainer.setClipChildren(true);
        webContainer.setClipToPadding(true);
        webContainer.setBackgroundColor(Color.TRANSPARENT);

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(36));
        pbLp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbLp);
        progressBar.setVisibility(sWebLoaded ? View.GONE : View.VISIBLE);
        webContainer.addView(progressBar);

        errorHint = new TextView(this);
        FrameLayout.LayoutParams errLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.gravity = Gravity.CENTER;
        errorHint.setLayoutParams(errLp);
        errorHint.setText("正在连接 DSHA 服务…");
        errorHint.setTextColor(palette.textSecondaryColor);
        errorHint.setTextSize(14);
        errorHint.setVisibility(View.GONE);
        webContainer.addView(errorHint);

        // 抽屉内置万能查看器容器
        fileViewerContainer = new FrameLayout(this);
        fileViewerContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fileViewerContainer.setBackgroundColor(Color.TRANSPARENT);
        fileViewerContainer.setVisibility(View.GONE);
        webContainer.addView(fileViewerContainer);

        sheetCard.addView(webContainer);

        // 7. 键盘底部占位底板（垫在键盘下方，具有与卡片一致的同色毛玻璃底色，绝不漏桌面壁纸）
        keyboardSpacer = new View(this);
        keyboardSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        keyboardSpacer.setBackgroundColor(Color.TRANSPARENT);
        sheetCard.addView(keyboardSpacer);

        rootOverlay.addView(sheetCard);

        return rootOverlay;
    }

    private HeaderIconButton createHeaderIconButton(int iconType, int iconColor, String contentDescription) {
        HeaderIconButton btn = new HeaderIconButton(this, iconType, iconColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(36), dpToPx(36));
        btn.setLayoutParams(lp);
        btn.setContentDescription(contentDescription);
        btn.setClickable(true);
        btn.setFocusable(true);

        // 圆形水波纹反馈
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#253D6FD4")), null, mask);
        btn.setBackground(ripple);

        return btn;
    }

    /** 4 按钮高精度矢量绘制 View（统一 36x36dp 容器、1.85dp 规范线宽、圆倒角与对称视觉） */
    private static class HeaderIconButton extends View {
        private int iconType;
        private final Paint paint;
        private final RectF rectF = new RectF();

        public HeaderIconButton(Context context, int iconType, int color) {
            super(context);
            this.iconType = iconType;
            float density = context.getResources().getDisplayMetrics().density;
            float strokeWidthPx = 1.85f * density;

            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(color);
            paint.setStrokeWidth(strokeWidthPx);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        public void setIconType(int iconType) {
            if (this.iconType != iconType) {
                this.iconType = iconType;
                invalidate();
            }
        }

        public void setIconColor(int color) {
            if (paint.getColor() != color) {
                paint.setColor(color);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float dp = getResources().getDisplayMetrics().density;

            switch (iconType) {
                case ICON_CLOSE: { // ① [ ✕ ] 关闭 (光学收敛，端点半长 6.6dp，避免视觉膨胀)
                    float s = 6.6f * dp;
                    canvas.drawLine(cx - s, cy - s, cx + s, cy + s, paint);
                    canvas.drawLine(cx - s, cy + s, cx + s, cy - s, paint);
                    break;
                }
                case ICON_SETTINGS: { // ② [ >_ 容器控制台 ] (圆角窗口外框 + 内部命令行提示符 > _)
                    float halfW = 8.5f * dp;
                    float halfH = 7.0f * dp;
                    float r = 2.8f * dp;
                    rectF.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
                    canvas.drawRoundRect(rectF, r, r, paint);

                    // 命令行提示符 >
                    Path path = new Path();
                    path.moveTo(cx - 4.5f * dp, cy - 2.8f * dp);
                    path.lineTo(cx - 1.2f * dp, cy);
                    path.lineTo(cx - 4.5f * dp, cy + 2.8f * dp);
                    canvas.drawPath(path, paint);

                    // 光标下划线 _
                    canvas.drawLine(cx + 0.8f * dp, cy + 2.8f * dp, cx + 4.8f * dp, cy + 2.8f * dp, paint);
                    break;
                }
                case ICON_NEW_CHAT: { // ③ [ 💬➕ ] 新建会话 (圆角对话气泡 + 内部十字加号)
                    float halfW = 8.2f * dp;
                    float topH = 7.2f * dp;
                    float botH = 3.5f * dp;
                    float r = 2.5f * dp;
                    rectF.set(cx - halfW, cy - topH, cx + halfW, cy + botH);
                    canvas.drawRoundRect(rectF, r, r, paint);
                    // 气泡小尾巴
                    Path path = new Path();
                    path.moveTo(cx - 2.8f * dp, cy + botH);
                    path.lineTo(cx - 6.2f * dp, cy + botH + 4.2f * dp);
                    path.lineTo(cx - 6.2f * dp, cy + botH);
                    canvas.drawPath(path, paint);
                    // 内部加号
                    float plusR = 2.8f * dp;
                    float plusCy = cy - 1.8f * dp;
                    canvas.drawLine(cx, plusCy - plusR, cx, plusCy + plusR, paint);
                    canvas.drawLine(cx - plusR, plusCy, cx + plusR, plusCy, paint);
                    break;
                }
                case ICON_FULLSCREEN: { // ④ [ ⬒ ] 全屏展开 (顺时针旋转90°后的顶栏+主视口分栏)
                    float halfW = 8.0f * dp;
                    float halfH = 8.0f * dp;
                    float r = 2.8f * dp;
                    rectF.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
                    canvas.drawRoundRect(rectF, r, r, paint);
                    // 顶栏水平分割线
                    float dividerY = cy - 2.8f * dp;
                    canvas.drawLine(cx - halfW, dividerY, cx + halfW, dividerY, paint);
                    break;
                }
                case ICON_FILES: { // ⑤ [ 📁 工作区文件 ] (矢量圆角文件夹轮廓，与终端框光学一致)
                    float halfW = 8.5f * dp;
                    float topY = cy - 6.2f * dp;
                    float botY = cy + 6.2f * dp;
                    float tabW = 6.2f * dp;
                    float tabH = 2.4f * dp;
                    float r = 2.2f * dp;

                    // 文件夹轮廓 Path
                    Path folderPath = new Path();
                    folderPath.moveTo(cx - halfW + r, topY);
                    // 顶部标签 Tab
                    folderPath.lineTo(cx - halfW + tabW, topY);
                    folderPath.lineTo(cx - halfW + tabW + 1.8f * dp, topY + tabH);
                    folderPath.lineTo(cx + halfW - r, topY + tabH);
                    // 右上圆角
                    folderPath.quadTo(cx + halfW, topY + tabH, cx + halfW, topY + tabH + r);
                    // 右侧垂直线至底部
                    folderPath.lineTo(cx + halfW, botY - r);
                    // 右下圆角
                    folderPath.quadTo(cx + halfW, botY, cx + halfW - r, botY);
                    // 底部水平线
                    folderPath.lineTo(cx - halfW + r, botY);
                    // 左下圆角
                    folderPath.quadTo(cx - halfW, botY, cx - halfW, botY - r);
                    // 左侧垂直线至顶部
                    folderPath.lineTo(cx - halfW, topY + r);
                    // 左上圆角
                    folderPath.quadTo(cx - halfW, topY, cx - halfW + r, topY);
                    folderPath.close();
                    canvas.drawPath(folderPath, paint);

                    // 内部水平层叠线条（体现文件夹深度）
                    float lineY = topY + tabH + 2.8f * dp;
                    canvas.drawLine(cx - halfW + 3.0f * dp, lineY, cx + halfW - 3.0f * dp, lineY, paint);
                    break;
                }
                case ICON_BACK: { // ⑥ [ ‹ 返回 ] (经典优雅返回箭头，与关闭/终端线宽一致)
                    Path arrow = new Path();
                    arrow.moveTo(cx + 2.0f * dp, cy - 6.0f * dp);
                    arrow.lineTo(cx - 3.5f * dp, cy);
                    arrow.lineTo(cx + 2.0f * dp, cy + 6.0f * dp);
                    canvas.drawPath(arrow, paint);
                    canvas.drawLine(cx - 3.5f * dp, cy, cx + 5.5f * dp, cy, paint);
                    break;
                }
                case ICON_REFRESH: { // ⑦ [ 🔄 刷新 ] (与旁边新对话、全屏光学对齐，统一1.85dp线宽与圆倒角)
                    float r = 6.6f * dp;
                    rectF.set(cx - r, cy - r, cx + r, cy + r);
                    // 顺时针圆弧：从 45° 扫 275° 到 320° (-40°)，留出右上角 85° 缺口供箭头穿透
                    canvas.drawArc(rectF, 45f, 275f, false, paint);

                    // 终点在 -40° 处（右上方偏右）
                    double rad = Math.toRadians(-40.0);
                    float x0 = cx + r * (float) Math.cos(rad);
                    float y0 = cy + r * (float) Math.sin(rad);

                    // 顺时针切线方向为 50°，箭头向切向顺延，两翼折角回勾 (长度 3.2dp)
                    float arrowLen = 3.2f * dp;
                    Path arrowPath = new Path();
                    // 外侧翼 (约 195°)
                    float w1x = x0 + arrowLen * (float) Math.cos(Math.toRadians(195.0));
                    float w1y = y0 + arrowLen * (float) Math.sin(Math.toRadians(195.0));
                    // 内侧翼 (约 265°)
                    float w2x = x0 + arrowLen * (float) Math.cos(Math.toRadians(265.0));
                    float w2y = y0 + arrowLen * (float) Math.sin(Math.toRadians(265.0));

                    arrowPath.moveTo(w1x, w1y);
                    arrowPath.lineTo(x0, y0);
                    arrowPath.lineTo(w2x, w2y);
                    canvas.drawPath(arrowPath, paint);
                    break;
                }
            }
        }
    }

    /** 键盘精准监听：单次状态跃迁拉升高度，底部占位块承托输入法，使输入框自然上浮 */
    private void setupKeyboardObserver() {
        if (getWindow() == null || getWindow().getDecorView() == null) return;
        View decorView = getWindow().getDecorView();

        keyboardLayoutListener = () -> {
            int keyboardHeight = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && decorView.getRootWindowInsets() != null) {
                keyboardHeight = decorView.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
            }
            if (keyboardHeight <= 0) {
                Rect r = new Rect();
                decorView.getWindowVisibleDisplayFrame(r);
                int totalHeight = decorView.getRootView().getHeight();
                if (totalHeight <= 0) totalHeight = screenHeight;
                int diff = totalHeight - r.bottom;
                if (diff > dpToPx(100)) {
                    keyboardHeight = diff;
                }
            }

            boolean isKeyboardVisible = keyboardHeight > dpToPx(100);

            if (keyboardSpacer != null) {
                ViewGroup.LayoutParams lp = keyboardSpacer.getLayoutParams();
                if (lp != null && lp.height != keyboardHeight) {
                    lp.height = keyboardHeight;
                    keyboardSpacer.setLayoutParams(lp);
                }
            }

            // 状态跃迁锁：仅在键盘从“隐藏”变为“弹出”的单次边缘跳变时触发拉升，防止每帧循环打断动画
            if (isKeyboardVisible) {
                if (!isKeyboardElevated) {
                    isKeyboardElevated = true;
                    ConfigStore cfg = new ConfigStore(this);
                    if (cfg.isSheetAutoRestoreDefault() && currentHeight <= (int) (screenHeight * 0.45f)) {
                        animateHeightTo(defaultHeight);
                    }
                }
            } else {
                isKeyboardElevated = false;
            }
        };
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);
    }

    private void setupGesture() {
        View.OnTouchListener gestureListener = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchY = event.getRawY();
                    initialHeightOnTouch = currentHeight;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - initialTouchY; // 下滑 > 0，上滑 < 0
                    int targetH = (int) (initialHeightOnTouch - dy);
                    if (targetH > maxHeight) targetH = maxHeight;
                    if (targetH > 0) {
                        currentHeight = targetH;
                        updateCardHeight(targetH);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 1. 低于安全下限阈值（< 25%），顺滑向下退出
                    if (currentHeight < minHeight) {
                        dismissSheet();
                    } else {
                        // 2. 15% 步长阶梯智能吸附停留（35%, 50%, 65%, 80%, 95%）
                        snapToNearest15PercentStep();
                    }
                    return true;
            }
            return false;
        };

        if (sheetCard.getChildCount() > 0) {
            sheetCard.getChildAt(0).setOnTouchListener(gestureListener); // dragArea
        }
        if (sheetCard.getChildCount() > 1) {
            sheetCard.getChildAt(1).setOnTouchListener(gestureListener); // headerBar
        }
    }

    private void updateCardHeight(int height) {
        if (sheetCard != null) {
            ViewGroup.LayoutParams lp = sheetCard.getLayoutParams();
            if (lp != null && lp.height != height) {
                lp.height = height;
                sheetCard.setLayoutParams(lp);
            }
        }
    }

    /** 智能多档吸附算法（基础 35%, 50%, 65%, 80%, 95% + 用户自定义默认高度档位） */
    private void snapToNearest15PercentStep() {
        float userRatio = new ConfigStore(this).getSheetHeightPercent() / 100.0f;
        java.util.TreeSet<Float> stepSet = new java.util.TreeSet<>();
        stepSet.add(0.35f);
        stepSet.add(0.50f);
        stepSet.add(0.65f);
        stepSet.add(0.80f);
        stepSet.add(0.95f);
        stepSet.add(userRatio);

        float currentRatio = (float) currentHeight / (float) screenHeight;
        float closestRatio = userRatio;
        float minDiff = Float.MAX_VALUE;

        for (Float step : stepSet) {
            float diff = Math.abs(currentRatio - step);
            if (diff < minDiff) {
                minDiff = diff;
                closestRatio = step;
            }
        }

        int targetH = (int) (screenHeight * closestRatio);
        animateHeightTo(targetH);
    }

    private void animateHeightTo(int targetH) {
        int startH = currentHeight;
        if (startH == targetH) return;

        if (heightAnimator != null && heightAnimator.isRunning()) {
            heightAnimator.cancel();
        }

        if (sheetCard == null) {
            currentHeight = targetH;
            return;
        }

        currentHeight = targetH;

        if (targetH > startH) {
            int deltaY = targetH - startH;
            updateCardHeight(targetH);
            sheetCard.setTranslationY(deltaY);
            sheetCard.animate()
                    .translationY(0)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setListener(null)
                    .start();
        } else {
            int deltaY = startH - targetH;
            sheetCard.animate()
                    .translationY(deltaY)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (sheetCard != null) {
                                updateCardHeight(targetH);
                                sheetCard.setTranslationY(0);
                            }
                        }
                    })
                    .start();
        }
    }

    /** 创建并配置标准参数的 DshaWebView 实例（本机与远端槽位共用） */
    private WebView createConfiguredWebView() {
        DshaWebView web = new DshaWebView(getApplicationContext());
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setSupportMultipleWindows(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        ws.setTextZoom(100);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        boolean initDark = new ConfigStore(getApplicationContext()).isSheetInvertColor();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ws.setForceDark(initDark ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                ws.setAlgorithmicDarkeningAllowed(false);
            } catch (Throwable ignored) {}
        }

        web.setBackgroundColor(Color.TRANSPARENT);

        boolean desktop = getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getBoolean("desktop_mode", false);
        if (desktop) {
            ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        }

        web.setWebViewClient(createSheetWebViewClient());
        web.setWebChromeClient(new SheetChromeClient());
        web.addJavascriptInterface(new NativeBridgeInterface(), "DshaNativeBridge");

        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype);
                String cks = android.webkit.CookieManager.getInstance().getCookie(url);
                if (cks != null && !cks.isEmpty()) request.addRequestHeader("cookie", cks);
                request.addRequestHeader("User-Agent", userAgent);
                String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
                request.setDescription("正在下载文件 " + fileName);
                request.setTitle(fileName);
                request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "DSHA/" + fileName);
                android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(this, "开始下载：" + fileName + "（保存在 Download/DSHA/）", Toast.LENGTH_LONG).show();
                }
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable ignored) {}
            }
        });

        android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        return web;
    }

    private void ensureWebViewParentClean(WebView wv) {
        if (wv != null && wv.getParent() instanceof ViewGroup) {
            ((ViewGroup) wv.getParent()).removeView(wv);
        }
    }

    private void mountWebViewToContainer(WebView wv) {
        if (wv == null || webContainer == null) return;
        if (wv.getParent() != webContainer) {
            ensureWebViewParentClean(wv);
            webContainer.addView(wv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void updateWebViewSlotVisibility() {
        if (sLocalWebView != null) {
            sLocalWebView.setVisibility(sIsRemoteActive ? View.GONE : View.VISIBLE);
            if (!sIsRemoteActive) {
                sLocalWebView.onResume();
                sLocalWebView.resumeTimers();
            } else {
                sLocalWebView.onPause();
            }
        }
        if (sRemoteWebView != null) {
            sRemoteWebView.setVisibility(sIsRemoteActive ? View.VISIBLE : View.GONE);
            if (sIsRemoteActive) {
                sRemoteWebView.onResume();
                sRemoteWebView.resumeTimers();
            } else {
                sRemoteWebView.onPause();
            }
        }
        sCachedWebView = getActiveWebView();
        if (sCachedWebView != null) {
            mountWebViewToContainer(sCachedWebView);
            sCachedWebView.bringToFront();
            injectTransparentBackground(sCachedWebView);
        }
        if (progressBar != null) {
            progressBar.bringToFront();
            boolean loaded = sIsRemoteActive ? sRemoteWebLoaded : sLocalWebLoaded;
            String curUrl = sCachedWebView != null ? sCachedWebView.getUrl() : null;
            boolean hasValidContent = curUrl != null && !curUrl.isEmpty() && !"about:blank".equals(curUrl);
            progressBar.setVisibility((loaded || hasValidContent) ? View.GONE : View.VISIBLE);
        }
        if (btnRefresh != null) {
            btnRefresh.setContentDescription(sIsRemoteActive ? "重新连接远端DSH" : "重新获取Token并刷新进入DSH");
        }
        updateHeaderTitleText();
    }

    private void updateHeaderTitleText() {
        if (headerTitle == null) return;
        if (fileViewerContainer != null && fileViewerContainer.getVisibility() == View.VISIBLE && currentViewingFile != null) {
            return;
        }
        if (sIsRemoteActive) {
            ConfigStore cfg = ConfigStore.get(this);
            com.deepseekharness.app.core.ConfigStore.RemoteDshEntry entry = cfg.getActiveRemoteDshEntry();
            String name = (entry != null && entry.name != null && !entry.name.trim().isEmpty())
                    ? entry.name.trim() : "远端";
            headerTitle.setText(name + " 对话");
        } else {
            headerTitle.setText("DSHA 对话");
        }
        headerTitle.setTextSize(17);
    }

    private void toggleDshConnectionTarget() {
        ConfigStore cfg = new ConfigStore(this);
        String activeRemoteUrl = cfg.getActiveRemoteDshUrl();
        boolean hasActiveRemote = cfg.isAnyRemoteDshActive() && activeRemoteUrl != null && !activeRemoteUrl.isEmpty();

        if (!sIsRemoteActive) {
            // 当前为本机模式，尝试切换到远端模式
            if (!hasActiveRemote) {
                Toast.makeText(this, "未开启任何远端连接，请在「访问地址与凭据」中启用", Toast.LENGTH_SHORT).show();
                return;
            }
            sIsRemoteActive = true;
            ensureRemoteWebViewLoaded(activeRemoteUrl);
            mountWebViewToContainer(sRemoteWebView);
            updateWebViewSlotVisibility();
            com.deepseekharness.app.core.ConfigStore.RemoteDshEntry entry = cfg.getActiveRemoteDshEntry();
            String name = entry != null && entry.name != null && !entry.name.isEmpty() ? entry.name : "远端";
            Toast.makeText(this, "已切换至「" + name + "」", Toast.LENGTH_SHORT).show();
        } else {
            // 当前为远端模式，切换回本机模式
            sIsRemoteActive = false;
            if (sLocalWebView == null) {
                sLocalWebView = createConfiguredWebView();
                loadInitialLocalWeb();
            }
            mountWebViewToContainer(sLocalWebView);
            updateWebViewSlotVisibility();

            // 智能判定：如果本地已经载入过正常内容，直接呈现，杜绝重复转圈与白屏
            String localUrl = sLocalWebView != null ? sLocalWebView.getUrl() : null;
            boolean hasValidUrl = localUrl != null && !localUrl.isEmpty() && !"about:blank".equals(localUrl);
            if (!hasValidUrl) {
                forceReloadWithLatestToken();
            } else {
                sLocalWebLoaded = true;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            }
            Toast.makeText(this, "已切换至本机 DSH", Toast.LENGTH_SHORT).show();
        }
    }

    private void ensureRemoteWebViewLoaded(String remoteUrl) {
        if (sRemoteWebView == null) {
            sRemoteWebView = createConfiguredWebView();
        }
        if (remoteUrl != null && (!remoteUrl.equals(sLoadedRemoteUrl) || !sRemoteWebLoaded)) {
            sLoadedRemoteUrl = remoteUrl;
            sRemoteWebLoaded = false;
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            sRemoteWebView.loadUrl(remoteUrl);
        }
    }

    private void loadInitialLocalWeb() {
        if (sLocalWebView == null) return;
        android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
        int port = controller != null ? controller.getPort() : 3080;
        String base = "http://127.0.0.1:" + port + "/";
        sLoadedPort = port;

        new Thread(() -> {
            try {
                String authCookie = controller != null ? controller.exchangeDshAuthCookie() : null;
                if (authCookie != null && !authCookie.isEmpty()) {
                    String cookieVal = authCookie.contains(";") ? authCookie : (authCookie + "; Path=/; HttpOnly; SameSite=Lax");
                    cookies.setCookie(base, cookieVal);
                    cookies.setCookie("http://127.0.0.1/", cookieVal);
                }
                String bt = com.deepseekharness.app.HttpShellService.ensureToken();
                if (bt != null && !bt.isEmpty()) {
                    String dshaCookie = "dsha_t=" + bt + "; Path=/; SameSite=Lax; Max-Age=31536000";
                    cookies.setCookie(base, dshaCookie);
                    cookies.setCookie("http://127.0.0.1/", dshaCookie);
                }
                cookies.flush();
            } catch (Throwable ignored) {}
        }, "sheet-cookie-init").start();

        String authUrl = controller != null ? controller.getWebAuthUrl() : "";
        if (authUrl != null && !authUrl.isEmpty()) {
            if (controller != null) {
                sLoadedGeneration = controller.getWebGeneration();
            }
            sLocalWebView.loadUrl(authUrl);
        } else {
            if (progressBar != null && !sIsRemoteActive) progressBar.setVisibility(View.VISIBLE);
            if (controller != null) controller.tryRecoverRunningUrl();
            new Thread(() -> {
                for (int step = 0; step < 30; step++) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                    String readyUrl = controller != null ? controller.getWebAuthUrl() : "";
                    if (readyUrl != null && !readyUrl.isEmpty()) {
                        if (controller != null) {
                            sLoadedGeneration = controller.getWebGeneration();
                        }
                        runOnUiThread(() -> {
                            if (sLocalWebView != null && !isFinishing() && !isDestroyed()) {
                                sLocalWebView.loadUrl(readyUrl);
                            }
                        });
                        break;
                    }
                }
            }, "sheet-wait-auth").start();
        }
    }

    /** 挂载常驻双槽位 WebView，实现本机与远端 100% 零转圈秒开、瞬间热切换 */
    private void attachChatWeb() {
        ConfigStore cfg = new ConfigStore(getApplicationContext());
        String activeRemoteUrl = cfg.getActiveRemoteDshUrl();
        boolean hasActiveRemote = cfg.isAnyRemoteDshActive() && activeRemoteUrl != null && !activeRemoteUrl.isEmpty();

        // 核心规范：无论远端是否配置生效，打开 Quick 抽屉时始终默认进入本机端！
        // 远端连接完全由用户在抽屉内主动点击顶栏切换按钮时按需切入
        sIsRemoteActive = false;

        // 1. 本机槽位
        if (sLocalWebView == null) {
            sLocalWebView = createConfiguredWebView();
            loadInitialLocalWeb();
        } else {
            ensureWebViewParentClean(sLocalWebView);
        }

        // 2. 远端槽位
        if (sIsRemoteActive) {
            ensureRemoteWebViewLoaded(activeRemoteUrl);
        } else if (sRemoteWebView != null) {
            // 开关已关闭，彻底释放网络连接与心跳
            sRemoteWebView.stopLoading();
            sRemoteWebView.loadUrl("about:blank");
            sRemoteWebView.onPause();
        }

        // 3. 挂载到容器
        mountWebViewToContainer(sLocalWebView);
        if (sRemoteWebView != null && sIsRemoteActive) {
            mountWebViewToContainer(sRemoteWebView);
        }

        // 4. 更新槽位可见性与当前活跃 WebView
        updateWebViewSlotVisibility();
    }

    private static void dispatchNativeBridgeFileAction(String rawPath, int action, float touchX, float touchY) {
        if (rawPath == null || rawPath.isEmpty()) return;
        String path = rawPath.trim();
        try {
            path = java.net.URLDecoder.decode(path, "UTF-8");
        } catch (Exception ignored) {}

        // 剥离 file:// 协议头
        if (path.startsWith("file://")) {
            path = path.substring("file://".length());
        }

        // 剥离查询参数与行号锚点 (?xxx, #xxx)
        int queryIdx = path.indexOf('?');
        if (queryIdx >= 0) path = path.substring(0, queryIdx);
        int hashIdx = path.indexOf('#');
        if (hashIdx >= 0) path = path.substring(0, hashIdx);

        if (sIsRemoteActive) {
            // 远端模式保留真实路径或工作区相对路径
        } else if (path.contains("dsh-resource://file/session/")) {
            int idx = path.indexOf("/session/");
            if (idx >= 0) {
                String sub = path.substring(idx + 9);
                int slash = sub.indexOf('/');
                if (slash >= 0) {
                    String tail = sub.substring(slash + 1);
                    if (tail.startsWith("/")) {
                        path = tail;
                    } else if (tail.startsWith("sdcard/") || tail.startsWith("storage/")) {
                        path = "/" + tail;
                    } else {
                        path = "/sdcard/Download/DSHA/工作区/" + tail;
                    }
                }
            }
        } else if (!path.startsWith("/")) {
            if (path.startsWith("sdcard/") || path.startsWith("storage/")) {
                path = "/" + path;
            } else {
                path = "/sdcard/Download/DSHA/工作区/" + path;
            }
        }

        final String finalPath = path;
        final String origRawPath = rawPath;
        QuickChatSheetActivity act = sCurrentInstance;
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        act.runOnUiThread(() -> {
            QuickChatSheetActivity currentAct = sCurrentInstance;
            if (currentAct == null || currentAct.isFinishing() || currentAct.isDestroyed()) return;
            if (sIsRemoteActive && action == 0) {
                // 远端模式下短按放行给 Web 原生查看，不强行尝试从本地磁盘打开
                return;
            }
            File f = new File(finalPath);
            if (!f.exists()) {
                try {
                    File c = f.getCanonicalFile();
                    if (c.exists()) f = c;
                } catch (Exception ignored) {}
            }
            if (!f.exists() && finalPath.startsWith("/sdcard/")) {
                File emu = new File(finalPath.replaceFirst("^/sdcard", "/storage/emulated/0"));
                if (emu.exists()) f = emu;
            } else if (!f.exists() && finalPath.startsWith("/storage/emulated/0/")) {
                File sd = new File(finalPath.replaceFirst("^/storage/emulated/0", "/sdcard"));
                if (sd.exists()) f = sd;
            }
            if (!f.exists() && finalPath.contains("/Download/DSHA/工作区/")) {
                File dl = new File(finalPath.replace("/Download/DSHA/工作区/", "/Download/"));
                if (dl.exists()) f = dl;
            }

            // Root 特权保障：赋予所有人可读权限，确保 MT 管理器和安装器畅通读取物理文件
            if (f.exists()) {
                try {
                    f.setReadable(true, false);
                } catch (Throwable ignored) {}
            }

            android.util.Log.i("DSHA_OPEN", "action=" + action + " rawPath=" + origRawPath + " resolved=" + f.getAbsolutePath() + " exists=" + f.exists());
            if (action == 2) {
                currentAct.showWorkspaceFileActionMenu(f, origRawPath, touchX, touchY);
            } else if (action == 1 || action == 3) {
                if (currentAct.rootOverlay != null) {
                    try {
                        currentAct.rootOverlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    } catch (Throwable ignored) {}
                }
                // 工作区内部维持原逻辑不变；工作区外部通过 Root 预先放开文件读取权限并呼出系统打开方式选择面板
                boolean isInside = f.getAbsolutePath().startsWith("/sdcard/Download/DSHA/工作区/")
                        || f.getAbsolutePath().startsWith("/storage/emulated/0/Download/DSHA/工作区/");
                if (!isInside) {
                    new Thread(() -> {
                        try {
                            Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 '" + f.getAbsolutePath() + "'"}).waitFor();
                        } catch (Throwable ignored) {}
                    }).start();
                }
                com.deepseekharness.app.viewer.FileOpenHelper.openWithSystem(currentAct, f);
            } else {
                currentAct.openFileInSheet(finalPath);
            }
        });
    }

    public static class NativeBridgeInterface {
        @android.webkit.JavascriptInterface
        public void openWorkspaceFile(String rawPath) {
            dispatchNativeBridgeFileAction(rawPath, 0, -1, -1);
        }

        @android.webkit.JavascriptInterface
        public void openExternalFile(String rawPath) {
            dispatchNativeBridgeFileAction(rawPath, 1, -1, -1);
        }

        @android.webkit.JavascriptInterface
        public void showFileActionMenu(String rawPath) {
            dispatchNativeBridgeFileAction(rawPath, 2, -1, -1);
        }

        @android.webkit.JavascriptInterface
        public void showFileActionMenuAt(String rawPath, float touchX, float touchY) {
            dispatchNativeBridgeFileAction(rawPath, 2, touchX, touchY);
        }

        @android.webkit.JavascriptInterface
        public void locateOrOpenExternalFile(String rawPath) {
            dispatchNativeBridgeFileAction(rawPath, 1, -1, -1);
        }
    }

    /** 设置页「抽屉反色开关」与「莫奈取色开关」变动时即时刷新活动中的抽屉及缓存的 WebView */
    public static void refreshThemeFromConfig(Context context) {
        if (context == null) return;
        MonetThemeHelper.clearCache(context);
        boolean invert = new ConfigStore(context).isSheetInvertColor();
        boolean monet = new ConfigStore(context).isSheetMonetColor();
        boolean monetInvert = new ConfigStore(context).isSheetMonetInvert();
        QuickChatSheetActivity act = sCurrentInstance;
        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
            act.runOnUiThread(() -> {
                act.isDarkMode = invert;
                act.isMonetColor = monet;
                act.isMonetInvert = monetInvert;
                act.updateCardTheme();
            });
        }
        refreshImmersiveTheme(context);
    }

    /** 覆写前端背景与输入框底座保护，确保沉浸透光同时彻底根除输入框塌陷、文字穿透与二层菜单失真 */
    public static void refreshImmersiveTheme(Context context) {
        if (sCachedWebView == null || context == null) return;
        sCachedWebView.post(() -> {
            try {
                com.deepseekharness.app.core.ConfigStore cfg = new com.deepseekharness.app.core.ConfigStore(context);
                boolean dark = cfg.isSheetInvertColor();
                boolean monet = cfg.isSheetMonetColor();
                boolean immersive = cfg.isSheetImmersive();
                int opacity = dark ? cfg.getSheetOpacityNight() : cfg.getSheetOpacityDay();
                MonetThemeHelper.Palette palette = MonetThemeHelper.resolve(context, dark, monet, opacity);

                // 动态同步 WebView 内核深浅色模式，使 (prefers-color-scheme: dark) 真实跟随容器模式
                WebSettings ws = sCachedWebView.getSettings();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        ws.setForceDark(dark ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
                    } catch (Throwable ignored) {}
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    try {
                        ws.setAlgorithmicDarkeningAllowed(false);
                    } catch (Throwable ignored) {}
                }

                String inputBg = palette.inputBg;
                String inputBorder = palette.inputBorder;

                // 二层菜单、抽屉与弹窗的防穿透高质感底色
                String drawerBg = palette.drawerBg;
                String menuBg = palette.menuBg;
                String selectorBg = palette.selectorBg;
                String dialogBg = palette.dialogBg;
                String menuBorder = palette.menuBorder;

                String commonVars = "  --dsw-alias-bg-base: transparent !important;\n"
                        + "  --dsh-boot-bg: transparent !important;\n"
                        + "  /* 任务完成横幅彻底透明透光 */\n"
                        + "  --dsw-specific-tip: transparent !important;\n"
                        + "  /* 二层菜单、抽屉与选择器恢复防穿透实体底色与毛玻璃变量 */\n"
                        + "  --dsh-drawer-bg: " + drawerBg + " !important;\n"
                        + "  --dsh-menu-bg: " + menuBg + " !important;\n"
                        + "  --dsh-dialog-bg: " + dialogBg + " !important;\n"
                        + "  --dsh-menu-border: " + menuBorder + " !important;\n"
                        + "  --dsw-specific-sidebar-fill: " + drawerBg + " !important;\n"
                        + "  --dsw-specific-menu: " + menuBg + " !important;\n"
                        + "  --dsw-specific-selector: " + selectorBg + " !important;\n"
                        + "  --dsw-alias-bg-layer-1: " + menuBg + " !important;\n"
                        + "  --dsw-alias-bg-layer-2: " + dialogBg + " !important;\n"
                        + "  --dsw-alias-bg-layer-3: " + menuBg + " !important;\n"
                        + "  /* 代码块与行内代码半透微光，彻底消除不透明黑块 */\n"
                        + "  --dsw-alias-markdown-code-block: rgba(128, 128, 128, 0.08) !important;\n"
                        + "  --dsw-alias-markdown-code-block-banner: rgba(128, 128, 128, 0.05) !important;\n"
                        + "  --dsw-alias-markdown-inline-code: rgba(128, 128, 128, 0.12) !important;\n"
                        + "  --dsw-specific-input-major: " + inputBg + " !important;\n";

                String textColorVars = "  --dsw-alias-label-primary: " + palette.textPrimaryHex + " !important;\n"
                        + "  --dsw-alias-label-secondary: " + palette.textSecondaryHex + " !important;\n"
                        + "  --dsw-alias-brand-text: " + palette.brandTextHex + " !important;\n";

                String dashboardDarkCss = dark ? (
                        "/* ===== dsh-api-dashboard 插件深色模式强制覆盖 ===== */\n"
                        + ".dshadb_bar { background: #262a33 !important; border: 1px solid #363c48 !important; color: #8ba0b8 !important; }\n"
                        + ".dshadb_bar_name, .dshadb_bar_amount { color: #8ba0b8 !important; }\n"
                        + ".dshadb_bar_cost { border-left: 1px solid #363c48 !important; color: #56697e !important; }\n"
                        + ".dshadb_field { background: #1e222a !important; border-color: #363c48 !important; color: #8ba0b8 !important; }\n"
                        + ".dshadb_field_select { background-image: linear-gradient(45deg,transparent 50%,#56697e 50%),linear-gradient(135deg,#56697e 50%,transparent 50%) !important; }\n"
                        + ".dshadb_switch { background: #3d434f !important; }\n"
                        + ".dshadb_tab { background: #262a33 !important; color: #8ba0b8 !important; }\n"
                        + ".dshadb_kind, .dshadb_sub, .dshadb_wf_row, .dshadb_wf_row_col { background: #262a33 !important; border-color: #363c48 !important; color: #56697e !important; }\n"
                        + ".dshadb_wf_name, .dshadb_sub_name { color: #8ba0b8 !important; }\n"
                        + ".dshadb_whale-bubble { background: #262a33 !important; }\n"
                        + ".dshadb_whale-bubble::after { border-top-color: #262a33 !important; }\n"
                        + ".dshadb_whale-l-a { color: #56697e !important; }\n"
                        + ".dshadb_whale-l-b { color: #8ba0b8 !important; }\n"
                        + ".dshadb_whale-l-c { color: #4a5a6d !important; }\n"
                ) : "";

                String cssImmersive = "html, body, #root, main, .dsh-layout-root, "
                        + "div[class*='pI_x6G_frame'], div[class*='pI_x6G_centerCol'], "
                        + "div[class*='_scrollBody'], div[class*='_viewArea'], "
                        + "div[class*='wSkVaW_root'], div[class*='_composerHero'], div[class*='_dock'], "
                        + "div[class*='_bannerWrap'] {\n"
                        + "  background: transparent !important;\n"
                        + "  background-color: transparent !important;\n"
                        + "  background-image: none !important;\n"
                        + "}\n"
                        + ":root, html, body, body[data-ds-dark-theme], .dark, [data-theme] {\n"
                        + commonVars + textColorVars
                        + "}\n"
                        + dashboardDarkCss
                        + "/* 任务完成横幅：半透明微透光卡片，带精致圆角边框 */\n"
                        + "div[class*='lXshSW_root'] {\n"
                        + "  background: rgba(128, 128, 128, 0.06) !important;\n"
                        + "  background-color: rgba(128, 128, 128, 0.06) !important;\n"
                        + "  border-color: rgba(128, 128, 128, 0.18) !important;\n"
                        + "}\n"
                        + "/* 消息视口：底部边界精确止于输入框上方物理截断，绝不设置包含块 transform 防止破坏贴底定位 */\n"
                        + "div[class*='_scrollBody'] {\n"
                        + "  margin-bottom: var(--dsh-composer-height, 148px) !important;\n"
                        + "  overflow-y: auto !important;\n"
                        + "  overflow-x: hidden !important;\n"
                        + "}\n"
                        + "/* 轨迹全景视图专项隔离：消除官方 overlay 引入的相对定位与底部断层，底座无缝贴底，且 100% 绝不干扰普通对话 */\n"
                        + "div[class*='_scrollBody']:has([data-conversation-composer-overlay]) {\n"
                        + "  margin-bottom: 0px !important;\n"
                        + "  position: static !important;\n"
                        + "}\n"
                        + "/* 输入框底座：完全透明透光，绝对定位固定贴底，绝不使用实心色遮挡 */\n"
                        + "div[class*='_composerSeat'] {\n"
                        + "  position: absolute !important;\n"
                        + "  bottom: 0 !important;\n"
                        + "  left: 0 !important;\n"
                        + "  right: 0 !important;\n"
                        + "  z-index: 10 !important;\n"
                        + "  background: transparent !important;\n"
                        + "  background-color: transparent !important;\n"
                        + "  background-image: none !important;\n"
                        + "}\n"
                        + "/* 输入框卡片：半透明通透衬底，局部样式与布局隔离，防止打字波及外层 */\n"
                        + "div[class*='uV2eYG_card'] {\n"
                        + "  background: " + inputBg + " !important;\n"
                        + "  background-color: " + inputBg + " !important;\n"
                        + "  border: 1px solid " + inputBorder + " !important;\n"
                        + "  contain: layout style !important;\n"
                        + "}\n"
                        + "[data-mobile-nav=\"frame\"] {\n"
                        + "  padding-top: 0px !important;\n"
                        + "  /* 把会话侧(滚动层/输入座/菜单弹窗)被提升出的合成层全部收进同一个层叠上下文，\n"
                        + "     让抽屉的 z-index:1300 在 WebView 合成器里重新具有权威性，根治\"抽屉有时被主对话盖住\"。\n"
                        + "     isolation 不会给 position:fixed 后代制造包含块，设置弹窗仍锚定视口。 */\n"
                        + "  isolation: isolate !important;\n"
                        + "}\n"
                        + "/* ===== 全景下层遮挡自动隐身机制 ===== */\n"
                        + "/* 当右侧边栏（文件列表/文档预览）、文件树、侧边栏会话列表、模态弹窗或全屏看板等展开时，底层主会话流、输入框与顶栏瞬间整体隐身，消灭字体重叠 */\n"
                        + "[data-mobile-nav=\"frame\"]:has([data-sidebar-right-open]) div[class*='pI_x6G_centerCol'],\n"
                        + "div[class*='pI_x6G_frame']:has([data-sidebar-right-open]) div[class*='pI_x6G_centerCol'],\n"
                        + "body:has([data-sidebar-right-open]) div[class*='pI_x6G_centerCol'],\n"
                        + "body:has([class*='P3OORG_panel'][data-sidebar-right-open]) div[class*='pI_x6G_centerCol'],\n"
                        + "[data-mobile-nav=\"frame\"]:has([data-sidebar-right-open]) div[class*='_composerSeat'],\n"
                        + "div[class*='pI_x6G_frame']:has([data-sidebar-right-open]) div[class*='_composerSeat'],\n"
                        + "body:has([data-sidebar-right-open]) div[class*='_composerSeat'],\n"
                        + "body:has([class*='P3OORG_panel'][data-sidebar-right-open]) div[class*='_composerSeat'],\n"
                        + "[data-mobile-nav=\"frame\"]:has([data-sidebar-right-open]) [data-phase] header,\n"
                        + "div[class*='pI_x6G_frame']:has([data-sidebar-right-open]) [data-phase] header,\n"
                        + "body:has([data-sidebar-right-open]) [data-phase] header,\n"
                        + "[data-mobile-nav=\"frame\"][data-aionui-preview-open] div[class*='pI_x6G_centerCol'],\n"
                        + "[data-mobile-nav=\"frame\"][data-aionui-explorer-open] div[class*='pI_x6G_centerCol'],\n"
                        + "[data-mobile-nav=\"frame\"][data-aionui-preview-open] div[class*='_composerSeat'],\n"
                        + "[data-mobile-nav=\"frame\"][data-aionui-explorer-open] div[class*='_composerSeat'],\n"
                        + "body:has([aria-modal=\"true\"]) div[class*='pI_x6G_centerCol'],\n"
                        + "body:has([aria-modal=\"true\"]) div[class*='_composerSeat'],\n"
                        + "html[data-dsh-taskboard-active] div[class*='pI_x6G_centerCol'],\n"
                        + "html[data-dsh-ssh-active] div[class*='pI_x6G_centerCol'],\n"
                        + "html[data-dsh-taskboard-active] div[class*='_composerSeat'],\n"
                        + "html[data-dsh-ssh-active] div[class*='_composerSeat'] {\n"
                        + "  visibility: hidden !important;\n"
                        + "  opacity: 0 !important;\n"
                        + "  pointer-events: none !important;\n"
                        + "}\n"
                        + "/* ===== 二层菜单与抽屉防穿透加固 ===== */\n"
                        + "/* 1. 移动端左侧抽屉（会话侧边栏）：防穿透实体底座，严格剔除 backdrop-filter 防止破坏 fixed 弹窗包含块 */\n"
                        + "[data-mobile-nav=\"frame\"] > :first-child,\n"
                        + "div[class*='pI_x6G_sidebarCol'],\n"
                        + "div[class*='hHd-Xa_root'] {\n"
                        + "  background: var(--dsh-drawer-bg) !important;\n"
                        + "  background-color: var(--dsh-drawer-bg) !important;\n"
                        + "  box-shadow: 4px 0 24px rgba(0, 0, 0, 0.3) !important;\n"
                        + "  overflow-x: hidden !important;\n"
                        + "  /* 把抽屉提升为独立合成层，确保它在合成器排序中压过被提升的会话滚动层。\n"
                        + "     只用 will-change:opacity(不用 transform)，避免给内嵌 fixed 设置弹窗制造包含块。 */\n"
                        + "  will-change: opacity !important;\n"
                        + "}\n"
                        + "/* 遮罩同样提升为合成层：保证开屏时整块盖住会话层(z-1250 在抽屉 z-1300 之下、会话之上) */\n"
                        + "[data-mobile-nav=\"backdrop\"] {\n"
                        + "  will-change: opacity !important;\n"
                        + "}\n"
                        + "/* 2. 移动端设置弹窗居中与全宽自适应加固 */\n"
                        + "[aria-modal=\"true\"]:has(> :first-child > :last-child > button):not(:has([role=\"navigation\"])):not(:has([class*=\"ZuhsRW\"])) {\n"
                        + "  box-sizing: border-box !important;\n"
                        + "  left: 8px !important;\n"
                        + "  right: 8px !important;\n"
                        + "  width: calc(100vw - 16px) !important;\n"
                        + "  max-width: calc(100vw - 16px) !important;\n"
                        + "}\n"
                        + "/* 3. 所有下拉菜单、选项列表、快捷操作卡片 */\n"
                        + "[role=\"menu\"],\n"
                        + "[role=\"listbox\"],\n"
                        + "div[class*='_menu'],\n"
                        + "ul[class*='_menu'],\n"
                        + "div[class*='_submenu'],\n"
                        + "div[class*='_list_1nxmc'],\n"
                        + "div[class*='_submenu_1nxmc'],\n"
                        + "div[class*='_opPanel'],\n"
                        + "div[class*='bRhRbq_panel'],\n"
                        + "div[class*='JObwrW_panel'],\n"
                        + "div[class*='_dropdown'],\n"
                        + "div[class*='_popover'] {\n"
                        + "  background: var(--dsh-menu-bg) !important;\n"
                        + "  background-color: var(--dsh-menu-bg) !important;\n"
                        + "  backdrop-filter: blur(8px) !important;\n"
                        + "  -webkit-backdrop-filter: blur(8px) !important;\n"
                        + "  border: 1px solid var(--dsh-menu-border) !important;\n"
                        + "  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25) !important;\n"
                        + "  transform: translateZ(0) !important;\n"
                        + "  will-change: transform !important;\n"
                        + "}\n"
                        + "/* 4. 模态弹窗与设置面板：毛玻璃防穿透 */\n"
                        + "[aria-modal=\"true\"],\n"
                        + "[role=\"dialog\"],\n"
                        + "div[class*='_dialog_w1urq'] {\n"
                        + "  background: var(--dsh-dialog-bg) !important;\n"
                        + "  background-color: var(--dsh-dialog-bg) !important;\n"
                        + "  border: 1px solid var(--dsh-menu-border) !important;\n"
                        + "  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.25) !important;\n"
                        + "  transform: translateZ(0) !important;\n"
                        + "  will-change: transform !important;\n"
                        + "}\n"
                        + "/* 5. 右侧文件列表、文档预览抽屉：保持 100% 全景全透明沉浸透光，透出手机桌面壁纸 */\n"
                        + "[data-sidebar-right-panel],\n"
                        + "[data-sidebar-right-open],\n"
                        + "div[class*='P3OORG_panel'],\n"
                        + "div[class*='P3OORG_panelBody'],\n"
                        + "div[class*='k-1LKG_root'],\n"
                        + "div[class*='k-1LKG_body'],\n"
                        + "div[class*='Java6a_renderer'],\n"
                        + "div[class*='_0RKuNG_document'],\n"
                        + "[data-aionui-explorer-col],\n"
                        + "[data-aionui-preview-col] {\n"
                        + "  background: transparent !important;\n"
                        + "  background-color: transparent !important;\n"
                        + "  background-image: none !important;\n"
                        + "}\n"
                        + "/* 6. 彻底禁止文件树与列表节点被长按选中文本，杜绝弹出系统复制选择工具条 */\n"
                        + "[data-files-entry], [data-files-entry] *,\n"
                        + "[data-sidebar-right-panel] *, [data-tab=\"files\"] *,\n"
                        + "[data-sidebar-panel*=\"files\"] * {\n"
                        + "  -webkit-user-select: none !important;\n"
                        + "  user-select: none !important;\n"
                        + "  -webkit-touch-callout: none !important;\n"
                        + "}\n"
                        + "/* 7. 左右抽屉与面板 GPU 硬件加速与状态对齐，杜绝掉帧并修正右侧滑动展开方向 */\n"
                        + "[data-sidebar-right-panel]:not([data-sidebar-right-open]) {\n"
                        + "  visibility: hidden !important;\n"
                        + "}\n"
                        + "[data-sidebar-right-panel][data-sidebar-right-open] {\n"
                        + "  visibility: visible !important;\n"
                        + "}\n"
                        + "[data-mobile-nav=\"frame\"] > :first-child,\n"
                        + "div[class*='pI_x6G_sidebarCol'],\n"
                        + "[data-sidebar-right-panel],\n"
                        + "[data-sidebar-right-panel] [data-dockkit-host=\"dock\"],\n"
                        + "[data-sidebar-right-panel] [data-dockkit-pane],\n"
                        + "[data-aionui-preview-col],\n"
                        + "[data-aionui-explorer-col] {\n"
                        + "  will-change: transform !important;\n"
                        + "  transform: translateZ(0) !important;\n"
                        + "  backface-visibility: hidden !important;\n"
                        + "}\n"
                        + "[data-sidebar-right-panel] [data-dockkit-host=\"dock\"],\n"
                        + "[data-sidebar-right-panel] [data-dockkit-empty],\n"
                        + "[data-sidebar-right-panel] [data-dockkit-divider] {\n"
                        + "  transition: transform 220ms cubic-bezier(0.16, 1, 0.3, 1), visibility 0s linear 220ms !important;\n"
                        + "}\n"
                        + "[data-sidebar-right-panel][data-sidebar-right-open] [data-dockkit-host=\"dock\"],\n"
                        + "[data-sidebar-right-panel][data-sidebar-right-open] [data-dockkit-empty] {\n"
                        + "  transition: transform 220ms cubic-bezier(0.16, 1, 0.3, 1) !important;\n"
                        + "}\n";

                String js = "(function() {"
                        + "  var head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;\n"
                        + "  var style = document.getElementById('dsh-transparent-style');\n"
                        + "  if (!style) {\n"
                        + "    style = document.createElement('style');\n"
                        + "    style.id = 'dsh-transparent-style';\n"
                        + "    if (head) head.appendChild(style);\n"
                        + "  } else {\n"
                        + "    if (head && style.parentNode !== head) head.appendChild(style);\n"
                        + "  }\n"
                        + (immersive
                            ? "  style.innerHTML = " + org.json.JSONObject.quote(cssImmersive) + ";\n"
                            + "  if (document.documentElement) document.documentElement.style.backgroundColor = 'transparent';\n"
                            + "  if (document.body) document.body.style.backgroundColor = 'transparent';\n"
                            : "  var solidBg = " + org.json.JSONObject.quote(palette.solidBgHex) + ";\n"
                            + "  var cssSolid = 'html, body, #root, main, .dsh-layout-root, div[class*=\"pI_x6G_frame\"], div[class*=\"pI_x6G_centerCol\"], div[class*=\"_scrollBody\"], div[class*=\"_viewArea\"], div[class*=\"wSkVaW_root\"], div[class*=\"_composerHero\"], div[class*=\"_dock\"] { background: ' + solidBg + ' !important; background-color: ' + solidBg + ' !important; }\n' "
                            + "      + ':root, html, body { --dsw-alias-bg-base: ' + solidBg + ' !important; --dsh-boot-bg: ' + solidBg + ' !important; }\n' "
                            + "      + '[data-files-entry], [data-files-entry] *, [data-sidebar-right-panel] *, [data-tab=\"files\"] *, [data-sidebar-panel*=\"files\"] * { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; }\n' "
                            + "      + '[data-sidebar-right-panel]:not([data-sidebar-right-open]) { visibility: hidden !important; } [data-sidebar-right-panel][data-sidebar-right-open] { visibility: visible !important; } [data-mobile-nav=\"frame\"] > :first-child, div[class*=\"pI_x6G_sidebarCol\"], [data-sidebar-right-panel] { will-change: transform !important; transform: translateZ(0) !important; backface-visibility: hidden !important; }\n';\n"
                            + "  style.innerHTML = cssSolid;\n"
                            + "  if (document.documentElement) document.documentElement.style.backgroundColor = solidBg;\n"
                            + "  if (document.body) document.body.style.backgroundColor = solidBg;\n")
                        + "  if (document.documentElement) {\n"
                        + "    document.documentElement.style.colorScheme = " + (dark ? "'dark'" : "'light'") + ";\n"
                        + (dark
                                ? "    document.documentElement.classList.add('dark'); document.documentElement.setAttribute('data-theme', 'dark');\n"
                                : "    document.documentElement.classList.remove('dark'); document.documentElement.setAttribute('data-theme', 'light');\n")
                        + "  }\n"
                        + "  if (document.body) {\n"
                        + (dark
                                ? "    document.body.setAttribute('data-ds-dark-theme', '');\n"
                                : "    document.body.removeAttribute('data-ds-dark-theme');\n")
                        + "  }\n"
                        + "  /* 挂载原生工作区文件点击拦截与长按外部打开：短按原生抽屉预览，长按/下拉分流 */\n"
                        + "  if (!window.__dsha_file_click_hooked) {\n"
                        + "    window.__dsha_file_click_hooked = true;\n"
                        + "    var longPressTimer = null;\n"
                        + "    var touchStartX = 0, touchStartY = 0;\n"
                        + "    var isLongPressTriggered = false;\n"
                        + "    var lastLongPressTime = 0;\n"
                        + "\n"
                        + "    function findTargetFileElement(e, allowDirectory) {\n"
                        + "      var el = e.target && e.target.closest ? e.target.closest('[data-files-entry], [data-files-path], [data-file-path], [data-presented-file], button[title*=\"/sdcard/\"], button[title*=\"dsh-resource:\"], a[href*=\"/sdcard/\"], a[href*=\"dsh-resource://file\"]') : null;\n"
                        + "      if (!el) return null;\n"
                        + "      var entryType = el.getAttribute('data-files-entry');\n"
                        + "      if (!allowDirectory && entryType === 'directory') return null;\n"
                        + "      return el;\n"
                        + "    }\n"
                        + "\n"
                        + "    function extractFilePath(el) {\n"
                        + "      if (!el) return null;\n"
                        + "      if (el.hasAttribute('data-presented-file')) {\n"
                        + "        var preview = el.querySelector('button[title], [data-files-path], [data-file-path]');\n"
                        + "        if (preview) return preview.getAttribute('title') || preview.getAttribute('data-files-path') || preview.getAttribute('data-file-path');\n"
                        + "      }\n"
                        + "      return el.getAttribute('data-files-path')\n"
                        + "          || el.getAttribute('data-file-path')\n"
                        + "          || el.getAttribute('href')\n"
                        + "          || el.getAttribute('title');\n"
                        + "    }\n"
                        + "\n"
                        + "    document.addEventListener('touchstart', function(e) {\n"
                        + "      isLongPressTriggered = false;\n"
                        + "      var targetEl = findTargetFileElement(e, true);\n"
                        + "      if (!targetEl) return;\n"
                        + "      var p = extractFilePath(targetEl);\n"
                        + "      if (!p) return;\n"
                        + "      try {\n"
                        + "        var sel = window.getSelection();\n"
                        + "        if (sel) sel.removeAllRanges();\n"
                        + "      } catch(err) {}\n"
                        + "      touchStartX = e.touches[0].clientX;\n"
                        + "      touchStartY = e.touches[0].clientY;\n"
                        + "      clearTimeout(longPressTimer);\n"
                        + "      longPressTimer = setTimeout(function() {\n"
                        + "        isLongPressTriggered = true;\n"
                        + "        lastLongPressTime = Date.now();\n"
                        + "        try {\n"
                        + "          var sel = window.getSelection();\n"
                        + "          if (sel) sel.removeAllRanges();\n"
                        + "        } catch(err) {}\n"
                        + "        if (window.DshaNativeBridge) {\n"
                        + "          if (targetEl.closest('[data-files-entry]')) {\n"
                        + "            if (window.DshaNativeBridge.showFileActionMenuAt) {\n"
                        + "              window.DshaNativeBridge.showFileActionMenuAt(p, touchStartX, touchStartY);\n"
                        + "            } else if (window.DshaNativeBridge.showFileActionMenu) {\n"
                        + "              window.DshaNativeBridge.showFileActionMenu(p);\n"
                        + "            }\n"
                        + "          } else {\n"
                        + "            if (window.DshaNativeBridge.openExternalFile) {\n"
                        + "              window.DshaNativeBridge.openExternalFile(p);\n"
                        + "            }\n"
                        + "          }\n"
                        + "        }\n"
                        + "      }, 450);\n"
                        + "    }, { passive: true, capture: true });\n"
                        + "\n"
                        + "    document.addEventListener('touchmove', function(e) {\n"
                        + "      if (!longPressTimer) return;\n"
                        + "      var dx = Math.abs(e.touches[0].clientX - touchStartX);\n"
                        + "      var dy = Math.abs(e.touches[0].clientY - touchStartY);\n"
                        + "      if (dx > 10 || dy > 10) {\n"
                        + "        clearTimeout(longPressTimer);\n"
                        + "        longPressTimer = null;\n"
                        + "      }\n"
                        + "    }, { passive: true, capture: true });\n"
                        + "\n"
                        + "    document.addEventListener('touchend', function(e) {\n"
                        + "      clearTimeout(longPressTimer);\n"
                        + "      if (isLongPressTriggered || (Date.now() - lastLongPressTime < 600)) {\n"
                        + "        try {\n"
                        + "          var sel = window.getSelection();\n"
                        + "          if (sel) sel.removeAllRanges();\n"
                        + "        } catch(err) {}\n"
                        + "        e.preventDefault();\n"
                        + "        e.stopPropagation();\n"
                        + "        if (e.stopImmediatePropagation) e.stopImmediatePropagation();\n"
                        + "      }\n"
                        + "    }, true);\n"
                        + "\n"
                        + "    document.addEventListener('selectstart', function(e) {\n"
                        + "      if (isLongPressTriggered || (Date.now() - lastLongPressTime < 600) || (e.target && e.target.closest && e.target.closest('li[data-files-entry]'))) {\n"
                        + "        e.preventDefault();\n"
                        + "        e.stopPropagation();\n"
                        + "        return false;\n"
                        + "      }\n"
                        + "    }, true);\n"
                        + "\n"
                        + "    document.addEventListener('contextmenu', function(e) {\n"
                        + "      if (isLongPressTriggered || (Date.now() - lastLongPressTime < 600) || (e.target && e.target.closest && e.target.closest('li[data-files-entry]'))) {\n"
                        + "        e.preventDefault();\n"
                        + "        e.stopPropagation();\n"
                        + "        return false;\n"
                        + "      }\n"
                        + "    }, true);\n"
                        + "\n"
                        + "    document.addEventListener('click', function(e) {\n"
                        + "      if (isLongPressTriggered || (Date.now() - lastLongPressTime < 600)) {\n"
                        + "        isLongPressTriggered = false;\n"
                        + "        e.preventDefault();\n"
                        + "        e.stopPropagation();\n"
                        + "        if (e.stopImmediatePropagation) e.stopImmediatePropagation();\n"
                        + "        return;\n"
                        + "      }\n"
                        + "      /* 1. 交付卡片下拉小箭头：拦截并分流（定位文件树 / 外部应用打开） */\n"
                        + "      var chevronBtn = e.target && e.target.closest ? e.target.closest('[data-presented-file] button:last-child, [data-presented-file] [aria-haspopup=\"menu\"], [data-presented-file] [aria-label*=\"更多\"], [data-presented-file] [aria-label*=\"more\"]') : null;\n"
                        + "      if (chevronBtn) {\n"
                        + "        var card = chevronBtn.closest('[data-presented-file]');\n"
                        + "        if (card) {\n"
                        + "          e.preventDefault();\n"
                        + "          e.stopPropagation();\n"
                        + "          if (e.stopImmediatePropagation) e.stopImmediatePropagation();\n"
                        + "          var cardPath = extractFilePath(card);\n"
                        + "          if (cardPath && window.DshaNativeBridge && window.DshaNativeBridge.openExternalFile) {\n"
                        + "            window.DshaNativeBridge.openExternalFile(cardPath);\n"
                        + "          }\n"
                        + "          return;\n"
                        + "        }\n"
                        + "      }\n"
                        + "      /* 2. 文件树列表内部节点：短按文件保留原地查看器（Office 文档放行，由 DSH 内置查看器承载） */\n"
                        + "      var treeEntry = e.target && e.target.closest ? e.target.closest('li[data-files-entry=\"file\"]') : null;\n"
                        + "      if (treeEntry) {\n"
                        + "        if (window.__dsha_is_remote__) {\n"
                        + "          return;\n"
                        + "        }\n"
                        + "        var treePath = treeEntry.getAttribute('data-files-path');\n"
                        + "        if (treePath) {\n"
                        + "          var cleanPath = treePath.split('?')[0].split('#')[0];\n"
                        + "          var isOffice = /\\.(docx?|xlsx?|pptx?|odt|ods|odp)$/i.test(cleanPath);\n"
                        + "          if (isOffice) {\n"
                        + "            return;\n"
                        + "          }\n"
                        + "          if (window.DshaNativeBridge && window.DshaNativeBridge.openWorkspaceFile) {\n"
                        + "            e.preventDefault();\n"
                        + "            e.stopPropagation();\n"
                        + "            if (e.stopImmediatePropagation) e.stopImmediatePropagation();\n"
                        + "            window.DshaNativeBridge.openWorkspaceFile(treePath);\n"
                        + "          }\n"
                        + "        }\n"
                        + "        return;\n"
                        + "      }\n"
                        + "      /* 3. 交付卡片主区域短按：若是 .html / .htm，自动通过 Android 宿主调用系统外部浏览器打开，彻底避免 DSH 侧边栏 sandbox=\"\" 禁用 JS 导致白屏！ */\n"
                        + "      var hitCard = e.target && e.target.closest ? e.target.closest('[data-presented-file]') : null;\n"
                        + "      if (hitCard) {\n"
                        + "        var hitCardPath = extractFilePath(hitCard);\n"
                        + "        if (hitCardPath) {\n"
                        + "          var hitClean = hitCardPath.split('?')[0].split('#')[0];\n"
                        + "          if (/\\.(html|htm)$/i.test(hitClean)) {\n"
                        + "            e.preventDefault();\n"
                        + "            e.stopPropagation();\n"
                        + "            if (e.stopImmediatePropagation) e.stopImmediatePropagation();\n"
                        + "            if (window.DshaNativeBridge && window.DshaNativeBridge.openExternalFile) {\n"
                        + "              window.DshaNativeBridge.openExternalFile(hitCardPath);\n"
                        + "            }\n"
                        + "            return;\n"
                        + "          }\n"
                        + "        }\n"
                        + "      }\n"
                        + "      /* 4. 其余所有元素（包括聊天消息里的全部行内文件、提及按钮等）：100% 放行，交由 DSH Web 原生处理！ */\n"
                        + "    }, true);\n"
                        + "  }\n"
                        + "})();";
                sCachedWebView.evaluateJavascript(js, null);
            } catch (Throwable ignored) {}
        });
    }

    private void injectTransparentBackground(WebView view) {
        if (view != null) {
            boolean isRemote = (view == sRemoteWebView) || (sIsRemoteActive && view == sCachedWebView);
            view.evaluateJavascript("window.__dsha_is_remote__ = " + (isRemote ? "true" : "false") + ";", null);
        }
        refreshImmersiveTheme(this);
    }

    /**
     * 前台闪电唤醒：
     * 1. 恢复渲染管线与 JS 定时器；
     * 2. 状态跳变（offline -> online）绕过前端连接守卫，强制激活 WebSocket 重连与增量信息流拉取；
     * 3. 派发 visibilitychange 与 focus，让页面组件与框架立即感知前台活跃。
     */
    private void triggerForegroundWakeup() {
        if (sCachedWebView == null) return;
        sCachedWebView.onResume();
        sCachedWebView.resumeTimers();
        // 关键加固：唤醒时无条件同步刷新透明沉浸样式，消除后台任务渲染引起的样式断档与白底残留
        refreshImmersiveTheme(this);
        sCachedWebView.post(() -> {
            if (sCachedWebView == null || isFinishing() || isDestroyed()) return;
            try {
                String js = "(function() {\n"
                        + "  try {\n"
                        + "    if (document.hidden) {\n"
                        + "      try {\n"
                        + "        Object.defineProperty(document, 'hidden', { value: false, writable: true, configurable: true });\n"
                        + "        Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true, configurable: true });\n"
                        + "      } catch(e) {}\n"
                        + "    }\n"
                        + "    document.dispatchEvent(new Event('visibilitychange'));\n"
                        + "    window.dispatchEvent(new Event('focus'));\n"
                        + "    // 关键安全防线：审批或提问交互中（isInteractiveWaiting）绝对禁止派发 offline，防止冲断后端 WebSocket 与交互链路\n"
                        + "    if (!" + com.deepseekharness.app.HttpShellService.isInteractiveWaiting() + ") {\n"
                        + "      window.dispatchEvent(new Event('offline'));\n"
                        + "      window.dispatchEvent(new Event('online'));\n"
                        + "    }\n"
                        + "  } catch(e) {}\n"
                        + "})();";
                sCachedWebView.evaluateJavascript(js, null);
            } catch (Throwable ignored) {}
        });
    }

    /** 从底部顺滑滑入展开（屏幕外静默就绪，绝不闪屏变形） */
    private void animateIn() {
        isDismissing = false;
        triggerForegroundWakeup();
        if (sheetCard != null) {
            ConfigStore cfg = new ConfigStore(this);
            // 如果开启了低位自动恢复开关且上次处于低位 (<=45%)，重置为自定义默认高度
            if (cfg.isSheetAutoRestoreDefault() && currentHeight <= (int) (screenHeight * 0.45f)) {
                currentHeight = defaultHeight;
                updateCardHeight(defaultHeight);
            }
            int startY = screenHeight > 0 ? screenHeight : (defaultHeight + dpToPx(100));
            sheetCard.setTranslationY(startY);
            sheetCard.setVisibility(View.VISIBLE);
            sheetCard.animate()
                    .translationY(0)
                    .setDuration(340)
                    .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                    .setListener(null)
                    .start();
        }
    }

    /** 顺滑向下平移退出弹层并转入后台保活（moveTaskToBack，退出时绝不碰高度） */
    private void dismissSheet() {
        if (isDismissing) return;
        isDismissing = true;
        dismissActiveDialog();
        cancelFileSelection();

        // 退出前顺带隐藏键盘
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}

        if (sheetCard != null) {
            int exitY = screenHeight > 0 ? screenHeight : (sheetCard.getHeight() + dpToPx(100));
            sheetCard.animate()
                    .translationY(exitY)
                    .setDuration(300)
                    .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // 动画完全滑出屏幕后暂停渲染管线释放 GPU，保留 JS 定时器避免 WebSocket 探活重连卡死
                            if (sCachedWebView != null) {
                                sCachedWebView.onPause();
                            }
                            moveTaskToBack(true);
                            overridePendingTransition(0, 0);
                            isDismissing = false;
                        }
                    })
                    .start();
        } else {
            if (sCachedWebView != null) {
                sCachedWebView.onPause();
            }
            moveTaskToBack(true);
            overridePendingTransition(0, 0);
            isDismissing = false;
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        dispatchBackAction();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            dispatchBackAction();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean tryInterceptLocalFile(String url) {
        if (url == null || url.isEmpty()) return false;
        // 若指向本地工作区文件路径
        if (url.contains("/sdcard/Download/DSHA/工作区/") || url.startsWith("file:///sdcard/Download/DSHA/工作区/")) {
            String path = url.replace("file://", "");
            int queryIdx = path.indexOf('?');
            if (queryIdx >= 0) path = path.substring(0, queryIdx);
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                com.deepseekharness.app.viewer.FileViewerActivity.open(this, f.getAbsolutePath());
                return true;
            }
        }
        return false;
    }

    private void openExternal(String url) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (RuntimeException e) {
            Toast.makeText(this, "未找到可用的系统浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void cancelFileSelection() {
        if (fileCallback != null) {
            ValueCallback<Uri[]> callback = fileCallback;
            fileCallback = null;
            callback.onReceiveValue(null);
        }
    }

    private WebViewClient createSheetWebViewClient() {
        return new WebViewClient() {
            private boolean isInternalDshUrl(String url) {
                if (url == null) return false;
                if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
                    return true;
                }
                String remoteUrl = new ConfigStore(getApplicationContext()).getRemoteDshUrl();
                if (remoteUrl != null && !remoteUrl.isEmpty()) {
                    try {
                        Uri rUri = Uri.parse(remoteUrl);
                        Uri curUri = Uri.parse(url);
                        if (rUri.getHost() != null && rUri.getHost().equalsIgnoreCase(curUri.getHost())) {
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectTransparentBackground(view);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                injectTransparentBackground(view);
                if (view == sRemoteWebView) {
                    sRemoteWebLoaded = true;
                } else {
                    sLocalWebLoaded = true;
                }
                sWebLoaded = true;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (tryInterceptLocalFile(url)) return true;
                if (isInternalDshUrl(url)) {
                    return false;
                }
                openExternal(url);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                String url = request.getUrl().toString();
                if (tryInterceptLocalFile(url)) return true;
                if (!request.isForMainFrame()) return false;
                if (isInternalDshUrl(url)) {
                    return false;
                }
                openExternal(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view == sRemoteWebView) {
                    sRemoteWebLoaded = true;
                } else {
                    sLocalWebLoaded = true;
                }
                sWebLoaded = true;
                authRetried = false;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                injectTransparentBackground(view);
                // 若 + 号因 Token 失效触发了先重载再新建的流程，加载完成后 300ms 自动补发
                if (sPendingNewChat) {
                    sPendingNewChat = false;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> QuickChatSheetActivity.this.triggerNewChatJs(), 300);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame() && errorHint != null) {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    errorHint.setVisibility(View.VISIBLE);
                    errorHint.setText(view == sRemoteWebView ? "远端 DSH 服务未就绪或网络不可达" : "DSHA 服务未就绪，请先在控制台启动");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame() && !authRetried) {
                    int code = errorResponse != null ? errorResponse.getStatusCode() : 0;
                    if (code == 401 || code == 403) {
                        authRetried = true;
                        if (view == sRemoteWebView) {
                            runOnUiThread(() -> {
                                Toast.makeText(QuickChatSheetActivity.this, "远端 DSH 鉴权失败 (HTTP " + code + ")，请检查 Token", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            String retryUrl = controller != null ? controller.getWebAuthUrl() : "";
                            if (retryUrl != null && !retryUrl.isEmpty()) {
                                view.post(() -> view.loadUrl(retryUrl));
                            }
                        }
                    }
                }
            }
        };
    }

    private class SheetChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
            android.util.Log.d("DSHA_SHEET_CONSOLE", message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
            cancelFileSelection();
            fileCallback = callback;
            Intent primary = null;
            try {
                primary = params.createIntent();
                filePicker.launch(primary);
            } catch (Exception e) {
                try {
                    if (primary == null) {
                        primary = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
                                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                                .putExtra(Intent.EXTRA_MIME_TYPES, params.getAcceptTypes());
                    }
                    filePicker.launch(WebUploads.fallback(primary));
                } catch (Exception ignored) {
                    cancelFileSelection();
                    Toast.makeText(QuickChatSheetActivity.this, "无法打开系统文件选择器", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
    }

    /**
     * 通过 JS 在 WebView 内触发"新建会话"动作：
     * 优先点击页面内的"新会话"按钮；若找不到则回退到跳转首页。
     */
    private void triggerNewChatJs() {
        if (sCachedWebView == null) return;
        String js = "(function() {" +
                "  var btn = document.querySelector('[class*=\"newSession\"], [aria-label*=\"新会话\"], [aria-label*=\"新建\"], button[title*=\"新会话\"], button[title*=\"New session\"], button[title*=\"New Chat\"]');" +
                "  if (btn) {" +
                "    btn.click();" +
                "  } else {" +
                "    window.location.hash = '';" +
                "    window.location.href = '/';" +
                "  }" +
                "})();";
        sCachedWebView.evaluateJavascript(js, null);
    }

    /**
     * 重新从后端获取 Token 并刷新抽屉 Web 进入 DSH：
     * 1. 彻底解决抽屉比后端提前启动导致无法进入的问题；
     * 2. 强制清空陈旧缓存，在子线程直接从宿主日志或 .launch_token 抽取带最新 Token 的 URL；
     * 3. 轮询等待后端启动就绪（最多 32 轮 * 250ms = 8 秒）；
     * 4. 成功获取后同步 Cookie 并调用 WebView 加载进入 DSH；
     * 5. 若超时或后端未运行，给予用户清晰明确的 Toast 提示。
     */
    private void forceReloadWithLatestToken() {
        if (sCachedWebView == null) return;
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
        sWebLoaded = false;
        authRetried = false;

        if (controller != null) {
            controller.clearWebAuthUrl();
        }

        final int port = controller != null ? controller.getPort() : 3080;
        sLoadedPort = port;

        new Thread(() -> {
            String targetUrl = null;

            // 1. 轮询检索后端真实 token（最多等待 32 轮 * 250ms = 8 秒，适应后端开机初始化慢的场景）
            for (int step = 0; step < 32; step++) {
                targetUrl = queryLatestBackendAuthUrl(port);
                if (targetUrl != null && !targetUrl.isEmpty()) {
                    break;
                }
                if (step % 4 == 0 && controller != null) {
                    controller.asyncRefreshStatus();
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                    break;
                }
            }

            // 2. 若轮询未果，尝试从 controller 中获取最后已记录的 URL
            if (targetUrl == null || targetUrl.isEmpty()) {
                if (controller != null) {
                    targetUrl = controller.getWebAuthUrl();
                }
            }

            final String finalUrl = targetUrl;

            // 若依然无法获取到带有鉴权 token 的 URL，诊断后端状态并提示用户
            if (finalUrl == null || finalUrl.isEmpty()) {
                boolean running = controller != null && controller.isWebRunning();
                runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (!running) {
                        Toast.makeText(this, "DSH 后端服务未运行，请先在主页启动服务", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "未能获取到后端鉴权 Token，请稍后重试", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }

            // 3. 成功获取到最新鉴权 URL，更新 controller 状态
            if (controller != null) {
                controller.updateWebAuthUrl(finalUrl);
                sLoadedGeneration = controller.getWebGeneration();
            }

            // 4. 重新与后端交换 dsh-auth-* Cookie 并注入 CookieManager，同步注入 3095 桥凭据
            try {
                android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
                String base = "http://127.0.0.1:" + port + "/";
                String authCookie = controller != null ? controller.exchangeDshAuthCookie() : null;
                if (authCookie != null && !authCookie.isEmpty()) {
                    String cookieVal = authCookie.contains(";") ? authCookie : (authCookie + "; Path=/; HttpOnly; SameSite=Lax");
                    cookies.setCookie(base, cookieVal);
                    cookies.setCookie("http://127.0.0.1/", cookieVal);
                }
                String bt = com.deepseekharness.app.HttpShellService.ensureToken();
                if (bt != null && !bt.isEmpty()) {
                    String dshaCookie = "dsha_t=" + bt + "; Path=/; SameSite=Lax; Max-Age=31536000";
                    cookies.setCookie(base, dshaCookie);
                    cookies.setCookie("http://127.0.0.1/", dshaCookie);
                }
                cookies.flush();
            } catch (Throwable ignored) {}

            // 5. 在主线程加载 WebView
            runOnUiThread(() -> {
                if (sCachedWebView != null && !isFinishing() && !isDestroyed()) {
                    sCachedWebView.loadUrl(finalUrl);
                    Toast.makeText(this, "正在重新连接 DSH...", Toast.LENGTH_SHORT).show();
                }
            });
        }, "dsha-force-reload-token").start();
    }

    /**
     * 从宿主运行目录直接读取最新的鉴权 URL。
     * 双轨校验：
     * 1. 优先提取 /data/adb/dsha/run/dsh-web.log 尾部的最新 URL
     * 2. 若日志尚未落盘，直接从 /data/adb/dsha/rootfs/root/.dsh/.launch_token 拼合
     */
    private String queryLatestBackendAuthUrl(int port) {
        try {
            String cmd = "URL=$(grep -o 'http://127\\.0\\.0\\.1:[0-9]*/?token=[^ ]*' /data/adb/dsha/run/dsh-web.log 2>/dev/null | tail -n 1)\n" +
                         "if [ -z \"$URL\" ]; then\n" +
                         "  TOK=$(cat /data/adb/dsha/rootfs/root/.dsh/.launch_token 2>/dev/null | tr -d '\\r\\n')\n" +
                         "  if [ -n \"$TOK\" ]; then\n" +
                         "    P=$(cat /data/adb/dsha/run/port 2>/dev/null | tr -d '\\r\\n')\n" +
                         "    [ -z \"$P\" ] && P=" + port + "\n" +
                         "    URL=\"http://127.0.0.1:$P/?token=$TOK\"\n" +
                         "  fi\n" +
                         "fi\n" +
                         "echo \"$URL\"";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            java.io.InputStream in = p.getInputStream();
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            String out = bos.toString("UTF-8").trim();
            if (out.startsWith("http://127.0.0.1:")) {
                return out;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void reloadWithLatestToken() {
        forceReloadWithLatestToken();
    }

    @Override
    protected void onResume() {
        sCurrentInstance = this;
        super.onResume();
        isDismissing = false;
        if (sheetCard != null) {
            // 若卡片当前处于屏幕外（平移距离大于 0 或不可见），可靠执行进场动画恢复显示
            if (sheetCard.getTranslationY() > 0 || sheetCard.getVisibility() != View.VISIBLE) {
                animateIn();
            }
        }
        boolean dark = new ConfigStore(this).isSheetInvertColor();
        boolean monet = new ConfigStore(this).isSheetMonetColor();
        boolean monetInvert = new ConfigStore(this).isSheetMonetInvert();
        if (dark != isDarkMode || monet != isMonetColor || monetInvert != isMonetInvert) {
            isDarkMode = dark;
            isMonetColor = monet;
            isMonetInvert = monetInvert;
            updateCardTheme();
        }
        if (com.deepseekharness.app.HttpShellService.isApprovalWaiting && com.deepseekharness.app.HttpShellService.sCurrentApprovalInfo != null) {
            displayApprovalBanner(com.deepseekharness.app.HttpShellService.sCurrentApprovalInfo, com.deepseekharness.app.HttpShellService.sCurrentApprovalEpoch);
        } else {
            hideApprovalBanner();
        }
        if (sCachedWebView != null) {
            injectTransparentBackground(sCachedWebView);
            if (sPendingApprovalDecision != null) {
                boolean allow = "allowed-once".equals(sPendingApprovalDecision);
                sPendingApprovalDecision = null;
                executeApprovalDecisionScript(sCachedWebView, allow);
            }

            // 1. 唤醒 WebView 渲染管线与 JS 定时器，并执行状态跳变触发信息流拉取
            triggerForegroundWakeup();
            // 进场动画完成后（350ms）执行二次兜底唤醒，确保动效期间若有卡顿仍能可靠补齐
            sCachedWebView.postDelayed(() -> {
                if (sCachedWebView != null && !isFinishing() && !isDestroyed()) {
                    triggerForegroundWakeup();
                }
            }, 350);

            // 2. 检查底层服务与远端开关状态
            ConfigStore cfg = ConfigStore.get(this);
            String activeRemoteUrl = cfg.getActiveRemoteDshUrl();
            boolean hasActiveRemote = cfg.isAnyRemoteDshActive() && activeRemoteUrl != null && !activeRemoteUrl.isEmpty();

            if (sIsRemoteActive && !hasActiveRemote) {
                // 用户在凭据页关闭了远端，立即切回本机并彻底释放远端资源，防止后台偷跑心跳与电量
                sIsRemoteActive = false;
                if (sRemoteWebView != null) {
                    sRemoteWebView.stopLoading();
                    sRemoteWebView.loadUrl("about:blank");
                    sRemoteWebView.onPause();
                }
                updateWebViewSlotVisibility();
                if (!sLocalWebLoaded) {
                    forceReloadWithLatestToken();
                }
            } else if (sIsRemoteActive) {
                if (!activeRemoteUrl.equals(sLoadedRemoteUrl) || (!sRemoteWebLoaded && sRemoteWebView != null)) {
                    ensureRemoteWebViewLoaded(activeRemoteUrl);
                    updateWebViewSlotVisibility();
                }
            } else {
                long currentGen = controller != null ? controller.getWebGeneration() : -1;
                boolean serviceRestarted = sLoadedGeneration > 0 && currentGen > 0 && sLoadedGeneration != currentGen;
                String curUrl = sLocalWebView != null ? sLocalWebView.getUrl() : null;
                boolean isLocalDsh = curUrl != null && (curUrl.contains("://127.0.0.1:") || curUrl.contains("://localhost:"));

                // 核心重加载防卡死：仅当服务真正重启、完全脱离本地服务或此前未成功载入时才触发重载；
                // 只要 WebView 已在正常显示本地网页，绝对不重新刷新，彻底消除白屏与卡顿！
                if (!sLocalWebLoaded || serviceRestarted || !isLocalDsh) {
                    forceReloadWithLatestToken();
                }
            }
        }
    }

    // ---------------- 抽屉顶栏 📁 按钮：直接联动 Web 右上角官方原生展开/收起按钮 ----------------
    private static final String SCRIPT_TOGGLE_WORKSPACE_FILE_TREE =
            "(function() {\n" +
            "    var btn = document.querySelector('[data-sidebar-right-expand]') || document.querySelector('[data-sidebar-right-toggle]');\n" +
            "    if (btn && typeof btn.click === 'function') {\n" +
            "        btn.click();\n" +
            "        return true;\n" +
            "    }\n" +
            "    return false;\n" +
            "})()";

    private void toggleWorkspaceFileTree() {
        if (fileViewerContainer != null && fileViewerContainer.getVisibility() == View.VISIBLE) {
            closeFileViewer();
            return;
        }
        if (sCachedWebView == null) return;
        executeToggleWorkspaceFileTreeWithRetry(0);
    }

    private void executeToggleWorkspaceFileTreeWithRetry(int retryCount) {
        if (sCachedWebView == null || isFinishing() || isDestroyed()) return;
        sCachedWebView.evaluateJavascript(SCRIPT_TOGGLE_WORKSPACE_FILE_TREE, value -> {
            if ("true".equals(value)) {
                return;
            }
            // 刚加载初次点击时，若 React 组件尚未挂载完成，延时 150ms 自动轻量重试（最多 2 次）
            if (retryCount < 2) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    executeToggleWorkspaceFileTreeWithRetry(retryCount + 1);
                }, 150);
            }
        });
    }

    private void reloadWorkspaceFileTree() {
        if (sCachedWebView == null) return;
        // 深度穿透真实指针点击事件，确保 React 内部 actions.reset 真正执行重载
        String js = "(function() {" +
                "  function fireClick(el) {" +
                "    if (!el) return false;" +
                "    el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));" +
                "    el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true }));" +
                "    el.click();" +
                "    return true;" +
                "  }" +
                "  var reloadBtn = document.querySelector('[data-files-reload]');" +
                "  if (reloadBtn && fireClick(reloadBtn)) return;" +
                "  var panel = document.querySelector('[data-sidebar-right-panel]');" +
                "  if (panel) {" +
                "    var r = panel.querySelector('button[title*=\"刷新\"], button[aria-label*=\"刷新\"], [data-files-reload]');" +
                "    if (r && fireClick(r)) return;" +
                "  }" +
                "  var filesTab = document.querySelector('[data-tab=\"files\"], [data-sidebar-tab=\"files\"]');" +
                "  if (filesTab) fireClick(filesTab);" +
                "})();";
        sCachedWebView.evaluateJavascript(js, null);
    }

    private void copyToClipboard(String text, String tip) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("text", text);
                cm.setPrimaryClip(clip);
                Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 手势位置跟随的悬浮气泡微菜单（100% 继承抽屉毛玻璃与莫奈主题） ----------------
    private void showWorkspaceFileActionMenu(final File file, final String rawPath, float touchX, float touchY) {
        if (file == null || (!sIsRemoteActive && !file.exists()) || rootOverlay == null) {
            Toast.makeText(this, "目标不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        // 清理焦点与网页可能残留的选区
        if (sCachedWebView != null) {
            sCachedWebView.clearFocus();
            sCachedWebView.evaluateJavascript("try{window.getSelection().removeAllRanges();}catch(e){}", null);
        }

        // 触觉反馈：长按成功呼出气泡菜单
        try {
            rootOverlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Throwable ignored) {}

        final boolean isDir = file.isDirectory();
        final MonetThemeHelper.Palette palette = MonetThemeHelper.resolve(
                this, isDarkMode, new ConfigStore(this).isSheetMonetColor(),
                isDarkMode ? new ConfigStore(this).getSheetOpacityNight() : new ConfigStore(this).getSheetOpacityDay());

        final FrameLayout mask = new FrameLayout(this);
        mask.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mask.setBackgroundColor(Color.TRANSPARENT);
        mask.setClickable(true);
        mask.setFocusable(true);

        final LinearLayout menuCard = new LinearLayout(this);
        menuCard.setOrientation(LinearLayout.VERTICAL);
        int cardWidth = dpToPx(210);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);

        // 智能定位：紧跟长按手指点击位置（根据 WebView 在当前窗口的物理坐标精确换算）
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;

        float posX;
        float posY;
        int estimatedCardH = dpToPx(290);

        if (touchX >= 0 && touchY >= 0 && sCachedWebView != null) {
            // 计算 WebView 相对 rootOverlay 的实际物理像素偏移
            int[] rootLoc = new int[2];
            rootOverlay.getLocationInWindow(rootLoc);
            int[] webLoc = new int[2];
            sCachedWebView.getLocationInWindow(webLoc);

            float offsetX = webLoc[0] - rootLoc[0];
            float offsetY = webLoc[1] - rootLoc[1];

            // 触摸点在 rootOverlay 坐标系下的真实像素坐标
            float realTouchX = offsetX + (touchX * density);
            float realTouchY = offsetY + (touchY * density);

            // 水平对齐：以手指为锚点微调，左右保留安全边距
            posX = realTouchX - dpToPx(36);
            if (posX + cardWidth > screenW - dpToPx(16)) {
                posX = screenW - cardWidth - dpToPx(16);
            }
            if (posX < dpToPx(16)) {
                posX = dpToPx(16);
            }

            // 垂直对齐：靠近底部时向上浮现（在手指上方 8dp），否则在手指下方 8dp 浮现
            float bottomLimit = screenH - dpToPx(24);
            if (realTouchY + estimatedCardH > bottomLimit) {
                posY = realTouchY - estimatedCardH - dpToPx(8);
            } else {
                posY = realTouchY + dpToPx(8);
            }

            // 垂直防出界：顶部至少保留 60dp
            if (posY < dpToPx(60)) {
                posY = dpToPx(60);
            }
        } else {
            // 兜底居中
            posX = (screenW - cardWidth) / 2f;
            posY = (screenH - estimatedCardH) / 2f;
        }

        cardLp.leftMargin = (int) posX;
        cardLp.topMargin = (int) posY;
        menuCard.setLayoutParams(cardLp);
        menuCard.setElevation(dpToPx(20));

        // 样式 100% 继承抽屉：高不透明度底色隔绝底层文字穿透，圆角与微光描边
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dpToPx(16));
        int menuBgColor;
        if (isDarkMode) {
            menuBgColor = Color.argb(0xFA, 0x1A, 0x22, 0x30);
        } else {
            int raw = palette.cardBgColor;
            menuBgColor = Color.argb(0xF8, Color.red(raw), Color.green(raw), Color.blue(raw));
        }
        cardBg.setColor(menuBgColor);
        cardBg.setStroke(dpToPx(1), palette.borderColor);
        menuCard.setBackground(cardBg);
        menuCard.setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6));

        // 标题条（紧凑展示选中的文件名）
        String displayName = file.getName();
        int lastPathSep = Math.max(displayName.lastIndexOf('/'), displayName.lastIndexOf('\\'));
        if (lastPathSep >= 0) {
            displayName = displayName.substring(lastPathSep + 1);
        }
        final String finalFileName = displayName;

        TextView titleTv = new TextView(this);
        titleTv.setText((isDir ? "📁 " : "📄 ") + finalFileName);
        titleTv.setTextColor(palette.textSecondaryColor);
        titleTv.setTextSize(11);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleTv.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));
        menuCard.addView(titleTv);

        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        sep.setBackgroundColor(palette.lineColor);
        menuCard.addView(sep);

        mask.setOnClickListener(v -> dismissActiveDialog());

        // 1. 外部打开
        menuCard.addView(createMenuItem("↗   调用系统打开方式", palette.textColor, v -> {
            dismissActiveDialog();
            if (sIsRemoteActive) {
                // 远端文件：先下载到手机本地 Download 目录，再调起系统打开
                Toast.makeText(this, "正在下载远端文件后调用系统打开...", Toast.LENGTH_SHORT).show();
                performDownloadFileToDownloads(file, rawPath, destFile -> {
                    if (destFile != null && destFile.exists()) {
                        com.deepseekharness.app.viewer.FileOpenHelper.openWithSystem(QuickChatSheetActivity.this, destFile);
                    }
                });
            } else {
                com.deepseekharness.app.viewer.FileOpenHelper.openWithSystem(QuickChatSheetActivity.this, file);
            }
        }));

        // 2. 重命名
        menuCard.addView(createMenuItem("✏️   重命名", palette.textColor, v -> {
            dismissActiveDialog();
            if (sIsRemoteActive) {
                Toast.makeText(this, "远端文件请在网页中直接重命名", Toast.LENGTH_SHORT).show();
            } else {
                promptRenameFileCustom(file, palette);
            }
        }));

        // 3. 复制文件名
        menuCard.addView(createMenuItem("📋   复制文件名", palette.textColor, v -> {
            dismissActiveDialog();
            copyToClipboard(finalFileName, "✓ 已复制文件名：" + finalFileName);
        }));

        // 4. 复制文件路径
        menuCard.addView(createMenuItem("📍   复制文件路径", palette.textColor, v -> {
            dismissActiveDialog();
            String showPath = (rawPath != null && !rawPath.isEmpty()) ? rawPath : file.getAbsolutePath();
            copyToClipboard(showPath, "✓ 已复制路径：" + showPath);
        }));

        // 5. 下载到 Download 目录（全环境支持）
        menuCard.addView(createMenuItem("📥   下载到 Download 目录", palette.textColor, v -> {
            dismissActiveDialog();
            if (isDir) {
                Toast.makeText(this, "暂不支持直接下载整个文件夹", Toast.LENGTH_SHORT).show();
                return;
            }
            confirmDownloadFileCustom(file, palette, rawPath);
        }));

        // 6. 删除（警示红）
        menuCard.addView(createMenuItem("🗑️   删除" + (isDir ? "文件夹" : ""), Color.parseColor("#FF5252"), v -> {
            dismissActiveDialog();
            if (sIsRemoteActive) {
                Toast.makeText(this, "远端文件请在网页中直接删除", Toast.LENGTH_SHORT).show();
            } else {
                confirmDeleteFileCustom(file, palette);
            }
        }));

        mask.addView(menuCard);
        showDialogLayer(mask);
    }

    private TextView createMenuItem(String text, int textColor, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
        tv.setClickable(true);
        tv.setFocusable(true);

        // 优雅条目圆角按下反馈
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(dpToPx(10));
        mask.setColor(Color.WHITE);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#253D6FD4")), null, mask);
        tv.setBackground(ripple);
        tv.setOnClickListener(click);
        return tv;
    }

    // ---------------- 抽屉同款毛玻璃 UI 重命名弹窗 ----------------
    private void promptRenameFileCustom(final File file, final MonetThemeHelper.Palette palette) {
        final FrameLayout mask = new FrameLayout(this);
        mask.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mask.setBackgroundColor(Color.parseColor("#33000000"));
        mask.setClickable(true);
        mask.setFocusable(true);

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.84f);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 居中靠上（距顶 26%），给软键盘留出充足展示空间，彻底防止输入法遮挡
        cardLp.gravity = Gravity.CENTER_HORIZONTAL;
        cardLp.topMargin = (int) (getResources().getDisplayMetrics().heightPixels * 0.26f);
        card.setLayoutParams(cardLp);
        card.setElevation(dpToPx(24));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(20));
        int dialogBgColor;
        if (isDarkMode) {
            dialogBgColor = Color.argb(0xFA, 0x1A, 0x22, 0x30);
        } else {
            int raw = palette.cardBgColor;
            dialogBgColor = Color.argb(0xF8, Color.red(raw), Color.green(raw), Color.blue(raw));
        }
        bg.setColor(dialogBgColor);
        bg.setStroke(dpToPx(1), palette.borderColor);
        card.setBackground(bg);
        card.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(16));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("重命名 " + (file.isDirectory() ? "文件夹" : "文件"));
        tvTitle.setTextColor(palette.textColor);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tvTitle);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(file.getName());
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setTextColor(palette.textColor);
        input.setTextSize(14);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(0, dpToPx(14), 0, dpToPx(18));
        input.setLayoutParams(inputLp);
        input.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        // 半透明输入框底板
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setShape(GradientDrawable.RECTANGLE);
        inputBg.setCornerRadius(dpToPx(10));
        inputBg.setColor(isDarkMode ? Color.parseColor("#20FFFFFF") : Color.parseColor("#10000000"));
        inputBg.setStroke(dpToPx(1), palette.borderColor);
        input.setBackground(inputBg);
        card.addView(input);

        // 按钮栏
        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);

        Runnable dismiss = () -> {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            } catch (Throwable ignored) {}
            dismissActiveDialog();
        };
        mask.setOnClickListener(v -> dismiss.run());

        TextView btnCancel = new TextView(this);
        btnCancel.setText("取消");
        btnCancel.setTextColor(palette.textSecondaryColor);
        btnCancel.setTextSize(14);
        btnCancel.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
        btnCancel.setOnClickListener(v -> dismiss.run());
        btnBar.addView(btnCancel);

        TextView btnOk = new TextView(this);
        btnOk.setText("确定");
        btnOk.setTextColor(Color.parseColor("#4C8DFF"));
        btnOk.setTextSize(14);
        btnOk.setTypeface(Typeface.DEFAULT_BOLD);
        btnOk.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
        btnOk.setOnClickListener(v -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty() || newName.equals(file.getName())) {
                dismiss.run();
                return;
            }
            File target = new File(file.getParentFile(), newName);
            if (target.exists()) {
                Toast.makeText(this, "同名目标已存在", Toast.LENGTH_SHORT).show();
                return;
            }
            if (file.renameTo(target)) {
                Toast.makeText(this, "✓ 重命名成功", Toast.LENGTH_SHORT).show();
                dismiss.run();
                reloadWorkspaceFileTree();
            } else {
                Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show();
            }
        });
        btnBar.addView(btnOk);

        card.addView(btnBar);
        mask.addView(card);
        showDialogLayer(mask);

        input.postDelayed(() -> {
            input.requestFocus();
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            } catch (Throwable ignored) {}
        }, 120);
    }

    // ---------------- 抽屉同款毛玻璃 UI 删除确认弹窗 ----------------
    private void confirmDeleteFileCustom(final File file, final MonetThemeHelper.Palette palette) {
        final FrameLayout mask = new FrameLayout(this);
        mask.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mask.setBackgroundColor(Color.parseColor("#33000000"));
        mask.setClickable(true);
        mask.setFocusable(true);

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);
        card.setElevation(dpToPx(24));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(20));
        int dialogBgColor;
        if (isDarkMode) {
            dialogBgColor = Color.argb(0xFA, 0x1A, 0x22, 0x30);
        } else {
            int raw = palette.cardBgColor;
            dialogBgColor = Color.argb(0xF8, Color.red(raw), Color.green(raw), Color.blue(raw));
        }
        bg.setColor(dialogBgColor);
        bg.setStroke(dpToPx(1), palette.borderColor);
        card.setBackground(bg);
        card.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(16));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("删除确认");
        tvTitle.setTextColor(palette.textColor);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tvTitle);

        TextView tvMsg = new TextView(this);
        tvMsg.setText("确定彻底删除 " + (file.isDirectory() ? "文件夹" : "文件") + "「" + file.getName() + "」吗？\n此操作不可撤销。");
        tvMsg.setTextColor(palette.textColor);
        tvMsg.setTextSize(13);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dpToPx(12), 0, dpToPx(18));
        tvMsg.setLayoutParams(msgLp);
        card.addView(tvMsg);

        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);

        Runnable dismiss = () -> dismissActiveDialog();
        mask.setOnClickListener(v -> dismiss.run());

        TextView btnCancel = new TextView(this);
        btnCancel.setText("取消");
        btnCancel.setTextColor(palette.textSecondaryColor);
        btnCancel.setTextSize(14);
        btnCancel.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
        btnCancel.setOnClickListener(v -> dismiss.run());
        btnBar.addView(btnCancel);

        TextView btnDelete = new TextView(this);
        btnDelete.setText("删除");
        btnDelete.setTextColor(Color.parseColor("#FF5252"));
        btnDelete.setTextSize(14);
        btnDelete.setTypeface(Typeface.DEFAULT_BOLD);
        btnDelete.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
        btnDelete.setOnClickListener(v -> {
            boolean ok;
            if (file.isDirectory()) {
                ok = deleteRecursively(file);
            } else {
                ok = file.delete();
            }
            if (ok) {
                Toast.makeText(this, "✓ 已删除", Toast.LENGTH_SHORT).show();
                dismiss.run();
                reloadWorkspaceFileTree();
            } else {
                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
            }
        });
        btnBar.addView(btnDelete);

        card.addView(btnBar);
        mask.addView(card);
        showDialogLayer(mask);
    }

    private boolean deleteRecursively(File dir) {
        if (dir == null) return false;
        if (dir.isDirectory()) {
            File[] subs = dir.listFiles();
            if (subs != null) {
                for (File s : subs) deleteRecursively(s);
            }
        }
        return dir.delete();
    }

    // ---------------- 抽屉同款毛玻璃 UI 下载确认弹窗 ----------------
    private void confirmDownloadFileCustom(final File file, final MonetThemeHelper.Palette palette, final String rawPath) {
        final FrameLayout mask = new FrameLayout(this);
        mask.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mask.setBackgroundColor(Color.parseColor("#33000000"));
        mask.setClickable(true);
        mask.setFocusable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dpToPx(290), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        card.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(16));
        int dialogBgColor;
        if (isDarkMode) {
            dialogBgColor = Color.argb(0xFA, 0x1A, 0x22, 0x30);
        } else {
            int raw = palette.cardBgColor;
            dialogBgColor = Color.argb(0xF8, Color.red(raw), Color.green(raw), Color.blue(raw));
        }
        bg.setColor(dialogBgColor);
        bg.setStroke(dpToPx(1), palette.borderColor);
        card.setBackground(bg);
        card.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(16));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("下载确认");
        tvTitle.setTextColor(palette.textColor);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tvTitle);

        TextView tvMsg = new TextView(this);
        tvMsg.setText("确定将文件「" + file.getName() + "」保存至客户端的 Download 目录吗？");
        tvMsg.setTextColor(palette.textColor);
        tvMsg.setTextSize(13);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dpToPx(12), 0, dpToPx(18));
        tvMsg.setLayoutParams(msgLp);
        card.addView(tvMsg);

        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);

        Runnable dismiss = () -> dismissActiveDialog();
        mask.setOnClickListener(v -> dismiss.run());

        TextView btnCancel = new TextView(this);
        btnCancel.setText("取消");
        btnCancel.setTextColor(palette.textSecondaryColor);
        btnCancel.setTextSize(14);
        btnCancel.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
        btnCancel.setOnClickListener(v -> dismiss.run());
        btnBar.addView(btnCancel);

        TextView btnDownload = new TextView(this);
        btnDownload.setText("下载");
        btnDownload.setTextColor(Color.parseColor("#4C8DFF"));
        btnDownload.setTextSize(14);
        btnDownload.setTypeface(Typeface.DEFAULT_BOLD);
        btnDownload.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
        btnDownload.setOnClickListener(v -> {
            dismiss.run();
            performDownloadFileToDownloads(file, rawPath, null);
        });
        btnBar.addView(btnDownload);

        card.addView(btnBar);
        mask.addView(card);
        showDialogLayer(mask);
    }

    private interface OnFileDownloadedCallback {
        void onDownloaded(File destFile);
    }

    private void performDownloadFileToDownloads(final File srcFile, final String rawPath, final OnFileDownloadedCallback callback) {
        if (!sIsRemoteActive) {
            if (srcFile == null || !srcFile.exists() || !srcFile.isFile()) {
                Toast.makeText(this, "文件不可读或不存在", Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(this, "正在保存至 Download 目录...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (downloadDir != null && !downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }
                    if (downloadDir == null || !downloadDir.canWrite()) {
                        downloadDir = new File("/sdcard/Download");
                    }

                    String baseName = srcFile.getName();
                    String namePart = baseName;
                    String extPart = "";
                    int dot = baseName.lastIndexOf('.');
                    if (dot > 0) {
                        namePart = baseName.substring(0, dot);
                        extPart = baseName.substring(dot);
                    }

                    File dest = new File(downloadDir, baseName);
                    int count = 1;
                    while (dest.exists()) {
                        dest = new File(downloadDir, namePart + " (" + count + ")" + extPart);
                        count++;
                    }

                    try (java.io.FileInputStream in = new java.io.FileInputStream(srcFile);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                        byte[] buffer = new byte[64 * 1024];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                        out.flush();
                    }

                    final File finalDest = dest;
                    try {
                        android.media.MediaScannerConnection.scanFile(
                                getApplicationContext(),
                                new String[]{finalDest.getAbsolutePath()},
                                null,
                                null
                        );
                    } catch (Throwable ignored) {}

                    runOnUiThread(() -> {
                        if (rootOverlay != null) {
                            try {
                                rootOverlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                            } catch (Throwable ignored) {}
                        }
                        Toast.makeText(this, "✓ 已下载至 Download/" + finalDest.getName(), Toast.LENGTH_LONG).show();
                        if (callback != null) callback.onDownloaded(finalDest);
                    });
                } catch (Exception e) {
                    final String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "下载失败: " + err, Toast.LENGTH_LONG).show();
                    });
                }
            }, "dsha-file-download").start();
        } else {
            // 远端文件流式下载
            Toast.makeText(this, "正在从远端下载至手机 Download 目录...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    ConfigStore cfg = new ConfigStore(this);
                    String remoteUrl = cfg.getRemoteDshUrl();
                    if (remoteUrl == null || remoteUrl.isEmpty()) {
                        runOnUiThread(() -> Toast.makeText(this, "未配置远端 DSH 地址", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    Uri rUri = Uri.parse(remoteUrl);
                    String baseUrl = rUri.getScheme() + "://" + rUri.getHost() + (rUri.getPort() != -1 ? ":" + rUri.getPort() : "");
                    String token = rUri.getQueryParameter("token");

                    String targetPath = rawPath != null ? rawPath : srcFile.getName();
                    String downloadApi = baseUrl + "/api/file?path=" + java.net.URLEncoder.encode(targetPath, "UTF-8");
                    if (token != null && !token.isEmpty()) {
                        downloadApi += "&token=" + java.net.URLEncoder.encode(token, "UTF-8");
                    }

                    File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (downloadDir != null && !downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }
                    if (downloadDir == null || !downloadDir.canWrite()) {
                        downloadDir = new File("/sdcard/Download");
                    }

                    String fileName = srcFile.getName();
                    int lastSep = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
                    if (lastSep >= 0) {
                        fileName = fileName.substring(lastSep + 1);
                    }
                    if (fileName.isEmpty()) {
                        fileName = "downloaded_file";
                    }

                    String namePart = fileName;
                    String extPart = "";
                    int dot = fileName.lastIndexOf('.');
                    if (dot > 0) {
                        namePart = fileName.substring(0, dot);
                        extPart = fileName.substring(dot);
                    }

                    File dest = new File(downloadDir, fileName);
                    int count = 1;
                    while (dest.exists()) {
                        dest = new File(downloadDir, namePart + " (" + count + ")" + extPart);
                        count++;
                    }

                    java.net.URL url = new java.net.URL(downloadApi);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(15000);

                    String cks = android.webkit.CookieManager.getInstance().getCookie(remoteUrl);
                    if (cks != null && !cks.isEmpty()) {
                        conn.setRequestProperty("Cookie", cks);
                    }

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new RuntimeException("远端服务器响应错误: HTTP " + code);
                    }

                    try (java.io.InputStream in = conn.getInputStream();
                         java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                        byte[] buffer = new byte[64 * 1024];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                        out.flush();
                    }
                    conn.disconnect();

                    final File finalDest = dest;
                    try {
                        android.media.MediaScannerConnection.scanFile(
                                getApplicationContext(),
                                new String[]{finalDest.getAbsolutePath()},
                                null,
                                null
                        );
                    } catch (Throwable ignored) {}

                    runOnUiThread(() -> {
                        if (rootOverlay != null) {
                            try {
                                rootOverlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                            } catch (Throwable ignored) {}
                        }
                        Toast.makeText(this, "✓ 已下载至 Download/" + finalDest.getName(), Toast.LENGTH_LONG).show();
                        if (callback != null) callback.onDownloaded(finalDest);
                    });
                } catch (Exception e) {
                    final String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "下载失败: " + err, Toast.LENGTH_LONG).show();
                    });
                }
            }, "dsha-remote-download").start();
        }
    }

    // ---------------- 抽屉内置万能查看器核心引擎（异步化多线程加载架构） ----------------
    public void openFileInSheet(String path) {
        if (path == null || path.isEmpty()) return;
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            Toast.makeText(this, "文件不存在：" + path, Toast.LENGTH_SHORT).show();
            return;
        }

        currentViewingFile = file;
        final long thisEpoch = ++currentFileLoadEpoch;

        // 1. 顶栏瞬间切换至文档模式（排版居中受限，绝不遮挡左右按钮）
        headerTitle.setText(file.getName());
        headerTitle.setTextSize(16);
        if (headerSubTitle != null) {
            headerSubTitle.setText(formatFileSize(file.length()) + " · 加载中…");
            headerSubTitle.setVisibility(View.VISIBLE);
        }
        btnClose.setIconType(ICON_BACK); // 切换为 ‹ 返回箭头
        btnSettings.setVisibility(View.GONE);
        btnFiles.setVisibility(View.GONE);
        if (btnRefresh != null) btnRefresh.setVisibility(View.GONE);
        btnNewChat.setVisibility(View.GONE);
        btnFullscreen.setVisibility(View.GONE);
        if (btnFileOpenExternal != null) btnFileOpenExternal.setVisibility(View.VISIBLE);
        if (btnFileSave != null) btnFileSave.setVisibility(View.GONE);

        // 2. 容器瞬间切换，展示居中半透明加载圈，主线程 0 阻塞！
        fileViewerContainer.removeAllViews();
        fileViewerContainer.setBackgroundColor(Color.TRANSPARENT);
        fileViewerContainer.setVisibility(View.VISIBLE);
        if (sCachedWebView != null) sCachedWebView.setVisibility(View.GONE);

        ProgressBar loadingSpinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(36));
        spinLp.gravity = Gravity.CENTER;
        fileViewerContainer.addView(loadingSpinner, spinLp);

        // 3. 异步后台工作线程：执行重度 I/O、XML解压解析与大图降采样解码
        new Thread(() -> {
            final com.deepseekharness.app.viewer.FileTypeClassifier.FileType ft =
                    com.deepseekharness.app.viewer.FileTypeClassifier.classify(file);

            if (thisEpoch != currentFileLoadEpoch) return;

            if (ft.kind == com.deepseekharness.app.viewer.FileTypeClassifier.FileKind.OFFICE) {
                String content = null;
                String name = file.getName().toLowerCase();
                boolean isTable = name.endsWith(".xlsx") || name.endsWith(".xls");
                if (name.endsWith(".docx")) {
                    content = com.deepseekharness.app.viewer.OfficeTextExtractor.extractDocx(file);
                } else if (name.endsWith(".doc")) {
                    content = com.deepseekharness.app.viewer.OfficeTextExtractor.extractDoc(file);
                } else if (name.endsWith(".xlsx")) {
                    content = com.deepseekharness.app.viewer.OfficeTextExtractor.extractXlsx(file);
                } else if (name.endsWith(".xls")) {
                    content = com.deepseekharness.app.viewer.OfficeTextExtractor.extractXls(file);
                } else if (name.endsWith(".pptx")) {
                    content = com.deepseekharness.app.viewer.OfficeTextExtractor.extractPptx(file);
                } else if (name.endsWith(".ppt")) {
                    content = com.deepseekharness.app.viewer.OfficeTextExtractor.extractDoc(file);
                }
                final String finalOfficeContent = content;
                final boolean finalIsTable = isTable;
                runOnUiThread(() -> {
                    if (thisEpoch != currentFileLoadEpoch) return;
                    fileViewerContainer.removeAllViews();
                    if (headerSubTitle != null) {
                        headerSubTitle.setText(formatFileSize(file.length()) + " · " + ft.kind.name());
                    }
                    if (finalOfficeContent != null && !finalOfficeContent.isEmpty()) {
                        if (finalIsTable) {
                            View gridView = com.deepseekharness.app.viewer.SheetTableGrid.createGridView(this, finalOfficeContent);
                            fileViewerContainer.addView(gridView);
                        } else {
                            io.github.rosemoe.sora.widget.CodeEditor editor = new io.github.rosemoe.sora.widget.CodeEditor(this);
                            editor.setLayoutParams(new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                            editor.setColorScheme(createTransparentColorScheme());
                            editor.setBackgroundColor(Color.TRANSPARENT);
                            editor.setTextSize(13);
                            editor.setLineNumberEnabled(false);
                            editor.setEditable(false);
                            editor.setWordwrap(true);
                            editor.setText(finalOfficeContent);
                            fileViewerContainer.addView(editor);
                        }
                    } else {
                        Toast.makeText(this, "Office 结构复杂，已转为十六进制视图", Toast.LENGTH_SHORT).show();
                        loadSheetHexViewer(file);
                    }
                });
            } else if (ft.kind == com.deepseekharness.app.viewer.FileTypeClassifier.FileKind.TEXT) {
                String textData = null;
                try {
                    int maxRead = (int) Math.min(file.length(), 2 * 1024 * 1024);
                    byte[] bytes = new byte[maxRead];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        fis.read(bytes);
                    }
                    textData = new String(bytes, StandardCharsets.UTF_8);
                    if (file.length() > maxRead) {
                        textData += "\n\n/* ----- (文件过大，仅加载前 2MB 内容) ----- */";
                    }
                } catch (Exception ignored) {}
                final String finalText = textData;
                runOnUiThread(() -> {
                    if (thisEpoch != currentFileLoadEpoch) return;
                    fileViewerContainer.removeAllViews();
                    if (headerSubTitle != null) {
                        headerSubTitle.setText(formatFileSize(file.length()) + " · TEXT");
                    }
                    if (finalText != null) {
                        if (btnFileSave != null) btnFileSave.setVisibility(View.VISIBLE);
                        currentCodeEditor = new io.github.rosemoe.sora.widget.CodeEditor(this);
                        currentCodeEditor.setLayoutParams(new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                        currentCodeEditor.setColorScheme(createTransparentColorScheme());
                        currentCodeEditor.setBackgroundColor(Color.TRANSPARENT);
                        currentCodeEditor.setTextSize(13);
                        currentCodeEditor.setLineNumberEnabled(true);
                        currentCodeEditor.setWordwrap(true);
                        currentCodeEditor.setText(finalText);
                        fileViewerContainer.addView(currentCodeEditor);
                    } else {
                        loadSheetHexViewer(file);
                    }
                });
            } else if (ft.kind == com.deepseekharness.app.viewer.FileTypeClassifier.FileKind.IMAGE) {
                Bitmap decoded = null;
                try {
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                    int reqW = getResources().getDisplayMetrics().widthPixels;
                    int reqH = getResources().getDisplayMetrics().heightPixels;
                    int sample = 1;
                    if (opts.outHeight > reqH || opts.outWidth > reqW) {
                        int halfH = opts.outHeight / 2;
                        int halfW = opts.outWidth / 2;
                        while ((halfH / sample) >= reqH && (halfW / sample) >= reqW) {
                            sample *= 2;
                        }
                    }
                    opts.inSampleSize = sample;
                    opts.inJustDecodeBounds = false;
                    decoded = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                } catch (Throwable ignored) {}
                final Bitmap finalBmp = decoded;
                runOnUiThread(() -> {
                    if (thisEpoch != currentFileLoadEpoch) return;
                    fileViewerContainer.removeAllViews();
                    if (headerSubTitle != null) {
                        headerSubTitle.setText(formatFileSize(file.length()) + " · IMAGE");
                    }
                    if (finalBmp != null) {
                        TouchImageView iv = new TouchImageView(this);
                        iv.setLayoutParams(new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                        iv.setBackgroundColor(Color.TRANSPARENT);
                        iv.setImageBitmap(finalBmp);
                        fileViewerContainer.addView(iv);
                    } else {
                        loadSheetHexViewer(file);
                    }
                });
            } else if (ft.kind == com.deepseekharness.app.viewer.FileTypeClassifier.FileKind.PDF) {
                runOnUiThread(() -> {
                    if (thisEpoch != currentFileLoadEpoch) return;
                    fileViewerContainer.removeAllViews();
                    if (headerSubTitle != null) {
                        headerSubTitle.setText(formatFileSize(file.length()) + " · PDF");
                    }
                    loadSheetPdfViewer(file);
                });
            } else if (ft.kind == com.deepseekharness.app.viewer.FileTypeClassifier.FileKind.ARCHIVE) {
                final java.util.List<com.deepseekharness.app.viewer.ArchiveBrowser.Entry> entries =
                        com.deepseekharness.app.viewer.ArchiveBrowser.listEntries(file);
                runOnUiThread(() -> {
                    if (thisEpoch != currentFileLoadEpoch) return;
                    fileViewerContainer.removeAllViews();
                    if (headerSubTitle != null) {
                        headerSubTitle.setText(formatFileSize(file.length()) + " · ARCHIVE");
                    }
                    bindSheetArchiveListView(file, entries);
                });
            } else {
                runOnUiThread(() -> {
                    if (thisEpoch != currentFileLoadEpoch) return;
                    fileViewerContainer.removeAllViews();
                    if (headerSubTitle != null) {
                        headerSubTitle.setText(formatFileSize(file.length()) + " · HEX");
                    }
                    loadSheetHexViewer(file);
                });
            }
        }, "dsha-file-async-loader").start();
    }

    public void closeFileViewer() {
        currentFileLoadEpoch++; // 立即作废未完成的后台加载任务
        if (fileViewerContainer != null) {
            fileViewerContainer.removeAllViews();
            fileViewerContainer.setVisibility(View.GONE);
        }
        if (sCachedWebView != null) {
            sCachedWebView.setVisibility(View.VISIBLE);
        }
        if (currentPdfAdapter != null) {
            currentPdfAdapter.release();
            currentPdfAdapter = null;
        }
        currentCodeEditor = null;
        currentViewingFile = null;

        // 恢复顶栏为对话模式
        updateHeaderTitleText();
        if (headerSubTitle != null) headerSubTitle.setVisibility(View.GONE);
        btnClose.setIconType(ICON_CLOSE);
        btnSettings.setVisibility(View.VISIBLE);
        btnFiles.setVisibility(View.VISIBLE);
        if (btnRefresh != null) btnRefresh.setVisibility(View.VISIBLE);
        btnNewChat.setVisibility(View.VISIBLE);
        btnFullscreen.setVisibility(View.VISIBLE);
        if (btnFileSave != null) btnFileSave.setVisibility(View.GONE);
        if (btnFileOpenExternal != null) btnFileOpenExternal.setVisibility(View.GONE);
    }

    private io.github.rosemoe.sora.widget.schemes.EditorColorScheme createTransparentColorScheme() {
        io.github.rosemoe.sora.widget.schemes.EditorColorScheme scheme =
                new io.github.rosemoe.sora.widget.schemes.EditorColorScheme();
        // 彻底消除白底画刷，使 Sora Editor 全画幅透明，完美透出抽屉毛玻璃与桌面壁纸
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, Color.TRANSPARENT);
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, Color.TRANSPARENT);
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.CURRENT_LINE, Color.parseColor("#08FFFFFF"));
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SELECTION_INSERT, Color.parseColor("#4C8DFF"));
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SELECTION_HANDLE, Color.parseColor("#4C8DFF"));
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SELECTED_TEXT_BACKGROUND, Color.parseColor("#334C8DFF"));

        // 根据当前抽屉的深浅色/反色状态适配文字与行号颜色
        int normalTextColor = isDarkMode ? Color.parseColor("#E8E8E8") : Color.parseColor("#1C1C1C");
        int lineNumColor = isDarkMode ? Color.parseColor("#777777") : Color.parseColor("#999999");
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL, normalTextColor);
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER, lineNumColor);
        return scheme;
    }

    private void saveCurrentEditorText() {
        if (currentCodeEditor == null || currentViewingFile == null) return;
        try {
            String text = currentCodeEditor.getText().toString();
            File tmp = new File(currentViewingFile.getParentFile(), "." + currentViewingFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (tmp.renameTo(currentViewingFile) || (currentViewingFile.delete() && tmp.renameTo(currentViewingFile))) {
                Toast.makeText(this, "✓ 已安全保存", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存覆盖失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "保存出错：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSheetPdfViewer(File file) {
        try {
            if (currentPdfAdapter != null) {
                currentPdfAdapter.release();
                currentPdfAdapter = null;
            }
            currentPdfAdapter = new com.deepseekharness.app.viewer.SheetPdfAdapter(this, file);

            android.widget.ListView listView = new android.widget.ListView(this);
            listView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            listView.setBackgroundColor(Color.TRANSPARENT);
            listView.setDivider(null);
            listView.setAdapter(currentPdfAdapter);
            fileViewerContainer.addView(listView);
        } catch (Exception e) {
            Toast.makeText(this, "PDF打开异常，已切为十六进制", Toast.LENGTH_SHORT).show();
            loadSheetHexViewer(file);
        }
    }

    private void bindSheetArchiveListView(File file, java.util.List<com.deepseekharness.app.viewer.ArchiveBrowser.Entry> entries) {
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lv.setBackgroundColor(Color.TRANSPARENT);
        lv.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                } else {
                    row = new LinearLayout(QuickChatSheetActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));
                    TextView tvName = new TextView(QuickChatSheetActivity.this);
                    tvName.setId(101);
                    tvName.setTextColor(Color.WHITE);
                    tvName.setTextSize(13);
                    row.addView(tvName);

                    TextView tvInfo = new TextView(QuickChatSheetActivity.this);
                    tvInfo.setId(102);
                    tvInfo.setTextColor(Color.parseColor("#888888"));
                    tvInfo.setTextSize(11);
                    row.addView(tvInfo);
                }
                com.deepseekharness.app.viewer.ArchiveBrowser.Entry e = entries.get(position);
                TextView tvName = row.findViewById(101);
                TextView tvInfo = row.findViewById(102);
                tvName.setText((e.isDirectory ? "📁 " : "📄 ") + e.path);
                tvInfo.setText(e.size < 1024 ? e.size + " B" : String.format("%.1f KB", e.size / 1024.0));
                return row;
            }
        });
        lv.setOnItemClickListener((parent, view, position, id) -> {
            com.deepseekharness.app.viewer.ArchiveBrowser.Entry e = entries.get(position);
            if (!e.isDirectory) {
                String text = com.deepseekharness.app.viewer.ArchiveBrowser.readEntryText(file, e.path);
                if (text != null) {
                    new AlertDialog.Builder(this)
                            .setTitle(e.name)
                            .setMessage(text.length() > 3000 ? text.substring(0, 3000) + "\n\n(截断显示)" : text)
                            .setPositiveButton("确定", null)
                            .show();
                } else {
                    Toast.makeText(this, "该文件不支持直接预览文本", Toast.LENGTH_SHORT).show();
                }
            }
        });
        fileViewerContainer.addView(lv);
    }

    private void loadSheetHexViewer(File file) {
        int totalRows = com.deepseekharness.app.viewer.HexDumper.rowCount(file.length());
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lv.setBackgroundColor(Color.TRANSPARENT);
        lv.setDivider(null);

        lv.setAdapter(new android.widget.BaseAdapter() {
            private int cachedBlockIndex = -1;
            private java.util.List<com.deepseekharness.app.viewer.HexDumper.HexRow> cachedRows = new ArrayList<>();

            @Override public int getCount() { return totalRows; }
            @Override public Object getItem(int position) { return position; }
            @Override public long getItemId(int position) { return position; }
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv;
                if (convertView instanceof TextView) {
                    tv = (TextView) convertView;
                } else {
                    tv = new TextView(QuickChatSheetActivity.this);
                    tv.setTypeface(Typeface.MONOSPACE);
                    tv.setTextSize(11);
                    tv.setTextColor(Color.parseColor("#D4D4D4"));
                    tv.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
                }

                long offset = com.deepseekharness.app.viewer.HexDumper.rowOffset(position);
                int blockIdx = com.deepseekharness.app.viewer.HexDumper.blockOf(offset);
                if (blockIdx != cachedBlockIndex) {
                    cachedBlockIndex = blockIdx;
                    byte[] blk = com.deepseekharness.app.viewer.HexDumper.readBlock(file, blockIdx);
                    cachedRows = com.deepseekharness.app.viewer.HexDumper.formatBlock(blk, (long) blockIdx * com.deepseekharness.app.viewer.HexDumper.BLOCK_SIZE);
                }

                int localRow = position % (com.deepseekharness.app.viewer.HexDumper.BLOCK_SIZE / com.deepseekharness.app.viewer.HexDumper.ROW_BYTES);
                if (localRow >= 0 && localRow < cachedRows.size()) {
                    com.deepseekharness.app.viewer.HexDumper.HexRow r = cachedRows.get(localRow);
                    tv.setText(String.format("%08X  %s  |%s|", r.offset, r.hex, r.ascii));
                }
                return tv;
            }
        });
        fileViewerContainer.addView(lv);
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** 支持手势缩放的双指 ImageView */
    private static class TouchImageView extends androidx.appcompat.widget.AppCompatImageView implements View.OnTouchListener {
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final android.graphics.Matrix savedMatrix = new android.graphics.Matrix();
        private int mode = 0;
        private final PointF start = new PointF();
        private final PointF mid = new PointF();
        private float oldDist = 1f;

        public TouchImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            setOnTouchListener(this);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix);
                    start.set(event.getX(), event.getY());
                    mode = 1;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    oldDist = spacing(event);
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix);
                        midPoint(mid, event);
                        mode = 2;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    mode = 0;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == 1) {
                        matrix.set(savedMatrix);
                        matrix.postTranslate(event.getX() - start.x, event.getY() - start.y);
                    } else if (mode == 2) {
                        float newDist = spacing(event);
                        if (newDist > 10f) {
                            matrix.set(savedMatrix);
                            float scale = newDist / oldDist;
                            matrix.postScale(scale, scale, mid.x, mid.y);
                        }
                    }
                    break;
            }
            setImageMatrix(matrix);
            return true;
        }

        private float spacing(MotionEvent event) {
            float x = event.getX(0) - event.getX(1);
            float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(x * x + y * y);
        }

        private void midPoint(PointF point, MotionEvent event) {
            point.set((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        
        // 抽屉退入后台时仅暂停单个 WebView 渲染合成释放 GPU，保留全局 JS 定时器与网络心跳存活
        if (sCachedWebView != null) {
            sCachedWebView.onPause();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        
        if (sCachedWebView != null) {
            sCachedWebView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        if (sCurrentInstance == this) {
            sCurrentInstance = null;
        }
        
        cancelFileSelection();
        WebUploads.clean(uploads);
        if (keyboardLayoutListener != null && getWindow() != null && getWindow().getDecorView() != null) {
            getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
        }
        super.onDestroy();
        if (sCachedWebView != null && sCachedWebView.getParent() == webContainer) {
            webContainer.removeView(sCachedWebView);
        }
    }
}
