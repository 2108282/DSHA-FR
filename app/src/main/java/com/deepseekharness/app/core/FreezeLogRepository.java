package com.deepseekharness.app.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** 直接访问宿主冻结日志，核心休眠或未启动时也可使用。 */
public final class FreezeLogRepository {
    private static final int MAX_LOG_BYTES = 64 * 1024;
    public static final String PATH = "/data/adb/dsha/run/idle-freezer.log";
    public static final String FALLBACK_PATH = "/data/adb/dsha/rootfs/root/.dsh/idle-freezer.log";
    // 宿主 run 中可保留指向 rootfs 的日志软链接；只允许解析到这两个既定目标。
    private static final String SELECT_LOG =
            "LOG=/data/adb/dsha/run/idle-freezer.log; "
            + "if [ ! -e \"$LOG\" ] && [ ! -L \"$LOG\" ] && [ -f /data/adb/dsha/rootfs/root/.dsh/idle-freezer.log ]; then "
            + "LOG=/data/adb/dsha/rootfs/root/.dsh/idle-freezer.log; fi; "
            + "REAL=$(readlink -f \"$LOG\") || { echo '无法解析冻结日志位置' >&2; exit 1; }; "
            + "case \"$REAL\" in /data/adb/dsha/run/idle-freezer.log|/data/adb/dsha/rootfs/root/.dsh/idle-freezer.log) ;; "
            + "*) echo '冻结日志目标异常，拒绝访问' >&2; exit 1 ;; esac; "
            + "[ ! -L \"$REAL\" ] || { echo '冻结日志目标发生变化' >&2; exit 1; }; "
            + "[ ! -e \"$REAL\" ] || [ -f \"$REAL\" ] || { echo '冻结日志目标不是普通文件' >&2; exit 1; }; "
            + "LOG=\"$REAL\"; ";

    private FreezeLogRepository() {}

    static String readCommand() {
        return SELECT_LOG + "if [ -f \"$LOG\" ]; then tail -c " + MAX_LOG_BYTES
                + " \"$LOG\"; else printf '暂无冻结日志'; fi";
    }

    static String clearCommand() {
        // 截断原文件，保留路径与权限；后续日志仍由守护脚本正常追加。
        return SELECT_LOG + "if [ -f \"$LOG\" ]; then : > \"$LOG\"; fi";
    }

    static String locationCommand() {
        return SELECT_LOG + "[ -d \"${LOG%/*}\" ] || { echo '冻结日志目录不存在' >&2; exit 1; }; printf '%s' \"$LOG\"";
    }

    static String deleteCommand() {
        // 删除解析后的日志本体，保留 run 中既有软链接；下一次 >> 会重新建立日志本体。
        return SELECT_LOG + "rm -f -- \"$LOG\"";
    }

    /** 返回宿主真实文件位置，不创建副本。 */
    public static String location(File cacheDir) throws IOException, InterruptedException {
        String result = execute(cacheDir, locationCommand());
        if (!PATH.equals(result) && !FALLBACK_PATH.equals(result)) throw new IOException("冻结日志返回了异常位置");
        return result;
    }

    public static void delete(File cacheDir) throws IOException, InterruptedException {
        execute(cacheDir, deleteCommand());
    }

    public static String read(File cacheDir) throws IOException, InterruptedException {
        String text = execute(cacheDir, readCommand());
        return text.isEmpty() ? "冻结日志为空" : text;
    }

    public static void clear(File cacheDir) throws IOException, InterruptedException {
        execute(cacheDir, clearCommand());
    }

    private static String execute(File cacheDir, String command) throws IOException, InterruptedException {
        File output = File.createTempFile("freeze-log-", ".txt", cacheDir);
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).redirectOutput(output).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new IOException("操作超时，请检查 Root 授权");
            }
            if (process.exitValue() != 0) {
                throw new IOException("无法访问冻结日志，请检查 Root 权限（退出码 " + process.exitValue() + "）");
            }
            // 命令已限制输出；再次限制读取，避免意外输出占用过多界面内存。
            try (java.io.InputStream input = Files.newInputStream(output.toPath())) {
                byte[] bytes = new byte[MAX_LOG_BYTES];
                int size = 0;
                int count;
                while (size < bytes.length && (count = input.read(bytes, size, bytes.length - size)) != -1) {
                    size += count;
                }
                return new String(bytes, 0, size, StandardCharsets.UTF_8);
            }
        } finally {
            if (process != null && process.isAlive()) process.destroy();
            output.delete();
        }
    }
}
