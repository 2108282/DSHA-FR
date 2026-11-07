package com.deepseekharness.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.deepseekharness.app.core.ConfigStore
import com.deepseekharness.app.ui.MonetEngine
import com.deepseekharness.app.ui.MonetThemeHelper
import com.deepseekharness.app.ui.ThemeController
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 危险命令 4 项决断选项
 */
enum class ShellDecision {
    ALLOW_ONCE,
    ALLOW_TASK,
    ALLOW_10MIN,
    DENY
}

/**
 * 决策回调监听器（SAM 接口，方便 Java 与 Kotlin 互调）
 */
fun interface OnDecisionListener {
    fun onDecision(decision: ShellDecision, epoch: Long)
}

/**
 * 危险命令统一悬浮卡片：
 * - 样式与色彩 100% 对齐快捷对话（QuickChat 抽屉）调色板与审批横幅；
 * - 严格按照快捷对话的三大色彩模式：
 *   1. 深色模式（反色开启）：坚决不使用莫奈杂色，维持科技蓝灰黑高对比纯正深色底（#10141B）；
 *   2. 正常浅色模式（莫奈关闭）：经典冷瓷白（#F5F8FC）配深黑墨字与琥珀焦糖告警标题；
 *   3. 浅色莫奈模式（莫奈开启）：调用 MonetThemeHelper 提取壁纸主色与容器底色，主按钮与背景平滑动态演色；
 * - 优先使用无障碍顶层窗口（TYPE_ACCESSIBILITY_OVERLAY）；
 * - 若无障碍未开启，自动使用 Root 特权（TYPE_APPLICATION_OVERLAY）两段式兜底挂载；
 * - 挂载失败时返回 false，供调用方优雅降级到系统通知与灵动岛通道。
 */
object DangerConfirmOverlay {
    private const val TAG = "DangerConfirmOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeRoot: View? = null
    @Volatile
    private var activeWindowManager: WindowManager? = null
    @Volatile
    private var countdownRunnable: Runnable? = null
    @Volatile
    private var currentEpoch: Long = -1L
    private val isDismissing = AtomicBoolean(false)

    fun isShowing(): Boolean = activeRoot != null

    /**
     * 弹出确认卡片
     * @return true 表示成功挂载到屏幕上；false 表示挂载失败，需退回通知形式
     */
    @JvmStatic
    fun show(
        context: Context,
        cmd: String,
        epoch: Long,
        timeoutSeconds: Long = 60L,
        onDecision: OnDecisionListener
    ): Boolean {
        dismiss()
        val appContext = context.applicationContext ?: context
        val latch = java.util.concurrent.CountDownLatch(1)
        val success = AtomicBoolean(false)

        val task = Runnable {
            try {
                success.set(showInternal(appContext, cmd, epoch, timeoutSeconds, onDecision))
            } catch (t: Throwable) {
                Log.w(TAG, "showInternal uncaught error", t)
                success.set(false)
            } finally {
                latch.countDown()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            mainHandler.post(task)
            try {
                latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {}
        }

        return success.get()
    }

    private fun showInternal(
        context: Context,
        cmd: String,
        epoch: Long,
        timeoutSeconds: Long,
        onDecision: OnDecisionListener
    ): Boolean {
        // 1. 获取 WindowManager 与 WindowType（优先无障碍，Root 形式兜底）
        var wm: WindowManager? = null
        var windowType: Int = 0

        val a11y = DshaAccessibilityService.getInstance()
        if (a11y != null && DshaAccessibilityService.isConnected()) {
            wm = a11y.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        }

        if (wm == null) {
            // Root 形式兜底：利用已有的 Root 权限静默确保 SYSTEM_ALERT_WINDOW 允许
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                ensureRootAlertWindow(context)
            }
            wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        }

        if (wm == null) {
            Log.w(TAG, "WindowManager unavailable")
            return false
        }

        // 2. 准备主题与调色板（严格跟随快捷对话 QuickChat 抽屉的配置与 MonetThemeHelper 引擎）
        val cfg = ConfigStore(context)
        val isDarkMode = cfg.isSheetInvertColor || ThemeController.isDark(context)
        val isMonet = cfg.isSheetMonetColor && !isDarkMode
        val opacity = if (isDarkMode) cfg.sheetOpacityNight else cfg.sheetOpacityDay
        val palette = MonetThemeHelper.resolve(context, isDarkMode, isMonet, opacity)

        val cardBgColor = palette.cardBgColor
        val borderColor = palette.borderColor
        val textColor = palette.textColor
        val textSecondaryColor = palette.textSecondaryColor
        val lineColor = palette.lineColor

        // 快捷对话审批横幅 1:1 对应的颜色定义
        val titleColor = if (isDarkMode) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")
        val cmdBoxBg = if (isDarkMode) Color.parseColor("#181D26") else Color.parseColor("#F0F4F9")

        // 统一全套半透明毛玻璃胶囊质感（绿、中性、红三色对齐）
        val allowBtnBg = if (isDarkMode) Color.parseColor("#2910B981") else Color.parseColor("#1410B981")
        val allowBtnStroke = Color.parseColor("#4D10B981")
        val allowBtnText = if (isDarkMode) Color.parseColor("#34D399") else Color.parseColor("#059669")

        val tonalBtnBg = if (isDarkMode) Color.parseColor("#1FFFFFFF") else Color.parseColor("#0F000000")
        val tonalBtnStroke = if (isDarkMode) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")
        val tonalBtnText = textColor

        val denyBtnBg = if (isDarkMode) Color.parseColor("#29EF4444") else Color.parseColor("#14EF4444")
        val denyBtnStroke = Color.parseColor("#4DEF4444")
        val denyBtnText = Color.parseColor("#EF4444")

        val rippleColor = if (isDarkMode) 0x22FFFFFF else 0x1F000000

        // 3. 构造窗口参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "DSHA 安全审批"
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
            }
        }

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        params.width = minOf(screenW - dp(context, 16), dp(context, 480))
        params.y = dp(context, 16)

