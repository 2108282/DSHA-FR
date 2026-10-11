package com.deepseekharness.app.core;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH 远程主机与本机密钥管理器：
 * 1. 唯一真实源 (Single Source of Truth)：直接读写容器内物理文件 /data/adb/dsha/rootfs/root/.ssh/config；
 * 2. 任何外部编辑器 (如 MT 管理器) 或 APK 界面的新增、修改、删除均保持 100% 物理双向同步；
 * 3. 负责读取与生成本机的 Ed25519 SSH 密钥对，并保持纯文本解析的高鲁棒性。
 */
public final class SshManager {

    public static final String SSH_CONFIG_PATH = "/data/adb/dsha/rootfs/root/.ssh/config";
    public static final String PUBKEY_PATH = "/data/adb/dsha/rootfs/root/.ssh/id_ed25519.pub";

    private static volatile SshManager sInstance;
    private final Context context;

    private SshManager(Context context) {
        this.context = context.getApplicationContext();
        ensureConfigFileExists();
    }

    public static SshManager get(Context context) {
        if (sInstance == null) {
            synchronized (SshManager.class) {
                if (sInstance == null) {
                    sInstance = new SshManager(context);
                }
            }
        }
        return sInstance;
    }

    /** 远程主机对象数据结构 */
    public static class HostProfile {
        public String name;
        public String host;
        public int port;
        public String user;
        public String identityFile; // 专属私钥文件路径 (可选)

        public HostProfile(String name, String host, int port, String user) {
            this(name, host, port, user, null);
        }

        public HostProfile(String name, String host, int port, String user, String identityFile) {
            this.name = name;
            this.host = host;
            this.port = port <= 0 ? 22 : port;
            this.user = TextUtils.isEmpty(user) ? "root" : user;
            this.identityFile = identityFile != null ? identityFile.trim() : "";
        }

        public String getSshCommand() {
            StringBuilder sb = new StringBuilder("ssh ");
            if (!TextUtils.isEmpty(identityFile)) {
                sb.append("-i ").append(identityFile).append(" ");
            }
            if (port != 22) {
                sb.append("-p ").append(port).append(" ");
            }
            sb.append(user).append("@").append(host);
            return sb.toString();
        }
    }

    private void ensureConfigFileExists() {
        execRoot("mkdir -p /data/adb/dsha/rootfs/root/.ssh && chmod 700 /data/adb/dsha/rootfs/root/.ssh");
        String check = execRoot("test -f " + SSH_CONFIG_PATH + " && echo 1 || echo 0").trim();
        if (!"1".equals(check)) {
            String initContent = "# DSHA 外部连接配置文件\nHost *\n    StrictHostKeyChecking no\n    UserKnownHostsFile /dev/null\n";
            execRoot("printf '" + initContent + "' > " + SSH_CONFIG_PATH + " && chmod 600 " + SSH_CONFIG_PATH);
        }
    }

    /**
     * 读取本机的 Ed25519 公钥内容 (直接读取物理文件)
     */
    public String getPublicKey() {
        String out = execRoot("cat " + PUBKEY_PATH + " 2>/dev/null").trim();
        if (out.startsWith("ssh-ed25519") || out.startsWith("ssh-rsa")) {
            return out;
        }
        return "";
    }

    /**
     * 生成或重新生成本机 Ed25519 密钥对
     */
    public boolean generateKeyPair() {
        String cmd = "chroot /data/adb/dsha/rootfs /bin/bash -c '"
                + "export PATH=/usr/local/bin:/usr/bin:/bin:$PATH; "
                + "mkdir -p /root/.ssh && chmod 700 /root/.ssh; "
                + "rm -f /root/.ssh/id_ed25519 /root/.ssh/id_ed25519.pub; "
                + "ssh-keygen -t ed25519 -N \"\" -f /root/.ssh/id_ed25519 -C \"dsha-phone\"'";
        execRoot(cmd);
        return !TextUtils.isEmpty(getPublicKey());
    }

    /**
     * 从物理文件 ~/.ssh/config 全量解析所有已保存的远程主机列表
     */
    public synchronized List<HostProfile> getSavedHosts() {
        List<HostProfile> list = new ArrayList<>();
        String content = execRoot("cat " + SSH_CONFIG_PATH + " 2>/dev/null");
        if (TextUtils.isEmpty(content)) {
            return list;
        }

        String[] lines = content.split("\n");
        HostProfile current = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            if (trimmed.startsWith("Host ") || trimmed.startsWith("host ")) {
                String hostPattern = trimmed.substring(5).trim();
                if ("*".equals(hostPattern)) {
                    current = null;
                    continue;
                }
                current = new HostProfile(hostPattern, "", 22, "root");
                list.add(current);
            } else if (current != null) {
                String[] parts = trimmed.split("\\s+", 2);
                if (parts.length == 2) {
                    String key = parts[0].toLowerCase();
                    String val = parts[1].trim();
                    if ("hostname".equals(key)) {
                        current.host = val;
                    } else if ("user".equals(key)) {
                        current.user = val;
                    } else if ("port".equals(key)) {
                        try {
                            current.port = Integer.parseInt(val);
                        } catch (Throwable ignored) {}
                    } else if ("identityfile".equals(key)) {
                        current.identityFile = val;
                    }
                }
            }
        }
        return list;
    }

    /**
     * 保存或更新远程主机配置 (直接回写物理文件 ~/.ssh/config)
     */
    public synchronized void saveHost(HostProfile host) {
        if (host == null || TextUtils.isEmpty(host.name) || TextUtils.isEmpty(host.host)) {
            return;
        }

        List<HostProfile> hosts = getSavedHosts();
        boolean found = false;
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).name.equals(host.name)) {
                hosts.set(i, host);
                found = true;
                break;
            }
        }
        if (!found) {
            hosts.add(0, host);
        }
        rewriteConfigFile(hosts);
    }

    /**
     * 物理删除一个远程主机配置 (从 ~/.ssh/config 中抹除对应段落)
     */
    public synchronized void deleteHost(String hostName) {
        if (TextUtils.isEmpty(hostName)) return;
        List<HostProfile> hosts = getSavedHosts();
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).name.equals(hostName)) {
                hosts.remove(i);
                break;
            }
        }
        rewriteConfigFile(hosts);
    }

    private synchronized void rewriteConfigFile(List<HostProfile> hosts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# DSHA 外部连接配置文件\n");
        sb.append("Host *\n");
        sb.append("    StrictHostKeyChecking no\n");
        sb.append("    UserKnownHostsFile /dev/null\n\n");

        for (HostProfile h : hosts) {
            sb.append("Host ").append(h.name).append("\n");
            sb.append("    HostName ").append(h.host).append("\n");
            sb.append("    User ").append(h.user).append("\n");
            sb.append("    Port ").append(h.port).append("\n");
            if (!TextUtils.isEmpty(h.identityFile)) {
                sb.append("    IdentityFile ").append(h.identityFile).append("\n");
            }
            sb.append("\n");
        }

        String safeContent = sb.toString();
        execRoot("printf '" + safeContent + "' > " + SSH_CONFIG_PATH + " && chmod 600 " + SSH_CONFIG_PATH);
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
            return "";
        }
        return sb.toString();
    }
}
