package com.deepseekharness.app.core;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.deepseekharness.app.util.ShellQuote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * 工作区数据访问层：负责与 /root/.dsh/storages/workspace.json 交互，
 * 实现多工作区识别、状态同步与双轨（原生 Linux / 手机共享存储）创建管理。
 */
public final class WorkspaceRepository {

    private static final String TAG = "WorkspaceRepository";
    public static final String WS_JSON_REL = "root/.dsh/storages/workspace.json";

    private WorkspaceRepository() {}

    /**
     * 判断指定路径是否支持原生 Linux 软链接（symlink）。
     * 位于 /sdcard 或 /storage/emulated 下属于 Android FUSE 文件系统，系统层未实现 symlink；
     * 位于 /root（除内部存储符号链接外）、/data 或其他 ext4/f2fs 原生分区下则完美支持软链接。
     */
    public static boolean checkSymlinkSupport(String path) {
        if (path == null || path.isEmpty()) return false;
        String p = path.trim();
        if (p.startsWith("/sdcard") || p.startsWith("/storage/emulated")) {
            return false;
        }
        if (p.startsWith("/root/内部存储")) {
            return false;
        }
        return true;
    }

    /**
     * 读取 workspace.json 原始 JSON 字符串
     */
    public static String readWorkspaceJson(HarnessController controller) {
        try {
            File directFile = new File(controller.proot().getRootfsDir(), WS_JSON_REL);
            if (directFile.isFile() && directFile.canRead() && directFile.length() > 0) {
                byte[] buf = new byte[(int) directFile.length()];
                try (InputStream in = new FileInputStream(directFile)) {
                    int read = in.read(buf);
                    if (read > 0) {
                        return new String(buf, 0, read, StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            String out = controller.proot().execChecked("cat /root/.dsh/storages/workspace.json 2>/dev/null || true");
            if (out != null && !out.trim().isEmpty()) {
                return out.trim();
            }
        } catch (Throwable t) {
            Log.w(TAG, "exec cat workspace.json failed: " + t.getMessage());
        }
        return null;
    }

    /**
     * 原子安全地将 JSON 字符串写回 /root/.dsh/storages/workspace.json
     */
    public static boolean writeWorkspaceJson(HarnessController controller, String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            String cmd = "mkdir -p /root/.dsh/storages && "
                    + "printf '%s' '" + b64 + "' | base64 -d > /root/.dsh/storages/workspace.json.tmp && "
                    + "mv -f /root/.dsh/storages/workspace.json.tmp /root/.dsh/storages/workspace.json && "
                    + "chmod 644 /root/.dsh/storages/workspace.json";
            controller.proot().execChecked(cmd);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "writeWorkspaceJson failed", t);
            return false;
        }
    }

    /**
     * 加载当前所有工作区，当前活跃工作区置于首位
     */
    public static List<WorkspaceModel> loadWorkspaces(HarnessController controller) {
        List<WorkspaceModel> list = new ArrayList<>();
        String currentWd = controller.config().getWorkdir();
        if (currentWd == null) currentWd = "";
        String normCurrentWd = normalizePath(currentWd);

        String json = readWorkspaceJson(controller);
        if (json != null && !json.trim().isEmpty()) {
            try {
                JSONObject root = new JSONObject(json);
                JSONObject tables = root.optJSONObject("tables");
                JSONObject workspaces = tables != null ? tables.optJSONObject("workspaces") : null;

                if (workspaces != null) {
                    java.util.Iterator<String> keys = workspaces.keys();
                    while (keys.hasNext()) {
                        String wid = keys.next();
                        JSONObject obj = workspaces.optJSONObject(wid);
                        if (obj == null) continue;

                        String path = obj.optString("path", "");
                        String title = obj.optString("title", "工作区");
                        JSONArray sessionIds = obj.optJSONArray("sessionIds");
                        int count = sessionIds != null ? sessionIds.length() : 0;
                        String createdAt = obj.optString("createdAt", "");
                        String updatedAt = obj.optString("updatedAt", "");

                        boolean isCur = normalizePath(path).equals(normCurrentWd);
                        boolean symlink = checkSymlinkSupport(path);

                        list.add(new WorkspaceModel(wid, title, path, count, isCur, symlink, createdAt, updatedAt));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "parse workspace.json failed", t);
            }
        }

        // 如果 workspace.json 中没有条目，构建默认条目
        if (list.isEmpty()) {
            String defaultPath = currentWd.isEmpty() ? "/sdcard/Download/DSHA/工作区" : currentWd;
            boolean symlink = checkSymlinkSupport(defaultPath);
            list.add(new WorkspaceModel("default", "默认工作区", defaultPath, 0, true, symlink, "", ""));
        } else {
            // 确保如果没有任何条目匹配上当前工作区，将首项设为当前
            boolean hasCurrent = false;
            for (WorkspaceModel item : list) {
                if (item.isCurrent) {
                    hasCurrent = true;
                    break;
                }
            }
            if (!hasCurrent && !list.isEmpty()) {
                WorkspaceModel first = list.remove(0);
                list.add(0, new WorkspaceModel(first.id, first.title, first.path, first.sessionCount, true, first.supportsSymlink, first.createdAt, first.updatedAt));
            }

            // 排序：当前活跃工作区置顶，其余按 updatedAt 降序
            Collections.sort(list, (a, b) -> {
                if (a.isCurrent != b.isCurrent) {
                    return a.isCurrent ? -1 : 1;
                }
                return b.updatedAt.compareTo(a.updatedAt);
            });
        }

        return list;
    }

    /**
     * 创建新工作区（支持 原生 Linux 路径与 手机共享存储 路径）
     */
    public static WorkspaceModel createWorkspace(HarnessController controller, String title, boolean isNativeLinux) throws Exception {
        String safeTitle = title != null ? title.trim().replaceAll("[/\\\\:*?\"<>|]", "_") : "";
        if (safeTitle.isEmpty()) {
            throw new IllegalArgumentException("工作区名称不能为空");
        }

        String targetPath;
        if (isNativeLinux) {
            targetPath = "/root/workspaces/" + safeTitle;
            controller.proot().execChecked("mkdir -p " + ShellQuote.arg(targetPath));
        } else {
            targetPath = "/sdcard/Download/DSHA/" + safeTitle;
            new File(targetPath).mkdirs();
            controller.proot().execChecked("mkdir -p " + ShellQuote.arg(targetPath) + " 2>/dev/null || true");
        }

        String wid = UUID.randomUUID().toString();
        String nowIso = getIsoTimestamp();

        String json = readWorkspaceJson(controller);
        JSONObject root;
        if (json != null && !json.trim().isEmpty()) {
            root = new JSONObject(json);
        } else {
            root = new JSONObject();
            JSONObject unit = new JSONObject();
            unit.put("name", "workspace");
            unit.put("version", 2);
            root.put("unit", unit);
        }

        JSONObject global = root.optJSONObject("global");
        if (global == null) {
            global = new JSONObject();
            global.put("initialized", true);
            root.put("global", global);
        }
        JSONArray wsIds = global.optJSONArray("workspaceIds");
        if (wsIds == null) {
            wsIds = new JSONArray();
            global.put("workspaceIds", wsIds);
        }
        wsIds.put(wid);

        JSONObject tables = root.optJSONObject("tables");
        if (tables == null) {
            tables = new JSONObject();
            root.put("tables", tables);
        }
        JSONObject workspaces = tables.optJSONObject("workspaces");
        if (workspaces == null) {
            workspaces = new JSONObject();
            tables.put("workspaces", workspaces);
        }

        JSONObject newWs = new JSONObject();
        newWs.put("path", targetPath);
        newWs.put("title", safeTitle);
        newWs.put("sessionIds", new JSONArray());
        newWs.put("createdAt", nowIso);
        newWs.put("updatedAt", nowIso);
        workspaces.put(wid, newWs);

        writeWorkspaceJson(controller, root.toString(2));

        // 自动激活切换到新创建的工作区
        controller.config().setWorkdir(targetPath);

        boolean symlink = checkSymlinkSupport(targetPath);
        return new WorkspaceModel(wid, safeTitle, targetPath, 0, true, symlink, nowIso, nowIso);
    }

    /**
     * 切换当前活动工作区
     */
    public static boolean switchWorkspace(HarnessController controller, String wid, String targetPath) {
        if (targetPath == null || targetPath.trim().isEmpty()) return false;
        controller.config().setWorkdir(targetPath.trim());

        try {
            String json = readWorkspaceJson(controller);
            if (json != null && !json.trim().isEmpty()) {
                JSONObject root = new JSONObject(json);
                JSONObject tables = root.optJSONObject("tables");
                JSONObject workspaces = tables != null ? tables.optJSONObject("workspaces") : null;
                if (workspaces != null && workspaces.has(wid)) {
                    JSONObject cur = workspaces.getJSONObject(wid);
                    cur.put("updatedAt", getIsoTimestamp());
                    writeWorkspaceJson(controller, root.toString(2));
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * 删除工作区（仅解除 DSH 注册，不物理清除用户磁盘上的文件以保证物理安全）
     */
    public static boolean deleteWorkspace(HarnessController controller, String wid) {
        try {
            String json = readWorkspaceJson(controller);
            if (json == null || json.trim().isEmpty()) return false;

            JSONObject root = new JSONObject(json);
            JSONObject global = root.optJSONObject("global");
            if (global != null) {
                JSONArray wsIds = global.optJSONArray("workspaceIds");
                if (wsIds != null) {
                    JSONArray newIds = new JSONArray();
                    for (int i = 0; i < wsIds.length(); i++) {
                        String id = wsIds.optString(i);
                        if (!wid.equals(id)) newIds.put(id);
                    }
                    global.put("workspaceIds", newIds);
                }
            }

            JSONObject tables = root.optJSONObject("tables");
            JSONObject workspaces = tables != null ? tables.optJSONObject("workspaces") : null;
            if (workspaces != null && workspaces.has(wid)) {
                workspaces.remove(wid);
                writeWorkspaceJson(controller, root.toString(2));
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "deleteWorkspace failed", t);
        }
        return false;
    }

    /**
     * 重命名工作区
     */
    public static boolean renameWorkspace(HarnessController controller, String wid, String newTitle) {
        if (newTitle == null || newTitle.trim().isEmpty()) return false;
        try {
            String json = readWorkspaceJson(controller);
            if (json == null || json.trim().isEmpty()) return false;

            JSONObject root = new JSONObject(json);
            JSONObject tables = root.optJSONObject("tables");
            JSONObject workspaces = tables != null ? tables.optJSONObject("workspaces") : null;
            if (workspaces != null && workspaces.has(wid)) {
                JSONObject ws = workspaces.getJSONObject(wid);
                ws.put("title", newTitle.trim());
                ws.put("updatedAt", getIsoTimestamp());
                writeWorkspaceJson(controller, root.toString(2));
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "renameWorkspace failed", t);
        }
        return false;
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String p = path.trim();
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.startsWith("/root/内部存储")) {
            p = "/sdcard/Download/DSHA" + p.substring("/root/内部存储".length());
        }
        return p;
    }

    private static String getIsoTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }
}
