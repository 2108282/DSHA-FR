package com.deepseekharness.app.ui.contract

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.deepseekharness.app.HttpShellService
import com.deepseekharness.app.core.ConfigStore
import com.deepseekharness.app.core.HarnessController
import com.deepseekharness.app.ui.dialog.OverlayStyleDialog
import com.deepseekharness.app.util.Constants

class ConfigPresenter(
    private val activity: Activity,
    private val callback: ViewCallback
) {

    interface ViewCallback {
        fun onRender(state: ConfigUiState)
        fun onOpenWorkspace()
        fun onGoBack()
        fun onRequestLocationPermission()
    }

    private val context: Context = activity.applicationContext
    private val config: ConfigStore = HarnessController.get(context).config()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init() {
        refreshState()
    }

    fun refreshState() {
        val sp = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

        val port = config.port
        val taskset = config.taskset
        val confirmShell = config.isConfirmShell
        val lanMode = config.isLanMode
        val overlayStream = sp.getBoolean("overlay_stream", false)
        val capSensors = sp.getBoolean("cap_sensors", false)
        val capLocation = sp.getBoolean("cap_location", false)
        val asrContinuous = sp.getBoolean("asr_continuous", false)

        val allFilesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val allFilesStatus = if (allFilesGranted) {
            "✅ 已开启：容器可读写手机存储任意文件（含工作区直通）"
        } else {
            "⚠️ 未开启：仅能访问 App 私有目录；点击跳转系统设置开启"
        }

        val a11yStatus = checkA11yStatus()
        val asrStatus = checkAsrStatus()

        val state = ConfigUiState(
            port = port,
            taskset = taskset,
            isConfirmShell = confirmShell,
            isLanMode = lanMode,
            isOverlayStream = overlayStream,
            isCapSensors = capSensors,
            isCapLocation = capLocation,
            isAsrContinuous = asrContinuous,
            allFilesStatusText = allFilesStatus,
            a11yStatusText = a11yStatus,
            asrStatusText = asrStatus
        )
        mainHandler.post { callback.onRender(state) }
    }

    fun dispatch(action: ConfigAction) {
        when (action) {
            is ConfigAction.SaveConfig -> handleSave(action)
            ConfigAction.Back -> callback.onGoBack()
            ConfigAction.OpenWorkspace -> callback.onOpenWorkspace()
            ConfigAction.OpenOverlayStyle -> OverlayStyleDialog.show(activity)
            ConfigAction.OpenAllFilesSettings -> openAllFilesAccess()
            ConfigAction.OpenBatteryOptimization -> openBatteryOptimization()
            ConfigAction.OpenA11ySettings -> openA11ySettings()
            ConfigAction.CheckAsrStatus -> {
                refreshState()
                toast("ASR 状态检测已更新")
            }
            ConfigAction.FixAsrConfig -> applyAsrConfig()
            is ConfigAction.ToggleAsrContinuous -> {
                context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("asr_continuous", action.enabled).apply()
                toast(if (action.enabled) "已开启长语音连续接力模式" else "已恢复短语音模式（默认）")
                refreshState()
            }
        }
    }

    private fun handleSave(action: ConfigAction.SaveConfig) {
        config.port = action.port
        val cleanTaskset = action.taskset.trim().replace(Regex("[^0-9,-]"), "")
        config.taskset = cleanTaskset
        applyTasksetImmediately(cleanTaskset)
        config.isConfirmShell = action.confirmShell
        config.isLanMode = action.lanMode

        context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("overlay_stream", action.overlayStream)
            .putBoolean("cap_sensors", action.capSensors)
            .putBoolean("cap_location", action.capLocation)
            .apply()

        if (action.capLocation && context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            callback.onRequestLocationPermission()
        }

        toast("配置已保存")
        refreshState()
    }

    private fun applyTasksetImmediately(cpus: String) {
        Thread {
            try {
                val cleanCpus = cpus.trim().replace(Regex("[^0-9,-]"), "")
                val writeCmd = "mkdir -p /data/adb/dsha/run /data/adb/dsha/rootfs/root/.dsh 2>/dev/null; " +
                        "echo '$cleanCpus' > /data/adb/dsha/run/taskset 2>/dev/null; " +
                        "echo '$cleanCpus' > /data/adb/dsha/rootfs/root/.dsh/taskset 2>/dev/null; "
                val applyCmd = "PID=$(cat /data/adb/dsha/run/dsh.pid 2>/dev/null); " +
                        "if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then " +
                        "  if [ -f /dev/cpuset/cgroup.procs ]; then echo \"\$PID\" > /dev/cpuset/cgroup.procs 2>/dev/null || true; fi; " +
                        "  TOTAL_CPUS=$(cat /sys/devices/system/cpu/online 2>/dev/null || echo '0-7'); " +
                        "  TARGET_CPUS=\"" + (if (cleanCpus.isEmpty()) "\$TOTAL_CPUS" else cleanCpus) + "\"; " +
                        "  chroot /data/adb/dsha/rootfs /usr/bin/taskset -a -p -c \"\$TARGET_CPUS\" \"\$PID\" >/dev/null 2>&1 || true; " +
                        "fi"
                Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", writeCmd + applyCmd)).waitFor()
            } catch (e: Throwable) {
                android.util.Log.w("DSHA", "动态应用 CPU 亲和度异常: " + e.message)
            }
        }.start()
    }

    private fun checkA11yStatus(): String {
        return try {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val myService = context.packageName + "/com.deepseekharness.app.DshaAccessibilityService"
            if (enabled.contains(myService)) {
                "✅ 正常：无障碍服务已开启，具备读屏/点按/输入能力"
            } else {
                "⚠️ 未开启：点此前往系统设置开启无障碍，否则 Agent 无法操作屏幕"
            }
        } catch (t: Throwable) {
            "状态检测失败：" + t.message
        }
    }

    private fun checkAsrStatus(): String {
        return try {
            val currentService = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            val isXiaomi = currentService != null && currentService.contains("com.xiaomi.mibrain.speech")
            val hasAudioPerm = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            if (isXiaomi && hasAudioPerm) {
                "✅ 已就绪：小米底层 ASR 引擎正常，录音权限已授予"
            } else {
                val sb = StringBuilder("⚠️ 需配置：")
                if (!isXiaomi) {
                    val shortName = currentService?.substringAfterLast('.') ?: "未设置"
                    sb.append("当前引擎=$shortName；")
                }
                if (!hasAudioPerm) {
                    sb.append("录音权限未授予；")
                }
                sb.append("请点击「一键配置」修复")
                sb.toString()
            }
        } catch (t: Throwable) {
            "状态读取失败: " + t.message
        }
    }

    private fun applyAsrConfig() {
        toast("正在通过 Root 配置小米原生 ASR 引擎...")
        Thread {
            try {
                val pkg = context.packageName
                val cmd = "settings put secure voice_recognition_service \"com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.asr.AsrService\"" +
                        " && pm grant $pkg android.permission.RECORD_AUDIO" +
                        " && cmd appops set com.xiaomi.mibrain.speech RECORD_AUDIO allow" +
                        " && cmd appops set $pkg RECORD_AUDIO allow"
                HttpShellService.execRootCommand(cmd)
                mainHandler.post {
                    refreshState()
                    toast("ASR 引擎与录音权限已配置完成")
                }
            } catch (t: Throwable) {
                mainHandler.post { toast("配置失败：" + t.message) }
            }
        }.start()
    }

    private fun openAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:" + context.packageName)
                })
            } else {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + context.packageName)
                })
            }
        } catch (t: Throwable) {
            toast("无法打开设置：" + t.message)
        }
    }

    private fun openBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:" + context.packageName)
                })
            }
        } catch (t: Throwable) {
            toast("无法打开电池优化设置")
        }
    }

    private fun openA11ySettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            toast("无法打开无障碍设置")
        }
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}
