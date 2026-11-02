package com.deepseekharness.app.phone.a11y

// System pages always handed back to the person: accessibility, device admin, developer options, app
// install / uninstall, permission pages — so the agent can never grant itself
// anything. The driver reads these pages but refuses to act on them. Plain Kotlin.

object ProtectedPages {
    /** Whole packages that only ever show install / permission / security UI. */
    val packages: Set<String> = setOf(
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller", "com.oplus.packageinstaller", "com.coloros.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.lbe.security.miui", "com.miui.securitycenter", "com.miui.securityadd",
        "com.coloros.securitypermission", "com.oplus.securitypermission", "com.coloros.safecenter", "com.oplus.safecenter",
        "com.iqoo.secure", "com.vivo.permissionmanager", "com.vivo.safecenter",
        "com.huawei.systemmanager", "com.hihonor.systemmanager", "com.huawei.securitymgr",
        "com.android.settings.intelligence",
    )

    /** Settings apps: protected only on the pages whose title matches [titleWords]. */
    val settingsPackages: Set<String> = setOf(
        "com.android.settings", "com.miui.settings", "com.coloros.settings", "com.oplus.settings",
        "com.vivo.settings", "com.huawei.settings", "com.hihonor.settings", "com.samsung.android.settings",
    )

    val titleWords: List<String> = listOf(
        // l10n:keep external UI matcher
        "无障碍", "辅助功能", "开发者选项", "开发人员选项", "设备管理", "权限", "安装未知应用", "未知来源",
        // l10n:keep external UI matcher
        "安装外部来源", "特殊应用权限", "锁屏", "屏幕锁定", "密码", "指纹", "人脸", "生物识别", "安全", "隐私",
        "Accessibility", "Developer options", "Device admin", "Permission", "Install unknown", "Security", "Privacy",
    )

    /**
     * [titles]: the window title and [titleTexts] — where Settings names the page it shows.
     */
    fun isProtected(packageName: String?, titles: List<String>): Boolean {
        if (packageName == null) return false
        if (packageName in packages) return true
        if (packageName !in settingsPackages) return false
        return titles.any { t -> titleWords.any { t.contains(it, ignoreCase = true) } }
    }

    /**
     * Where a page names itself: headings in the top 30% of the screen, and texts in the top
     * 20% that are not inside a scrollable (list rows scrolled up under the toolbar are
     * content, not the title — Settings' home list says "Security & privacy" there).
     */
    fun titleTexts(root: UiNode?, screenHeight: Int, limit: Int = 8): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: UiNode, inScroll: Boolean) {
            if (out.size >= limit) return
            val text = (n.text ?: n.desc)?.takeIf { it.isNotBlank() && !n.editable }
            if (text != null && ((n.heading && n.bounds.centerY < screenHeight * 0.3) || (!inScroll && n.bounds.centerY < screenHeight * 0.2))) out += text
            n.children.forEach { walk(it, inScroll || n.scrollable) }
        }
        root?.let { walk(it, false) }
        return out
    }
}
