package com.deepseekharness.app.util;

/** 将插件管理器返回的实际安装位置转换为 Android 宿主可见路径。 */
public final class PluginInstallPath {
    private static final String ROOTFS = "/data/adb/dsha/rootfs";
    private static final String UNRESOLVED = "未解析到实际安装位置";

    private PluginInstallPath() { }

    public static String display(String installPath) {
        if (installPath == null || installPath.isEmpty() || !installPath.startsWith("/")) {
            return UNRESOLVED;
        }
        if (installPath.equals(ROOTFS) || installPath.startsWith(ROOTFS + "/")) {
            return installPath;
        }
        return ROOTFS + installPath;
    }
}
