package com.deepseekharness.app.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Prepares one asset generation without overwriting scripts a running Python process may read. */
public final class PluginScriptDeployment {
    public static final String MANAGER = "plugin-manager.py";
    public static final String REGISTER = "register-builtin-plugins.py";
    public static final String LIFECYCLE = "plugin-lifecycle.py";
    private static final String[] NAMES = {MANAGER, REGISTER, LIFECYCLE};

    private PluginScriptDeployment() { }

    /**
     * Run only the verified, content-addressed group. Canonical CLI paths are published by
     * individual atomic renames; they are not a cross-process atomic group. Old groups stay
     * available so a concurrent manager can still lazily import its own lifecycle module.
     * extraArgs is already shell-quoted by the caller, matching the existing runtime contract.
     */
    public static String command(String home, String manager, String register,
                                 String lifecycle, String extraArgs) {
        if (home == null || !home.startsWith("/") || home.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid plugin script directory");
        }
        String[] scripts = {manager, register, lifecycle};
        String[] hashes = new String[scripts.length];
        StringBuilder generation = new StringBuilder();
        for (int i = 0; i < scripts.length; i++) {
            if (scripts[i] == null || scripts[i].trim().isEmpty()) {
                throw new IllegalArgumentException("ASSET_MISSING:" + NAMES[i]);
            }
            hashes[i] = sha256(scripts[i]);
            generation.append(NAMES[i]).append(':').append(hashes[i]).append('\n');
        }
        String group = home + "/.plugin-scripts-" + sha256(generation.toString());
        StringBuilder cmd = new StringBuilder("set -Eeuo pipefail; ")
                .append("trap 'printf \"ERROR: 插件脚本部署失败\\n\" >&2' ERR; ")
                .append("home=").append(ShellQuote.arg(home)).append("; ")
                .append("group=").append(ShellQuote.arg(group)).append("; ")
                .append("mkdir -p -- \"$home\"; ")
                .append("if [ ! -e \"$group\" ]; then ")
                .append("stage=$(mktemp -d -- \"$home/.plugin-scripts-stage.XXXXXX\"); ");
        for (int i = 0; i < scripts.length; i++) {
            String encoded = Base64.getEncoder().encodeToString(
                    scripts[i].getBytes(StandardCharsets.UTF_8));
            cmd.append("printf '%s' ").append(ShellQuote.arg(encoded))
                    .append(" | base64 -d > \"$stage/").append(NAMES[i]).append("\"; ");
        }
        appendChecks(cmd, "stage", hashes);
        // compile() checks syntax without executing the modules or creating __pycache__.
        cmd.append("python3 -c ").append(ShellQuote.arg(
                "import pathlib, sys\nfor name in sys.argv[1:]:\n"
                        + " p = pathlib.Path(name)\n compile(p.read_bytes(), str(p), 'exec')"));
        for (String name : NAMES) cmd.append(" \"$stage/").append(name).append("\"");
        cmd.append("; chmod 0555 -- \"$stage\"");
        for (String name : NAMES) cmd.append(" \"$stage/").append(name).append("\"");
        // stage and group are fixed children of home, so this rename stays on one filesystem.
        cmd.append("; mv -T -- \"$stage\" \"$group\"; fi; ")
                .append("[ -d \"$group\" ]; [ ! -L \"$group\" ]; ");
        appendChecks(cmd, "group", hashes);
        for (String name : NAMES) {
            cmd.append("publish=$(mktemp -- \"$home/.plugin-script-publish.XXXXXX\"); ")
                    .append("cp -- \"$group/").append(name).append("\" \"$publish\"; ")
                    .append("chmod 0555 -- \"$publish\"; ")
                    // -T replaces a canonical symlink itself instead of following its target.
                    .append("mv -fT -- \"$publish\" \"$home/").append(name).append("\"; ");
        }
        cmd.append("trap - ERR; python3 -B \"$group/").append(MANAGER).append("\"");
        if (extraArgs != null && !extraArgs.isEmpty()) cmd.append(' ').append(extraArgs);
        return cmd.append(" 2>&1").toString();
    }

    private static void appendChecks(StringBuilder cmd, String dir, String[] hashes) {
        for (int i = 0; i < NAMES.length; i++) {
            cmd.append("[ -f \"$").append(dir).append('/').append(NAMES[i])
                    .append("\" ]; [ ! -L \"$").append(dir).append('/').append(NAMES[i])
                    .append("\" ]; ")
                    .append("printf '%s  %s\\n' ").append(ShellQuote.arg(hashes[i]))
                    .append(" \"$").append(dir).append('/').append(NAMES[i])
                    .append("\" | sha256sum -c --status; ");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
