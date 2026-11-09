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
import android.content.MutableContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.deepseekharness.app.util.ToastHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯净版快捷对话底部抽屉弹层（Pure QuickChat）：
 * 1. 采用标准实体浏览器窗口样式（无毛玻璃、无半透壁纸穿透、无液态玻璃高光渐变）；
 * 2. 彻底移除所有透明与颜色控制 CSS 注入，WebView 内部呈现网页原始设计；
 * 3. 100% 复用原版全部成熟能力与底层机制：
 *    - 直接复用 QuickChatSheetActivity.HeaderIconButton，按钮图标像素级完全一致；
 *    - 键盘监听 (WindowInsets IME) 与输入框抬高机制；
 *    - 点击标题的历史对话大纲提取与平滑跳转；
 *    - 35%/50%/65%/80%/95% 多档智能阶梯吸附与下拉退出手势；
 *    - 全局静态保活单例与 MutableContextWrapper 动态换绑，秒级热唤醒零白屏；
 *    - DshaWebView 专有的软键盘与物理键盘 Shift+Enter 换行拦截（防打字误发送）；
 *    - 原生安全审批横幅（Approval Banner）与文件查看器。
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class PureQuickChatSheetActivity extends AppCompatActivity {

    public static final int ICON_CLOSE = QuickChatSheetActivity.ICON_CLOSE;
    public static final int ICON_SETTINGS = QuickChatSheetActivity.ICON_SETTINGS;
    public static final int ICON_NEW_CHAT = QuickChatSheetActivity.ICON_NEW_CHAT;
    public static final int ICON_FULLSCREEN = QuickChatSheetActivity.ICON_FULLSCREEN;
    public static final int ICON_FILES = QuickChatSheetActivity.ICON_FILES;
    public static final int ICON_REFRESH = QuickChatSheetActivity.ICON_REFRESH;

    // 纯净版独立的全局静态保活双槽位，彻底杜绝白屏并与特效版双向隔离
    @SuppressLint("StaticFieldLeak")
    private static WebView sLocalWebView = null;
    @SuppressLint("StaticFieldLeak")
    private static WebView sRemoteWebView = null;
    public static boolean sIsRemoteActive = false;

    private static boolean sLocalWebLoaded = false;
    private static boolean sRemoteWebLoaded = false;
    private static long sLoadedGeneration = -1;
    private static int sLoadedPort = 0;
    private static String sLoadedRemoteUrl = "";
    private static volatile boolean sPendingNewChat = false;
    public static volatile String sPendingApprovalDecision = null;

    public static volatile PureQuickChatSheetActivity sCurrentInstance;

    public static WebView getActiveWebView() {
        if (sIsRemoteActive && sRemoteWebView != null) {
            return sRemoteWebView;
        }
        return sLocalWebView;
    }

    public static void syncApprovalDecision(boolean allow) {
        sPendingApprovalDecision = allow ? "ALLOWED" : "DENIED";
        PureQuickChatSheetActivity act = sCurrentInstance;
        if (act != null) {
            WebView activeWeb = getActiveWebView();
            if (activeWeb != null) {
                act.runOnUiThread(() -> executeApprovalDecisionScript(activeWeb, allow));
            }
        }
    }

    public static void executeApprovalDecisionScript(WebView webView, boolean allow) {
        if (webView == null) return;
        String js = "(function() {" +
                "  try {" +
                "    var targetText = '" + (allow ? "允许" : "拒绝") + "';" +
                "    var buttons = document.querySelectorAll('button');" +
                "    for (var i = 0; i < buttons.length; i++) {" +
                "      var b = buttons[i];" +
                "      if (b.innerText && b.innerText.trim() === targetText) {" +
                "        b.click();" +
                "        return 'CLICKED_' + targetText;" +
                "      }" +
                "    }" +
                "    return 'BTN_NOT_FOUND';" +
                "  } catch(e) {" +
                "    return 'ERR_' + e.message;" +
                "  }" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    // 视图层级结构
    private FrameLayout rootOverlay;
    private LinearLayout sheetCard;
    private FrameLayout dragArea;
    private View dragHandle;
    private FrameLayout webContainer;
    private View keyboardSpacer;
    private ProgressBar progressBar;
    private TextView errorHint;

    // 顶栏按钮与标题
    private QuickChatSheetActivity.HeaderIconButton btnClose;
    private QuickChatSheetActivity.HeaderIconButton btnSettings;
    private QuickChatSheetActivity.HeaderIconButton btnFiles;
    private QuickChatSheetActivity.HeaderIconButton btnRefresh;
    private QuickChatSheetActivity.HeaderIconButton btnNewChat;
    private QuickChatSheetActivity.HeaderIconButton btnFullscreen;
    private TextView btnFileSave;
    private TextView btnFileOpenExternal;
    private TextView headerTitle;
    private TextView headerSubTitle;
    private View headerDivider;

    // 历史对话大纲下拉面板
    private FrameLayout turnOutlineDropdownMask;
    private LinearLayout turnOutlineDropdownCard;

    // 原生安全审批横幅卡片
    private View approvalBannerView;
    private TextView approvalTitleText;
    private TextView approvalDetailText;
    private View approvalBtnAllow;
    private View approvalBtnDeny;
    private long currentApprovalEpoch = -1;

    // 内置文件查看器与编辑器
    private FrameLayout fileViewerContainer;
    private File currentViewingFile;
    private View currentActiveEditorView;
    private FrameLayout currentActiveDialogMask;

    // 尺寸、手势与拖拽状态
    private int screenHeight;
    private int screenWidth;
    private int defaultHeight;
    private int currentHeight;
    private int maxHeight;
    private int minHeight;
    private float touchDownY;
    private int heightAtTouchDown;
    private boolean isDragging = false;
    private boolean isDismissing = false;
    private boolean isKeyboardElevated = false;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;

    private HarnessController controller;
    private boolean isDarkTheme = false;

    // 纯净标准窗口配色表
    private static class BrowserTheme {
        final int cardBg;
        final int headerBg;
        final int textPrimary;
        final int textSecondary;
        final int border;
        final int dragHandle;
        final int webBg;

        BrowserTheme(boolean dark) {
            if (dark) {
                cardBg = Color.parseColor("#18181B");
                headerBg = Color.parseColor("#202124");
                textPrimary = Color.parseColor("#F4F4F5");
                textSecondary = Color.parseColor("#A1A1AA");
                border = Color.parseColor("#3F3F46");
                dragHandle = Color.parseColor("#52525B");
                webBg = Color.parseColor("#18181B");
            } else {
                cardBg = Color.parseColor("#FFFFFF");
                headerBg = Color.parseColor("#F9FAFB");
                textPrimary = Color.parseColor("#1F2937");
                textSecondary = Color.parseColor("#6B7280");
                border = Color.parseColor("#E5E7EB");
                dragHandle = Color.parseColor("#D1D5DB");
                webBg = Color.parseColor("#FFFFFF");
            }
        }
    }

    private BrowserTheme currentTheme;

    // 文件选择器启动器
    private ValueCallback<Uri[]> filePathCallback;
    private final ActivityResultLauncher<Intent> fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (filePathCallback == null) return;
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        overridePendingTransition(0, 0);
        enforceExcludeFromRecents();
        super.onCreate(savedInstanceState);

        // 如果用户关闭了纯净模式，自动双向重定向回经典特效抽屉
        if (!new ConfigStore(this).isSheetPureMode()) {
            Intent redirect = new Intent(getIntent());
            redirect.setClass(this, QuickChatSheetActivity.class);
            startActivity(redirect);
            finish();
            overridePendingTransition(0, 0);
            return;
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setFinishOnTouchOutside(true);

        sCurrentInstance = this;
        controller = HarnessController.get(this);
        isDarkTheme = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        currentTheme = new BrowserTheme(isDarkTheme);

        Window window = getWindow();
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.35f);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
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

    private void enforceExcludeFromRecents() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                List<android.app.ActivityManager.AppTask> tasks = am.getAppTasks();
                if (tasks != null) {
                    for (android.app.ActivityManager.AppTask task : tasks) {
                        android.app.ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                        if (info != null && info.taskId == getTaskId()) {
                            task.setExcludeFromRecents(true);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void calculateDimensions() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        maxHeight = (int) (screenHeight * 0.95f);
        minHeight = (int) (screenHeight * 0.25f);

        ConfigStore cfg = new ConfigStore(this);
        int percent = cfg.getSheetHeightPercent();
        if (percent < 30 || percent > 95) percent = 75;
        defaultHeight = (int) (screenHeight * (percent / 100.0f));
        currentHeight = defaultHeight;
    }

    private void updateSystemBarsTheme() {
        Window window = getWindow();
        if (window == null) return;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkTheme);
            controller.setAppearanceLightNavigationBars(!isDarkTheme);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ================================= 布局与 UI 构建 =================================

    private View buildUi() {
        ConfigStore cfg = new ConfigStore(this);

        // 1. 根全屏透明遮罩容器
        rootOverlay = new FrameLayout(this);
        rootOverlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootOverlay.setBackgroundColor(Color.TRANSPARENT);
        rootOverlay.setPadding(0, 0, 0, 0);
        rootOverlay.setFitsSystemWindows(false);

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

        // 2. 底部实体卡片主体（标准实色浏览器窗口，彻底剔除毛玻璃与液态渐变）
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
        sheetCard.setClipToOutline(true);
        sheetCard.setFitsSystemWindows(false);

        // 标准实体窗口背景：实色白/黑 + 顶部 18dp 圆角 + 1px 干净中性边框
        sheetCard.setBackground(buildSolidCardBackground(cfg));

        // 3. 拖拽横条把手区域
        dragArea = new FrameLayout(this);
        dragArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(14)));
        dragArea.setPadding(0, dpToPx(4), 0, dpToPx(2));
        dragArea.setBackgroundColor(currentTheme.headerBg);

        dragHandle = new View(this);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        dragHandle.setLayoutParams(handleLp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setShape(GradientDrawable.RECTANGLE);
        handleBg.setCornerRadius(dpToPx(2));
        handleBg.setColor(currentTheme.dragHandle);
        dragHandle.setBackground(handleBg);
        dragArea.addView(dragHandle);
        sheetCard.addView(dragArea);

        // 4. 顶部操作栏
        RelativeLayout headerBar = new RelativeLayout(this);
        headerBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(42)));
        headerBar.setPadding(dpToPx(10), 0, dpToPx(10), dpToPx(2));
        headerBar.setBackgroundColor(currentTheme.headerBg);

        // 左侧按钮组：[✕ 关闭] + [>_ 本机/远端切换] + [📁 工作区文件]
        LinearLayout leftGroup = new LinearLayout(this);
        leftGroup.setId(View.generateViewId());
        RelativeLayout.LayoutParams leftLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        leftLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        leftLp.addRule(RelativeLayout.CENTER_VERTICAL);
        leftGroup.setLayoutParams(leftLp);
        leftGroup.setOrientation(LinearLayout.HORIZONTAL);
        leftGroup.setGravity(Gravity.CENTER_VERTICAL);

        // 直接复用原版 QuickChatSheetActivity.createHeaderIconButton！
        btnClose = QuickChatSheetActivity.createHeaderIconButton(this, ICON_CLOSE, currentTheme.textPrimary, "关闭弹层");
        btnClose.setOnClickListener(v -> {
            if (fileViewerContainer != null && fileViewerContainer.getVisibility() == View.VISIBLE) {
                closeFileViewer();
            } else {
                dismissSheet();
            }
        });
        leftGroup.addView(btnClose);

        btnSettings = QuickChatSheetActivity.createHeaderIconButton(this, ICON_SETTINGS, currentTheme.textPrimary, "切换本机/远端 DSH 连接");
        LinearLayout.LayoutParams settingsLp = (LinearLayout.LayoutParams) btnSettings.getLayoutParams();
        settingsLp.setMarginStart(dpToPx(4));
        btnSettings.setLayoutParams(settingsLp);
        btnSettings.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            toggleDshConnectionTarget();
        });
        leftGroup.addView(btnSettings);

        btnFiles = QuickChatSheetActivity.createHeaderIconButton(this, ICON_FILES, currentTheme.textPrimary, "切换工作区文件树");
        LinearLayout.LayoutParams filesLp = (LinearLayout.LayoutParams) btnFiles.getLayoutParams();
        filesLp.setMarginStart(dpToPx(4));
        btnFiles.setLayoutParams(filesLp);
        btnFiles.setOnClickListener(v -> toggleWorkspaceFileTree());
        leftGroup.addView(btnFiles);

        headerBar.addView(leftGroup);

        // 右侧按钮组：[🔄 刷新鉴权] + [💬➕ 新建会话] + [⬒ 全屏展开] + [💾 保存] + [↗ 外部]
        LinearLayout rightGroup = new LinearLayout(this);
        rightGroup.setId(View.generateViewId());
        RelativeLayout.LayoutParams rightLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rightLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        rightLp.addRule(RelativeLayout.CENTER_VERTICAL);
        rightGroup.setLayoutParams(rightLp);
        rightGroup.setOrientation(LinearLayout.HORIZONTAL);
        rightGroup.setGravity(Gravity.CENTER_VERTICAL);

        btnRefresh = QuickChatSheetActivity.createHeaderIconButton(this, ICON_REFRESH, currentTheme.textPrimary, "重新获取Token并刷新进入DSH");
        btnRefresh.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            btnRefresh.animate().rotationBy(360f).setDuration(600)
                    .setInterpolator(new DecelerateInterpolator()).start();
            if (fileViewerContainer != null && fileViewerContainer.getVisibility() == View.VISIBLE) {
                closeFileViewer();
            }
            if (errorHint != null) errorHint.setVisibility(View.GONE);

            if (sIsRemoteActive) {
                String activeUrl = ConfigStore.get(this).getActiveRemoteDshUrl();
                if (activeUrl == null || activeUrl.isEmpty()) {
                    ToastHelper.makeText(this, "未开启任何远端连接", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                ToastHelper.makeText(this, "正在重新连接远端 DSH...", Toast.LENGTH_SHORT).show();
                ensureRemoteWebViewLoaded(activeUrl);
                if (sRemoteWebView != null) {
                    sLoadedRemoteUrl = activeUrl;
                    sRemoteWebLoaded = false;
                    sRemoteWebView.loadUrl(activeUrl);
                }
            } else {
                ToastHelper.makeText(this, "正在重新获取凭据并刷新本机 DSH...", Toast.LENGTH_SHORT).show();
                forceReloadWithLatestToken();
            }
        });
        rightGroup.addView(btnRefresh);

        btnNewChat = QuickChatSheetActivity.createHeaderIconButton(this, ICON_NEW_CHAT, currentTheme.textPrimary, "开启新对话");
        LinearLayout.LayoutParams newChatLp = (LinearLayout.LayoutParams) btnNewChat.getLayoutParams();
        newChatLp.setMarginStart(dpToPx(4));
        btnNewChat.setLayoutParams(newChatLp);
        btnNewChat.setOnClickListener(v -> triggerNewChat());
        rightGroup.addView(btnNewChat);

        btnFullscreen = QuickChatSheetActivity.createHeaderIconButton(this, ICON_FULLSCREEN, currentTheme.textPrimary, "全屏打开聊天页面");
        LinearLayout.LayoutParams fullscreenLp = (LinearLayout.LayoutParams) btnFullscreen.getLayoutParams();
        fullscreenLp.setMarginStart(dpToPx(4));
        btnFullscreen.setLayoutParams(fullscreenLp);
        btnFullscreen.setOnClickListener(v -> expandToFullscreen());
        rightGroup.addView(btnFullscreen);

        btnFileSave = new TextView(this);
        btnFileSave.setText("保存");
        btnFileSave.setTextColor(Color.parseColor("#4C8DFF"));
        btnFileSave.setTextSize(14);
        btnFileSave.setTypeface(Typeface.DEFAULT_BOLD);
        btnFileSave.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        btnFileSave.setVisibility(View.GONE);
        btnFileSave.setOnClickListener(v -> saveCurrentEditorText());
        rightGroup.addView(btnFileSave);

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

        // 中间标题容器（点击标题唤出历史对话大纲与跳转！）
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER);
        RelativeLayout.LayoutParams titleBoxLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleBoxLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        int initialMargin = dpToPx(124);
        titleBoxLp.leftMargin = initialMargin;
        titleBoxLp.rightMargin = initialMargin;
        titleBox.setLayoutParams(titleBoxLp);

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
                titleBox.setLayoutParams(lp);
            }
        };
        leftGroup.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> updateTitleMargin.run());
        rightGroup.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> updateTitleMargin.run());

        headerTitle = new TextView(this);
        headerTitle.setText("DSHA 对话");
        headerTitle.setTextColor(currentTheme.textPrimary);
        headerTitle.setTextSize(17);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        headerTitle.setGravity(Gravity.CENTER);
        headerTitle.setPadding(dpToPx(10), dpToPx(3), dpToPx(10), dpToPx(3));
        headerTitle.setClickable(true);
        headerTitle.setFocusable(true);
        headerTitle.setOnClickListener(v -> {
            if (fileViewerContainer != null && fileViewerContainer.getVisibility() == View.VISIBLE) return;
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            toggleTurnOutlineDropdown();
        });
        titleBox.addView(headerTitle);

        headerSubTitle = new TextView(this);
        headerSubTitle.setTextColor(currentTheme.textSecondary);
        headerSubTitle.setTextSize(10);
        headerSubTitle.setSingleLine(true);
        headerSubTitle.setGravity(Gravity.CENTER);
        headerSubTitle.setVisibility(View.GONE);
        titleBox.addView(headerSubTitle);

        headerBar.addView(titleBox);
        leftGroup.bringToFront();
        rightGroup.bringToFront();
        sheetCard.addView(headerBar);

        // 5. 分割线
        headerDivider = new View(this);
        headerDivider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        headerDivider.setBackgroundColor(currentTheme.border);
        sheetCard.addView(headerDivider);

        // 5.5 原生安全审批横幅卡片
        approvalBannerView = buildApprovalBannerView();
        sheetCard.addView(approvalBannerView);

        // 6. WebView 主体容器
        webContainer = new FrameLayout(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        webContainer.setLayoutParams(webLp);
        webContainer.setClipChildren(true);
        webContainer.setClipToPadding(true);
        webContainer.setBackgroundColor(currentTheme.webBg);

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(36));
        pbLp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbLp);
        progressBar.setVisibility(View.GONE);
        webContainer.addView(progressBar);

        errorHint = new TextView(this);
        FrameLayout.LayoutParams errLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.gravity = Gravity.CENTER;
        errorHint.setLayoutParams(errLp);
        errorHint.setText("正在连接 DSHA 服务…");
        errorHint.setTextColor(currentTheme.textSecondary);
        errorHint.setTextSize(14);
        errorHint.setVisibility(View.GONE);
        webContainer.addView(errorHint);

        fileViewerContainer = new FrameLayout(this);
        fileViewerContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fileViewerContainer.setBackgroundColor(currentTheme.webBg);
        fileViewerContainer.setVisibility(View.GONE);
        webContainer.addView(fileViewerContainer);

        sheetCard.addView(webContainer);

        // 7. 键盘底部占位底板（承托输入法，使输入框自然上浮）
        keyboardSpacer = new View(this);
        keyboardSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        keyboardSpacer.setBackgroundColor(currentTheme.cardBg);
        sheetCard.addView(keyboardSpacer);

        rootOverlay.addView(sheetCard);
        return rootOverlay;
    }

    private Drawable buildSolidCardBackground(ConfigStore cfg) {
        int radiusPx = dpToPx(18);
        float[] radii = new float[]{
                radiusPx, radiusPx,
                radiusPx, radiusPx,
                0, 0,
                0, 0
        };
        GradientDrawable solidBg = new GradientDrawable();
        solidBg.setShape(GradientDrawable.RECTANGLE);
        solidBg.setCornerRadii(radii);
        solidBg.setColor(currentTheme.cardBg);
        solidBg.setStroke(dpToPx(1), currentTheme.border);
        return solidBg;
    }

    // ================================= WebView 核心与加载 =================================

    private WebView createConfiguredWebView() {
        MutableContextWrapper contextWrapper = new MutableContextWrapper(this);
        DshaWebView web = new DshaWebView(contextWrapper);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setSupportMultipleWindows(false);
        ws.setSupportZoom(false);
        try {
            ws.setSafeBrowsingEnabled(false);
        } catch (Throwable ignored) {}
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        ws.setTextZoom(100);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 纯净模式呈现网页原本底色
        web.setBackgroundColor(currentTheme.webBg);

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
                    ToastHelper.makeText(this, "开始下载：" + fileName + "（保存在 Download/DSHA/）", Toast.LENGTH_LONG).show();
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

    private void attachChatWeb() {
        ConfigStore cfg = new ConfigStore(getApplicationContext());
        String activeRemoteUrl = cfg.getActiveRemoteDshUrl();
        boolean hasActiveRemote = cfg.isAnyRemoteDshActive() && activeRemoteUrl != null && !activeRemoteUrl.isEmpty();

        sIsRemoteActive = false;

        if (sLocalWebView == null) {
            sLocalWebView = createConfiguredWebView();
            loadInitialLocalWeb();
        } else {
            ensureWebViewParentClean(sLocalWebView);
            if (sLocalWebView.getContext() instanceof MutableContextWrapper) {
                ((MutableContextWrapper) sLocalWebView.getContext()).setBaseContext(this);
            }
            sLocalWebView.setWebViewClient(createSheetWebViewClient());
            sLocalWebView.setWebChromeClient(new SheetChromeClient());
            sLocalWebView.addJavascriptInterface(new NativeBridgeInterface(), "DshaNativeBridge");
        }

        if (sIsRemoteActive) {
            ensureRemoteWebViewLoaded(activeRemoteUrl);
        } else if (!hasActiveRemote && sRemoteWebView != null) {
            sRemoteWebView.stopLoading();
            sRemoteWebView.loadUrl("about:blank");
            sRemoteWebView.onPause();
            sLoadedRemoteUrl = "";
            sRemoteWebLoaded = false;
        } else if (sRemoteWebView != null) {
            sRemoteWebView.onPause();
            if (sRemoteWebView.getContext() instanceof MutableContextWrapper) {
                ((MutableContextWrapper) sRemoteWebView.getContext()).setBaseContext(this);
            }
            sRemoteWebView.setWebViewClient(createSheetWebViewClient());
            sRemoteWebView.setWebChromeClient(new SheetChromeClient());
            sRemoteWebView.addJavascriptInterface(new NativeBridgeInterface(), "DshaNativeBridge");
        }

        mountWebViewToContainer(sLocalWebView);
        if (sRemoteWebView != null && sIsRemoteActive) {
            mountWebViewToContainer(sRemoteWebView);
        }

        updateWebViewSlotVisibility();
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
        updateHeaderTitleText();
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
        }, "pure-sheet-cookie-init").start();

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
            }, "pure-sheet-wait-auth").start();
        }
    }

    private void ensureRemoteWebViewLoaded(String targetUrl) {
        if (targetUrl == null || targetUrl.isEmpty()) return;
        if (sRemoteWebView == null) {
            sRemoteWebView = createConfiguredWebView();
        }
        if (!targetUrl.equals(sLoadedRemoteUrl) || !sRemoteWebLoaded) {
            sLoadedRemoteUrl = targetUrl;
            sRemoteWebLoaded = false;
            sRemoteWebView.loadUrl(targetUrl);
        }
    }

    private void forceReloadWithLatestToken() {
        if (sLocalWebView == null) return;
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        sLocalWebLoaded = false;
        final int port = controller != null ? controller.getPort() : 3080;
        sLoadedPort = port;

        new Thread(() -> {
            String targetUrl = null;
            if (controller != null) {
                targetUrl = controller.getWebAuthUrl();
            }
            if ((targetUrl == null || targetUrl.isEmpty()) && controller != null) {
                controller.tryRecoverRunningUrl();
                targetUrl = controller.getWebAuthUrl();
            }
            final String finalUrl = targetUrl;
            runOnUiThread(() -> {
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (finalUrl != null && !finalUrl.isEmpty() && sLocalWebView != null) {
                    if (controller != null) {
                        sLoadedGeneration = controller.getWebGeneration();
                    }
                    sLocalWebView.loadUrl(finalUrl);
                } else if (sLocalWebView != null) {
                    sLocalWebView.reload();
                }
            });
        }, "pure-sheet-reload-token").start();
    }

    private WebViewClient createSheetWebViewClient() {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                String url = request.getUrl().toString();
                if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
                    return false;
                }
                ConfigStore cfg = new ConfigStore(getApplicationContext());
                String activeRemote = cfg.getActiveRemoteDshUrl();
                if (activeRemote != null && !activeRemote.isEmpty() && url.startsWith(activeRemote)) {
                    return false;
                }
                if (request.hasGesture()) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                if (errorHint != null) errorHint.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (view == sLocalWebView) {
                    sLocalWebLoaded = true;
                } else if (view == sRemoteWebView) {
                    sRemoteWebLoaded = true;
                }

                // 核心布局保证：锁定输入框贴底定位（消除键盘遮挡），限定横向隐藏（允许右边缘滑动手势拉出文件栏），并隐藏未展开的右侧面板，但绝对不注入任何透明/变色 CSS
                String layoutJs = "(function() {\n" +
                        "  var style = document.getElementById('pure-layout-style');\n" +
                        "  if (!style) {\n" +
                        "    style = document.createElement('style');\n" +
                        "    style.id = 'pure-layout-style';\n" +
                        "    (document.head || document.documentElement).appendChild(style);\n" +
                        "  }\n" +
                        "  style.textContent = \"div[class*='_scrollBody'] { margin-bottom: var(--dsh-composer-height, 148px) !important; overflow-y: auto !important; overflow-x: hidden !important; } \" +\n" +
                        "                      \"div[class*='_composerSeat'] { position: absolute !important; bottom: 0 !important; left: 0 !important; right: 0 !important; z-index: 10 !important; } \" +\n" +
                        "                      \"[data-sidebar-right-panel]:not([data-sidebar-right-open]) { visibility: hidden !important; } \" +\n" +
                        "                      \"[data-sidebar-right-panel][data-sidebar-right-open] { visibility: visible !important; }\";\n" +
                        "})();";
                view.evaluateJavascript(layoutJs, null);

                boolean isRemote = (view == sRemoteWebView) || (sIsRemoteActive);
                view.evaluateJavascript("window.__dsha_is_remote__ = " + (isRemote ? "true" : "false") + ";", null);

                if (sPendingNewChat) {
                    sPendingNewChat = false;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> triggerNewChatJs(), 300);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (errorHint != null) {
                        errorHint.setVisibility(View.VISIBLE);
                        errorHint.setText("页面加载遇到问题，点击右上角 🔄 重试");
                    }
                }
            }
        };
    }

    private class SheetChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (progressBar != null) {
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else if (progressBar.getVisibility() != View.VISIBLE) {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            PureQuickChatSheetActivity.this.filePathCallback = filePathCallback;
            try {
                Intent intent = fileChooserParams.createIntent();
                fileChooserLauncher.launch(intent);
                return true;
            } catch (Throwable t) {
                PureQuickChatSheetActivity.this.filePathCallback = null;
                ToastHelper.makeText(PureQuickChatSheetActivity.this, "无法打开系统文件选择器", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
    }

    // ================================= 历史对话轮次大纲面板 =================================

    private void ensureTurnOutlineDropdownMask() {
        if (turnOutlineDropdownMask == null && webContainer != null) {
            turnOutlineDropdownMask = new FrameLayout(this);
            turnOutlineDropdownMask.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            turnOutlineDropdownMask.setBackgroundColor(Color.TRANSPARENT);
            turnOutlineDropdownMask.setClipChildren(true);
            turnOutlineDropdownMask.setOnClickListener(v -> dismissTurnOutlineDropdown());
            turnOutlineDropdownMask.setVisibility(View.GONE);
            webContainer.addView(turnOutlineDropdownMask);
        }
    }

    private void toggleTurnOutlineDropdown() {
        if (turnOutlineDropdownMask != null && turnOutlineDropdownMask.getVisibility() == View.VISIBLE) {
            dismissTurnOutlineDropdown();
            return;
        }
        showTurnOutlineDropdown();
    }

    private void showTurnOutlineDropdown() {
        if (isFinishing() || isDestroyed() || webContainer == null) return;
        hideSoftKeyboard();

        WebView activeWeb = getActiveWebView();
        if (activeWeb == null) return;

        activeWeb.evaluateJavascript(QuickChatSheetActivity.SCRIPT_GET_TURN_OUTLINE, value -> {
            if (isFinishing() || isDestroyed()) return;
            List<QuickChatSheetActivity.TurnOutlineItem> items = parseTurnOutlineJson(value);
            renderAndShowTurnOutline(items);
        });
    }

    private List<QuickChatSheetActivity.TurnOutlineItem> parseTurnOutlineJson(String value) {
        List<QuickChatSheetActivity.TurnOutlineItem> list = new ArrayList<>();
        if (value == null || "null".equals(value) || value.isEmpty()) {
            return list;
        }
        String json = value;
        if (json.startsWith("\"") && json.endsWith("\"")) {
            try {
                Object unescaped = new org.json.JSONTokener(json).nextValue();
                if (unescaped instanceof String) {
                    json = (String) unescaped;
                }
            } catch (Throwable t) {
                if (json.length() >= 2) {
                    json = json.substring(1, json.length() - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                            .replace("\\n", "\n")
                            .replace("\\r", "\r");
                }
            }
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            if (root.optBoolean("ok", false)) {
                org.json.JSONArray arr = root.optJSONArray("turns");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject obj = arr.optJSONObject(i);
                        if (obj != null) {
                            int turn = obj.optInt("turn", 0);
                            String prompt = obj.optString("prompt", "");
                            String response = obj.optString("response", "");
                            boolean active = obj.optBoolean("active", false);
                            list.add(new QuickChatSheetActivity.TurnOutlineItem(turn, prompt, response, active));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private void renderAndShowTurnOutline(List<QuickChatSheetActivity.TurnOutlineItem> items) {
        if (isFinishing() || isDestroyed() || webContainer == null) return;
        ensureTurnOutlineDropdownMask();
        if (turnOutlineDropdownMask == null) return;

        int solidBgColor = currentTheme.cardBg;
        int textColor = currentTheme.textPrimary;
        int textSecondaryColor = currentTheme.textSecondary;
        int borderColor = currentTheme.border;
        int lineColor = currentTheme.border;
        int activeBadgeColor = Color.parseColor("#4C8DFF");

        if (turnOutlineDropdownCard != null && turnOutlineDropdownCard.getParent() == turnOutlineDropdownMask) {
            turnOutlineDropdownMask.removeView(turnOutlineDropdownCard);
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dpToPx(16)); // 四周统一 16dp 优雅大圆角悬浮卡片
        cardBg.setColor(solidBgColor);
        cardBg.setStroke(dpToPx(1), borderColor);
        card.setBackground(cardBg);
        card.setElevation(dpToPx(12));
        card.setClipToOutline(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            card.setOutlineAmbientShadowColor(isDarkTheme ? Color.parseColor("#80000000") : Color.parseColor("#40000000"));
            card.setOutlineSpotShadowColor(isDarkTheme ? Color.parseColor("#B0000000") : Color.parseColor("#60000000"));
        }

        // 占满抽屉宽度的 95%，水平居中，距离顶栏下边距 8dp 悬浮
        int containerW = webContainer.getWidth() > 0 ? webContainer.getWidth() : screenWidth;
        int cardW = (int) (containerW * 0.95f);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cardLp.topMargin = dpToPx(8);
        card.setLayoutParams(cardLp);

        // 顶栏概要行：左侧「历史对话」，右侧「xx 轮」
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("历史对话");
        tvTitle.setTextColor(textColor);
        tvTitle.setTextSize(13);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        topBar.addView(tvTitle, titleLp);

        TextView tvCount = new TextView(this);
        tvCount.setText((items != null && !items.isEmpty()) ? (items.size() + " 轮") : "");
        tvCount.setTextColor(textSecondaryColor);
        tvCount.setTextSize(11);
        topBar.addView(tvCount);

        card.addView(topBar);

        View div = new View(this);
        div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        div.setBackgroundColor(lineColor);
        card.addView(div);

        View activeRowView = null;
        ScrollView scrollView = null;

        if (items == null || items.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("当前会话暂无历史轮次");
            emptyTv.setTextColor(textSecondaryColor);
            emptyTv.setTextSize(13);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(0, dpToPx(28), 0, dpToPx(28));
            card.addView(emptyTv);
        } else {
            scrollView = new ScrollView(this);
            scrollView.setVerticalScrollBarEnabled(true);
            int maxContainerH = webContainer.getHeight();
            if (maxContainerH <= 0) maxContainerH = dpToPx(380);
            int maxScrollH = Math.max(dpToPx(120), Math.min(dpToPx(340), maxContainerH - dpToPx(60)));
            if (items.size() > 5) {
                scrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxScrollH));
            } else {
                scrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            LinearLayout listLayout = new LinearLayout(this);
            listLayout.setOrientation(LinearLayout.VERTICAL);
            listLayout.setPadding(0, dpToPx(4), 0, dpToPx(8));

            for (int i = 0; i < items.size(); i++) {
                QuickChatSheetActivity.TurnOutlineItem item = items.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dpToPx(16), dpToPx(9), dpToPx(16), dpToPx(9));
                row.setClickable(true);
                row.setFocusable(true);

                android.util.TypedValue out = new android.util.TypedValue();
                if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    row.setBackgroundResource(out.resourceId);
                }

                // 轮次徽章 (#1, #2...)
                TextView badge = new TextView(this);
                badge.setText("#" + item.turn);
                badge.setTextSize(11);
                badge.setGravity(Gravity.CENTER);
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setShape(GradientDrawable.RECTANGLE);
                badgeBg.setCornerRadius(dpToPx(6));
                if (item.active) {
                    badgeBg.setColor(activeBadgeColor);
                    badge.setTextColor(Color.WHITE);
                    badge.setTypeface(Typeface.DEFAULT_BOLD);
                } else {
                    badgeBg.setColor(lineColor);
                    badge.setTextColor(textSecondaryColor);
                }
                badge.setBackground(badgeBg);
                badge.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2));
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                badgeLp.rightMargin = dpToPx(10);
                row.addView(badge, badgeLp);

                // 文字列（Prompt + Response 预览）
                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                textCol.setLayoutParams(colLp);

                TextView promptTv = new TextView(this);
                String pText = item.prompt;
                if (pText == null || pText.trim().isEmpty()) {
                    pText = "轮次 #" + item.turn;
                }
                promptTv.setText(pText);
                promptTv.setTextColor(textColor);
                promptTv.setTextSize(13);
                promptTv.setSingleLine(true);
                promptTv.setEllipsize(TextUtils.TruncateAt.END);
                if (item.active) promptTv.setTypeface(Typeface.DEFAULT_BOLD);
                textCol.addView(promptTv);

                if (item.response != null && !item.response.trim().isEmpty()) {
                    TextView respTv = new TextView(this);
                    respTv.setText(item.response.trim());
                    respTv.setTextColor(textSecondaryColor);
                    respTv.setTextSize(11);
                    respTv.setSingleLine(true);
                    respTv.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams respLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    respLp.topMargin = dpToPx(2);
                    textCol.addView(respTv, respLp);
                }

                row.addView(textCol);

                final int targetTurn = item.turn;
                row.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    dismissTurnOutlineDropdown();
                    jumpToTurn(targetTurn);
                });

                if (item.active) {
                    activeRowView = row;
                }

                listLayout.addView(row);

                if (i < items.size() - 1) {
                    View rowDiv = new View(this);
                    LinearLayout.LayoutParams rowDivLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                    rowDivLp.leftMargin = dpToPx(16);
                    rowDivLp.rightMargin = dpToPx(16);
                    rowDiv.setLayoutParams(rowDivLp);
                    rowDiv.setBackgroundColor(lineColor);
                    listLayout.addView(rowDiv);
                }
            }

            scrollView.addView(listLayout);
            card.addView(scrollView);
        }

        turnOutlineDropdownCard = card;
        turnOutlineDropdownMask.addView(card);
        turnOutlineDropdownMask.bringToFront();
        turnOutlineDropdownMask.setVisibility(View.VISIBLE);

        if (scrollView != null && activeRowView != null) {
            final ScrollView sv = scrollView;
            final View target = activeRowView;
            sv.post(() -> sv.smoothScrollTo(0, Math.max(0, target.getTop() - dpToPx(36))));
        }

        // 1:1 原版自顶向下滑出动画
        card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                card.getViewTreeObserver().removeOnPreDrawListener(this);
                int h = card.getHeight() + dpToPx(16);
                card.setTranslationY(-h);
                card.animate()
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(new DecelerateInterpolator(1.8f))
                        .start();
                return true;
            }
        });
    }

    private void dismissTurnOutlineDropdown() {
        if (turnOutlineDropdownMask == null || turnOutlineDropdownMask.getVisibility() != View.VISIBLE) return;
        if (turnOutlineDropdownCard != null) {
            int h = turnOutlineDropdownCard.getHeight() + dpToPx(16);
            if (h <= dpToPx(16)) h = dpToPx(240);
            turnOutlineDropdownCard.animate()
                    .translationY(-h)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        if (turnOutlineDropdownMask != null) {
                            turnOutlineDropdownMask.setVisibility(View.GONE);
                        }
                    })
                    .start();
        } else {
            turnOutlineDropdownMask.setVisibility(View.GONE);
        }
    }

    private void jumpToTurn(int turn) {
        WebView activeWeb = getActiveWebView();
        if (activeWeb == null) return;
        String js = String.format(java.util.Locale.US,
                "(function() {\n" +
                "    try {\n" +
                "        var el = document.querySelector('[aria-label*=\"轮次\" i], [aria-label*=\"Turn\" i], [class*=\"eGxaPq_frame\"], [class*=\"_marks\"], nav[class*=\"frame\"]');\n" +
                "        if (el) {\n" +
                "            var fk = Object.keys(el).find(function(k) { return k.startsWith('__reactFiber$') || k.startsWith('__reactInternalInstance$'); });\n" +
                "            if (fk) {\n" +
                "                var f = el[fk];\n" +
                "                while (f) {\n" +
                "                    if (f.memoizedProps && Array.isArray(f.memoizedProps.items) && typeof f.memoizedProps.onNavigate === 'function') {\n" +
                "                        var target = f.memoizedProps.items.find(function(it) { return it.turn === %d; });\n" +
                "                        if (target) {\n" +
                "                            f.memoizedProps.onNavigate(target);\n" +
                "                            return true;\n" +
                "                        }\n" +
                "                    }\n" +
                "                    f = f.return;\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    } catch(e) {}\n" +
                "    try {\n" +
                "        var node = document.querySelector('[data-chat-turn=\"%d\"]');\n" +
                "        if (node) {\n" +
                "            node.scrollIntoView({ behavior: 'smooth', block: 'start' });\n" +
                "            return true;\n" +
                "        }\n" +
                "    } catch(e) {}\n" +
                "    return false;\n" +
                "})();", turn, turn);
        activeWeb.evaluateJavascript(js, null);
    }

    // ================================= 交互逻辑与顶栏功能 =================================

    private void updateHeaderTitleText() {
        if (headerTitle == null) return;
        if (sIsRemoteActive) {
            headerTitle.setText("DSHA 远端对话");
        } else {
            headerTitle.setText("DSHA 对话");
        }
    }

    private void toggleDshConnectionTarget() {
        ConfigStore cfg = new ConfigStore(getApplicationContext());
        String activeRemoteUrl = cfg.getActiveRemoteDshUrl();
        boolean hasActiveRemote = cfg.isAnyRemoteDshActive() && activeRemoteUrl != null && !activeRemoteUrl.isEmpty();

        if (!hasActiveRemote && !sIsRemoteActive) {
            ToastHelper.makeText(this, "未开启任何远端连接，请先在主应用配置远端 DSH", Toast.LENGTH_SHORT).show();
            return;
        }

        sIsRemoteActive = !sIsRemoteActive;
        if (sIsRemoteActive) {
            ToastHelper.makeText(this, "已切换至远端 DSH", Toast.LENGTH_SHORT).show();
            ensureRemoteWebViewLoaded(activeRemoteUrl);
            mountWebViewToContainer(sRemoteWebView);
        } else {
            ToastHelper.makeText(this, "已切回本机 DSH", Toast.LENGTH_SHORT).show();
        }
        updateWebViewSlotVisibility();
    }

    private void triggerNewChat() {
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
        triggerNewChatJs();
    }

    private void triggerNewChatJs() {
        WebView activeWeb = getActiveWebView();
        if (activeWeb == null) return;
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
    }

    private void toggleWorkspaceFileTree() {
        WebView activeWeb = getActiveWebView();
        if (activeWeb == null) return;
        String js = "(function() {" +
                "  var btn = document.querySelector('[data-mobile-nav=\"files\"], [aria-label*=\"文件\"], [title*=\"工作区文件\"]');" +
                "  if (btn) { btn.click(); return 'CLICKED'; }" +
                "  return 'NOT_FOUND';" +
                "})();";
        activeWeb.evaluateJavascript(js, null);
    }

    private void expandToFullscreen() {
        WebView activeWeb = getActiveWebView();
        String currentUrl = activeWeb != null ? activeWeb.getUrl() : null;
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
        }, "pure-sheet-expand-web").start();
    }

    // ================================= 审批横幅与文件查看器 =================================

    private View buildApprovalBannerView() {
        LinearLayout banner = new LinearLayout(this);
        banner.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
        banner.setBackgroundColor(isDarkTheme ? Color.parseColor("#2D1D1D") : Color.parseColor("#FFF4E5"));
        banner.setVisibility(View.GONE);

        approvalTitleText = new TextView(this);
        approvalTitleText.setTextColor(isDarkTheme ? Color.parseColor("#FFA39E") : Color.parseColor("#D4380D"));
        approvalTitleText.setTextSize(14);
        approvalTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        banner.addView(approvalTitleText);

        approvalDetailText = new TextView(this);
        approvalDetailText.setTextColor(currentTheme.textPrimary);
        approvalDetailText.setTextSize(12);
        approvalDetailText.setPadding(0, dpToPx(4), 0, dpToPx(8));
        banner.addView(approvalDetailText);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        approvalBtnDeny = new TextView(this);
        ((TextView) approvalBtnDeny).setText("拒绝");
        ((TextView) approvalBtnDeny).setTextColor(Color.parseColor("#FF4D4F"));
        approvalBtnDeny.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        approvalBtnDeny.setOnClickListener(v -> {
            ConfirmReceiver.triggerVibrate(this, 50);
            ConfirmReceiver.writeApprovalDecision("rejected");
            HttpShellService svc = HttpShellService.instance();
            if (svc != null) {
                svc.resolveConfirm(false, currentApprovalEpoch);
            }
            syncApprovalDecision(false);
            hideApprovalBanner();
        });
        btnRow.addView(approvalBtnDeny);

        approvalBtnAllow = new TextView(this);
        ((TextView) approvalBtnAllow).setText("允许执行");
        ((TextView) approvalBtnAllow).setTextColor(Color.parseColor("#52C41A"));
        ((TextView) approvalBtnAllow).setTypeface(Typeface.DEFAULT_BOLD);
        approvalBtnAllow.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        approvalBtnAllow.setOnClickListener(v -> {
            ConfirmReceiver.triggerVibrate(this, 50);
            ConfirmReceiver.writeApprovalDecision("allowed-once");
            HttpShellService svc = HttpShellService.instance();
            if (svc != null) {
                svc.resolveConfirm(true, currentApprovalEpoch);
            }
            syncApprovalDecision(true);
            hideApprovalBanner();
        });
        btnRow.addView(approvalBtnAllow);

        banner.addView(btnRow);
        return banner;
    }

    public void displayApprovalBanner(HttpShellService.AuthPromptInfo info, long epoch) {
        if (approvalBannerView == null || info == null) return;
        currentApprovalEpoch = epoch;
        approvalTitleText.setText(info.title != null ? info.title : "⚠️ 安全操作审批");
        approvalDetailText.setText(info.detail != null ? info.detail : "模型正在请求执行敏感指令，请确认是否允许。");
        if (approvalBtnAllow instanceof TextView && info.primaryBtn != null && !info.primaryBtn.isEmpty()) {
            ((TextView) approvalBtnAllow).setText(info.primaryBtn);
        }
        if (approvalBtnDeny instanceof TextView && info.secondaryBtn != null && !info.secondaryBtn.isEmpty()) {
            ((TextView) approvalBtnDeny).setText(info.secondaryBtn);
        }
        approvalBannerView.setVisibility(View.VISIBLE);
    }

    public void hideApprovalBanner() {
        if (approvalBannerView != null) {
            approvalBannerView.setVisibility(View.GONE);
        }
        currentApprovalEpoch = -1;
    }

    private void closeFileViewer() {
        if (fileViewerContainer != null) {
            fileViewerContainer.removeAllViews();
            fileViewerContainer.setVisibility(View.GONE);
        }
        if (btnFileSave != null) btnFileSave.setVisibility(View.GONE);
        if (btnFileOpenExternal != null) btnFileOpenExternal.setVisibility(View.GONE);
        currentViewingFile = null;
        currentActiveEditorView = null;
    }

    private void saveCurrentEditorText() {
        if (currentViewingFile == null || !(currentActiveEditorView instanceof android.widget.EditText)) return;
        android.widget.EditText editor = (android.widget.EditText) currentActiveEditorView;
        String content = editor.getText().toString();
        try (FileOutputStream fos = new FileOutputStream(currentViewingFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            ToastHelper.makeText(this, "文件保存成功", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            ToastHelper.makeText(this, "保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ================================= 手势、拖拽与键盘避让 =================================

    private float initialTouchY;
    private int initialHeightOnTouch;
    private ValueAnimator heightAnimator;

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

    /** 键盘精准监听：复用原版逻辑，按 IME bottom 动态收缩与垫板支撑，输入框精准上浮 */
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

    private void setupBackDispatcher() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                dispatchBackAction();
            }
        });
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

    private void dispatchBackAction() {
        if (currentActiveDialogMask != null) {
            dismissActiveDialog();
            return;
        }
        if (turnOutlineDropdownMask != null && turnOutlineDropdownMask.getVisibility() == View.VISIBLE) {
            dismissTurnOutlineDropdown();
            return;
        }
        if (fileViewerContainer != null && fileViewerContainer.getVisibility() == View.VISIBLE) {
            closeFileViewer();
            return;
        }

        WebView webView = getActiveWebView();
        if (webView != null) {
            webView.evaluateJavascript(QuickChatSheetActivity.SCRIPT_TRIGGER_MOBILE_BACK, value -> {
                android.util.Log.i("DSHA-Back", "Pure triggerMobileBack result: " + value);
                boolean isHandledByPlugin = false;
                if (value != null) {
                    String clean = value.replace("\"", "").trim();
                    if ("true".equalsIgnoreCase(clean)) {
                        isHandledByPlugin = true;
                    }
                }
                if (isHandledByPlugin) {
                    return;
                }
                dismissSheet();
            });
            return;
        }
        dismissSheet();
    }

    private void dismissActiveDialog() {
        if (currentActiveDialogMask != null) {
            if (rootOverlay != null) rootOverlay.removeView(currentActiveDialogMask);
            currentActiveDialogMask = null;
        }
    }

    private void triggerForegroundWakeup() {
        if (controller != null && controller.isFrozen()) {
            controller.resumeIfFrozen("快捷抽屉拉出");
        }
        WebView activeWeb = getActiveWebView();
        if (activeWeb == null) return;
        activeWeb.onResume();
        activeWeb.resumeTimers();

        boolean isRemote = (activeWeb == sRemoteWebView) || sIsRemoteActive;
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
                + "    if (" + isRemote + ") {\n"
                + "      window.dispatchEvent(new Event('online'));\n"
                + "    } else if (!" + com.deepseekharness.app.HttpShellService.isInteractiveWaiting() + ") {\n"
                + "      window.dispatchEvent(new Event('offline'));\n"
                + "      window.dispatchEvent(new Event('online'));\n"
                + "    }\n"
                + "  } catch(e) {}\n"
                + "})();";
        activeWeb.evaluateJavascript(js, null);
    }

    private void animateIn() {
        isDismissing = false;
        triggerForegroundWakeup();
        if (sheetCard != null) {
            ConfigStore cfg = new ConfigStore(this);
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

    public void dismissSheet() {
        if (isDismissing) return;
        isDismissing = true;
        dismissActiveDialog();
        dismissTurnOutlineDropdown();

        hideSoftKeyboard();

        if (sheetCard != null) {
            int exitY = screenHeight > 0 ? screenHeight : (sheetCard.getHeight() + dpToPx(100));
            sheetCard.animate()
                    .translationY(exitY)
                    .setDuration(300)
                    .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            WebView activeWeb = getActiveWebView();
                            if (activeWeb != null) {
                                activeWeb.onPause();
                            }
                            moveTaskToBack(true);
                            overridePendingTransition(0, 0);
                            isDismissing = false;
                        }
                    })
                    .start();
        } else {
            WebView activeWeb = getActiveWebView();
            if (activeWeb != null) {
                activeWeb.onPause();
            }
            moveTaskToBack(true);
            overridePendingTransition(0, 0);
            isDismissing = false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        overridePendingTransition(0, 0);
        enforceExcludeFromRecents();
        super.onNewIntent(intent);
        setIntent(intent);
        sCurrentInstance = this;

        if (com.deepseekharness.app.HttpShellService.isApprovalWaiting && com.deepseekharness.app.HttpShellService.sCurrentApprovalInfo != null) {
            displayApprovalBanner(com.deepseekharness.app.HttpShellService.sCurrentApprovalInfo, com.deepseekharness.app.HttpShellService.sCurrentApprovalEpoch);
        } else {
            hideApprovalBanner();
        }

        WebView activeWeb = getActiveWebView();
        if (activeWeb != null) {
            triggerForegroundWakeup();
            if (sPendingApprovalDecision != null) {
                boolean allow = "ALLOWED".equals(sPendingApprovalDecision);
                sPendingApprovalDecision = null;
                executeApprovalDecisionScript(activeWeb, allow);
            }
            if (activeWeb.getParent() != webContainer) {
                if (activeWeb.getParent() instanceof ViewGroup) {
                    ((ViewGroup) activeWeb.getParent()).removeView(activeWeb);
                }
                webContainer.addView(activeWeb, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            if (fileViewerContainer == null || fileViewerContainer.getVisibility() != View.VISIBLE) {
                activeWeb.setVisibility(View.VISIBLE);
            }
        }
        animateIn();
    }

    private void hideSoftKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sCurrentInstance == this) {
            sCurrentInstance = null;
        }
    }

    // ================================= 原生桥接接口 =================================

    private class NativeBridgeInterface {
        @android.webkit.JavascriptInterface
        public void openFile(String path) {
            if (path == null) return;
            runOnUiThread(() -> com.deepseekharness.app.viewer.FileOpenHelper.openWithSystem(
                    PureQuickChatSheetActivity.this, new File(path)));
        }
    }
}
