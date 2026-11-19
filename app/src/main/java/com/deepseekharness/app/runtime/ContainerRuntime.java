package com.deepseekharness.app.runtime;

import java.io.File;

/**
 * 容器运行时抽象：把「怎么进 rootfs 执行命令」独立出来。
 *
 * <ul>
 *   <li>{@link KsuChroot} —— KernelSU / Magisk 原生 Linux chroot（零损耗）。</li>
 * </ul>
 */
public interface ContainerRuntime {

    String id();
    String displayName();

    /** KernelSU / Magisk 原生 Linux chroot 运行时（0 虚拟化损耗，极致省电） */
    class KsuChroot implements ContainerRuntime {

        @Override public String id() { return "ksu_chroot"; }

        @Override public String displayName() { return "KernelSU / Magisk 原生 Chroot（零损耗）"; }

        public static String findSuBinary() {
            String envPath = System.getenv("PATH");
            if (envPath != null) {
                for (String p : envPath.split(":")) {
                    File f = new File(p.trim(), "su");
                    if (f.exists() && f.canExecute()) {
                        return f.getAbsolutePath();
                    }
                }
            }
            String[] candidates = {
                    "/product/bin/su",
                    "/data/adb/ksu/bin/su",
                    "/data/adb/ap/bin/su",
                    "/data/adb/magisk/su",
                    "/system/bin/su",
                    "/system/xbin/su",
                    "/sbin/su",
                    "/vendor/bin/su"
            };
            for (String p : candidates) {
                try {
                    File f = new File(p);
                    if (f.exists() && f.canExecute()) return p;
                } catch (Throwable ignored) {}
            }
            return "su";
        }

        public static boolean checkAvailable() {
            // 按模块托管约定直接返回就绪；此处不探测模块文件或 Root 授权状态。
            // 保持无 su 调用，避免冷启动和页面刷新触发 Root 权限申请。
            return true;
        }
    }
}
