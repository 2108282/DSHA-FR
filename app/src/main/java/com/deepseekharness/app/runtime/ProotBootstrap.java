package com.deepseekharness.app.runtime;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.util.PluginScriptDeployment;
import com.deepseekharness.app.util.PluginScriptProcess;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.deepseekharness.app.util.SensitiveData;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 原生 chroot 运行时入口；保留历史类名与仍可达的离线环境兼容入口。
 */
public class ProotBootstrap {

    private static final String[] BUNDLE_NAMES = {
            "offline-rootfs.bin", "offline-rootfs.tar.gz", "offline-rootfs.tar", "offline-rootfs.tgz",
    };

    private final Context ctx;
    private final File baseDir;
    private final File rootfsDir;
    private final File offlineMarkerFile;

    public ProotBootstrap(Context c) {
        ctx = c.getApplicationContext();
        baseDir = new File(ctx.getFilesDir(), "linux");
        rootfsDir = new File(baseDir, "ubuntu");
        offlineMarkerFile = new File(baseDir, ".offline-extracted");
    }

    public File getRootfsDir() {
        return new File("/data/adb/dsha/rootfs");
    }

    /** 内置离线包的版本标记，供显式离线解压入口比较版本。 */
    public static final String OFFLINE_VERSION_ASSET = "offline-rootfs.version";

    /** 已解压 rootfs 的版本记录文件（app 私有目录，覆盖安装保留）。 */
    private File offlineVersionFile() {
        return new File(baseDir, ".offline-version");
    }

    /**
     * 已解压 rootfs 的版本是否与 APK 内置离线包一致。
     * 仅供显式解压入口决定是否清理 App 私有目录中的旧副本，不参与原生模块的就绪判定。
     */
    public boolean rootfsVersionMatches() {
        try {
            String baked = readAssetString(OFFLINE_VERSION_ASSET).trim();
            if (baked.isEmpty()) return true; // 精简包没有版本标记，不强制
            File vf = offlineVersionFile();
            String stored = vf.isFile()
                    ? new String(Compat.readAllBytes(vf),
                    java.nio.charset.StandardCharsets.UTF_8).trim() : "";
            return baked.equals(stored);
        } catch (Throwable e) {
            return true; // 读不到版本时不做强制
        }
    }

    public boolean isEnvironmentReady() {
        return ContainerRuntime.KsuChroot.checkAvailable();
    }

