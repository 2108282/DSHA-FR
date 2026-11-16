package com.deepseekharness.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.deepseekharness.app.util.ToastHelper

/** Manual, settings-only permission requests. Phone tools never call this helper. */
internal class PhoneTaskPermissionUi(
    private val fragment: Fragment,
    private val onChanged: () -> Unit,
) {
    data class Task(val id: String, val title: String, val explanation: String, val permissions: List<String>)
    data class Group(val title: String, val explanation: String, val tasks: List<Task>)
    data class State(val granted: List<String>, val missing: List<String>, val settingsOnly: List<String>) {
        val complete: Boolean get() = missing.isEmpty()
    }

    private val launcher = fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ctx = fragment.context ?: return@registerForActivityResult
        // Empty results can mean cancellation; do not mistake them for a permanent refusal.
        if (result.isNotEmpty()) {
            val edit = ctx.getSharedPreferences(PREFS, 0).edit()
            result.keys.forEach { edit.putBoolean(it, true) }
            edit.apply()
            ToastHelper.show(ctx, if (result.values.all { it }) "已更新原生任务权限" else "部分权限未授予；可在原生任务权限中查看，短信权限还可能受系统限制")
        }
        onChanged()
    }

    val groups: List<Group> = buildList {
        add(Group("联系人", "按姓名查找联系人和号码，仅在你需要联系某人时使用。", listOf(
            Task("contacts", "读取联系人", "用于查找联系人和电话号码；不修改通讯录。", listOf(Manifest.permission.READ_CONTACTS)),
        )))
        add(Group("日历", "分别授予日程读取和写入；只查看日程时无需授予写入权限。", listOf(
            Task("calendar_read", "读取日程", "查询已有日历、事件和提醒。", listOf(Manifest.permission.READ_CALENDAR)),
            Task("calendar_write", "创建与修改日程", "创建、修改或删除日程，并读取结果核对。", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
        )))
        add(Group("电话 / 短信", "按任务分别授权。短信读取或发送可能受系统、安装来源或默认短信应用限制；权限显示已授予也不保证系统接受操作。", listOf(
            Task("call", "直接拨打电话", "允许发起电话。仅打开拨号盘无需此权限；授权不会自动拨号。", listOf(Manifest.permission.CALL_PHONE)),
            Task("sms_read", "读取任务短信", "用于查询你指定的短信会话。短信权限可能受系统限制；不会自动设为默认短信应用。", listOf(Manifest.permission.READ_SMS)),
            Task("sms_send", "直接发送短信", "允许发送任务短信，可能产生运营商费用；授权不会自动发送。系统仍可能限制此权限。", listOf(Manifest.permission.SEND_SMS)),
            Task("phone_state", "SIM 与电话状态", "用于区分可用 SIM 和电话状态；不包含拨号或短信发送授权。", listOf(Manifest.permission.READ_PHONE_STATE)),
        )))
        val images = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val videos = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        val mediaNote = if (Build.VERSION.SDK_INT >= 33) "分别授权图片、视频和音频。系统可能只允许访问所选照片或视频，这不等于完整媒体库权限。" else "Android 12 及以下使用同一个共享存储读取权限；授权任一媒体项会同时影响其他媒体项。"
        add(Group("任务媒体", mediaNote, listOf(
            Task("images", "任务图片", "查找或分享任务需要的图片。$mediaNote", listOf(images)),
            Task("videos", "任务视频", "查找或分享任务需要的视频。$mediaNote", listOf(videos)),
            Task("audio", "任务音频", "查找或分享任务需要的音频。${if (Build.VERSION.SDK_INT < 33) mediaNote else "不会同时申请图片或视频权限。"}", listOf(audio)),
        )))
    }

    fun state(task: Task): State {
        val ctx = fragment.requireContext()
        val missing = task.permissions.distinct().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        val requested = ctx.getSharedPreferences(PREFS, 0)
        val settingsOnly = missing.filter { requested.getBoolean(it, false) && !fragment.shouldShowRequestPermissionRationale(it) }
        return State(task.permissions.distinct() - missing.toSet(), missing, settingsOnly)
    }

    fun summary(group: Group): String {
        val ctx = fragment.requireContext()
        val permissions = group.tasks.flatMap { it.permissions }.distinct()
        val missing = permissions.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        return if (missing.isEmpty()) "已授予 · 本组所需权限均已授予" else "缺失 ${missing.size} 项权限 · 已授予 ${permissions.size - missing.size}/${permissions.size}，按任务管理"
    }

    fun taskSummary(task: Task): String {
        val state = state(task)
        return when {
            state.complete -> "已授予"
            state.settingsOnly.isNotEmpty() -> "缺失 ${state.missing.size} 项 · 部分需到应用设置处理"
            else -> "缺失 ${state.missing.size} 项 · 点此手动申请"
        }
    }

    /** Called only by the settings dialog's explicit user action. Never requests other tasks. */
    fun requestMissing(task: Task) {
        val state = state(task)
        val requestable = state.missing - state.settingsOnly.toSet()
        if (requestable.isEmpty()) {
            openAppSettings()
            return
        }
        runCatching { launcher.launch(requestable.toTypedArray()) }.onFailure {
            fragment.context?.let { ctx -> ToastHelper.show(ctx, "系统未能打开授权弹窗，请到应用设置检查权限") }
        }
    }

    fun openAppSettings() {
        val ctx = fragment.context ?: return
        runCatching {
            fragment.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }.onFailure { ToastHelper.show(ctx, "无法打开应用详情，请在系统设置中找到 DSH 的应用权限") }
    }

    fun permissionName(permission: String): String = when (permission) {
        Manifest.permission.READ_CONTACTS -> "读取联系人"
        Manifest.permission.READ_CALENDAR -> "读取日历"
        Manifest.permission.WRITE_CALENDAR -> "写入日历"
        Manifest.permission.CALL_PHONE -> "拨打电话"
        Manifest.permission.READ_SMS -> "读取短信"
        Manifest.permission.SEND_SMS -> "发送短信"
        Manifest.permission.READ_PHONE_STATE -> "电话 / SIM 状态"
        Manifest.permission.READ_MEDIA_IMAGES -> "读取图片"
        Manifest.permission.READ_MEDIA_VIDEO -> "读取视频"
        Manifest.permission.READ_MEDIA_AUDIO -> "读取音频"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "读取共享存储（图片 / 视频 / 音频共用）"
        else -> permission.substringAfterLast('.')
    }

    private companion object { const val PREFS = "phone_task_permission_requests" }
}
