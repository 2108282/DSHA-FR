package com.deepseekharness.app.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.StrictMode;

import com.deepseekharness.app.core.FreezeLogRepository;
import com.deepseekharness.app.core.FreezeWhitelistRepository;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** 与 SSH 配置入口相同：Root am 打开具体文件，失败后回退系统应用选择器。 */
public final class RootFileLocation {
    private RootFileLocation() {}

    public static boolean isSupported(String absoluteFile) {
        return FreezeLogRepository.PATH.equals(absoluteFile)
                || FreezeLogRepository.FALLBACK_PATH.equals(absoluteFile)
                || FreezeWhitelistRepository.PATH.equals(absoluteFile);
    }

    static String rootOpenCommand(String absoluteFile) {
        if (!isSupported(absoluteFile)) throw new IllegalArgumentException("不支持的冻结文件位置");
        return "am start -a android.intent.action.VIEW -d "
                + ShellQuote.arg("file://" + absoluteFile) + " -t " + ShellQuote.arg("*/*") + " 2>&1";
    }

    /** 在后台线程调用；返回 true 只表示系统接受了启动请求。 */
    public static boolean openWithRoot(File cacheDir, String absoluteFile) {
        if (!isSupported(absoluteFile)) return false;
        File output = null;
        Process process = null;
        try {
            output = File.createTempFile("freeze-open-", ".txt", cacheDir);
            process = new ProcessBuilder("su", "-c", rootOpenCommand(absoluteFile))
                    .redirectErrorStream(true).redirectOutput(output).start();
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) return false;
            byte[] bytes = new byte[8192];
            int size = 0, count;
            try (java.io.InputStream stream = new java.io.FileInputStream(output)) {
                while (size < bytes.length && (count = stream.read(bytes, size, bytes.length - size)) != -1) size += count;
            }
            String result = new String(bytes, 0, size, java.nio.charset.StandardCharsets.UTF_8);
            return !result.contains("Error:") && !result.contains("Exception")
                    && (result.contains("Starting: Intent") || result.contains("Warning: Activity not started"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroy();
            if (output != null) output.delete();
        }
    }

    /** Root 启动失败时在主线程回退；文件和 MIME 与 SSH 配置入口一致。 */
    public static boolean open(Context context, String absoluteFile) {
        if (context == null || !isSupported(absoluteFile)) return false;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(new File(absoluteFile)), "*/*");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            Intent chooser = Intent.createChooser(intent, "选择外部应用打开");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            StrictMode.VmPolicy previous = StrictMode.getVmPolicy();
            try {
                StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
                context.startActivity(chooser);
            } finally {
                StrictMode.setVmPolicy(previous);
            }
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
