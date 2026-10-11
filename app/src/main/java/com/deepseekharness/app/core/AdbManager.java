package com.deepseekharness.app.core;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ADB 外部连接与远程调试底层管理器：
 * 1. 纯 Root 指令驱动（通过 su -c 直通宿主系统），不依赖容器与 DSH 运行状态；
 * 2. 支持检测与控制有线 USB 调试、无线网络调试（5555端口）、被连接权限模式（Root/Shell）；
 * 3. 支持主动向远端目标设备发起连接、断开及解析已连接设备列表。
 */
public final class AdbManager {

    private static volatile AdbManager sInstance;

    private AdbManager() {}

    public static AdbManager get() {
        if (sInstance == null) {
            synchronized (AdbManager.class) {
                if (sInstance == null) {
                    sInstance = new AdbManager();
                }
            }
        }
        return sInstance;
    }

    /** 有线 USB 调试状态数据结构 */
    public static class UsbStatus {
        public final boolean adbEnabled;
        public final boolean isConnected;
        public final String stateDescription;

        public UsbStatus(boolean adbEnabled, boolean isConnected, String stateDescription) {
            this.adbEnabled = adbEnabled;
            this.isConnected = isConnected;
            this.stateDescription = stateDescription;
        }
    }

    /** 无线网络调试状态数据结构 */
    public static class WirelessStatus {
        public final boolean isEnabled;
        public final int port;
        public final String ipAddress;
        public final String connectCommand;

        public WirelessStatus(boolean isEnabled, int port, String ipAddress, String connectCommand) {
            this.isEnabled = isEnabled;
            this.port = port;
            this.ipAddress = ipAddress;
            this.connectCommand = connectCommand;
        }
    }

    /** 已连接远程设备数据结构 */
    public static class AdbDevice {
        public final String address;
        public final String state;
        public final String extraInfo;

        public AdbDevice(String address, String state, String extraInfo) {
            this.address = address;
            this.state = state;
            this.extraInfo = extraInfo;
        }
    }

    /**
     * 查询有线 USB 调试状态
     */
    public UsbStatus getUsbStatus() {
        String adbGlobal = execRoot("settings get global adb_enabled").trim();
        boolean adbEnabled = "1".equals(adbGlobal);

        String usbState = execRoot("getprop sys.usb.state").trim().toLowerCase();
        if (TextUtils.isEmpty(usbState)) {
            usbState = execRoot("cat /sys/class/android_usb/android0/state 2>/dev/null").trim().toLowerCase();
        }

        boolean isConnected = usbState.contains("configured") || usbState.contains("connected");
        boolean isAdbReady = usbState.contains("adb");

        String desc;
        if (!isConnected) {
            desc = "未连接 USB 数据线";
        } else if (adbEnabled && isAdbReady) {
            desc = "已连接 (USB 调试就绪)";
        } else if (isConnected && !adbEnabled) {
            desc = "已连接 USB (系统调试开关未开启)";
        } else {
            desc = "已连接 USB (传输模式: " + usbState + ")";
        }

        return new UsbStatus(adbEnabled, isConnected, desc);
    }

    /**
     * 查询无线网络调试状态
     */
    public WirelessStatus getWirelessStatus() {
        String portStr = execRoot("getprop service.adb.tcp.port").trim();
        int port = -1;
        try {
            port = Integer.parseInt(portStr);
        } catch (Throwable ignored) {}

        boolean enabled = (port > 0);
        String ip = HarnessController.getLanAddress();
        if (ip == null || ip.isEmpty()) {
            ip = "未连入局域网";
        }

        String cmd = "";
        if (enabled && !"未连入局域网".equals(ip)) {
            cmd = "adb connect " + ip + ":" + port;
        } else if (enabled) {
            cmd = "adb connect <手机IP>:" + port;
        }

        return new WirelessStatus(enabled, port > 0 ? port : 5555, ip, cmd);
    }

    /**
     * 开启或关闭无线网络调试
     */
    public boolean setWirelessEnabled(boolean enable) {
        if (enable) {
            // 确保全局调试总开关开启，并令 adbd 监听 5555
            String cmd = "settings put global adb_enabled 1 && setprop service.adb.tcp.port 5555 && stop adbd && start adbd";
            execRoot(cmd);
        } else {
            // 恢复默认仅 USB 模式 (-1 或 0)
            String cmd = "setprop service.adb.tcp.port -1 && stop adbd && start adbd";
            execRoot(cmd);
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}
        return getWirelessStatus().isEnabled == enable;
    }

    /**
     * 获取当前被连接权限模式：
     * 0: 普通权限 (Shell UID 2000)
     * 1: 最高权限 (Root UID 0)
     */
    public int getPermissionMode() {
        String secure = execRoot("getprop ro.secure").trim();
        String debuggable = execRoot("getprop ro.debuggable").trim();
        if ("0".equals(secure) || "1".equals(debuggable)) {
            return 1; // Root 模式
        }
        return 0; // Shell 模式
    }

