package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PluginInstallPathTest {
    @Test public void mapsProfilePackageToHostPath() {
        String path = "/root/.dsh/profiles/web/node_modules/@scope/plugin";
        assertEquals("/data/adb/dsha/rootfs" + path, PluginInstallPath.display(path));
    }

    @Test public void preservesResolvedBuiltinAndGlobalLocations() {
        assertEquals("/data/adb/dsha/rootfs/root/dsha-task-notifier",
                PluginInstallPath.display("/root/dsha-task-notifier"));
        assertEquals("/data/adb/dsha/rootfs/usr/local/lib/node_modules/example",
                PluginInstallPath.display("/usr/local/lib/node_modules/example"));
    }

    @Test public void doesNotPrefixHostPathTwice() {
        String path = "/data/adb/dsha/rootfs/root/.dsh/profiles/web/node_modules/example";
        assertEquals(path, PluginInstallPath.display(path));
        assertEquals("/data/adb/dsha/rootfs", PluginInstallPath.display("/data/adb/dsha/rootfs"));
    }

    @Test public void checksHostRootAtDirectoryBoundary() {
        assertEquals("/data/adb/dsha/rootfs/data/adb/dsha/rootfs-other/example",
                PluginInstallPath.display("/data/adb/dsha/rootfs-other/example"));
    }

    @Test public void unresolvedOrRelativeLocationsDoNotInventAPath() {
        assertEquals("未解析到实际安装位置", PluginInstallPath.display(null));
        assertEquals("未解析到实际安装位置", PluginInstallPath.display(""));
        assertEquals("未解析到实际安装位置", PluginInstallPath.display("node_modules/example"));
    }
}