    public void markOfflineExtracted() {
        try {
            baseDir.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            offlineMarkerFile.createNewFile();
            // 记录本次解压的内置包版本，供下次覆盖安装比对
            String baked = readAssetString(OFFLINE_VERSION_ASSET).trim();
            if (!baked.isEmpty()) {
                Compat.write(offlineVersionFile(),
                        baked.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    /** 撤销 App 私有目录中的离线解压标记，不清理原生模块环境或自动触发解压。 */
    public void markNotExtracted() {
        //noinspection ResultOfMethodCallIgnored
        offlineMarkerFile.delete();
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        try {
            if (Compat.isSymbolicLink(f)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return;
            }
        } catch (Throwable ignored) {
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ================= 运行时文件 =================

    /** 原生运行时由模块准备，保留历史调用入口。 */
    public void ensureRuntimeFiles() {
        // no-op: Magisk / KernelSU 模块管理运行时文件。
    }

    // ================= 内置插件注册 =================

    /** 内置插件注册脚本（rootfs 烘焙的四个内置插件 → web profile），资产名。 */
    public static final String BUILTIN_REGISTER_SCRIPT = "register-builtin-plugins.py";
    private static final Object PLUGIN_SCRIPT_LOCK = new Object();

    /**
     * 幂等：把内置插件注册脚本注入 rootfs 并运行，把 dsh-device-shell-guide 等四个
     * 内置插件登记进 web profile（bundles + dependencies[link:] + node_modules 链接）。
     *
     * <p>为什么需要：插件的<b>实体</b>随离线 rootfs 烘焙在 /root/dsha-*，但 dsh 只在
     * profile 的 dsh.profile.bundles 里列名、且 node_modules 下能解析到实体时才加载。
     * 重构骨架曾丢失这一步 —— 覆盖安装（rootfs 保留）与全新安装（rootfs 重新解压）
     * 两条路径都要靠它补齐注册。脚本幂等、只合并不删除；用户禁用过的插件
     * （node_modules/<name>.disabled 标记）会被尊重而跳过。见脚本头部注释。
     *
     * @return 脚本输出摘要（BUILTIN_REGISTER_OK / PARTIAL / FAIL），供日志与插件页对账。
     */
    public String registerBuiltinPlugins() {
        return runBuiltinScript("");
    }

    /**
     * 启用 / 禁用某个插件（内置、官方核心或第三方）：
     * 遵循 DSH 官方规范，修改 profile 的 package.json 中的 dsh.profile.bundles 数组。
     * 启用时追加到 bundles 列表，禁用时从 bundles 移除，完全对齐官方 DSH 后端基准。
     * 同时顺手清理历史 PatchToggle 写入 cordis.patch.yml 的残留块以及 .disabled 标记。
     *
     * @param name   插件名（如 dsh-web-mobile、@deepseek-ai/dsh-web-app 或 dsh-xiaomi-asr）
     * @param enable true=启用 false=禁用
     */
    public String setPluginEnabled(String name, boolean enable) {
        if (name == null || name.isEmpty()) return "NO_NAME";
        synchronized (PLUGIN_SCRIPT_LOCK) {
            try {
                // 1. 读取当前的 profile package.json 文本
                String pkgFile = "/root/.dsh/profiles/web/package.json";
                String jsonStr = execAndRead("cat " + pkgFile + " 2>/dev/null || true", 5000);
                if (jsonStr == null || jsonStr.startsWith("ERROR:") || jsonStr.trim().isEmpty()) {
                    return "ERROR: package.json 读取失败";
                }

                org.json.JSONObject doc = new org.json.JSONObject(jsonStr);
                org.json.JSONObject dsh = doc.optJSONObject("dsh");
                if (dsh == null) {
                    dsh = new org.json.JSONObject();
                    doc.put("dsh", dsh);
                }
                org.json.JSONObject profile = dsh.optJSONObject("profile");
                if (profile == null) {
                    profile = new org.json.JSONObject();
                    dsh.put("profile", profile);
                }
                org.json.JSONArray bundlesArr = profile.optJSONArray("bundles");
                List<String> bundles = new ArrayList<>();
                if (bundlesArr != null) {
                    for (int i = 0; i < bundlesArr.length(); i++) {
                        bundles.add(bundlesArr.getString(i));
                    }
                }

                if (enable) {
                    if (!bundles.contains(name)) {
                        bundles.add(name);
                    }
                } else {
                    bundles.remove(name);
                }
                profile.put("bundles", new org.json.JSONArray(bundles));

                // 2. 原子安全写入 package.json
                String newJson = doc.toString(2) + "\n";
                String b64Pkg = Base64.encodeToString(newJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                String writePkgCmd = "printf '%s' '" + b64Pkg + "' | base64 -d > " + pkgFile;
                String writePkgOut = execAndRead(writePkgCmd, 10000);
                if (writePkgOut != null && writePkgOut.startsWith("ERROR:")) return writePkgOut;

                // 3. 清理历史遗留在 cordis.patch.yml 中的 managed block
                String patchFile = "/root/.dsh/profiles/web/cordis.patch.yml";
                String currentYaml = execAndRead("cat " + patchFile + " 2>/dev/null || true", 5000);
                if (currentYaml != null && currentYaml.contains("# >>> DSHA managed")) {
                    String cleanedYaml = stripLegacyPatchBlock(currentYaml);
                    String b64Patch = Base64.encodeToString(cleanedYaml.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                    execAndRead("printf '%s' '" + b64Patch + "' | base64 -d > " + patchFile, 5000);
                }

                // 4. 清理历史遗留的 .disabled 物理标记文件
                execAndRead("rm -f /root/.dsh/profiles/web/node_modules/" + name + ".disabled /root/.dsh/plugin-src/" + name + ".disabled 2>/dev/null || true", 3000);

                return "BUILTIN_REGISTER_OK: " + name + (enable ? " 已启用" : " 已禁用");
            } catch (Throwable e) {
                Log.w("DSHA", "插件启用/禁用切换失败: " + SensitiveData.redact(String.valueOf(e)));
                return "ERROR: " + SensitiveData.redact(String.valueOf(e));
            }
        }
    }

    private static String stripLegacyPatchBlock(String yaml) {
        if (yaml == null || !yaml.contains("# >>> DSHA managed")) return yaml == null ? "" : yaml;
        return yaml.replaceAll("(?s)# >>> DSHA managed[^\n]*\n.*?# <<< DSHA managed <<<(?:\n|$)", "").trim();
    }

    /** 注入注册脚本（幂等覆盖）并按需带参数运行。 */
    private String runBuiltinScript(String extraArgs) {
        synchronized (PLUGIN_SCRIPT_LOCK) {
        if (!isEnvironmentReady()) return "ENV_NOT_READY";
        if (!ensureBundledPython()) return "ERROR: Ubuntu Python 环境未就绪";
        try {
            String script = readAssetString(BUILTIN_REGISTER_SCRIPT);
            if (script.isEmpty()) return "ASSET_MISSING:" + BUILTIN_REGISTER_SCRIPT;
            String b64 = Base64.encodeToString(script.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
            String cmd = "set -e; mkdir -p /root/.dsh; "
                    + "printf '%s' '" + b64 + "' | base64 -d > /root/.dsh/" + BUILTIN_REGISTER_SCRIPT + "; "
                    + "chmod +x /root/.dsh/" + BUILTIN_REGISTER_SCRIPT + "; "
                    + "python3 /root/.dsh/" + BUILTIN_REGISTER_SCRIPT
                    + (extraArgs.isEmpty() ? "" : " " + extraArgs) + " 2>&1";
            return execAndRead(cmd, 90_000);
        } catch (Throwable e) {
            Log.w("DSHA", "内置插件脚本执行失败: " + SensitiveData.redact(String.valueOf(e)));
            return "ERROR: " + SensitiveData.redact(String.valueOf(e));
        }
        }
    }

    // ================= 第三方插件管理（导入/导出/GitHub 下载） =================

    /** 插件管理脚本（导入、导出、链接安装与状态读取），资产名。 */
    public static final String PLUGIN_MANAGER_SCRIPT = "plugin-manager.py";

    /**
     * 同组部署插件管理、共用注册与生命周期脚本，再通过 python3 执行。
     * 末行 PLUGIN_RESULT JSON 区分成功、部分成功和失败。
     *
     * @param extraArgs 例如 {@code import /root/.dsh/import-upload.bin}、
     *                  {@code export '["dsh-web-mobile"]' /root/.dsh/export.tar.gz}、
     *                  {@code github owner repo 'branch/subdir'}
     */
    public String runPluginManager(String extraArgs) {
        synchronized (PLUGIN_SCRIPT_LOCK) {
        if (!isEnvironmentReady()) return "ENV_NOT_READY";
        if (!ensureBundledPython()) return "ERROR: Ubuntu Python 环境未就绪";
        try {
            String script = readAssetString(PLUGIN_MANAGER_SCRIPT);
            if (script.isEmpty()) return "ASSET_MISSING:" + PLUGIN_MANAGER_SCRIPT;
            String common = readAssetString(BUILTIN_REGISTER_SCRIPT);
            if (common.isEmpty()) return "ASSET_MISSING:" + BUILTIN_REGISTER_SCRIPT;
            String lifecycle = readAssetString(PluginScriptDeployment.LIFECYCLE);
            if (lifecycle.isEmpty()) return "ASSET_MISSING:" + PluginScriptDeployment.LIFECYCLE;
            // 保留 canonical CLI 路径；本次调用从完整校验的同版本组导入 siblings。
            String cmd = PluginScriptDeployment.command("/root/.dsh", script, common,
                    lifecycle, extraArgs);
            // 长脚本通过 PIPE stdin 传入；绝不能放入 su -c 的单个 argv（Android E2BIG）。
            ProcessBuilder runner = new ProcessBuilder(PluginScriptProcess.argv(
                    getRootfsDir().getAbsolutePath())).redirectErrorStream(true);
            return PluginScriptProcess.exchange(runner.start(), cmd, 600_000);
        } catch (Throwable e) {
            Log.w("DSHA", "插件管理脚本执行失败: " + SensitiveData.redact(String.valueOf(e)));
            return "ERROR: " + SensitiveData.redact(String.valueOf(e));
        }
        }
    }

    /** 把本地文件推入容器（containerPath 为容器内绝对路径，如 /root/.dsh/import-upload.bin）。 */
    public boolean pushFileIntoContainer(java.io.File src, String containerPath) {
        if (src == null || !src.isFile() || containerPath == null) return false;
        try {
            java.io.File target = containerFile(containerPath);
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-mm", "-c", "mkdir -p " + ShellQuote.arg(target.getParentFile().getAbsolutePath())
                    + " && cat > " + ShellQuote.arg(target.getAbsolutePath())
            });
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = p.getOutputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }
            return p.waitFor() == 0;
        } catch (Throwable e) {
            Log.w("DSHA", "推文件进原生 rootfs 失败: " + SensitiveData.redact(String.valueOf(e)));
            return false;
        }
    }

    /** 从容器取出文件到本地（containerPath 为容器内绝对路径）。 */
    public boolean pullFileFromContainer(String containerPath, java.io.File dest) {
        if (containerPath == null || dest == null) return false;
        try {
            java.io.File src = containerFile(containerPath);
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-mm", "-c", "cat " + ShellQuote.arg(src.getAbsolutePath())
            });
            if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
            try (java.io.InputStream in = p.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return p.waitFor() == 0;
        } catch (Throwable e) {
            Log.w("DSHA", "从原生 rootfs 取文件失败: " + SensitiveData.redact(String.valueOf(e)));
            return false;
        }
    }

    /** 容器内绝对路径 → 宿主文件系统路径（rootfs 根下）。 */
    public java.io.File containerFile(String containerPath) {
        String rel = containerPath.startsWith("/")
                ? containerPath.substring(1) : containerPath;
        return new java.io.File(getRootfsDir(), rel);
    }

    /** 读 assets 文本（Windows 检出可能是 CRLF，统一转 LF 再交给容器脚本）。 */
    private String readAssetString(String name) {
        try (InputStream in = ctx.getAssets().open(name);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            return "";
        }
    }

    // ================= Android 组补丁（id -Gn 报错） =================

    /**
     * 为旧 App 私有 rootfs 的 {@code /etc/group} 补充 Android GID 名称（幂等）。
     * 私有副本不存在时直接返回；原生模块的组表不由此方法管理。
     * 兼容副本中的 shell 查询缺失 GID 时可能报 {@code cannot find name for group ID}，
     * 此处合并历史映射与当前 App 进程的 supplementary groups。
     */
    public void ensureAndroidGroups() {
        try {
            if (!rootfsDir.isDirectory()) return;
            File groupFile = new File(rootfsDir, "etc/group");
            if (!groupFile.isFile()) return;
            String content = new String(Compat.readAllBytes(groupFile),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[][] known = {
                    {"input", "1004"},
                    {"log", "1007"},
                    {"adb", "1011"},
                    {"sdcard_rw", "1015"},
                    {"sdcard_r", "1028"},
                    {"ext_data_rw", "1078"},
                    {"ext_obb_rw", "1079"},
                    {"net_bt_admin", "3001"},
                    {"net_bt", "3002"},
                    {"inet", "3003"},
                    {"net_bw_stats", "3006"},
                    {"readproc", "3009"},
                    {"uhid", "3011"},
                    {"readtracefs", "3012"},
                    {"everybody", "9997"},
                    {"all_a428", "50428"},
                    {"u0_a428", "20428"},
            };
            // 合并已知映射与当前 App 进程的组；ROM 自定义组从 /proc/self/status 读取。
            java.util.LinkedHashMap<String, String> groups = new java.util.LinkedHashMap<>();
            for (String[] g : known) groups.put(g[0], g[1]);
            for (int gid : readSelfGroupList()) {
                if (!groups.containsValue(String.valueOf(gid))) {
                    groups.put("aid_" + gid, String.valueOf(gid));
                }
            }
            StringBuilder need = new StringBuilder();
            for (java.util.Map.Entry<String, String> g : groups.entrySet()) {
                // 精确匹配「:GID:」段，避免误判名字相同但 ID 不同的行
                if (content.indexOf(":" + g.getValue() + ":") < 0) {
                    need.append(g.getKey()).append(":x:").append(g.getValue()).append(":\n");
                }
            }
            if (need.length() == 0) return;
            Compat.append(groupFile, need.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Log.i("DSHA", "已补 " + groups.size() + " 个 Android 组到 /etc/group");
            // 兜底：把 /etc/bash.bashrc 里登录时执行的 $(groups) 改成吞掉 stderr。
            // 未来出现未列出的新 GID 时，id 仍会打 cannot find name，但不会再刷到终端里。
            patchBashrcGroups(groupFile);
        } catch (Throwable e) {
            Log.w("DSHA", "补 /etc/group 失败（不影响核心功能）: "
                    + SensitiveData.redact(String.valueOf(e)));
        }
    }

    /** 读本进程全部 supplementary groups（/proc/self/status 的 Groups 行）。 */
    private static java.util.List<Integer> readSelfGroupList() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        try {
            String st = new String(Compat.readAllBytes(
                    new java.io.File("/proc/self/status")),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : st.split("\n")) {
                if (line.startsWith("Groups:")) {
                    for (String id : line.substring(7).trim().split("\\s+")) {
                        if (id.isEmpty()) continue;
                        try {
                            int v = Integer.parseInt(id);
                            if (v > 0 && v != 0x7fffffff) out.add(v);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 把 /etc/bash.bashrc 的 sudo 检测 {@code $(groups)} 改为 {@code $(groups 2>/dev/null)}。 */
    private void patchBashrcGroups(File groupFile) {
        try {
            File bashrc = new File(rootfsDir, "etc/bash.bashrc");
            if (!bashrc.isFile()) return;
            String c = new String(Compat.readAllBytes(bashrc),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (c.contains("groups 2>/dev/null")) return; // 已 patch
            String patched = c.replace("$(groups) ", "$(groups 2>/dev/null) ");
            if (!patched.equals(c)) {
                Compat.write(bashrc, patched.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Log.i("DSHA", "已 patch /etc/bash.bashrc：$(groups) 加 2>/dev/null");
            }
        } catch (Throwable e) {
            Log.w("DSHA", "patch /etc/bash.bashrc 失败: "
                    + SensitiveData.redact(String.valueOf(e)));
        }
    }

    // ================= 运行时选择 =================

    public ContainerRuntime runtime() {
        return new ContainerRuntime.KsuChroot();
    }

    // ================= 执行 =================

    /** 在 rootfs 内执行 bash 命令，返回进程（stderr 并入 stdout）。 */
    public Process execRootfs(String bashCommand) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add("su");
        argv.add("-mm");
        argv.add("-c");
        String fullCmd = "chroot " + getRootfsDir().getAbsolutePath() + " /usr/bin/env -i "
                + "HOME=/root USER=root LOGNAME=root "
                + "PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
                + "TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 "
                + "/bin/bash -c " + ShellQuote.arg(bashCommand);
        argv.add(fullCmd);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        Compat.redirectStdinDevNull(pb);
        return pb.start();
    }

    /** 同步执行 rootfs 命令并读回输出（默认 60s 超时防卡死）。 */
    public String execAndRead(String bashCommand) {
        return execAndRead(bashCommand, 60_000);
    }

    public String execAndRead(String bashCommand, long timeoutMs) {
        try {
            Process p = execRootfs(bashCommand);
            java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(
                    () -> readStream(p.getInputStream()));
            Thread t = new Thread(task, "exec-read");
            t.setDaemon(true);
            t.start();
            String out;
            try {
                out = task.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception te) {
                Compat.destroy(p);
                return "ERROR: 命令执行超时(>" + (timeoutMs / 1000) + "s)，已强杀";
            }
            if (!Compat.waitFor(p, 3000)) {
                Compat.destroy(p);
            }
            return out;
        } catch (Throwable e) {
            return "ERROR: " + SensitiveData.redact(String.valueOf(e));
        }
    }

    /** 保留历史方法名；固定原生 chroot 后直接使用当前运行时。 */
    public String execAndReadWithProot(String bashCommand, long timeoutMs) {
        return execAndRead(bashCommand, timeoutMs);
    }

    /** 同步执行 rootfs 命令，退出码非 0 抛异常。 */
    public String execChecked(String bashCommand) throws IOException {        Process p = execRootfs(bashCommand);
        String out = readStream(p.getInputStream());
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断", e);
        }
        if (code != 0) {
            String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
            throw new IOException("退出码 " + code + "：\n" + tail);
        }
        return out;
    }

    // ================= PTY 终端（Termux terminal-view） =================

    /**
     * 委托模块 term.sh 创建交互式 bash 会话，保留 stdin/stdout 供终端读写。
     * 与 execRootfs 执行单条命令不同，此入口传入交互确认环境变量。
     */
    public Process execRootfsInteractive() throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add(ContainerRuntime.KsuChroot.findSuBinary());
        argv.add("-c");
        argv.add("/data/adb/dsha/scripts/term.sh");
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().put("DSH_CONFIRM", "1");
        pb.environment().put("DSH_INTERACTIVE", "1");
        return pb.start();
    }

    /**
     * PTY 终端 argv：直接通过 su -mm 以 Magisk/KernelSU root namespace 调用 term.sh，
     * term.sh 负责所有 bind-mount 与 chroot 准备工作，与手动在 MT 管理器执行完全等价。
     */
    public String[] ptyArgv(String... guestCmd) {
        String su = ContainerRuntime.KsuChroot.findSuBinary();
        String termScript = "/data/adb/dsha/scripts/term.sh";
        if (guestCmd != null && guestCmd.length > 0) {
            StringBuilder sb = new StringBuilder(termScript);
            for (String arg : guestCmd) sb.append(" ").append(arg);
            return new String[]{su, "-mm", "-c", sb.toString()};
        }
        return new String[]{su, "-mm", "-c", termScript};
    }

    /**
     * PTY 终端环境变量：补充终端类型、locale 与宿主系统 PATH，
     * 确保 JNI clearenv 后子进程仍持有完整的 PATH 变量。
     */
    public String[] ptyEnv() {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            path = "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin";
        }
        return new String[]{
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                "PATH=" + path
        };
    }

    private String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int kept = 0;
        final int MAX = 256 * 1024;
        while ((n = in.read(buf)) != -1) {
            if (kept < MAX) {
                int w = Math.min(n, MAX - kept);
                bos.write(buf, 0, w);
                kept += w;
            }
        }
        return bos.toString("UTF-8");
    }

    /** 冒烟测试：原生 chroot 能否 exec + 进 rootfs。 */
    public String smokeTest() {
        try {
            ensureRuntimeFiles();
            StringBuilder diag = new StringBuilder();
            diag.append("运行时: ").append(runtime().displayName()).append("\n");
            diag.append("rootfs: ").append(getRootfsDir().getAbsolutePath()).append("\n");
            String out = execAndRead("/bin/echo SMOKE_OK");
            diag.append("rootfs exec: ").append(out == null ? "" : out.trim()).append("\n");
            return SensitiveData.redact(diag.toString());
        } catch (Throwable e) {
            return SensitiveData.redact("CHROOT_FAIL: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    // ================= 离线 rootfs 解压 =================

    public boolean hasOfflineBundle() {
        try (ZipFile z = new ZipFile(ctx.getPackageCodePath())) {
            if (findBundleEntry(z) != null) return true;
        } catch (Exception ignored) {
        }
        for (String n : BUNDLE_NAMES) {
            try {
                ctx.getAssets().open(n).close();
                return true;
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    private ZipEntry findBundleEntry(ZipFile z) {
        for (String n : BUNDLE_NAMES) {
            ZipEntry e = z.getEntry("assets/" + n);
            if (e != null && !e.isDirectory()) return e;
            e = z.getEntry(n);
            if (e != null && !e.isDirectory()) return e;
        }
        ZipEntry best = null;
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (e.isDirectory()) continue;
            if (name.contains("offline-rootfs") || name.contains("offline_rootfs")) {
                if (best == null || e.getSize() > best.getSize()) best = e;
            }
        }
        return best;
    }

    /**
     * 从 APK 内置包解压 rootfs。优先按 zip 条目流式解压（不经 AssetManager，
     * 也不先拷 300MB 到 tmp）。
     */
    public void extractOfflineBundle(java.util.function.BiConsumer<Long, Long> onProgress)
            throws IOException {
        ensureRuntimeFiles();
        ZipFile apk = null;
        InputStream raw = null;
        try {
            apk = new ZipFile(ctx.getPackageCodePath());
            ZipEntry e = findBundleEntry(apk);
            if (e != null) raw = apk.getInputStream(e);
        } catch (IOException ignored) {
            if (apk != null) {
                try { apk.close(); } catch (IOException ignored2) { }
                apk = null;
            }
        }
        if (raw == null) {
            IOException last = null;
            for (String n : BUNDLE_NAMES) {
                try {
                    raw = ctx.getAssets().open(n);
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }
            if (raw == null) {
                throw last != null ? last : new IOException("assets 里也没有离线包");
            }
        }

        InputStream counted = raw;
        final java.util.function.BiConsumer<Long, Long> cb = onProgress;
        if (cb != null) {
            counted = new java.io.FilterInputStream(raw) {
                long done = 0;
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) {
                        done += n;
                        cb.accept(done, -1L);
                    }
                    return n;
                }
            };
        }

        // 覆盖安装换了内置包（版本不符）时，先清掉旧 rootfs 再解压，
        // 避免旧版残留文件（alpha.5 独有的 dsh 文件）与新包混在一起
        if (!rootfsVersionMatches()) {
            Log.i("DSHA", "rootfs 版本变化，清除旧环境后重新解压");
            deleteRecursively(rootfsDir);
        }
        rootfsDir.mkdirs();
        TarGzipExtractor.extractAuto(counted, rootfsDir, 0);
        if ("split-runtime-v1".equals(readAssetString("offline-rootfs.layout").trim())) {
            try (InputStream input = ctx.getAssets().open("dsh-runtime.bin")) {
                TarGzipExtractor.extractAuto(input, rootfsDir, 0);
            } catch (Throwable e) {
                Log.w("DSHA", "解压独立 dsh-runtime 失败或不存在: " + e.getMessage());
            }
        }
        // Python 与 pnpm 由原生模块提供；此兼容入口为私有副本准备证书和插件管理工具。
        RuntimeTools.prepare(ctx, rootfsDir);
        markOfflineExtracted();
    }

    public boolean ensureBundledPython() {
        return isEnvironmentReady();
    }

    public boolean ensureGlibcPython() {
        return isEnvironmentReady();
    }

    public boolean ensureBundledPnpm() {
        return true;
    }
}