    /**
     * 切换被连接权限模式：
     * mode 0 -> Shell (UID 2000)
     * mode 1 -> Root (UID 0)
     */
    public boolean setPermissionMode(int mode) {
        if (mode == 1) {
            // 开启 Root 权限
            String cmd = "resetprop ro.debuggable 1 && resetprop ro.secure 0 && stop adbd && start adbd";
            execRoot(cmd);
        } else {
            // 恢复普通 Shell 权限
            String cmd = "resetprop ro.secure 1 && resetprop ro.debuggable 0 && stop adbd && start adbd";
            execRoot(cmd);
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}
        return getPermissionMode() == mode;
    }

    /**
     * 主动连接目标外部设备 (例如 192.168.31.88:5555)
     */
    public String connectRemote(String target) {
        if (TextUtils.isEmpty(target)) {
            return "目标地址不能为空";
        }
        String cleanTarget = target.trim();
        if (!cleanTarget.contains(":")) {
            cleanTarget = cleanTarget + ":5555";
        }
        String adbCmd = resolveAdbCmd("connect " + cleanTarget);
        return execRoot(adbCmd).trim();
    }

    /**
     * 断开指定远程外部设备
     */
    public String disconnectRemote(String target) {
        if (TextUtils.isEmpty(target)) {
            return "目标地址不能为空";
        }
        String cleanTarget = target.trim();
        String adbCmd = resolveAdbCmd("disconnect " + cleanTarget);
        return execRoot(adbCmd).trim();
    }

    /**
     * 获取当前已连接的所有设备列表
     */
    public List<AdbDevice> getConnectedDevices() {
        List<AdbDevice> list = new ArrayList<>();
        String adbCmd = resolveAdbCmd("devices -l");
        String output = execRoot(adbCmd);
        if (TextUtils.isEmpty(output)) {
            return list;
        }

        String[] lines = output.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices") || trimmed.startsWith("*")) {
                continue;
            }
            // 格式类似: 192.168.31.88:5555 device product:xxx model:xxx device:xxx
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                String address = parts[0];
                String state = parts[1];
                StringBuilder extra = new StringBuilder();
                for (int i = 2; i < parts.length; i++) {
                    extra.append(parts[i]).append(" ");
                }
                list.add(new AdbDevice(address, state, extra.toString().trim()));
            }
        }
        return list;
    }

    /**
     * 自动解析适配系统中最优的 adb 客户端命令调用路径
     */
    private String resolveAdbCmd(String args) {
        return "sh /data/adb/dsha/bin/adb " + args + " 2>&1";
    }

    /**
     * 查询是否开启了 ADB 直连 chroot 容器模式
     */
    public boolean isChrootDirectEnabled() {
        String res = execRoot("test -f /data/adb/dsha/run/adb_chroot_direct && echo 1 || echo 0").trim();
        return "1".equals(res);
    }

    /**
     * 设置是否开启 ADB 直连 chroot 模式：
     * 开启时：挂载直通配置，使外部 adb shell 连入瞬间自动穿透进 Ubuntu 容器；
     * 关闭时：卸载挂载点，恢复纯原生 Android 宿主 Shell。
     */
    public boolean setChrootDirectEnabled(boolean enable) {
        if (enable) {
            String script = "mkdir -p /data/adb/dsha/run && "
                    + "touch /data/adb/dsha/run/adb_chroot_direct && "
                    + "grep -q 'DSHA_ADB_HOOK' /system/etc/mkshrc 2>/dev/null || ("
                    + "cat /system/etc/mkshrc > /data/adb/dsha/run/mkshrc && "
                    + "echo '' >> /data/adb/dsha/run/mkshrc && "
                    + "echo '# DSHA_ADB_HOOK' >> /data/adb/dsha/run/mkshrc && "
                    + "echo 'if [ -f /data/adb/dsha/run/adb_chroot_direct ] && [ -z \"$DSHA_IN_CHROOT\" ]; then' >> /data/adb/dsha/run/mkshrc && "
                    + "echo '    if [ -f /data/adb/dsha/rootfs/bin/bash ] && [ -f /data/adb/dsha/scripts/term.sh ]; then' >> /data/adb/dsha/run/mkshrc && "
                    + "echo '        export DSHA_IN_CHROOT=1' >> /data/adb/dsha/run/mkshrc && "
                    + "echo '        exec sh /data/adb/dsha/scripts/term.sh \"$@\"' >> /data/adb/dsha/run/mkshrc && "
                    + "echo '    fi' >> /data/adb/dsha/run/mkshrc && "
                    + "echo 'fi' >> /data/adb/dsha/run/mkshrc && "
                    + "mount -o bind /data/adb/dsha/run/mkshrc /system/etc/mkshrc )";
            execRoot(script);
        } else {
            String script = "rm -f /data/adb/dsha/run/adb_chroot_direct && umount -l /system/etc/mkshrc 2>/dev/null || true";
            execRoot(script);
        }
        return isChrootDirectEnabled() == enable;
    }

    /**
     * 基础执行 Root 命令并捕获完整输出
     */
    public String execRoot(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
            }
            p.waitFor();
        } catch (Throwable e) {
            return "执行失败: " + e.getMessage();
        }
        return sb.toString();
    }
}
