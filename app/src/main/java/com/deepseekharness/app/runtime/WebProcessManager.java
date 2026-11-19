package com.deepseekharness.app.runtime;

/** 原生模块 Web 进程的停止入口，具体生命周期由模块脚本管理。 */
public class WebProcessManager {

    public WebProcessManager(ProotBootstrap proot) {
        // 保留构造入口兼容；原生模块脚本不需要 APK 内部运行时对象。
    }

    /** 停止模块管理的 Web 进程。 */
    public void stop() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-mm", "-c", "/data/adb/dsha/scripts/stop.sh"});
            p.waitFor();
        } catch (Exception ignored) {
        }
    }
}
