package com.deepseekharness.app.core;

import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** 工作子进程豁免规则；仅访问宿主文件，不依赖 DSH 核心运行状态。 */
public final class FreezeWhitelistRepository {
    public static final int MAX_BYTES = 16 * 1024;
    public static final String PATH = "/data/adb/dsha/run/idle-freeze-whitelist.conf";
    public static final String GUIDE_PATH = "/data/adb/dsha/run/冻结白名单教程.md";
    private static final String INITIAL_RULES =
            "# 冻结白名单：每行一条；示例均为注释，不会生效。\n"
            + "# name:精确进程名\n"
            + "# script:/完整绝对路径/常驻脚本.cjs\n"
            + "# 保存后下轮检查生效；详细说明见同目录《冻结白名单教程.md》。\n"
            + "# 匹配的工作子进程不阻止冻结，冻结时仍会一起挂起。\n";
    private static final String GUIDE =
            "# 冻结白名单规则教程\n\n"
            + "用支持 Root 的文件管理器编辑同目录 idle-freeze-whitelist.conf，保存为 UTF-8。\n\n"
            + "## 两种精确匹配规则\n\n"
            + "- name:进程名：与 /proc/PID/comm 精确匹配；不是 Android 包名，不是完整命令。允许空格，不能含 /，值最多 80 字节 UTF-8。\n"
            + "- script:/完整绝对脚本路径：与 /proc/PID/cmdline 中的单个完整参数精确匹配；空格允许。不按子串、通配符或正则匹配，路径必须以 / 开头。\n\n"
            + "例如（需要自行替换为真实对象后再去掉 #）：\n\n"
            + "```text\n# name:my-worker\n# script:/root/example/bridge.cjs\n```\n\n"
            + "## 格式限制\n\n"
            + "每行一条规则，空行及 # 开头整行注释忽略。只移除整行首尾的空格和 tab，冒号后不要额外加空格。\n"
            + "最多 128 条有效规则，单条规则最多 512 字节 UTF-8，全文最多 16 KiB；规则内部不能有 tab 或控制字符。\n\n"
            + "## 生效范围\n\n"
            + "保存后下一轮冻结检查生效，无需重启 DSH。清空文件仅清除自定义规则，内置名单保持不变。\n"
            + "匹配的直接工作子进程不再阻止 DSH 冻结，冻结时仍会一起挂起；不是让进程永不冻结。\n"
            + "建议匹配具体常驻脚本路径；name:node、name:bash 等宽泛名称会同时忽略同名真实工作进程。\n"
            + "规则文件格式错误时本轮忽略全部自定义规则，原因会记录到冻结日志。\n";

    private FreezeWhitelistRepository() {}

    /** 仅规范化整行边缘空格/tab及换行；冒号后的值不单独 trim。 */
    public static String normalize(String input) {
        if (input == null) input = "";
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("白名单不能超过 16 KiB UTF-8");
        for (int offset = 0; offset < input.length();) {
            int cp = input.codePointAt(offset);
            if ((Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')
                    || cp == 0x2028 || cp == 0x2029 || (cp >= 0xd800 && cp <= 0xdfff))
                throw new IllegalArgumentException("白名单包含不支持的控制字符或无效字符");
            offset += Character.charCount(cp);
        }
        StringBuilder result = new StringBuilder();
        int rules = 0;
        String[] lines = input.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int number = 0; number < lines.length; number++) {
            String line = trimLine(lines[number]);
            if (line.isEmpty()) continue;
            if (!line.startsWith("#")) {
                String error = null;
                if (line.getBytes(StandardCharsets.UTF_8).length > 512) error = "规则最长为 512 字节 UTF-8";
                else if (line.indexOf('\t') >= 0) error = "规则内部不能包含 tab";
                else if (line.startsWith("name:")) {
                    String value = line.substring(5);
                    if (value.isEmpty() || value.indexOf('/') >= 0
                            || value.getBytes(StandardCharsets.UTF_8).length > 80)
                        error = "name: 后需填写无斜杠的精确进程名，最多 80 字节 UTF-8";
                } else if (line.startsWith("script:")) {
                    if (!line.substring(7).startsWith("/")) error = "script: 后需填写以 / 开头的完整绝对路径";
                } else error = "规则必须以 name: 或 script: 开头";
                if (error != null) throw new IllegalArgumentException("第 " + (number + 1) + " 行：" + error);
                if (++rules > 128) throw new IllegalArgumentException("最多保存 128 条有效规则");
            }
            result.append(line).append('\n');
        }
        String normalized = result.toString();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("规范化后的白名单不能超过 16 KiB UTF-8");
        return normalized;
    }

