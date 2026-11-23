package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class PluginScriptProcessTest {
    @Test public void rootArgvDoesNotContainAssetsOrGrowWithPayload() {
        String[] argv = PluginScriptProcess.argv("/data/adb/dsha/rootfs");
        assertArrayEquals(new String[]{"su", "-mm", "-c"}, new String[]{argv[0], argv[1], argv[2]});
        assertTrue(argv[3].endsWith("/bin/bash -s"));
        for (String argument : argv) assertTrue(argument.getBytes(StandardCharsets.UTF_8).length < 4096);
        assertFalse(argv[3].contains("base64"));
    }

    @Test public void realAssetsAboveAndroidArgLimitDeployAndReturnNonEmptyListViaStdin() throws Exception {
        Path assets = Path.of(System.getProperty("dsha.repo", "."), "app/src/main/assets");
        Path root = Files.createTempDirectory("plugin-real-assets-stdin-");
        Path home = Files.createDirectories(root.resolve("root/.dsh"));
        Path profile = Files.createDirectories(home.resolve("profiles/web"));
        String manifest = "{\"dependencies\":{\"dsh-visible\":\"1\"},\"dsh\":{\"profile\":{\"bundles\":[\"dsh-visible\"]}}}";
        Files.writeString(profile.resolve("package.json"), manifest);
        Path plugin = Files.createDirectories(profile.resolve("node_modules/dsh-visible"));
        Files.writeString(plugin.resolve("package.json"), "{\"name\":\"dsh-visible\",\"version\":\"1\",\"dsh\":{\"bundle\":{\"patch\":\"patch.yml\"}}}");
        String script = PluginScriptDeployment.command(home.toString(),
                Files.readString(assets.resolve(PluginScriptDeployment.MANAGER)),
                Files.readString(assets.resolve(PluginScriptDeployment.REGISTER)),
                Files.readString(assets.resolve(PluginScriptDeployment.LIFECYCLE)), "list");
        assertTrue("Regression fixture must exceed Android's old single-argument limit",
                script.getBytes(StandardCharsets.UTF_8).length > 131072);
        ProcessBuilder runner = new ProcessBuilder("/bin/bash", "-s").redirectErrorStream(true);
        runner.environment().put("DSHA_TEST_ROOT", root.toString());
        runner.environment().put("DSH_HOME", "/root/.dsh");
        String output = PluginScriptProcess.exchange(runner.start(), script, 15000);
        String result = PluginOutput.resultJson(output);
        assertTrue(output, result.contains("\"status\": \"ok\""));
        assertTrue(output, result.contains("\"name\": \"dsh-visible\""));
        assertEquals(manifest, Files.readString(profile.resolve("package.json")));
    }

    @Test public void earlyShellFailurePreservesDiagnosticEvenWhenStdinBreaks() throws Exception {
        String script = "printf 'ERROR: intentional deployment failure\\n' >&2\nexit 7\n#" + "x".repeat(2 * 1024 * 1024);
        String output = PluginScriptProcess.exchange(new ProcessBuilder("/bin/bash", "-s")
                .redirectErrorStream(true).start(), script, 5000);
        assertTrue(output, output.contains("ERROR: intentional deployment failure"));
    }

    @Test public void nonzeroExitWithoutResultIsNotReportedAsAnEmptySuccess() throws Exception {
        try {
            PluginScriptProcess.exchange(new ProcessBuilder("/bin/bash", "-s")
                    .redirectErrorStream(true).start(), "exit 7", 5000);
            fail("Empty failure accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("退出码 7"));
        }
    }

    @Test public void timeoutStopsOnlyTheOwnedInvocation() throws Exception {
        Process owned = new ProcessBuilder("/bin/bash", "-s").redirectErrorStream(true).start();
        try {
            PluginScriptProcess.exchange(owned, "sleep 0.5", 50);
            fail("Timeout accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("超时"));
        }
        assertTrue(owned.waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test public void blockedWriterCleanupCannotHoldCallerPastTimeout() throws Exception {
        BlockedPipeProcess process = new BlockedPipeProcess();
        java.util.concurrent.FutureTask<String> call = new java.util.concurrent.FutureTask<>(
                () -> PluginScriptProcess.exchange(process, "x".repeat(2 * 1024 * 1024), 100));
        Thread thread = new Thread(call, "bounded-stdin-test");
        thread.setDaemon(true);
        thread.start();
        try {
            assertTrue(process.writing.await(2, java.util.concurrent.TimeUnit.SECONDS));
            try {
                call.get(2, java.util.concurrent.TimeUnit.SECONDS);
                fail("Expected transport timeout");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause().getMessage().contains("超时"));
            }
        } finally {
            process.releaseWriter.countDown();
            process.destroy();
        }
    }

    /** Model an uninterruptible pipe write holding the same lock required by close(). */
    private static final class BlockedPipeProcess extends Process {
        final java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseWriter = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch exited = new java.util.concurrent.CountDownLatch(1);
        volatile boolean alive = true;
        final java.io.OutputStream output = new java.io.OutputStream() {
            @Override public synchronized void write(int ignored) {
                writing.countDown();
                boolean released = false;
                while (!released) {
                    try { releaseWriter.await(); released = true; }
                    catch (InterruptedException ignoredInterrupt) { /* Kernel pipe write is not interruptible. */ }
                }
            }
            @Override public synchronized void write(byte[] bytes, int offset, int length) { write(0); }
            @Override public synchronized void close() { }
        };
        final java.io.InputStream input = new java.io.InputStream() {
            @Override public int read() throws IOException {
                try { exited.await(); return -1; }
                catch (InterruptedException error) { throw new IOException(error); }
            }
        };
        @Override public java.io.OutputStream getOutputStream() { return output; }
        @Override public java.io.InputStream getInputStream() { return input; }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { exited.await(); return 1; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 1; }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { alive = false; exited.countDown(); }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }
}
