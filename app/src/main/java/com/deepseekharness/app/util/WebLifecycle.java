package com.deepseekharness.app.util;

/** 进程级启停门控：只管单飞、用户停止意图和会话代次，不执行 IO。 */
public final class WebLifecycle {
    private long generation;
    private boolean starting;
    private boolean restarting;
    private boolean stopping;
    private boolean userStopped;

    /** 返回新代次；忙碌或不允许自动拉起时返回 -1。 */
    public synchronized long beginStart(boolean automatic, boolean stopSentinelPresent) {
        if (starting || stopping || (automatic && (userStopped || stopSentinelPresent))) {
            return -1;
        }
        userStopped = false;
        restarting = false;
        starting = true;
        return ++generation;
    }

    /** 强制启动：打破任何残留卡死状态，开启全新代次。 */
    public synchronized long forceBeginStart() {
        userStopped = false;
        stopping = false;
        restarting = false;
        starting = true;
        return ++generation;
    }

    /** 强制重启：记录重启标志并打破旧状态，开启全新代次。 */
    public synchronized long forceBeginRestart() {
        userStopped = false;
        stopping = false;
        restarting = true;
        starting = true;
        return ++generation;
    }

    /** 停止请求立即撤销旧代次，重复停止合并。 */
    public synchronized long beginStop() {
        if (!stopping) {
            userStopped = true;
            starting = false;
            restarting = false;
            stopping = true;
            generation++;
        }
        return generation;
    }

    public synchronized boolean finishStart(long expected) {
        if (!isCurrent(expected) || !starting) return false;
        starting = false;
        restarting = false;
        return true;
    }

    public synchronized void finishStop(long expected) {
        if (isCurrent(expected)) {
            stopping = false;
            restarting = false;
        }
    }

    public synchronized boolean canAutoStart(boolean stopSentinelPresent) {
        return !starting && !stopping && !userStopped && !stopSentinelPresent;
    }

    public synchronized boolean isCurrent(long expected) {
        return expected > 0 && generation == expected;
    }

    public synchronized long generation() { return generation; }
    public synchronized boolean isStarting() { return starting; }
    public synchronized boolean isRestarting() { return restarting; }
    public synchronized boolean isStopping() { return stopping; }
    public synchronized boolean isUserStopped() { return userStopped; }
}
