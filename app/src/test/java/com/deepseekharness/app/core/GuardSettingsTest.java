package com.deepseekharness.app.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.*;

public class GuardSettingsTest {
    @Test public void queuedInitializationsAndRapidTogglesUseLatestValue() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean(true);
        AtomicInteger active = new AtomicInteger();
        List<Boolean> writes = Collections.synchronizedList(new ArrayList<>());
        try {
            Future<Boolean> first = GuardSettings.enqueueLatest(io, saved::get, enabled -> {
                assertEquals(1, active.incrementAndGet());
                entered.countDown();
                try {
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    writes.add(enabled);
                    return true;
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                } finally {
                    active.decrementAndGet();
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            saved.set(false);
            Future<Boolean> toggleOff = GuardSettings.enqueueLatest(io, saved::get, enabled -> {
                assertEquals(0, active.get());
                writes.add(enabled);
                return true;
            });
            saved.set(true);
            Future<Boolean> oldInitialization = GuardSettings.enqueueLatest(io, saved::get, enabled -> {
                writes.add(enabled);
                return true;
            });
            saved.set(false);
            Future<Boolean> startup = GuardSettings.enqueueLatest(io, saved::get, enabled -> {
                writes.add(enabled);
                return true;
            });
            release.countDown();
            assertTrue(first.get(3, TimeUnit.SECONDS));
            assertTrue(toggleOff.get(3, TimeUnit.SECONDS));
            assertTrue(oldInitialization.get(3, TimeUnit.SECONDS));
            assertTrue(startup.get(3, TimeUnit.SECONDS));
            assertEquals(java.util.Arrays.asList(true, false, false, false), writes);
        } finally {
            release.countDown();
            io.shutdownNow();
        }
    }

    @Test public void privateFlagAlwaysContainsExplicitValueIncludingDisabled() throws Exception {
        Path directory = Files.createTempDirectory("guard-private-");
        File flag = directory.resolve(".guard_enabled").toFile();
        Files.write(flag.toPath(), new byte[0]); // 兼容升级前的空标志
        GuardSettings.writePrivateFlag(flag, false);
        assertEquals("0\n", Files.readString(flag.toPath()));
        GuardSettings.writePrivateFlag(flag, true);
        assertEquals("1\n", Files.readString(flag.toPath()));
        assertFalse(directory.resolve(".guard_enabled.app-tmp").toFile().exists());
    }

    @Test public void generatedRootCommandUpdatesBothMirrorsWithoutEnvironmentFallback() throws Exception {
        Path directory = Files.createTempDirectory("guard-root-");
        Path root = directory.resolve("rootfs/root");
        Path shared = directory.resolve("shared");
        Files.createDirectories(root);
        for (boolean enabled : new boolean[]{true, false, true}) {
            assertEquals(0, runInFixture(directory, enabled).waitFor());
            String expected = enabled ? "1\n" : "0\n";
            assertEquals(expected, Files.readString(root.resolve(".dsh/.guard_enabled")));
            assertEquals(expected, Files.readString(shared.resolve(".guard_enabled")));
            assertFalse(root.resolve(".dsh/.guard_enabled.app-tmp").toFile().exists());
        }
        assertFalse(GuardSettings.rootCommand(false).contains("DSH_CONFIRM"));
        assertFalse(GuardSettings.rootCommand(false).contains("rm "));
    }

    @Test public void missingModuleDoesNotCreateFakeRootfsAndWriteFailuresPropagate() throws Exception {
        Path absent = Files.createTempDirectory("guard-absent-");
        assertEquals(0, runInFixture(absent, false).waitFor());
        assertFalse(absent.resolve("rootfs").toFile().exists());
        assertEquals("0\n", Files.readString(absent.resolve("shared/.guard_enabled")));

        Path blocked = Files.createTempDirectory("guard-blocked-");
        Files.createDirectories(blocked.resolve("rootfs/root/.dsh"));
        Files.createDirectory(blocked.resolve("rootfs/root/.dsh/.guard_enabled.app-tmp"));
        assertNotEquals(0, runInFixture(blocked, false).waitFor());
    }

    private Process runInFixture(Path directory, boolean enabled) throws Exception {
        // 仅执行生产生成命令的路径替换副本；从不调用 su 或真实 /data、/sdcard。
        String command = GuardSettings.rootCommand(enabled)
                .replace("/data/adb/dsha/rootfs", directory.resolve("rootfs").toString())
                .replace("/sdcard/Download/DSHA", directory.resolve("shared").toString());
        return new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
    }
}
