package com.deepseekharness.app.util;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** Pure JVM integration tests; all shell writes stay under java.io.tmpdir, never the real rootfs. */
public class PluginScriptDeploymentTest {
    private static final String MANAGER = "import importlib.util, pathlib, sys\n"
            + "def load(name):\n"
            + " p = pathlib.Path(__file__).parent / name\n"
            + " spec = importlib.util.spec_from_file_location(name, p)\n"
            + " module = importlib.util.module_from_spec(spec)\n"
            + " spec.loader.exec_module(module)\n"
            + " return module.VALUE\n"
            + "common = load('register-builtin-plugins.py')\n"
            + "if sys.argv[1:2] == ['wait']:\n"
            + " print('READY', flush=True)\n"
            + " sys.stdin.readline()\n"
            + "print('PLUGIN_RESULT: ' + common + '|' + load('plugin-lifecycle.py'), flush=True)\n";
    private static final String REGISTER = "VALUE = 'register-new'\n";
    private static final String LIFECYCLE = "VALUE = 'lifecycle-new'\n";

    @Test public void everyAssetIsRequiredBeforeBuildingACommand() {
        String[] scripts = {MANAGER, REGISTER, LIFECYCLE};
        String[] names = {PluginScriptDeployment.MANAGER, PluginScriptDeployment.REGISTER,
                PluginScriptDeployment.LIFECYCLE};
        for (int i = 0; i < scripts.length; i++) {
            for (String missing : new String[]{null, "", " \n\t"}) {
                String[] values = scripts.clone();
                values[i] = missing;
                try {
                    PluginScriptDeployment.command("/unused", values[0], values[1], values[2], "");
                    fail("Missing " + names[i] + " accepted");
                } catch (IllegalArgumentException expected) {
                    assertEquals("ASSET_MISSING:" + names[i], expected.getMessage());
                }
            }
        }
    }

