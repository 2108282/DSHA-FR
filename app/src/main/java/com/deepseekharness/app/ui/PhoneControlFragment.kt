package com.deepseekharness.app.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.deepseekharness.app.DshaAccessibilityService
import com.deepseekharness.app.R
import com.deepseekharness.app.phone.ConfirmMode
import com.deepseekharness.app.phone.PhoneControl
import com.deepseekharness.app.phone.PhonePolicies
import com.deepseekharness.app.phone.SafetyRules
import com.deepseekharness.app.phone.a11y.OverlayPrefs
import com.deepseekharness.app.util.ToastHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 手机操控完整配置二级页：
 * 1. 页面主体 100% 沿用 DSHA Skia 硬件加速卡片 (ModernCardView) + 纯自绘胶囊开关 (DshaToggle) +
 *    38dp 图标底座 (bg_settings_icon_box) + 66dp 缩进分割线 + MonetEngine 莫奈全景动态取色；
 * 2. 所有二级弹窗 1:1 复刻插件市场「安装插件弹窗 (dialog_plugin_install_preview.xml)」视觉契约：
 *    外层 20dp FrameLayout + 20dp 圆角 ModernCardView + 40dp m3_btn_tonal_expressive 图标盒与 18sp 标题 +
 *    ModernBadgeView 纯 Skia 徽章 + bg_input 玻璃内嵌槽 + 底部 44dp 双按钮 (btnDialogCancel / btnDialogConfirm)。
 */
