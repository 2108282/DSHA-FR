package com.deepseekharness.app.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 将保存的确认开关同步到原生 chroot；所有调用方共用一个串行写入队列。 */
public final class GuardSettings {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sync-guard-settings");
        thread.setDaemon(true);
        return thread;
    });

    private static final java.util.concurrent.atomic.AtomicBoolean INITIALIZED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private GuardSettings() {}

    public static void initialize(Context context, ConfigStore config) {
        if (INITIALIZED.compareAndSet(false, true)) sync(context, config);
    }

    public static Future<Boolean> sync(Context context, ConfigStore config) {
        return enqueue(context, config, null, null);
    }

    public static Future<Boolean> setEnabled(Context context, ConfigStore config, boolean enabled,
                                             java.util.function.Consumer<Boolean> onComplete) {
        // SharedPreferences.apply 更新内存值是同步的。与入队共用锁，页面重建也不能乱序。
        return enqueue(context, config, enabled, onComplete);
    }

    private static synchronized Future<Boolean> enqueue(Context context, ConfigStore config,
                                                         Boolean enabled,
                                                         java.util.function.Consumer<Boolean> onComplete) {
        if (enabled != null) config.setConfirmShell(enabled);
        File privateFlag = new File(context.getApplicationContext().getFilesDir(), ".guard_enabled");
        return enqueueLatest(IO, config::isConfirmShell, latest -> {
            boolean privateOk = true;
            try {
                writePrivateFlag(privateFlag, latest);
            } catch (IOException e) {
                privateOk = false;
                Log.w("DSHA", "同步私有危险命令确认设置失败", e);
            }
            boolean rootOk = syncRoot(latest);
            boolean success = privateOk && rootOk;
            if (onComplete != null) onComplete.accept(success);
            return success;
        });
    }

    interface FlagWriter {
        boolean write(boolean enabled);
    }

    static Future<Boolean> enqueueLatest(ExecutorService executor,
                                         java.util.function.BooleanSupplier current,
                                         FlagWriter writer) {
        // 在执行时读取最新值，排队中的初始化/旧切换不能重新写入过期的快照。
        return executor.submit(() -> writer.write(current.getAsBoolean()));
    }

    static void writePrivateFlag(File flag, boolean enabled) throws IOException {
        File parent = flag.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("无法创建确认设置目录");
        }
        File temporary = new File(parent, flag.getName() + ".app-tmp");
        Files.write(temporary.toPath(), (enabled ? "1\n" : "0\n").getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), flag.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean syncRoot(boolean enabled) {
        try {
            Process process = new ProcessBuilder("su", "-c", rootCommand(enabled))
                    .redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
            // 不积压输出；Root 授权异常最多等待 15 秒，避免同步队列和启动永久阻塞。
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Log.w("DSHA", "同步危险命令确认设置超时");
                return false;
            }
            int exit = process.exitValue();
            if (exit != 0) Log.w("DSHA", "同步危险命令确认设置失败，su exit=" + exit);
            return exit == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w("DSHA", "同步危险命令确认设置失败", e);
            return false;
        }
    }

    static String rootCommand(boolean enabled) {
        String value = enabled ? "1" : "0";
        // 先更新权威 rootfs 标志，再更新共享镜像；临时文件与目标同目录，读者始终看到完整 0/1。
        // 未安装模块时不可创建伪 rootfs，保留镜像让安装后启动同步补齐。
        return "write_guard() { p=\"$1\"; mkdir -p \"${p%/*}\" && "
                + "printf '%s\\n' '" + value + "' > \"$p.app-tmp\" && "
                + "chmod 644 \"$p.app-tmp\" && mv -f \"$p.app-tmp\" \"$p\"; }; "
                + "root_ok=0; if [ -d /data/adb/dsha/rootfs/root ]; then "
                + "write_guard /data/adb/dsha/rootfs/root/.dsh/.guard_enabled || root_ok=1; fi; "
                + "write_guard /sdcard/Download/DSHA/.guard_enabled || root_ok=1; "
                + "exit \"$root_ok\"";
    }
}
