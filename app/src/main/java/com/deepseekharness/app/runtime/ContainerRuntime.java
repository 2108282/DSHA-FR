package com.deepseekharness.app.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    boolean available();
    String unavailableReason();

    /** 组装进入 rootfs 的命令前缀（不含最终要跑的 /bin/bash …）。 */
    List<String> baseArgv(File rootfsDir, boolean hardlinkSupported);

    /** 设置进程环境（LD_LIBRARY_PATH、TMPDIR 之类）。 */
    void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir);

    /** 首次使用前的准备。抛异常表示失败，调用方应回退。 */
    void prepare() throws Exception;

    // ==================================================================

    /**
     * 容器 bind 列表。
     * Bundled Termux Python 要用 Android 的 linker 和 APEX 库，所以映射 /system、/apex。
     *
     * <p>注意：<b>不</b>映射 /linkerconfig —— app 进程对它无权限（selinux），
     * proot 每次启动都会打 {@code can't sanitize binding "/linkerconfig": Permission denied}，
     * 污染终端/日志；而且 bind 失败 = 从没绑上，映射它是纯负收益。
     */

    /** KernelSU / Magisk 原生 Linux chroot 运行时（0 虚拟化损耗，极致省电） */
    class KsuChroot implements ContainerRuntime {
        private final Context ctx;
        private static volatile Boolean sCachedAvailable = null;

        public KsuChroot(Context ctx) {
            this.ctx = ctx;
        }

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
            // 原生 chroot 模式下环境由底层 Magisk/KernelSU 模块保证，默认常驻就绪
            // 严禁在此处调用任何 su 进程命令，彻底杜绝冷启动与页面刷新时疯狂弹出 Root 权限申请
            return true;
        }

        @Override public boolean available() {
            return checkAvailable();
        }

        @Override public String unavailableReason() {
            return "未检测到 /data/adb/dsha 模块环境或未授予 Root 权限，请在 KernelSU/Magisk 中刷入 DSHA 原生模块并授权";
        }

        @Override public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported) {
            List<String> argv = new ArrayList<>();
            argv.add(findSuBinary());
            argv.add("-c");
            argv.add("/data/adb/dsha/scripts/term.sh");
            return argv;
        }

        @Override public void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir) {
            // 原生环境不需要 LD_PRELOAD 变量
        }

        @Override public void prepare() throws Exception {
            // 原生环境由模块统一管理，免去每次 prepare 过程中的冗余 su 拷脚本开销
        }
    }

    String[][] BINDS = {
            {"/dev"},
            {"/dev/urandom", "/dev/random"},
            {"/proc"},
            {"/sys"},
            {"/system"},
            {"/apex"},
            {"/proc/self/fd", "/dev/fd"},
            {"/storage/emulated/0", "/sdcard"},
            {"/storage/emulated/0", "/storage/emulated/0"},
    };
}
