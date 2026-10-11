package com.deepseekharness.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SSH 远程主机与本机密钥管理器：
 * 1. 负责读取与生成本机的 Ed25519 SSH 密钥对；
 * 2. 负责持久化保存/编辑/删除远程主机连接对象 (HostProfile)；
 * 3. 负责生成开箱即用的直连命令与跨页面调度。
 */
public final class SshManager {

    private static final String PREF_NAME = "dsha_ssh_manager";
    private static final String KEY_HOSTS_JSON = "saved_hosts_json";

    private static volatile SshManager sInstance;
    private final Context context;
    private final SharedPreferences prefs;

    private SshManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
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
        public String id;
        public String name;
        public String host;
        public int port;
        public String user;

        public HostProfile(String id, String name, String host, int port, String user) {
            this.id = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
            this.name = name;
            this.host = host;
            this.port = port <= 0 ? 22 : port;
            this.user = TextUtils.isEmpty(user) ? "root" : user;
        }

        public String getSshCommand() {
            if (port == 22) {
                return "ssh " + user + "@" + host;
            }
            return "ssh -p " + port + " " + user + "@" + host;
        }
    }

    /**
     * 读取本机的 Ed25519 公钥内容
     */
    public String getPublicKey() {
        String cmd = "chroot /data/adb/dsha/rootfs /bin/sh -c 'cat /root/.ssh/id_ed25519.pub 2>/dev/null'";
        String out = execRoot(cmd).trim();
        if (out.startsWith("ssh-ed25519") || out.startsWith("ssh-rsa")) {
            return out;
        }
        return "";
    }

    /**
     * 生成或重新生成本机 Ed25519 密钥对
     */
    public boolean generateKeyPair() {
        String cmd = "chroot /data/adb/dsha/rootfs /bin/sh -c '"
                + "mkdir -p /root/.ssh && chmod 700 /root/.ssh && "
                + "rm -f /root/.ssh/id_ed25519 /root/.ssh/id_ed25519.pub && "
                + "ssh-keygen -t ed25519 -N \"\" -f /root/.ssh/id_ed25519 -C \"dsha-phone\"'";
        execRoot(cmd);
        return !TextUtils.isEmpty(getPublicKey());
    }

    /**
     * 获取所有已保存的远程主机列表
     */
    public synchronized List<HostProfile> getSavedHosts() {
        List<HostProfile> list = new ArrayList<>();
        String jsonStr = prefs.getString(KEY_HOSTS_JSON, "[]");
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new HostProfile(
                        obj.optString("id"),
                        obj.optString("name"),
                        obj.optString("host"),
                        obj.optInt("port", 22),
                        obj.optString("user", "root")
                ));
            }
        } catch (Throwable ignored) {}
        return list;
    }

    /**
     * 保存或更新一个远程主机对象
     */
    public synchronized void saveHost(HostProfile host) {
        List<HostProfile> list = getSavedHosts();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(host.id)) {
                list.set(i, host);
                found = true;
                break;
            }
        }
        if (!found) {
            list.add(0, host); // 新增的排在最前
        }
        persistHosts(list);
    }

    /**
     * 删除一个指定的远程主机对象
     */
    public synchronized void deleteHost(String id) {
        List<HostProfile> list = getSavedHosts();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        persistHosts(list);
    }

    private synchronized void persistHosts(List<HostProfile> list) {
        JSONArray arr = new JSONArray();
        for (HostProfile h : list) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", h.id);
                obj.put("name", h.name);
                obj.put("host", h.host);
                obj.put("port", h.port);
                obj.put("user", h.user);
                arr.put(obj);
            } catch (Throwable ignored) {}
        }
        prefs.edit().putString(KEY_HOSTS_JSON, arr.toString()).apply();
    }

    private String execRoot(String cmd) {
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