    private static String trimLine(String value) {
        int start = 0, end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) start++;
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) end--;
        return value.substring(start, end);
    }

    static String readCommand() {
        return "BASE=/data/adb/dsha; DIR=\"$BASE/run\"; FILE=" + ShellQuote.arg(PATH) + "; "
                + "[ -d \"$BASE\" ] && [ -r \"$BASE\" ] && [ -x \"$BASE\" ] || { echo '无法访问DSHA目录' >&2; exit 1; }; "
                + "if [ -e \"$DIR\" ]; then [ -d \"$DIR\" ] && [ -r \"$DIR\" ] && [ -x \"$DIR\" ] || { echo '无法访问run目录' >&2; exit 1; }; fi; "
                + "[ ! -L \"$FILE\" ] || { echo '白名单不能是符号链接' >&2; exit 1; }; "
                + "if [ -e \"$FILE\" ]; then "
                + "[ -f \"$FILE\" ] && [ -r \"$FILE\" ] || { echo '白名单不是可读文件' >&2; exit 1; }; "
                + "SIZE=$(wc -c < \"$FILE\") || exit 1; "
                + "[ \"$SIZE\" -le 16384 ] || { echo '白名单超过16KiB，拒绝截断读取' >&2; exit 1; }; "
                + "head -c 16385 \"$FILE\"; fi";
    }

    static String saveCommand(String normalized) {
        return "set -e; umask 077; DIR=/data/adb/dsha/run; FILE=" + ShellQuote.arg(PATH) + "; "
                + "mkdir -p \"$DIR\"; "
                + "[ ! -L \"$FILE\" ] || { echo '白名单不能是符号链接' >&2; exit 1; }; "
                + "[ ! -e \"$FILE\" ] || [ -f \"$FILE\" ] || { echo '白名单目标不是普通文件' >&2; exit 1; }; "
                + "TMP=$(mktemp \"$DIR/.idle-freeze-whitelist.XXXXXX\"); "
                + "trap 'rm -f \"$TMP\"' EXIT; trap 'exit 1' HUP INT TERM; "
                + "chmod 600 \"$TMP\"; printf '%s' " + ShellQuote.arg(normalized)
                + " > \"$TMP\"; mv -f \"$TMP\" \"$FILE\"";
    }

    static String prepareLocationCommand() {
        // ln 原子地以“目标仍不存在”为条件发布，避免并发打开时覆盖用户已有规则。
        return "set -e; umask 077; DIR=/data/adb/dsha/run; mkdir -p \"$DIR\"; "
                + "TMP=''; trap '[ -z \"$TMP\" ] || rm -f \"$TMP\"' EXIT; trap 'exit 1' HUP INT TERM; "
                + "ensure_file() { FILE=\"$1\"; CONTENT=\"$2\"; "
                + "[ ! -L \"$FILE\" ] || { echo '配置或教程不能是符号链接' >&2; return 1; }; "
                + "if [ -e \"$FILE\" ]; then [ -f \"$FILE\" ] || { echo '配置或教程不是普通文件' >&2; return 1; }; return 0; fi; "
                + "TMP=$(mktemp \"$DIR/.freeze-location.XXXXXX\"); chmod 600 \"$TMP\"; printf '%s' \"$CONTENT\" > \"$TMP\"; "
                + "if ! ln \"$TMP\" \"$FILE\" 2>/dev/null; then "
                + "[ ! -L \"$FILE\" ] && [ -f \"$FILE\" ] || { echo '创建配置或教程失败' >&2; return 1; }; fi; "
                + "rm -f \"$TMP\"; TMP=''; }; "
                + "ensure_file " + ShellQuote.arg(PATH) + " " + ShellQuote.arg(INITIAL_RULES) + "; "
                + "ensure_file " + ShellQuote.arg(GUIDE_PATH) + " " + ShellQuote.arg(GUIDE) + "; "
                + "printf '%s' " + ShellQuote.arg(PATH);
    }

    /** 仅首次建立注释模板及同目录教程，已有规则和教程保持原样。 */
    public static String prepareLocation(File cacheDir) throws IOException, InterruptedException {
        String result = execute(cacheDir, prepareLocationCommand());
        if (!PATH.equals(result)) throw new IOException("白名单返回了异常位置");
        return result;
    }

    public static String read(File cacheDir) throws IOException, InterruptedException {
        return execute(cacheDir, readCommand());
    }

    public static void save(File cacheDir, String text) throws IOException, InterruptedException {
        execute(cacheDir, saveCommand(normalize(text)));
    }

    private static String execute(File cacheDir, String command) throws IOException, InterruptedException {
        File output = File.createTempFile("freeze-whitelist-out-", ".tmp", cacheDir);
        File error = null;
        Process process = null;
        try {
            error = File.createTempFile("freeze-whitelist-error-", ".tmp", cacheDir);
            process = new ProcessBuilder("su", "-c", command).redirectOutput(output).redirectError(error).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Root 操作超时，请检查授权");
            if (process.exitValue() != 0) {
                String detail = readUtf8(error, 2048);
                throw new IOException("Root 操作失败（退出码 " + process.exitValue() + "）" + (detail.isEmpty() ? "" : "：" + detail));
            }
            return readUtf8(output, MAX_BYTES);
        } finally {
            if (process != null && process.isAlive()) process.destroy();
            output.delete();
            if (error != null) error.delete();
        }
    }

    private static String readUtf8(File file, int limit) throws IOException {
        try (java.io.InputStream stream = Files.newInputStream(file.toPath())) {
            byte[] bytes = new byte[limit + 1];
            int size = 0, count;
            while (size < bytes.length && (count = stream.read(bytes, size, bytes.length - size)) != -1) size += count;
            if (size > limit) throw new IOException("读取内容超过大小限制，未截断展示或保存");
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString();
            } catch (CharacterCodingException invalid) {
                throw new IOException("文件不是有效 UTF-8，未开放编辑以避免覆盖原内容", invalid);
            }
        }
    }
}
