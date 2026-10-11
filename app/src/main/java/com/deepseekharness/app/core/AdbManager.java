package com.deepseekharness.app.core;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ADB 外部连接与远程调试底层管理器：
 * 1. 纯 Root 指令驱动（通过 su -c 直通宿主系统），不依赖容器与 DSH 运行状态；
 * 2. 具备被连入感知能力，能通过底层 TCP 套接字分析已连入本机的外部客户端 IP；
 * 3. 支持有线/无线切换、基于 su 直通的 Root 模式与直连 Linux 容器穿透；
 * 4. 支持主动向远端目标设备发起连接、断开及解析已连接设备列表。
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
        public final List<String> inboundClients;

        public WirelessStatus(boolean isEnabled, int port, String ipAddress, String connectCommand, List<String> inboundClients) {
            this.isEnabled = isEnabled;
            this.port = port;
            this.ipAddress = ipAddress;
            this.connectCommand = connectCommand;
            this.inboundClients = inboundClients != null ? inboundClients : new ArrayList<>();
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
     * 查询有线 USB 调试状态（精准排除 disconnected 假阳性）
     */
    public UsbStatus getUsbStatus() {
        String adbGlobal = execRoot("settings get global adb_enabled").trim();
        boolean adbEnabled = "1".equals(adbGlobal);

        String sysState = execRoot("cat /sys/class/android_usb/android0/state 2>/dev/null").trim().toUpperCase();
        String usbProp = execRoot("getprop sys.usb.state").trim().toUpperCase();

        boolean isConnected = false;
        if (!TextUtils.isEmpty(sysState)) {
            isConnected = sysState.contains("CONFIGURED") || (sysState.contains("CONNECTED") && !sysState.contains("DISCONNECT"));
        } else if (!TextUtils.isEmpty(usbProp)) {
            isConnected = usbProp.contains("CONFIGURED") || (usbProp.contains("CONNECTED") && !usbProp.contains("DISCONNECT"));
        }

        boolean isAdbReady = sysState.contains("ADB") || usbProp.contains("ADB");

        String desc;
        if (!isConnected) {
            desc = "未连接 USB 数据线";
        } else if (adbEnabled && isAdbReady) {
            desc = "已连接 (USB 调试就绪)";
        } else if (isConnected && !adbEnabled) {
            desc = "已连接 USB (系统调试开关未开启)";
        } else {
            desc = "已连接 USB (传输中)";
        }

        return new UsbStatus(adbEnabled, isConnected, desc);
    }

    /**
     * 查询无线网络调试状态（含外部连入设备嗅探）
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

        List<String> clients = enabled ? getInboundClients(port > 0 ? port : 5555) : new ArrayList<>();
        return new WirelessStatus(enabled, port > 0 ? port : 5555, ip, cmd, clients);
    }

    /**
     * 开启或关闭无线网络调试
     */
    public boolean setWirelessEnabled(boolean enable) {
        if (enable) {
            String cmd = "settings put global adb_enabled 1 && setprop service.adb.tcp.port 5555 && stop adbd && start adbd";
            execRoot(cmd);
        } else {
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
        String res = execRoot("test -f /data/local/tmp/dsha_root_direct && echo 1 || echo 0").trim();
        return "1".equals(res) ? 1 : 0;
    }

    /**
     * 切换被连接权限模式：
     * 利用 /data/local/tmp/dsha_root_direct 标志与 mkshrc 直通提升
     */
    public boolean setPermissionMode(int mode) {
        ensureMkshrcHookMounted();
        if (mode == 1) {
            execRoot("touch /data/local/tmp/dsha_root_direct && chmod 666 /data/local/tmp/dsha_root_direct");
        } else {
            execRoot("rm -f /data/local/tmp/dsha_root_direct");
        }
        return getPermissionMode() == mode;
    }

    /**
     * 查询是否开启了 ADB 直连 chroot 容器模式
     */
    public boolean isChrootDirectEnabled() {
        String res = execRoot("test -f /data/local/tmp/dsha_chroot_direct && echo 1 || echo 0").trim();
        return "1".equals(res);
    }

    /**
     * 设置是否开启 ADB 直连 chroot 模式
     */
    public boolean setChrootDirectEnabled(boolean enable) {
        ensureMkshrcHookMounted();
        if (enable) {
            execRoot("touch /data/local/tmp/dsha_chroot_direct && chmod 666 /data/local/tmp/dsha_chroot_direct");
        } else {
            execRoot("rm -f /data/local/tmp/dsha_chroot_direct");
        }
        return isChrootDirectEnabled() == enable;
    }

    /**
     * 确保宿主 /system/etc/mkshrc 中安全植入直通穿透钩子 (纯 mksh 配置文件，绝不替换系统二进制)
     */
    private void ensureMkshrcHookMounted() {
        String checkCmd = "grep -q 'DSHA_ADB_HOOK' /system/etc/mkshrc 2>/dev/null && echo 1 || echo 0";
        if ("1".equals(execRoot(checkCmd).trim())) {
            return;
        }

        String setupCmd = "mkdir -p /data/adb/dsha/run && "
                + "cat /system/etc/mkshrc > /data/adb/dsha/run/mkshrc.bak 2>/dev/null || true && "
                + "cat << 'HOOK_EOF' > /data/adb/dsha/run/mkshrc\n"
                + "#!/system/bin/sh\n"
                + "if [ -f /data/adb/dsha/run/mkshrc.bak ]; then . /data/adb/dsha/run/mkshrc.bak; fi\n"
                + "# DSHA_ADB_HOOK\n"
                + "if [ -z \"$DSHA_IN_HOOK\" ]; then\n"
                + "    export DSHA_IN_HOOK=1\n"
                + "    if [ -f /data/local/tmp/dsha_chroot_direct ] && [ -f /data/adb/dsha/rootfs/bin/bash ]; then\n"
                + "        if command -v su >/dev/null 2>&1; then exec su -c \"sh /data/adb/dsha/scripts/term.sh $@\";\n"
                + "        elif [ -f /data/adb/magisk/magisk ]; then exec /data/adb/magisk/magisk su -c \"sh /data/adb/dsha/scripts/term.sh $@\"; fi\n"
                + "    fi\n"
                + "    if [ -f /data/local/tmp/dsha_root_direct ] && [ \"$(id -u)\" != \"0\" ]; then\n"
                + "        if command -v su >/dev/null 2>&1; then exec su;\n"
                + "        elif [ -f /data/adb/magisk/magisk ]; then exec /data/adb/magisk/magisk su; fi\n"
                + "    fi\n"
                + "fi\n"
                + "HOOK_EOF\n"
                + "umount -l /system/etc/mkshrc 2>/dev/null || true && "
                + "mount -o bind /data/adb/dsha/run/mkshrc /system/etc/mkshrc";
        execRoot(setupCmd);
    }

    /**
     * 嗅探当前连入本机 5555 端口的外部客户端 IP 列表
     */
    public List<String> getInboundClients(int port) {
        Set<String> clients = new HashSet<>();
        String hexPort = String.format("%04X", port);
        String cmd = "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -iE ':" + hexPort + " '";
        String out = execRoot(cmd);
        if (TextUtils.isEmpty(out)) return new ArrayList<>();

        String[] lines = out.split("\n");
        for (String line : lines) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length >= 4) {
                String state = tokens[3];
                // 01 代表 TCP_ESTABLISHED
                if ("01".equalsIgnoreCase(state)) {
                    String remAddr = tokens[2];
                    String ip = parseHexIp(remAddr);
                    if (ip != null && !ip.equals("127.0.0.1") && !ip.equals("::1")) {
                        clients.add(ip);
                    }
                }
            }
        }
        return new ArrayList<>(clients);
    }

    private String parseHexIp(String raw) {
        try {
            int colon = raw.indexOf(':');
            if (colon <= 0) return null;
            String hex = raw.substring(0, colon);
            if (hex.length() == 8) {
                // IPv4 Little Endian
                long l = Long.parseLong(hex, 16);
                return (l & 0xFF) + "." + ((l >> 8) & 0xFF) + "." + ((l >> 16) & 0xFF) + "." + ((l >> 24) & 0xFF);
            } else if (hex.length() == 32) {
                // 常见 IPv4-mapped IPv6，最后 8 位为 IPv4
                String tail = hex.substring(24);
                long l = Long.parseLong(tail, 16);
                return (l & 0xFF) + "." + ((l >> 8) & 0xFF) + "." + ((l >> 16) & 0xFF) + "." + ((l >> 24) & 0xFF);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 对目标设备发起无线配对 (Android 11+ 原生配对码机制)
     */
    public String pairRemote(String target, String pairingCode) {
        if (TextUtils.isEmpty(target) || TextUtils.isEmpty(pairingCode)) {
            return "地址和配对码不能为空";
        }
        String cleanTarget = target.trim();
        String adbCmd = resolveAdbCmd("pair " + cleanTarget + " " + pairingCode.trim());
        return execRoot(adbCmd).trim();
    }

    /**
     * 主动连接目标外部设备
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
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices") || trimmed.startsWith("*") 
                    || trimmed.startsWith("sh:") || trimmed.startsWith("chroot:") || trimmed.contains("No such file")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                String address = parts[0];
                String state = parts[1];
                // 仅合法状态才认作设备
                if ("device".equalsIgnoreCase(state) || "offline".equalsIgnoreCase(state) || "unauthorized".equalsIgnoreCase(state)) {
                    StringBuilder extra = new StringBuilder();
                    for (int i = 2; i < parts.length; i++) {
                        extra.append(parts[i]).append(" ");
                    }
                    list.add(new AdbDevice(address, state, extra.toString().trim()));
                }
            }
        }
        return list;
    }

    private String resolveAdbCmd(String args) {
        return "chroot /data/adb/dsha/rootfs /usr/bin/adb " + args + " 2>&1";
    }

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