class PhoneControlFragment : Fragment() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isBinding = false

    private var masterToggle: DshaToggle? = null
    private var bridgeStatusText: TextView? = null
    private var activeTaskCard: ModernCardView? = null
    private var activeTaskText: TextView? = null

    private var a11yStatusText: TextView? = null
    private var confirmModeValueText: TextView? = null
    private var bgToggle: DshaToggle? = null
    private var bgStatusText: TextView? = null
    private var appRulesSubText: TextView? = null

    private var unlockSystemToggle: DshaToggle? = null
    private var unlockSecretToggle: DshaToggle? = null
    private var unlockOwnToggle: DshaToggle? = null

    private var opacityValueText: TextView? = null
    private var autoFoldToggle: DshaToggle? = null
    private var returnAppToggle: DshaToggle? = null
    private var logSubText: TextView? = null

    private val statusSink: (Map<String, Any?>) -> Unit = {
        if (isAdded) mainHandler.post { refreshState() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = NestedScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            isClickable = true
            isFocusable = true
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(16), dp(14), dp(16), dp(96))
        }
        scroll.addView(root)

        // 0. 运行中任务急停卡片（仅在任务活跃时展示）
        val taskCard = ModernCardView(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val taskTitle = TextView(ctx).apply {
            text = "正在操作手机"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
        }
        val taskDesc = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            setPadding(0, dp(4), 0, dp(10))
        }
        val stopBtn = Button(ctx).apply {
            id = R.id.launch_stop
            text = "立即停止手机操作"
            isAllCaps = false
            elevation = 0f
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, R.animator.btn_press_scale)
            setBackgroundResource(R.drawable.bg_btn_danger_small)
            setTextColor(ContextCompat.getColor(ctx, R.color.err))
            setOnClickListener {
                PhoneControl.get(ctx).stopTask("用户在设置页点击了停止")
                ToastHelper.show(ctx, "已停止当前手机操控任务")
                refreshState()
            }
        }
        taskCard.addView(taskTitle)
        taskCard.addView(taskDesc)
        taskCard.addView(stopBtn)
        activeTaskCard = taskCard
        activeTaskText = taskDesc
        root.addView(taskCard, lp(bottomMargin = dp(12)))

        // ==================== 1. 总开关与 MCP 桥状态 ====================
        root.addView(sectionHeader("总开关与状态"))
        val masterCard = card()
        val (masterRow, mToggle, mSub) = toggleRow(
            iconRes = R.drawable.ic_rocket,
            title = "启用手机操控 (mcp__phone__*)",
            subtitle = "让 DSH 看屏幕、点按、输入和发系统意图，随时可暂停或停止"
        ) { checked ->
            if (isBinding) return@toggleRow
            PhoneControl.get(ctx).setEnabled(checked)
            ToastHelper.show(
                ctx,
                if (checked) "手机操控已开启（重启核心后挂载 MCP 工具）" else "手机操控已关闭"
            )
            refreshState()
        }
        masterToggle = mToggle
        bridgeStatusText = mSub
        masterCard.addView(masterRow)
        root.addView(masterCard)

        // ==================== 2. 准备与读屏自检 ====================
        root.addView(sectionHeader("准备与屏幕自检"))
        val prepCard = card()
        val (a11yRow, a11ySub) = actionRow(
            iconRes = R.drawable.ic_check_circle,
            title = "无障碍服务（Root 自动守护）",
            subtitle = "正在检查状态…",
            actionLabel = "一键激活"
        ) {
            Thread {
                val ok = DshaAccessibilityService.ensureConnected(ctx)
                mainHandler.post {
                    if (!isAdded) return@post
                    if (ok) {
                        ToastHelper.show(ctx, "无障碍服务已通过 Root 自动激活并开启自愈守护")
                    } else {
                        ToastHelper.show(ctx, "正在打开系统无障碍设置…")
                        runCatching { startActivity(PhoneControl.get(ctx).accessibilitySettingsIntent()) }
                    }
                    refreshState()
                }
            }.start()
        }
        a11yStatusText = a11ySub
        prepCard.addView(a11yRow)
        prepCard.addView(divider())

        val (testRow, _) = actionRow(
            iconRes = R.drawable.ic_chat,
            title = "读一下当前屏幕",
            subtitle = "本地查看 DSH 能读到的蒸馏文本树与脱敏效果（不会发给模型）",
            actionLabel = "读取"
        ) {
            runScreenSelfTest()
        }
        prepCard.addView(testRow)
        root.addView(prepCard)

        // ==================== 3. 确认方式与不打扰模式（后台虚拟屏） ====================
        root.addView(sectionHeader("确认方式与后台虚拟屏"))
        val modeCard = card()
        val (confirmRow, confirmSub) = valueRow(
            iconRes = R.drawable.ic_settings_shield,
            title = "安全确认方式",
            subtitle = "进新应用、发送、删除、付款前的询问策略",
            initialValue = "每次都询问 ›"
        ) {
            showConfirmModeDialog()
        }
        confirmModeValueText = confirmSub
        modeCard.addView(confirmRow)
        modeCard.addView(divider())

        val (bgRow, bToggle, bSub) = toggleRow(
            iconRes = R.drawable.ic_box,
            title = "不打扰模式（后台虚拟屏）",
            subtitle = "在看不见的独立焦点虚拟屏上操作 App，不耽误你用手机（需 Android 14+ 与 Root）"
        ) { checked ->
            if (isBinding) return@toggleRow
            toggleBackgroundDisplay(checked)
        }
        bgToggle = bToggle
        bgStatusText = bSub
        modeCard.addView(bgRow)
        root.addView(modeCard)

        // ==================== 4. 应用名单 ====================
        root.addView(sectionHeader("应用规则"))
        val appCard = card()
        val (appRow, appSub) = actionRow(
            iconRes = R.drawable.ic_plugins,
            title = "应用名单管理",
            subtitle = "支付/银行/券商默认「不碰」，微信默认「步步确认」",
            actionLabel = "配置"
        ) {
            showAppRulesDialog()
        }
        appRulesSubText = appSub
        appCard.addView(appRow)
        root.addView(appCard)

        // ==================== 5. 解除限制（高危三项，长按 1.5s 解锁） ====================
        root.addView(sectionHeader("解除限制（高危 · 默认全关）"))
        val unlockCard = card()

        val (sysRow, sysToggle, _) = toggleRow(
            iconRes = R.drawable.ic_settings_sliders,
            title = "允许操作系统设置与权限页",
            subtitle = "含系统设置、权限弹窗、APK 安装器、安全中心、通知栏"
        ) { checked ->
            if (isBinding) return@toggleRow
            handleUnlockToggle("systemPages", "系统设置与权限页", checked)
        }
        unlockSystemToggle = sysToggle
        unlockCard.addView(sysRow)
        unlockCard.addView(divider())

        val (secRow, secToggle, _) = toggleRow(
            iconRes = R.drawable.ic_key,
            title = "允许填写密码和验证码",
            subtitle = "允许代填密码、验证码和支付密码（记录中仍显示 ••••）"
        ) { checked ->
            if (isBinding) return@toggleRow
            handleUnlockToggle("secretFields", "密码和验证码", checked)
        }
        unlockSecretToggle = secToggle
        unlockCard.addView(secRow)
        unlockCard.addView(divider())

        val (ownRow, ownToggle, _) = toggleRow(
            iconRes = R.drawable.ic_about,
            title = "允许操作 DSHA 自身界面",
            subtitle = "允许 AI 在 DSHA 应用内点按和输入"
        ) { checked ->
            if (isBinding) return@toggleRow
            handleUnlockToggle("ownApp", "DSHA 自身界面", checked)
        }
        unlockOwnToggle = ownToggle
        unlockCard.addView(ownRow)
        root.addView(unlockCard)

        // ==================== 6. 悬浮条与操作记录 ====================
        root.addView(sectionHeader("悬浮条（胶囊 · 卡片 · 小球）与审计记录"))
        val overlayCard = card()

        val (opRow, opVal) = valueRow(
            iconRes = R.drawable.ic_moon,
            title = "小球闲置时不透明度",
            subtitle = "小球 3 秒不碰时变淡到的程度（30% ~ 100%，默认 70%）",
            initialValue = "70% ›"
        ) {
            showOpacityDialog()
        }
        opacityValueText = opVal
        overlayCard.addView(opRow)
        overlayCard.addView(divider())

        val (foldRow, fToggle, _) = toggleRow(
            iconRes = R.drawable.ic_clock,
            title = "胶囊闲置时自动收成小球",
            subtitle = "胶囊闲置 3.5 秒自动收成贴边小球，需确认时自动展开"
        ) { checked ->
            if (isBinding) return@toggleRow
            OverlayPrefs.write(ctx, mapOf("autoCollapse" to checked))
            refreshState()
        }
        autoFoldToggle = fToggle
        overlayCard.addView(foldRow)
        overlayCard.addView(divider())

        val (retRow, rToggle, _) = toggleRow(
            iconRes = R.drawable.ic_launch,
            title = "完成后自动返回快捷对话抽屉",
            subtitle = "本轮操控结束时，自动唤起底部 QuickChat 快捷对话抽屉查看结果"
        ) { checked ->
            if (isBinding) return@toggleRow
            OverlayPrefs.setReturnToApp(ctx, checked)
            refreshState()
        }
        returnAppToggle = rToggle
        overlayCard.addView(retRow)
        overlayCard.addView(divider())

        val (logRow, lSub) = actionRow(
            iconRes = R.drawable.ic_copy,
            title = "最近操作记录",
            subtitle = "记录本次会话的每一步手机操作（不含屏幕隐私正文）",
            actionLabel = "查看"
        ) {
            showActionLogDialog()
        }
        logSubText = lSub
        overlayCard.addView(logRow)
        root.addView(overlayCard)

        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    parentFragmentManager.popBackStack()
                }
            }
        )
        MonetEngine.applyToViewTree(view, MonetEngine.resolveCurrentPalette(requireContext()))
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        syncActivityTitle(true)
        view?.let { MonetEngine.applyToViewTree(it, MonetEngine.resolveCurrentPalette(requireContext())) }
        PhoneControl.get(requireContext()).addSink(statusSink)
        refreshState()
    }

    override fun onPause() {
        PhoneControl.get(requireContext()).removeSink(statusSink)
        super.onPause()
    }

    override fun onDestroyView() {
        syncActivityTitle(false)
        super.onDestroyView()
    }

    private fun refreshState() {
        val ctx = context ?: return
        val pc = PhoneControl.get(ctx)
        val p = pc.policies()
        val snap = pc.tasks.snapshot()
        val bgStatus = pc.background.status()
        val ov = OverlayPrefs.read(ctx)

        isBinding = true
        try {
            if (snap.active) {
                activeTaskCard?.visibility = View.VISIBLE
                activeTaskText?.text = "当前应用：${snap.app ?: "桌面"} · 状态：${if (snap.paused) "已暂停" else "运行中"}${if (snap.waived) "（本任务免确认）" else ""}"
            } else {
                activeTaskCard?.visibility = View.GONE
            }

            val enabled = pc.isEnabled()
            masterToggle?.setChecked(enabled, false, false)
            bridgeStatusText?.text = if (enabled) {
                if (pc.bridgeListening) "🟢 MCP 桥已就绪 (@dsha.phone.bridge) · 13 个原生工具已挂载"
                else "🟡 手机操控已开启 · 正在准备 MCP 桥…"
            } else {
                "⚪ 已关闭 · 模型不可见任何手机操控工具"
            }

            val a11yConn = DshaAccessibilityService.isConnected()
            val keeperGranted = pc.status()["a11yKeeper"]?.let { (it as? Map<*, *>)?.get("granted") as? Boolean } == true
            a11yStatusText?.text = when {
                a11yConn && keeperGranted -> "🟢 已连接 · Root 自动恢复已开启"
                a11yConn -> "🟢 已连接（点右侧按钮可开启 Root 掉线自动恢复）"
                DshaAccessibilityService.enabled(ctx) -> "🟡 系统设置已开但未连接 · 点右侧「一键激活」重连"
                else -> "🔴 未开启 · 点右侧「一键激活」通过 Root 自动开启"
            }

            confirmModeValueText?.text = when (p.confirmMode) {
                ConfirmMode.ASK -> "每次都询问 ›"
                ConfirmMode.TASK -> "本任务免确认 ›"
                ConfirmMode.ALWAYS -> "始终免确认 ›"
            }

            val priv = bgStatus["privileged"] as? Map<*, *>
            val bgMode = (priv?.get("mode") as? String) ?: (bgStatus["mode"] as? String) ?: "off"
            val bgReady = (priv?.get("ready") as? Boolean) ?: (bgStatus["ready"] as? Boolean) ?: false
            val bgReason = (priv?.get("reason") as? String) ?: (bgStatus["reason"] as? String)
            bgToggle?.setChecked(bgMode != "off", false, false)
            bgStatusText?.text = when {
                bgMode == "off" -> "在看不见的独立焦点虚拟屏上操作 App，不耽误你用手机（需 Android 14+ 与 Root）"
                bgReady -> "🟢 后台虚拟屏已就绪（通道：$bgMode · 独立焦点与输入注入验证通过）"
                else -> "🟡 通道：$bgMode · ${bgReason ?: "正在准备…"}"
            }

            appRulesSubText?.text = "不碰：${p.denyPackages.size} 个应用 · 每步确认：${p.confirmEveryWritePackages.size} 个应用"

            unlockSystemToggle?.setChecked(p.unlocks.systemPages, false, false)
            unlockSecretToggle?.setChecked(p.unlocks.secretFields, false, false)
            unlockOwnToggle?.setChecked(p.unlocks.ownApp, false, false)

            opacityValueText?.text = "${(ov.idleOpacity * 100).roundToInt()}% ›"
            autoFoldToggle?.setChecked(ov.autoFold, false, false)
            returnAppToggle?.setChecked(OverlayPrefs.returnToApp(ctx), false, false)
            val entries = pc.log.entries()
            logSubText?.text = if (entries.isEmpty()) "本次会话暂无操作记录" else "本次会话已记录 ${entries.size} 步操作（点右侧查看）"
        } finally {
            isBinding = false
        }
    }

    // ==================== 统一 Skia + 莫奈卡片弹窗构造器（1:1 复刻插件市场安装弹窗） ====================

    private class CardDialogHolder(
        val dialog: AlertDialog,
        val bodySlot: LinearLayout,
        val footNoteView: TextView,
        val btnCancel: Button,
        val btnConfirm: Button,
    )

    private fun createPluginStyleDialog(
        @DrawableRes iconRes: Int,
        titleText: String,
        subTitleText: String,
        badge1Text: String,
        badge2Text: String?,
        footNote: String?,
        cancelText: String?,
        confirmText: String,
        isDangerConfirm: Boolean = false
    ): CardDialogHolder {
        val ctx = requireContext()
        val palette = MonetEngine.resolveCurrentPalette(ctx)

        val outer = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val cardView = ModernCardView(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        outer.addView(
            cardView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 1. 顶部标题行: 40dp m3_btn_tonal_expressive 图标框 + 18sp 粗体标题
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundResource(R.drawable.m3_btn_tonal_expressive)
            if (palette.isMonetActive) {
                backgroundTintList = ColorStateList.valueOf(palette.containerColor)
            }
        }
        val iconIv = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            setImageResource(iconRes)
            ImageViewCompat.setImageTintList(
                this,
                ColorStateList.valueOf(
                    if (isDangerConfirm) ContextCompat.getColor(ctx, R.color.err) else palette.primaryColor
                )
            )
        }
        iconFrame.addView(iconIv)
        val headerTitle = TextView(ctx).apply {
            text = titleText
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
            setPadding(dp(12), 0, 0, 0)
        }
        headerRow.addView(iconFrame)
        headerRow.addView(headerTitle)
        cardView.addView(headerRow)

        // 2. 副标题 + 纯 Skia ModernBadgeView 胶囊徽章行
        val subTitleView = TextView(ctx).apply {
            text = subTitleText
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
            setPadding(0, dp(14), 0, 0)
        }
        cardView.addView(subTitleView)

        val badgeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val b1 = ModernBadgeView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20))
            if (isDangerConfirm) {
                setDangerBadge(badge1Text)
            } else {
                setBadge(true, badge1Text)
            }
        }
        badgeRow.addView(b1)
        if (!badge2Text.isNullOrEmpty()) {
            val b2 = ModernBadgeView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)).apply {
                    leftMargin = dp(8)
                }
                setBadge(false, badge2Text)
            }
            badgeRow.addView(b2)
        }
        cardView.addView(badgeRow)

        // 3. 内嵌结构化内容槽 (bg_input 样式半透玻璃槽，支持超长滚动)
        val slotScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val bodySlot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val footNoteView = TextView(ctx).apply {
            text = footNote.orEmpty()
            textSize = 12f
            setLineSpacing(0f, 1.35f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            setPadding(0, dp(8), 0, 0)
            visibility = if (footNote.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        slotScroll.addView(bodySlot)
        cardView.addView(
            slotScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )
        cardView.addView(footNoteView)

        // 4. 底部操作按钮行 (高度 44dp, 间距 10dp)
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }

        val btnCancel = Button(ctx).apply {
            id = R.id.btnDialogCancel
            text = cancelText ?: "取消"
            isAllCaps = false
            textSize = 15f
            elevation = 0f
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, R.animator.btn_press_scale)
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundResource(R.drawable.m3_btn_tonal_expressive)
            setTextColor(palette.primaryColor)
            visibility = if (cancelText == null) View.GONE else View.VISIBLE
        }

        val btnConfirm = Button(ctx).apply {
            id = if (isDangerConfirm) R.id.launch_stop else R.id.btnDialogConfirm
            text = confirmText
            isAllCaps = false
            textSize = 15f
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, R.animator.btn_press_scale)
            setTypeface(typeface, Typeface.BOLD)
            if (isDangerConfirm) {
                elevation = 0f
                setBackgroundResource(R.drawable.bg_btn_danger)
                setTextColor(Color.WHITE)
            } else {
                elevation = dp(2).toFloat()
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    outlineAmbientShadowColor = Color.TRANSPARENT
                    outlineSpotShadowColor = 0x3847699F
                }
                setBackgroundResource(R.drawable.m3_btn_primary_expressive)
                setTextColor(ContextCompat.getColor(ctx, R.color.accent_on))
            }
        }

        btnRow.addView(
            btnCancel,
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )
        btnRow.addView(
            btnConfirm,
            LinearLayout.LayoutParams(0, dp(44), if (cancelText == null) 1f else 1.2f).apply {
                if (cancelText != null) leftMargin = dp(10)
            }
        )
        cardView.addView(btnRow)

        MonetEngine.applyToViewTree(outer, palette)

        val dialog = DshaDialogBuilder(ctx)
            .setView(outer)
            .setCancelable(true)
            .create()

        // 限制最大高度防溢出屏幕
        slotScroll.post {
            val maxH = (resources.displayMetrics.heightPixels * 0.45f).toInt()
            if (slotScroll.height > maxH) {
                slotScroll.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    maxH
                ).apply { topMargin = dp(12) }
            }
        }

        return CardDialogHolder(dialog, bodySlot, footNoteView, btnCancel, btnConfirm)
    }

    /** 构建 bg_input 槽内的可点选单选/列表项卡片 */
    private fun addSelectableOptionRow(
        container: LinearLayout,
        title: String,
        subtitle: String?,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val ctx = requireContext()
        val palette = MonetEngine.resolveCurrentPalette(ctx)
        val itemBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (selected) {
                    setColor(palette.containerColor)
                    setStroke(dp(1), palette.primaryColor)
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
            setOnClickListener { onClick() }
        }
        val tView = TextView(ctx).apply {
            text = (if (selected) "● " else "○ ") + title
            textSize = 13.5f
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(
                if (selected) palette.primaryColor else ContextCompat.getColor(ctx, R.color.text)
            )
        }
        itemBox.addView(tView)
        if (!subtitle.isNullOrEmpty()) {
            val sView = TextView(ctx).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                setPadding(dp(16), dp(2), 0, 0)
            }
            itemBox.addView(sView)
        }
        container.addView(itemBox, lp(bottomMargin = dp(4)))
    }

    // ==================== 各功能弹窗实现（全部采用插件安装弹窗卡片样式） ====================

    private fun runScreenSelfTest() {
        val ctx = context ?: return
        val pc = PhoneControl.get(ctx)
        ToastHelper.show(ctx, "正在读取当前屏幕…")
        Thread {
            if (!DshaAccessibilityService.isConnected()) {
                DshaAccessibilityService.ensureConnected(ctx)
            }
            val res = pc.testScreen()
            mainHandler.post {
                if (!isAdded) return@post
                val ok = res["ok"] == true
                val text = if (ok) (res["text"] as? String).orEmpty() else (res["reason"] as? String ?: "读取失败")
                val holder = createPluginStyleDialog(
                    iconRes = R.drawable.ic_chat,
                    titleText = if (ok) "当前屏幕读取预览" else "无法读取当前屏幕",
                    subTitleText = "ScreenFormat 蒸馏文本树",
                    badge1Text = if (ok) "读取成功" else "需要处理",
                    badge2Text = "本地只读 · 不发给模型",
                    footNote = "• 银行卡号与身份证号在离开原生层前会自动打码为 «银行卡 ****1234»\n• 元素编号 [1] [2] 仅对当次屏幕快照有效",
                    cancelText = null,
                    confirmText = "我知道了"
                )
                holder.bodySlot.addView(TextView(ctx).apply {
                    this.text = text
                    textSize = 12.5f
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(0f, 1.35f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                })
                holder.btnConfirm.setOnClickListener { holder.dialog.dismiss() }
                holder.dialog.show()
                refreshState()
            }
        }.start()
    }

    private fun showConfirmModeDialog() {
        val ctx = context ?: return
        val pc = PhoneControl.get(ctx)
        var picked = pc.policies().confirmMode

        val holder = createPluginStyleDialog(
            iconRes = R.drawable.ic_settings_shield,
            titleText = "安全确认方式",
            subTitleText = "选择 AI 操作手机前的询问策略",
            badge1Text = "SafetyGate 原生门禁",
            badge2Text = "30 秒超时默认拒绝",
            footNote = "• 无论选择哪种方式，系统设置页、密码验证码框、银行支付类 App 与 DSHA 自身默认均受硬拦截保护。",
            cancelText = "取消",
            confirmText = "确认切换"
        )

        fun renderOptions() {
            holder.bodySlot.removeAllViews()
            addSelectableOptionRow(
                holder.bodySlot,
                "每次都询问（推荐）",
                "首次进入应用、点击含发送/删除/支付等敏感词、微信每步写操作前均先询问你",
                picked == ConfirmMode.ASK
            ) { picked = ConfirmMode.ASK; renderOptions() }

            addSelectableOptionRow(
                holder.bodySlot,
                "本任务免确认",
                "每个任务只询问第一次，选择「本任务免确认」后该任务后续步骤自动执行",
                picked == ConfirmMode.TASK
            ) { picked = ConfirmMode.TASK; renderOptions() }

            addSelectableOptionRow(
                holder.bodySlot,
                "始终免确认",
                "全程自动执行不弹窗询问（四大高危禁区仍然拦截）",
                picked == ConfirmMode.ALWAYS
            ) { picked = ConfirmMode.ALWAYS; renderOptions() }
        }
        renderOptions()

        holder.btnCancel.setOnClickListener { holder.dialog.dismiss() }
        holder.btnConfirm.setOnClickListener {
            holder.dialog.dismiss()
            pc.setPolicies(mapOf("confirmMode" to picked.wire))
            ToastHelper.show(ctx, "确认方式已更新")
            refreshState()
        }
        holder.dialog.show()
    }

    private fun toggleBackgroundDisplay(enable: Boolean) {
        val ctx = context ?: return
        val pc = PhoneControl.get(ctx)
        if (!enable) {
            Thread {
                pc.background.setBackgroundMode("off")
                mainHandler.post { if (isAdded) refreshState() }
            }.start()
            return
        }
        ToastHelper.show(ctx, "正在通过 Root 启动后台虚拟屏并检验输入注入…")
        Thread {
            val res = pc.background.setBackgroundMode("root") { true }
            val ok = res["ok"] == true
            val reason = res["reason"] as? String
            mainHandler.post {
                if (!isAdded) return@post
                if (ok) {
                    ToastHelper.show(ctx, "不打扰模式（后台虚拟屏）已启用！")
                } else {
                    ToastHelper.show(ctx, "后台虚拟屏未能就绪：${reason ?: "未知原因"}", Toast.LENGTH_LONG)
                }
                refreshState()
            }
        }.start()
    }

    private fun showAppRulesDialog() {
        val ctx = context ?: return
        val pc = PhoneControl.get(ctx)
        val p = pc.policies()
        val pm = ctx.packageManager
        val allPkgs = (p.denyPackages + p.confirmEveryWritePackages + PhonePolicies().denyPackages + PhonePolicies().confirmEveryWritePackages).distinct()

        val holder = createPluginStyleDialog(
            iconRes = R.drawable.ic_plugins,
            titleText = "应用名单管理",
            subTitleText = "点击任意应用切换「不碰 / 步步确认 / 照常」",
            badge1Text = "不碰 ${p.denyPackages.size} 个",
            badge2Text = "步步确认 ${p.confirmEveryWritePackages.size} 个",
            footNote = "• 支付、银行、券商类 App 默认「不碰」；微信默认「每步都确认」",
            cancelText = "恢复默认",
            confirmText = "添加包名"
        )

        for (pkg in allPkgs) {
            val label = SafetyRules.labelFor(pm, pkg)
            val rule = when {
                pkg in p.denyPackages -> "🚫 不碰（禁止读屏与操作）"
                pkg in p.confirmEveryWritePackages -> "⚠️ 每一步都确认"
                else -> "✅ 照常"
            }
            val title = if (label != pkg) label else pkg
            val sub = if (label != pkg) "$pkg · 当前规则：$rule" else "当前规则：$rule"
            addSelectableOptionRow(
                holder.bodySlot,
                title,
                sub,
                pkg in p.denyPackages
            ) {
                holder.dialog.dismiss()
                showSingleAppRuleDialog(pkg, title)
            }
        }

        holder.btnCancel.setOnClickListener {
            holder.dialog.dismiss()
            val defaults = PhonePolicies()
            pc.setPolicies(
                mapOf(
                    "denyPackages" to defaults.denyPackages,
                    "confirmEveryWritePackages" to defaults.confirmEveryWritePackages
                )
            )
            ToastHelper.show(ctx, "已恢复默认应用名单")
            refreshState()
        }
        holder.btnConfirm.setOnClickListener {
            holder.dialog.dismiss()
            showAddPackageDialog()
        }
        holder.dialog.show()
    }

    private fun showAddPackageDialog() {
        val ctx = context ?: return
        val holder = createPluginStyleDialog(
            iconRes = R.drawable.ic_plugins,
            titleText = "添加应用规则",
            subTitleText = "按 Android 包名纳入名单管理",
            badge1Text = "自定义规则",
            badge2Text = "实时生效",
            footNote = "• 例如 QQ：com.tencent.mobileqq，淘宝：com.taobao.taobao",
            cancelText = "取消",
            confirmText = "下一步"
        )
        val input = EditText(ctx).apply {
            hint = "请输入应用包名，如 com.tencent.mobileqq"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            background = null
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        holder.bodySlot.addView(input)
        holder.btnCancel.setOnClickListener { holder.dialog.dismiss() }
        holder.btnConfirm.setOnClickListener {
            val pkg = input.text?.toString()?.trim().orEmpty()
            if (pkg.contains('.')) {
                holder.dialog.dismiss()
                val label = SafetyRules.labelFor(ctx.packageManager, pkg)
                showSingleAppRuleDialog(pkg, label)
            } else {
                ToastHelper.show(ctx, "包名格式不正确")
            }
        }
        holder.dialog.show()
    }

    private fun showSingleAppRuleDialog(pkg: String, label: String) {
        val ctx = context ?: return
        val pc = PhoneControl.get(ctx)
        val p = pc.policies()
        var picked = when {
            pkg in p.denyPackages -> 0
            pkg in p.confirmEveryWritePackages -> 1
            else -> 2
        }

        val holder = createPluginStyleDialog(
            iconRes = R.drawable.ic_settings_shield,
            titleText = label,
            subTitleText = pkg,
            badge1Text = "单应用策略",
            badge2Text = "SafetyGate",
            footNote = "• 将金融或社交应用放宽为「照常」会增加误操作风险，请谨慎修改。",
            cancelText = "返回名单",
            confirmText = "保存规则"
        )

        fun render() {
            holder.bodySlot.removeAllViews()
            addSelectableOptionRow(
                holder.bodySlot,
                "🚫 不碰",
                "禁止打开、禁止读屏、禁止点击与输入",
                picked == 0
            ) { picked = 0; render() }
            addSelectableOptionRow(
                holder.bodySlot,
                "⚠️ 每一步都确认",
                "允许读屏，但每一次点击或输入都必须先弹窗询问你",
                picked == 1
            ) { picked = 1; render() }
            addSelectableOptionRow(
                holder.bodySlot,
                "✅ 照常",
                "按全局确认方式处理（仅首次进入与敏感按钮询问）",
                picked == 2
            ) { picked = 2; render() }
        }
        render()

        holder.btnCancel.setOnClickListener {
            holder.dialog.dismiss()
            showAppRulesDialog()
        }
        holder.btnConfirm.setOnClickListener {
            holder.dialog.dismiss()
            val deny = p.denyPackages.toMutableList().apply { remove(pkg) }
            val every = p.confirmEveryWritePackages.toMutableList().apply { remove(pkg) }
            when (picked) {
                0 -> deny.add(pkg)
                1 -> every.add(pkg)
            }
            pc.setPolicies(mapOf("denyPackages" to deny, "confirmEveryWritePackages" to every))
            ToastHelper.show(ctx, "已更新「$label」的操控规则")
            refreshState()
        }
        holder.dialog.show()
    }

    /**
     * 解除限制：关闭只需点一下；开启必须阅读免责声明并按住「我了解风险，开启」1.5 秒。
     * 采用插件安装弹窗同款 ModernCardView 布局。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun handleUnlockToggle(key: String, title: String, wantEnable: Boolean) {
        val ctx = context ?: return
        val pc = PhoneControl.get(ctx)
        val currentUnlocks = pc.policies().unlocks

        if (!wantEnable) {
            val next = currentUnlocks.toMap().toMutableMap().apply { put(key, false) }
            pc.setPolicies(mapOf("unlocks" to next))
            ToastHelper.show(ctx, "已重新锁定：$title")
            refreshState()
            return
        }

        refreshState()

        if (pc.tasks.isActive) {
            ToastHelper.show(ctx, "手机操控任务进行中不能解除限制，请先停止当前任务", Toast.LENGTH_LONG)
            return
        }

        val detail = when (key) {
            "systemPages" -> "开启后，AI 可以在系统设置、权限授予弹窗、APK 安装器、安全中心和通知栏中点击或修改设置。"
            "secretFields" -> "开启后，AI 可以向密码框、短信验证码框和支付密码框中输入内容（日志中仍打码为 ••••）。"
            else -> "开启后，AI 可以在 DSHA 自身界面中点击和修改配置。"
        }

        val iconRes = when (key) {
            "systemPages" -> R.drawable.ic_settings_sliders
            "secretFields" -> R.drawable.ic_key
            else -> R.drawable.ic_about
        }

        val holder = createPluginStyleDialog(
            iconRes = iconRes,
            titleText = "解除高危限制",
            subTitleText = title,
            badge1Text = "高风险操作",
            badge2Text = "长按 1.5 秒开启",
            footNote = "• 建议仅在需要时临时开启，用完立即关闭\n• 开启后顶部悬浮胶囊前会显示红色开锁标记",
            cancelText = "取消",
            confirmText = "按住 1.5 秒开启",
            isDangerConfirm = true
        )

        holder.bodySlot.addView(TextView(ctx).apply {
            text = "$detail\n\n免责声明：\n• AI 可能被网页、消息或文件中的恶意提示词误导。\n• 由此造成的账号、资金、数据或设备损失由你自行承担。"
            textSize = 13f
            setLineSpacing(0f, 1.4f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        })

        holder.btnCancel.setOnClickListener { holder.dialog.dismiss() }

        var downAt = 0L
        var holding = false
        val holdDurationMs = 1500L
        val tickRunnable = object : Runnable {
            override fun run() {
                if (!holding) return
                val elapsed = SystemClock.uptimeMillis() - downAt
                if (elapsed >= holdDurationMs) {
                    holding = false
                    holder.btnConfirm.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    val next = pc.policies().unlocks.toMap().toMutableMap().apply { put(key, true) }
                    pc.setPolicies(mapOf("unlocks" to next))
                    ToastHelper.show(ctx, "已解除限制：$title")
                    holder.dialog.dismiss()
                    refreshState()
                } else {
                    val remain = ((holdDurationMs - elapsed) / 100f).roundToInt() / 10.0
                    holder.btnConfirm.text = "继续按住 ${remain}s…"
                    mainHandler.postDelayed(this, 50)
                }
            }
        }

        holder.btnConfirm.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    holding = true
                    mainHandler.post(tickRunnable)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val elapsed = SystemClock.uptimeMillis() - downAt
                    if (holding) {
                        holding = false
                        mainHandler.removeCallbacks(tickRunnable)
                        holder.btnConfirm.text = "按住 1.5 秒开启"
                        if (elapsed in 60 until holdDurationMs) {
                            ToastHelper.show(ctx, "⚠️ 请持续长按住按钮 1.5 秒以确认开启")
                        }
                    }
                    true
                }
                else -> false
            }
        }

        holder.dialog.show()
    }

    private fun showOpacityDialog() {
        val ctx = context ?: return
        val options = intArrayOf(30, 50, 70, 85, 100)
        var picked = (OverlayPrefs.read(ctx).idleOpacity * 100).roundToInt().let { cur ->
            if (cur in options) cur else 70
        }

        val holder = createPluginStyleDialog(
            iconRes = R.drawable.ic_moon,
            titleText = "小球闲置不透明度",
            subTitleText = "贴边小球 3 秒未触碰时的变淡程度",
            badge1Text = "默认 70%",
            badge2Text = "胶囊与卡片保持实心",
            footNote = "• 胶囊和展开卡片承载实时文字，始终保持不透明以确保清晰可读。",
            cancelText = "取消",
            confirmText = "确认应用"
        )

        fun render() {
            holder.bodySlot.removeAllViews()
            for (opt in options) {
                addSelectableOptionRow(
                    holder.bodySlot,
                    if (opt == 70) "$opt%（推荐默认）" else "$opt%",
                    null,
                    picked == opt
                ) { picked = opt; render() }
            }
        }
        render()

        holder.btnCancel.setOnClickListener { holder.dialog.dismiss() }
        holder.btnConfirm.setOnClickListener {
            holder.dialog.dismiss()
            OverlayPrefs.write(ctx, mapOf("idleOpacity" to (picked / 100f)))
            refreshState()
        }
        holder.dialog.show()
    }

    private fun showActionLogDialog() {
        val ctx = context ?: return
        val pc = PhoneControl.get(ctx)
        val entries = pc.log.entries()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val logText = if (entries.isEmpty()) {
            "本次会话暂无手机操作记录。\n\n当 AI 调用 mcp__phone__* 工具（读屏、点击、输入、打开应用、发送系统意图等）时，每一步操作的时间、目标应用与安全门禁放行结果都会实时记录在这里。"
        } else {
            entries.joinToString("\n\n") { e ->
                val time = (e["at"] as? Number)?.toLong()?.let { fmt.format(Date(it)) } ?: ""
                val app = (e["app"] as? String)?.let { " · $it" } ?: ""
                val summary = e["summary"] as? String ?: ""
                val decision = e["decision"] as? String ?: ""
                "[$time$app] $summary ($decision)"
            }
        }

        val holder = createPluginStyleDialog(
            iconRes = R.drawable.ic_copy,
            titleText = "最近手机操作记录",
            subTitleText = if (entries.isEmpty()) "本次会话暂无操作" else "本次会话共记录 ${entries.size} 步操作",
            badge1Text = "仅存内存",
            badge2Text = "不含屏幕隐私正文",
            footNote = "• 密码与验证码输入永远记录为 ••••，关闭 App 后记录自动销毁。",
            cancelText = if (entries.isEmpty()) null else "清空记录",
            confirmText = "完成"
        )
        holder.bodySlot.addView(TextView(ctx).apply {
            text = logText
            textSize = 12.5f
            typeface = if (entries.isEmpty()) Typeface.DEFAULT else Typeface.MONOSPACE
            setLineSpacing(0f, 1.35f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        })
        holder.btnCancel.setOnClickListener {
            holder.dialog.dismiss()
            pc.log.clear()
            refreshState()
        }
        holder.btnConfirm.setOnClickListener { holder.dialog.dismiss() }
        holder.dialog.show()
    }

    // ==================== UI 构建辅助（1:1 对齐 fragment_config.xml + MonetEngine） ====================

    private fun sectionHeader(title: String): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.09f
            isAllCaps = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            setPadding(dp(6), dp(12), dp(6), dp(4))
        }
    }

    private fun card(): ModernCardView {
        val ctx = requireContext()
        return ModernCardView(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
        }
    }

    private fun divider(): View {
        val ctx = requireContext()
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { leftMargin = dp(66) }
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.line_soft))
        }
    }

    private fun iconBox(@DrawableRes iconRes: Int): FrameLayout {
        val ctx = requireContext()
        val box = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setBackgroundResource(R.drawable.bg_settings_icon_box)
        }
        val iv = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            setImageResource(iconRes)
            ImageViewCompat.setImageTintList(
                this,
                ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.primary))
            )
        }
        box.addView(iv)
        return box
    }

    private fun toggleRow(
        @DrawableRes iconRes: Int,
        title: String,
        subtitle: String,
        onChanged: (Boolean) -> Unit
    ): Triple<LinearLayout, DshaToggle, TextView> {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            }
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
        }
        val subView = TextView(ctx).apply {
            text = subtitle
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(titleView)
        textCol.addView(subView)

        val toggle = DshaToggle(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(26)).apply {
                leftMargin = dp(8)
            }
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        row.setOnClickListener { toggle.toggle() }
        row.addView(iconBox(iconRes))
        row.addView(textCol)
        row.addView(toggle)
        return Triple(row, toggle, subView)
    }

    private fun actionRow(
        @DrawableRes iconRes: Int,
        title: String,
        subtitle: String,
        actionLabel: String,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            }
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
        }
        val subView = TextView(ctx).apply {
            text = subtitle
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(titleView)
        textCol.addView(subView)

        val btn = Button(ctx).apply {
            text = actionLabel
            textSize = 12.5f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            elevation = 0f
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, R.animator.btn_press_scale)
            includeFontPadding = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.primary))
            setBackgroundResource(R.drawable.m3_btn_tonal_expressive)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { onClick() }
        }
        row.addView(iconBox(iconRes))
        row.addView(textCol)
        row.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(10)
        })
        return row to subView
    }

    private fun valueRow(
        @DrawableRes iconRes: Int,
        title: String,
        subtitle: String,
        initialValue: String,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            }
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
        }
        val subView = TextView(ctx).apply {
            text = subtitle
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(titleView)
        textCol.addView(subView)

        val valView = TextView(ctx).apply {
            text = initialValue
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.primary))
            setPadding(dp(10), 0, 0, 0)
        }
        row.addView(iconBox(iconRes))
        row.addView(textCol)
        row.addView(valView)
        return row to valView
    }

    private fun lp(bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.bottomMargin = bottomMargin }

    private fun syncActivityTitle(isSubpage: Boolean) {
        if (!isAdded) return
        val activityTitle = requireActivity().findViewById<TextView>(R.id.app_title)
        activityTitle?.text = if (isSubpage) "手机操控" else getString(R.string.nav_settings)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()
}
