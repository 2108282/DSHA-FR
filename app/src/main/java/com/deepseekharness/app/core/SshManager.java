package com.deepseekharness.app.core;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH 远程主机与本机密钥管理器：
 * 1. 唯一真实源 (Single Source of Truth)：直接读写容器内物理文件 /data/adb/dsha/rootfs/root/.ssh/config；
 * 2. 密码安全托管：伴生物理文件 /data/adb/dsha/rootfs/root/.ssh/passwords.json (600 权限)；
 * 3. 双轨自适应直连：填了密码走 sshpass 全自动注入，留空走系统 Ed25519 或指定私钥公钥免密；
 * 4. 支持一键推送公钥 (ssh-copy-id) 快速免密绑定。
 */
public final class SshManager {

    public static final String SSH_CONFIG_PATH = "/data/adb/dsha/rootfs/root/.ssh/config";
    public static final String PUBKEY_PATH = "/data/adb/dsha/rootfs/root/.ssh/id_ed25519.pub";
    public static final String PASSWORDS_JSON_PATH = "/data/adb/dsha/rootfs/root/.ssh/passwords.json";

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
        public String password;     // 登录密码 (可选)

        public HostProfile(String name, String host, int port, String user) {
            this(name, host, port, user, null, null);
        }

        public HostProfile(String name, String host, int port, String user, String identityFile, String password) {
            this.name = name;
            this.host = host;
            this.port = port <= 0 ? 22 : port;
            this.user = TextUtils.isEmpty(user) ? "root" : user;
            this.identityFile = identityFile != null ? identityFile.trim() : "";
            this.password = password != null ? password.trim() : "";
        }

        public boolean hasPassword() {
            return !TextUtils.isEmpty(password);
        }

        public String getSshCommand() {
            StringBuilder sb = new StringBuilder();
            if (hasPassword()) {
                sb.append("sshpass -p '").append(password.replace("'", "'\''")).append("' ");
            }
            sb.append("ssh ");
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
        String checkPwd = execRoot("test -f " + PASSWORDS_JSON_PATH + " && echo 1 || echo 0").trim();
        if (!"1".equals(checkPwd)) {
            execRoot("echo '{}' > " + PASSWORDS_JSON_PATH + " && chmod 600 " + PASSWORDS_JSON_PATH);
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
     * 使用已存密码自动向远程主机推送本机公钥 (ssh-copy-id)
     */
    public String pushPublicKeyToHost(HostProfile host) {
        if (host == null || !host.hasPassword()) {
            return "该主机未设置密码，无法自动推送公钥";
        }
        String cmd = "chroot /data/adb/dsha/rootfs /bin/bash -c '"
                + "export PATH=/usr/local/bin:/usr/bin:/bin:$PATH; "
                + "sshpass -p \"" + host.password.replace("\"", "\\\"") + "\" "
                + "ssh-copy-id -p " + host.port + " -o StrictHostKeyChecking=no "
                + host.user + "@" + host.host + " 2>&1'";
        String out = execRoot(cmd).trim();
        return out.isEmpty() ? "公钥推送完成" : out;
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

        JSONObject pwdMap = loadPasswordMap();
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
                current = new HostProfile(hostPattern, "", 22, "root", null, pwdMap.optString(hostPattern, ""));
                list.add(current);
            } else if (current != null) {
                String[] parts = trimmed.split("\s+", 2);
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
     * 保存或更新远程主机配置 (直接回写物理文件 ~/.ssh/config 与 passwords.json)
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

        // 同步密码映射
        JSONObject pwdMap = loadPasswordMap();
        try {
            if (host.hasPassword()) {
                pwdMap.put(host.name, host.password);
            } else {
                pwdMap.remove(host.name);
            }
            savePasswordMap(pwdMap);
        } catch (Throwable ignored) {}
    }

    /**
     * 物理删除一个远程主机配置 (从 ~/.ssh/config 与 passwords.json 中彻底抹除)
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

        JSONObject pwdMap = loadPasswordMap();
        pwdMap.remove(hostName);
        savePasswordMap(pwdMap);
    }

    private JSONObject loadPasswordMap() {
        String jsonStr = execRoot("cat " + PASSWORDS_JSON_PATH + " 2>/dev/null").trim();
        try {
            if (!TextUtils.isEmpty(jsonStr) && jsonStr.startsWith("{")) {
                return new JSONObject(jsonStr);
            }
        } catch (Throwable ignored) {}
        return new JSONObject();
    }

    private void savePasswordMap(JSONObject map) {
        String safeContent = (map != null ? map.toString() : "{}").replace("'", "'\''");
        execRoot("printf '%s' '" + safeContent + "' > " + PASSWORDS_JSON_PATH + " && chmod 600 " + PASSWORDS_JSON_PATH);
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