        // 4. 构造 UI 层级（与快捷对话抽屉卡片 1:1 质感）
        val root = FrameLayout(context).apply {
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            clipToPadding = false
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardBgColor)
                cornerRadius = dp(context, 20).toFloat()
                setStroke(dp(context, 1), borderColor)
            }
            elevation = dp(context, 8).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = if (isDarkMode) Color.parseColor("#4D000000") else Color.parseColor("#203B6590")
                outlineSpotShadowColor = if (isDarkMode) Color.parseColor("#80000000") else Color.parseColor("#383B6590")
            }
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
            filterTouchesWhenObscured = true
        }

        // --- 标题栏（琥珀金告警色与倒计时） ---
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = "⚠️ 危险命令确认"
            textSize = 15f
            setTextColor(titleColor)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val countdownView = TextView(context).apply {
            text = "${timeoutSeconds}s"
            textSize = 12.5f
            setTextColor(textSecondaryColor)
            typeface = Typeface.MONOSPACE
        }
        header.addView(countdownView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(header)

        // --- 副提示文案 ---
        val hintView = TextView(context).apply {
            text = "智能体请求执行破坏性命令，请选择授权粒度："
            textSize = 12f
            setTextColor(textSecondaryColor)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(context, 6), 0, dp(context, 8))
        }
        card.addView(hintView)

        // --- 命令展示框（深色玻璃槽内嵌、等宽字体） ---
        val cmdBox = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(cmdBoxBg)
                cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), lineColor)
            }
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        }

        val cmdScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
        }

        val cmdText = TextView(context).apply {
            text = cmd
            textSize = 13.5f
            setTextColor(textColor)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
        }
        cmdScroll.addView(cmdText)

        val maxBoxHeight = dp(context, 110)
        cmdBox.addView(cmdScroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        })
        card.addView(cmdBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 12)
        })

        // 统一决策收束
        fun decide(decision: ShellDecision) {
            if (isDismissing.getAndSet(true)) return
            dismiss()
            onDecision.onDecision(decision, epoch)
        }

        // --- 按钮布局：垂直排列（允许一次 / 10分钟临时授权 / 拒绝请求） ---
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 统一全套半透明毛玻璃胶囊质感（背景半透明 + 统一描边）
        val btnStroke = Color.parseColor("#33FFFFFF") // 统一半透明描边

        val btnAllowOnce = createOptionButton(
            context = context,
            label = "允许一次",
            bgColor = Color.parseColor("#1A10B981"), // 半透明绿
            textColor = if (isDarkMode) Color.parseColor("#34D399") else Color.parseColor("#059669"),
            rippleColor = Color.argb(0x30, 0xFF, 0xFF, 0xFF),
            strokeColor = btnStroke,
            onClick = { decide(ShellDecision.ALLOW_ONCE) }
        )
        val btn10Min = createOptionButton(
            context = context,
            label = "10分钟临时授权",
            bgColor = Color.parseColor("#1A000000"), // 半透明黑
            textColor = textColor,
            rippleColor = rippleColor,
            strokeColor = btnStroke,
            onClick = { decide(ShellDecision.ALLOW_10MIN) }
        )
        val btnDeny = createOptionButton(
            context = context,
            label = "拒绝请求",
            bgColor = Color.parseColor("#1AEF4444"), // 半透明红
            textColor = Color.parseColor("#EF4444"),
            rippleColor = rippleColor,
            strokeColor = btnStroke,
            onClick = { decide(ShellDecision.DENY) }
        )

        val paramsBtn = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 48)).apply {
            bottomMargin = dp(context, 8)
        }
        val paramsLastBtn = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 48))

        container.addView(btnAllowOnce, paramsBtn)
        container.addView(btn10Min, paramsBtn)
        container.addView(btnDeny, paramsLastBtn)
        
        card.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // 5. 挂载窗口（优先无障碍通道，若失败或不可用则自动使用 Root 兜底挂载）
        var attached = false
        var targetWm = wm
        var targetType = windowType

        if (targetWm != null) {
            params.type = targetType
            try {
                targetWm.addView(root, params)
                attached = true
            } catch (e: Throwable) {
                Log.w(TAG, "首次挂载失败 (type=$targetType)，尝试 Root 兜底通道", e)
            }
        }

        // 若首次挂载未成功，立刻切换到 Root 通道兜底
        if (!attached) {
            try {
                ensureRootAlertWindow(context)
                val rootWm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (rootWm != null) {
                    val rootType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    params.type = rootType
                    rootWm.addView(root, params)
                    targetWm = rootWm
                    targetType = rootType
                    attached = true
                }
            } catch (e2: Throwable) {
                Log.w(TAG, "Root 兜底挂载也失败", e2)
            }
        }

        if (!attached || targetWm == null) {
            return false
        }

        return try {
            activeRoot = root
            activeWindowManager = targetWm
            currentEpoch = epoch
            isDismissing.set(false)

            // 入场微动画
            root.alpha = 0f
            root.translationY = dp(context, 24).toFloat()
            root.animate().alpha(1f).translationY(0f).setDuration(220).start()

            // 倒计时任务
            val deadline = SystemClock.uptimeMillis() + (timeoutSeconds * 1000L)
            val ticker = object : Runnable {
                override fun run() {
                    val remaining = ((deadline - SystemClock.uptimeMillis() + 999) / 1000).toInt()
                    if (remaining <= 0) {
                        decide(ShellDecision.DENY)
                    } else {
                        countdownView.text = "${remaining}s"
                        mainHandler.postDelayed(this, 1000)
                    }
                }
            }
            countdownRunnable = ticker
            mainHandler.postDelayed(ticker, 1000)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "初始化悬浮状态异常", e)
            false
        }
    }

    /**
     * 关闭弹窗
     */
    @JvmStatic
    fun dismiss() {
        val root = activeRoot
        val wm = activeWindowManager
        val ticker = countdownRunnable

        activeRoot = null
        activeWindowManager = null
        countdownRunnable = null
        currentEpoch = -1L

        if (ticker != null) {
            mainHandler.removeCallbacks(ticker)
        }

        if (root != null && wm != null) {
            val task = Runnable {
                try {
                    if (root.isAttachedToWindow) {
                        wm.removeViewImmediate(root)
                    }
                } catch (_: Throwable) {}
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                task.run()
            } else {
                mainHandler.post(task)
            }
        }
    }

    private fun ensureRootAlertWindow(context: Context) {
        try {
            val pkg = context.packageName
            val cmd = "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null; " +
                    "appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null"
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        } catch (_: Throwable) {}
    }

    private fun createOptionButton(
        context: Context,
        label: String,
        bgColor: Int,
        textColor: Int,
        rippleColor: Int,
        strokeColor: Int? = null,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(context, 12), 0, dp(context, 12), 0)

            val shape = GradientDrawable().apply {
                cornerRadius = dp(context, 14).toFloat()
                setColor(bgColor)
                if (strokeColor != null) {
                    setStroke(dp(context, 1), strokeColor)
                }
            }
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null)
            setOnClickListener { onClick() }
        }
    }

    private fun dp(context: Context, v: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).roundToInt()
    }
}