    @Test public void publishesAllScriptsAndReusesVerifiedGeneration() throws Exception {
        Path home = newHome();
        Result first = run(command(home, MANAGER, REGISTER, LIFECYCLE, ""), null);
        assertEquals(first.output, 0, first.exit);
        assertTrue(first.output.contains("PLUGIN_RESULT: register-new|lifecycle-new"));
        assertEquals(MANAGER, Files.readString(home.resolve(PluginScriptDeployment.MANAGER)));
        assertEquals(REGISTER, Files.readString(home.resolve(PluginScriptDeployment.REGISTER)));
        assertEquals(LIFECYCLE, Files.readString(home.resolve(PluginScriptDeployment.LIFECYCLE)));
        Result canonical = run("python3 -B " + ShellQuote.arg(
                home.resolve(PluginScriptDeployment.MANAGER).toString()), null);
        assertEquals(canonical.output, 0, canonical.exit);
        assertTrue(canonical.output.contains("PLUGIN_RESULT: register-new|lifecycle-new"));
        Path generation = group(home);
        assertEquals(0, run(command(home, MANAGER, REGISTER, LIFECYCLE, null), null).exit);
        assertEquals(generation, group(home));
        try (Stream<Path> entries = Files.list(home)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().contains("stage.")
                    || p.getFileName().toString().contains("publish.")));
        }
    }

    @Test public void replacesCanonicalInodeAndSymlinkWithoutTruncatingOldReaders() throws Exception {
        Path home = newHome();
        Path manager = home.resolve(PluginScriptDeployment.MANAGER);
        Files.writeString(manager, "old-manager");
        Path target = home.resolve("old-lifecycle-target.py");
        Files.writeString(target, "old-lifecycle");
        Path lifecycle = home.resolve(PluginScriptDeployment.LIFECYCLE);
        Files.createSymbolicLink(lifecycle, target);
        try (InputStream oldReader = Files.newInputStream(manager)) {
            Result result = run(command(home, MANAGER, REGISTER, LIFECYCLE, ""), null);
            assertEquals(result.output, 0, result.exit);
            assertEquals("old-manager", new String(oldReader.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("old-lifecycle", Files.readString(target));
        assertFalse(Files.isSymbolicLink(lifecycle));
        assertEquals(LIFECYCLE, Files.readString(lifecycle));
    }

    @Test public void decodeFailureStopsBeforeCanonicalPublicationAndExecution() throws Exception {
        Path home = newHome();
        seedOldCanonical(home);
        Path bin = fakeTool(home, "base64", "printf 'decode failed\\n' >&2\nexit 1\n");
        Result result = run(command(home, MANAGER, REGISTER, LIFECYCLE, ""), bin);
        assertFailure(result);
        assertOldCanonical(home);
    }

    @Test public void checksumMismatchStopsEvenWhenDecoderReportsSuccess() throws Exception {
        Path home = newHome();
        seedOldCanonical(home);
        Path bin = fakeTool(home, "base64", "printf 'corrupted bytes'\n");
        Result result = run(command(home, MANAGER, REGISTER, LIFECYCLE, ""), bin);
        assertFailure(result);
        assertOldCanonical(home);
    }

    @Test public void syntaxFailureDoesNotPublishOrExecuteOldLifecycle() throws Exception {
        Path home = newHome();
        seedOldCanonical(home);
        Result result = run(command(home, MANAGER, REGISTER, "def broken(:\n", ""), null);
        assertFailure(result);
        assertTrue(result.output.contains("SyntaxError"));
        assertOldCanonical(home);
    }

    @Test public void canonicalPublishFailureNeverRunsThePreparedManager() throws Exception {
        Path home = newHome();
        seedOldCanonical(home);
        Path bin = fakeTool(home, "mv", "for last do :; done\n"
                + "case \"$last\" in */plugin-lifecycle.py) exit 1;; esac\n"
                + "exec /usr/bin/mv \"$@\"\n");
        Result result = run(command(home, MANAGER, REGISTER, LIFECYCLE, ""), bin);
        assertFailure(result);
        assertEquals("old-" + PluginScriptDeployment.LIFECYCLE,
                Files.readString(home.resolve(PluginScriptDeployment.LIFECYCLE)));
        // Canonical publication is intentionally only per-file atomic, not one group transaction.
        assertEquals(MANAGER, Files.readString(home.resolve(PluginScriptDeployment.MANAGER)));
    }

    @Test public void damagedCachedGroupIsRejectedInsteadOfReadingCanonicalFallback() throws Exception {
        Path home = newHome();
        assertEquals(0, run(command(home, MANAGER, REGISTER, LIFECYCLE, ""), null).exit);
        Path cachedLifecycle = group(home).resolve(PluginScriptDeployment.LIFECYCLE);
        assertTrue(cachedLifecycle.toFile().setWritable(true, true));
        Files.writeString(cachedLifecycle, "VALUE = 'damaged'\n");
        Result result = run(command(home, MANAGER, REGISTER, LIFECYCLE, ""), null);
        assertFailure(result);
    }

    @Test public void concurrentLazyImportKeepsItsOwnGroupAfterCanonicalUpgrade() throws Exception {
        Path home = newHome();
        String oldRegister = "VALUE = 'register-old'\n";
        String oldLifecycle = "VALUE = 'lifecycle-old'\n";
        Process old = start(command(home, MANAGER, oldRegister, oldLifecycle, "wait"), null);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                old.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("READY", reader.readLine());
            Result upgraded = run(command(home, MANAGER, REGISTER, LIFECYCLE, ""), null);
            assertEquals(upgraded.output, 0, upgraded.exit);
            assertTrue(upgraded.output.contains("register-new|lifecycle-new"));
            old.getOutputStream().write('\n');
            old.getOutputStream().close();
            assertEquals("PLUGIN_RESULT: register-old|lifecycle-old", reader.readLine());
            assertTrue(old.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, old.exitValue());
        }
    }

    private static String command(Path home, String manager, String register, String lifecycle,
                                  String args) {
        return PluginScriptDeployment.command(home.toString(), manager, register, lifecycle, args);
    }

    private static Path newHome() throws Exception {
        // Spaces and apostrophes exercise shell path quoting as well as same-filesystem staging.
        return Files.createDirectories(Files.createTempDirectory("plugin-deployment-test-")
                .resolve("home with ' quote"));
    }

    private static Path group(Path home) throws Exception {
        try (Stream<Path> entries = Files.list(home)) {
            Path[] groups = entries.filter(p -> p.getFileName().toString()
                    .matches("\\.plugin-scripts-[0-9a-f]{64}")).toArray(Path[]::new);
            assertEquals(1, groups.length);
            return groups[0];
        }
    }

    private static void seedOldCanonical(Path home) throws Exception {
        Files.createDirectories(home);
        for (String name : new String[]{PluginScriptDeployment.MANAGER,
                PluginScriptDeployment.REGISTER, PluginScriptDeployment.LIFECYCLE}) {
            Files.writeString(home.resolve(name), "old-" + name);
        }
    }

    private static void assertOldCanonical(Path home) throws Exception {
        for (String name : new String[]{PluginScriptDeployment.MANAGER,
                PluginScriptDeployment.REGISTER, PluginScriptDeployment.LIFECYCLE}) {
            assertEquals("old-" + name, Files.readString(home.resolve(name)));
        }
    }

    private static Path fakeTool(Path home, String name, String body) throws Exception {
        Path bin = Files.createDirectories(home.resolve("fake-bin"));
        Path script = Files.writeString(bin.resolve(name), "#!/bin/sh\n" + body);
        assertTrue(script.toFile().setExecutable(true));
        return bin;
    }

    private static Process start(String command, Path bin) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", "-c", command).redirectErrorStream(true);
        if (bin != null) builder.environment().put("PATH", bin + ":" + builder.environment().get("PATH"));
        return builder.start();
    }

    private static Result run(String command, Path bin) throws Exception {
        Process process = start(command, bin);
        process.getOutputStream().close();
        assertTrue("Isolated deployment command timed out", process.waitFor(15, TimeUnit.SECONDS));
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
    }

    private static void assertFailure(Result result) {
        assertNotEquals(result.output, 0, result.exit);
        assertTrue(result.output, result.output.contains("ERROR: 插件脚本部署失败"));
        assertFalse(result.output, result.output.contains("PLUGIN_RESULT:"));
    }

    private static final class Result {
        final int exit;
        final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
    }
}
