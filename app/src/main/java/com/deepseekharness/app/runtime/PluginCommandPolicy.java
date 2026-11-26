package com.deepseekharness.app.runtime;

/** 插件命令外层预算；官方安装器负责内部超时、进程组清理与事务回查。 */
final class PluginCommandPolicy {
    private PluginCommandPolicy() { }

    static long timeoutMs(String args) {
        String value = args == null ? "" : args.trim();
        String command = value.isEmpty() ? "" : value.split("\\s+", 2)[0];
        switch (command) {
            case "list":
            case "show-preview":
                return 30_000L;
            case "install-preview":
            case "rollback":
            case "update":
            case "reconcile":
            case "import":
            case "download":
            case "npm":
            case "github":
            case "release":
                // 两次 600 秒官方调用（安装及失败恢复），另留清理、快照和回查余量。
                return 1_500_000L;
            default:
                return 600_000L;
        }
    }
}
