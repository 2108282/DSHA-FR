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
            // 1. 内存命中：只要本进程此前成功验证过，永久有效，绝对不在生命周期内反复触发 su 弹窗
            if (sCachedAvailable != null && sCachedAvailable) {
                return true;
            }

            // 2. 桥状态免 Root 探测：如果 3095 桥已经常驻，环境必然已就绪
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", 3095), 80);
                sCachedAvailable = true;
                return true;
            } catch (Throwable ignored) {}

            // 3. 宿主模块特征免 Root 探测：如果宿主模块文件/软链存在，直接视为就绪
            File moduleProp = new File("/data/adb/modules/dsha_native/module.prop");
            if (moduleProp.exists() && moduleProp.canRead()) {
                sCachedAvailable = true;
                return true;
            }

            // 4. 仅作为最后的冷启动兜底检测，验证成功后永久缓存在内存
            try {
                String su = findSuBinary();
                Process p = Runtime.getRuntime().exec(new String[]{su, "-c", "test -f /data/adb/dsha/scripts/start.sh"});
                boolean ok = (p.waitFor() == 0);
                sCachedAvailable = ok;
                return ok;
            } catch (Throwable e) {
                sCachedAvailable = false;
                return false;
            }
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
            try {
                String su = findSuBinary();
                String syncCmd = "if [ -d /sdcard/Download/DSHA/scripts ]; then "
                        + "cp -f /sdcard/Download/DSHA/scripts/*.sh /data/adb/dsha/scripts/ 2>/dev/null; "
                        + "fi; "
                        + "chmod 755 /data/adb/dsha/scripts/*.sh 2>/dev/null";
                Process p = Runtime.getRuntime().exec(new String[]{su, "-c", syncCmd});
                p.waitFor();
            } catch (Throwable ignored) {
            }
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
