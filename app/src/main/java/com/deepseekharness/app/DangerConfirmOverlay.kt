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
import com.deepseekharness.app.phone.a11y.OverlayPalette
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
 * - 样式 1:1 复刻手机操控底部确认卡片（Skia 圆角卡片、莫奈动态取色、平滑动画与防自点保护）；
 * - 优先使用无障碍顶层窗口（TYPE_ACCESSIBILITY_OVERLAY）；
 * - 若无障碍未开启，自动使用 Root 特权（TYPE_APPLICATION_OVERLAY）兜底挂载，无需手动翻找系统悬浮窗权限；
 * - 失败时返回 false，供调用方优雅降级到系统通知与灵动岛通道。
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

        // 2. 准备主题与调色板（完整跟随 DSHA 内部主题选择、系统深浅色以及 MonetEngine 莫奈动态取色）
        val isDark = runCatching { com.deepseekharness.app.ui.ThemeController.isDark(context) }
            .getOrDefault((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        val c = OverlayPalette.resolve(context, isDark)

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

        // 4. 构造 UI 层级（与手机操控确认弹窗 1:1 风格）
        val root = FrameLayout(context).apply {
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            clipToPadding = false
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c.surfaceTop, c.surfaceBottom)).apply {
                cornerRadius = dp(context, 20).toFloat()
                setStroke(dp(context, 1), c.hairline)
            }
            elevation = dp(context, 8).toFloat()
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
            filterTouchesWhenObscured = true
        }

        // --- 标题栏（带琥珀金告警色与倒计时） ---
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleColor = if (isDark) 0xFFF59E0B.toInt() else 0xFFD97706.toInt()
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
            setTextColor(c.muted)
            typeface = Typeface.MONOSPACE
        }
        header.addView(countdownView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(header)

        // --- 副提示文案 ---
        val hintView = TextView(context).apply {
            text = "智能体请求执行破坏性命令，请选择授权粒度："
            textSize = 12f
            setTextColor(c.faint)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(context, 6), 0, dp(context, 8))
        }
        card.addView(hintView)

        // --- 命令展示框（深色玻璃槽内嵌、等宽字体） ---
        val cmdBox = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(c.field)
                cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), c.hairline)
            }
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        }

        val cmdScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
        }

        val cmdText = TextView(context).apply {
            text = cmd
            textSize = 13.5f
            setTextColor(c.ink)
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

        // --- 按钮第 1 行：两项次级选项（本任务允许 / 10分钟临时授权） ---
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnTask = createOptionButton(
            context = context,
            label = "本任务允许",
            bgColor = c.tonal,
            textColor = c.ink,
            rippleColor = c.ripple,
            onClick = { decide(ShellDecision.ALLOW_TASK) }
        )
        val btn10Min = createOptionButton(
            context = context,
            label = "10分钟临时授权",
            bgColor = c.tonal,
            textColor = c.ink,
            rippleColor = c.ripple,
            onClick = { decide(ShellDecision.ALLOW_10MIN) }
        )

        row1.addView(btnTask, LinearLayout.LayoutParams(0, dp(context, 42), 1f).apply { marginEnd = dp(context, 8) })
        row1.addView(btn10Min, LinearLayout.LayoutParams(0, dp(context, 42), 1f))
        card.addView(row1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 8)
        })

        // --- 按钮第 2 行：主操作行（拒绝请求 / 允许一次） ---
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnDeny = createOptionButton(
            context = context,
            label = "拒绝请求",
            bgColor = c.dangerSoft,
            textColor = c.danger,
            rippleColor = c.ripple,
            strokeColor = c.danger,
            onClick = { decide(ShellDecision.DENY) }
        )
        val btnAllowOnce = createOptionButton(
            context = context,
            label = "允许一次",
            bgColor = c.accent,
            textColor = c.onAccent,
            rippleColor = c.ripple,
            onClick = { decide(ShellDecision.ALLOW_ONCE) }
        )

        row2.addView(btnDeny, LinearLayout.LayoutParams(0, dp(context, 44), 1f).apply { marginEnd = dp(context, 8) })
        row2.addView(btnAllowOnce, LinearLayout.LayoutParams(0, dp(context, 44), 1.25f))
        card.addView(row2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

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
