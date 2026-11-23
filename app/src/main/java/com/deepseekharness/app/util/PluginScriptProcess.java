package com.deepseekharness.app.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Plugin-only transport: assets belong in stdin, never in Android's size-limited su argv. */
public final class PluginScriptProcess {
    private PluginScriptProcess() { }

    public static String[] argv(String rootfs) {
        String command = "chroot " + ShellQuote.arg(rootfs) + " /usr/bin/env -i "
                + "HOME=/root USER=root LOGNAME=root "
                + "PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
                + "TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 /bin/bash -s";
        return new String[]{"su", "-mm", "-c", command};
    }

    /** Caller supplies a process with PIPE stdin and merged stderr; reads and writes concurrently. */
    public static String exchange(Process process, String script, long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        FutureTask<String> reader = new FutureTask<>(() -> read(process.getInputStream()));
        FutureTask<Void> writer = new FutureTask<>(() -> {
            try (OutputStream out = process.getOutputStream()) {
                out.write((script + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        start(reader, "plugin-script-read");
        start(writer, "plugin-script-write");
        try {
            String output = reader.get(remaining(deadline), TimeUnit.NANOSECONDS);
            try {
                writer.get(remaining(deadline), TimeUnit.NANOSECONDS);
            } catch (ExecutionException writeError) {
                // An early shell/deployment error may close stdin. Preserve its actual diagnostic.
                if (output.contains("ERROR:") || output.contains("PLUGIN_RESULT: ")) return output;
                throw new IOException("插件脚本输入失败：" + output, writeError.getCause());
            }
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException();
            }
            if (process.exitValue() != 0 && output.trim().isEmpty()) {
                throw new IOException("插件脚本未返回结果，退出码 " + process.exitValue());
            }
            return output;
        } catch (TimeoutException error) {
            throw new IOException("插件脚本等待超时，操作结果未知；请先刷新核对状态，勿直接重复安装", error);
        } catch (ExecutionException error) {
            throw new IOException("插件脚本输出读取失败", error.getCause());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("插件操作被中断", error);
        } finally {
            reader.cancel(true);
            writer.cancel(true);
            // destroy/close may wait on a writer's stream lock while a child retains the pipe.
            // Keep cleanup off the calling thread so even stdin backpressure cannot exceed timeout.
            FutureTask<Void> cleanup = new FutureTask<>(() -> {
                if (process.isAlive()) process.destroyForcibly();
                close(process.getOutputStream());
                close(process.getInputStream());
                return null;
            });
            start(cleanup, "plugin-script-cleanup");
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException();
        return nanos;
    }

    private static void start(FutureTask<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        final int limit = 256 * 1024;
        while ((count = input.read(buffer)) != -1) {
            int keep = Math.min(count, limit - output.size());
            if (keep > 0) output.write(buffer, 0, keep);
        }
        return output.toString("UTF-8");
    }

    private static void close(java.io.Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }
}
