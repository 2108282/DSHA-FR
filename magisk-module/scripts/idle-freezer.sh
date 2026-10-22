#!/system/bin/sh
# ============================================================
# DSHA 闲置深度冻结守护程序 (DSHA Idle Freezer Daemon - Turbo Edition)
# 
# 核心特性：
# 1. 毫秒级极速唤醒：所有广播与远程通知全面后台异步化 (&)，唤醒耗时 < 15ms；
# 2. 杜绝回调死循环：移除冗余的 frozen=0 反向循环调用，切断自我触发；
# 3. 动态双模阈值：息屏 5 分钟加速冻结，亮屏 30 分钟避让；
# 4. 亮屏防背刺：硬件挂起唤醒后若处于亮屏，自动避让重置计时；
# 5. 内存换页保护与 ZRAM 自然压缩无缝协同。
# ============================================================

# 路径自适应：检测宿主视角 vs 容器视角
if [ -d "/data/adb/dsha/rootfs" ]; then
    RUN_DIR="/data/adb/dsha/run"
    DSH_DIR="/data/adb/dsha/rootfs/root/.dsh"
else
    RUN_DIR="/data/adb/dsha/run"
    [ -d "$RUN_DIR" ] || RUN_DIR="/root/.dsh"
    DSH_DIR="/root/.dsh"
fi

ENABLED_FILE="$RUN_DIR/idle_freeze_enabled"
[ -f "$ENABLED_FILE" ] || ENABLED_FILE="$DSH_DIR/idle_freeze_enabled"

PID_FILE="$RUN_DIR/dsh.pid"
[ -f "$PID_FILE" ] || PID_FILE="$DSH_DIR/dsh.pid"

FREEZER_PID_FILE="$RUN_DIR/idle-freezer.pid"
FREEZER_LOG="$RUN_DIR/idle-freezer.log"
STATE_FILE="$RUN_DIR/freezer.state"
START_LOCK="$RUN_DIR/freezer_start.lock"
WAKE_LOCK="$RUN_DIR/freezer_wake.lock"

SCRIPT_PATH="/data/adb/dsha/scripts/idle-freezer.sh"
[ -f "$SCRIPT_PATH" ] || SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null || echo "$0")"

IDLE_THRESHOLD_SCREEN_ON=1800    # 亮屏使用中：30 分钟
IDLE_THRESHOLD_SCREEN_OFF=300    # 息屏锁屏后：5 分钟
CHECK_INTERVAL_SCREEN_ON=60      # 亮屏心跳：60 秒
CHECK_INTERVAL_SCREEN_OFF=30     # 息屏心跳：30 秒
WAKELOCK_NAME="dsha_freezer"

acquire_wakelock() {
    echo "$WAKELOCK_NAME" > /sys/power/wake_lock 2>/dev/null || true
}

release_wakelock() {
    echo "$WAKELOCK_NAME" > /sys/power/wake_unlock 2>/dev/null || true
}

is_screen_on() {
    local wake=$(dumpsys power 2>/dev/null | grep -m1 'mWakefulness=' | cut -d= -f2)
    [ "$wake" = "Awake" ] && return 0
    [ "$wake" = "Asleep" ] || [ "$wake" = "Dozing" ] && return 1
    return 0
}

log_msg() {
    local ACTION="$1"
    local MSG="$2"
    local NOW_STR=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date)
    echo "[$NOW_STR] [$ACTION] $MSG" >> "$FREEZER_LOG" 2>/dev/null
}

# 异步无阻塞通知，消除 3.5s+ 的 am 跨进程开销
notify_core_state() {
    local STATE="$1" # 1=RUNNING, 2=FROZEN, 3=STOPPED, 4=ERROR
    local REASON="$2"
    (
        am broadcast -a com.deepseekharness.app.ACTION_CORE_STATE --ei state "$STATE" --es reason "$REASON" -p com.dsha.fr >/dev/null 2>&1
    ) &
}

has_active_workers() {
    local mpid="$1"
    [ -z "$mpid" ] && return 1
    local lan_pid=""
    [ -f "$RUN_DIR/lan-proxy.pid" ] && lan_pid=$(cat "$RUN_DIR/lan-proxy.pid" 2>/dev/null)
    local freezer_pid=""
    [ -f "$FREEZER_PID_FILE" ] && freezer_pid=$(cat "$FREEZER_PID_FILE" 2>/dev/null)

    for cpid in $(pgrep -P "$mpid" 2>/dev/null); do
        if [ "$cpid" = "$lan_pid" ] || [ "$cpid" = "$freezer_pid" ] || [ "$cpid" = "$$" ] || [ "$cpid" = "$PPID" ]; then
            continue
        fi
        local cstat=$(awk '{print $3}' "/proc/$cpid/stat" 2>/dev/null)
        if [ -n "$cstat" ] && [ "$cstat" != "Z" ]; then
            return 0
        fi
    done
    return 1
}

freeze_process_tree() {
    local mpid="$1"
    [ -z "$mpid" ] && return 0
    local lan_pid=""
    [ -f "$RUN_DIR/lan-proxy.pid" ] && lan_pid=$(cat "$RUN_DIR/lan-proxy.pid" 2>/dev/null)
    local freezer_pid=""
    [ -f "$FREEZER_PID_FILE" ] && freezer_pid=$(cat "$FREEZER_PID_FILE" 2>/dev/null)

    kill -STOP "$mpid" 2>/dev/null

    for cpid in $(pgrep -P "$mpid" 2>/dev/null); do
        if [ "$cpid" != "$lan_pid" ] && [ "$cpid" != "$freezer_pid" ] && [ "$cpid" != "$$" ] && [ "$cpid" != "$PPID" ]; then
            kill -STOP "$cpid" 2>/dev/null || true
        fi
    done

    if [ -d "/dev/memcg/dsh" ]; then
        echo 100 > /dev/memcg/dsh/memory.swappiness 2>/dev/null || true
        if [ -d "/dev/memcg/apps" ]; then
            echo "$mpid" > /dev/memcg/apps/cgroup.procs 2>/dev/null || true
            for cpid in $(pgrep -P "$mpid" 2>/dev/null); do
                echo "$cpid" > /dev/memcg/apps/cgroup.procs 2>/dev/null || true
            done
        fi
    fi
}

thaw_process_tree() {
    local mpid="$1"
    [ -z "$mpid" ] && return 0

    # 优先最速恢复主进程执行！
    kill -CONT "$mpid" 2>/dev/null

    for cpid in $(pgrep -P "$mpid" 2>/dev/null); do
        local cstat=$(awk '{print $3}' "/proc/$cpid/stat" 2>/dev/null)
        if [ "$cstat" = "T" ]; then
            kill -CONT "$cpid" 2>/dev/null || true
        fi
    done

    # 异步恢复物理内存保护，避免阻塞解冻主链路
    (
        if [ -d "/dev/memcg/dsh" ]; then
            echo 0 > /dev/memcg/dsh/memory.swappiness 2>/dev/null || true
            [ -w "/dev/memcg/dsh/memory.anno_writeback_protected" ] && echo 1 > /dev/memcg/dsh/memory.anno_writeback_protected 2>/dev/null || true
            echo "$mpid" > /dev/memcg/dsh/cgroup.procs 2>/dev/null || true
            for cpid in $(pgrep -P "$mpid" 2>/dev/null); do
                echo "$cpid" > /dev/memcg/dsh/cgroup.procs 2>/dev/null || true
            done
        fi
    ) &
}

is_daemon_alive() {
    if [ -f "$FREEZER_PID_FILE" ]; then
        local fpid=$(cat "$FREEZER_PID_FILE" 2>/dev/null)
        if [ -n "$fpid" ] && kill -0 "$fpid" 2>/dev/null; then
            if grep -q "idle-freezer" "/proc/$fpid/cmdline" 2>/dev/null; then
                return 0
            fi
        fi
    fi
    return 1
}

do_wake() {
    local TRIGGER="${1:-手动执行或未指定来源}"
    release_wakelock
    if [ ! -f "$PID_FILE" ]; then return 0; fi
    local MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -z "$MAIN_PID" ] || ! kill -0 "$MAIN_PID" 2>/dev/null; then
        if [ -f "$STATE_FILE" ] || [ -f "$DSH_DIR/freezer.state" ]; then
            rm -f "$STATE_FILE" "$DSH_DIR/freezer.state" 2>/dev/null
            log_msg "DEAD" "唤醒失败：主进程 (PID: $MAIN_PID) 已不存在/死亡 | 唤醒源: [$TRIGGER]"
            notify_core_state 4 "主进程已死亡"
        fi
        return 0
    fi

    local PROC_STAT=$(awk '{print $3}' "/proc/$MAIN_PID/stat" 2>/dev/null)
    if [ "$PROC_STAT" = "T" ] || [ -f "$STATE_FILE" ] || [ -f "$DSH_DIR/freezer.state" ]; then
        if ! mkdir "$WAKE_LOCK" 2>/dev/null; then
            local W_AGE=$(( $(date +%s) - $(stat -c %Y "$WAKE_LOCK" 2>/dev/null || echo 0) ))
            if [ "$W_AGE" -gt 3 ]; then
                rm -rf "$WAKE_LOCK" 2>/dev/null
                mkdir "$WAKE_LOCK" 2>/dev/null || return 0
            else
                return 0
            fi
        fi

        # 毫秒级原地解冻！
        thaw_process_tree "$MAIN_PID"
        rm -f "$STATE_FILE" "$DSH_DIR/freezer.state" 2>/dev/null
        log_msg "WAKE" "主进程已原地解冻恢复 (PID: $MAIN_PID) | 唤醒源: [$TRIGGER]"
        
        # 异步广播状态切回运行中 (0ms 阻塞)
        notify_core_state 1 "解冻恢复"
        
        if [ -f "$ENABLED_FILE" ]; then
            sh "$SCRIPT_PATH" start >/dev/null 2>&1 &
        fi

        rm -rf "$WAKE_LOCK" 2>/dev/null
    fi
    return 0
}

do_stop() {
    release_wakelock
    if [ -f "$FREEZER_PID_FILE" ]; then
        local fpid=$(cat "$FREEZER_PID_FILE" 2>/dev/null)
        [ -n "$fpid" ] && kill -9 "$fpid" 2>/dev/null
        rm -f "$FREEZER_PID_FILE" 2>/dev/null
    fi
    for pid in $(pgrep -f "idle-freezer.sh daemon" 2>/dev/null); do
        if [ "$pid" != "$$" ] && [ "$pid" != "$PPID" ]; then
            kill -9 "$pid" 2>/dev/null || true
        fi
    done
    rm -rf "$START_LOCK" "$WAKE_LOCK" 2>/dev/null
    if [ -f "$PID_FILE" ]; then
        local MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$MAIN_PID" ] && kill -0 "$MAIN_PID" 2>/dev/null; then
            local PROC_STAT=$(awk '{print $3}' "/proc/$MAIN_PID/stat" 2>/dev/null)
            if [ "$PROC_STAT" = "T" ]; then
                thaw_process_tree "$MAIN_PID"
            fi
        fi
    fi
    rm -f "$STATE_FILE" "$DSH_DIR/freezer.state" 2>/dev/null
}

do_daemon() {
    echo "$$" > "$FREEZER_PID_FILE"
    trap 'release_wakelock' EXIT INT TERM

    local last_check_log=0
    local touch_offset=0

    while true; do
        local screen_on=1
        local check_interval=$CHECK_INTERVAL_SCREEN_ON
        local idle_threshold=$IDLE_THRESHOLD_SCREEN_ON

        if is_screen_on; then
            screen_on=1
            check_interval=$CHECK_INTERVAL_SCREEN_ON
            idle_threshold=$IDLE_THRESHOLD_SCREEN_ON
            release_wakelock
        else
            screen_on=0
            check_interval=$CHECK_INTERVAL_SCREEN_OFF
            idle_threshold=$IDLE_THRESHOLD_SCREEN_OFF
            acquire_wakelock
        fi

        local sleep_start=$(date +%s)
        sleep $check_interval
        local sleep_end=$(date +%s)
        local sleep_delta=$((sleep_end - sleep_start))

        # 亮屏防背刺：硬件挂起唤醒且处于亮屏，自动避让
        if [ "$sleep_delta" -gt $((check_interval + 60)) ] && is_screen_on; then
            log_msg "RESUME" "检测到从系统硬件挂起唤醒且屏幕已亮起 (休眠跳变: ${sleep_delta}s)，重置闲置计时，避让前台使用"
            touch_offset=$sleep_end
            release_wakelock
            continue
        fi

        if [ ! -f "$ENABLED_FILE" ]; then
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            release_wakelock
            exit 0
        fi

        if [ ! -f "$PID_FILE" ]; then
            notify_core_state 4 "PID文件不存在/主进程异常终止"
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            release_wakelock
            exit 0
        fi
        local MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
        if [ -z "$MAIN_PID" ] || ! kill -0 "$MAIN_PID" 2>/dev/null; then
            notify_core_state 4 "主进程已死亡/意外关闭"
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            release_wakelock
            exit 0
        fi

        local PROC_STAT=$(awk '{print $3}' "/proc/$MAIN_PID/stat" 2>/dev/null)
        if [ "$PROC_STAT" = "T" ]; then
            echo "FROZEN" > "$STATE_FILE"
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            release_wakelock
            exit 0
        fi

        if [ -f "$DSH_DIR/.approval_status.json" ]; then
            if grep -q '"active":true' "$DSH_DIR/.approval_status.json" 2>/dev/null; then
                if [ $((sleep_end - last_check_log)) -ge 300 ]; then
                    log_msg "CHECK" "闲置检测: 检测到活跃审批任务挂起，跳过冻结避让中"
                    last_check_log=$sleep_end
                fi
                continue
            fi
        fi

        if has_active_workers "$MAIN_PID"; then
            if [ $((sleep_end - last_check_log)) -ge 300 ]; then
                log_msg "CHECK" "闲置检测: 检测到主进程有活跃工作子进程正在执行，跳过冻结避让中"
                last_check_log=$sleep_end
            fi
            continue
        fi

        local NOW=$sleep_end
        local LAST_WRITE=0

        local LATEST_SESSION=$(ls -t "$DSH_DIR/storages/session_projcache/sessions/session-"*.json \
                               "$DSH_DIR/storages/session_projcache/sessions/"*.json 2>/dev/null | head -1)
        if [ -n "$LATEST_SESSION" ] && [ -f "$LATEST_SESSION" ]; then
            LAST_WRITE=$(stat -c %Y "$LATEST_SESSION" 2>/dev/null || echo 0)
        fi

        if [ "${LAST_WRITE:-0}" -le 0 ] && [ -f "$DSH_DIR/agy/agy-recent.json" ]; then
            LAST_WRITE=$(stat -c %Y "$DSH_DIR/agy/agy-recent.json" 2>/dev/null || echo 0)
        fi

        if [ "${LAST_WRITE:-0}" -le 0 ]; then
            LAST_WRITE=$NOW
        fi

        if [ "$touch_offset" -gt "$LAST_WRITE" ]; then
            LAST_WRITE=$touch_offset
        fi

        local IDLE_SEC=$((NOW - LAST_WRITE))

        if [ $IDLE_SEC -ge $idle_threshold ]; then
            freeze_process_tree "$MAIN_PID"
            echo "FROZEN" > "$STATE_FILE"
            echo "FROZEN" > "$DSH_DIR/freezer.state" 2>/dev/null || true
            rm -f "$FREEZER_PID_FILE" 2>/dev/null

            if [ "$screen_on" = "0" ]; then
                log_msg "FREEZE" "闲置检测: 息屏且会话已闲置 $IDLE_SEC 秒 (达息屏阈值 5分钟)，已执行深度休眠冻结 (PID: $MAIN_PID)"
            else
                log_msg "FREEZE" "闲置检测: 会话已闲置 $IDLE_SEC 秒 (满30分钟)，满足冻结条件，已执行深度休眠冻结 (PID: $MAIN_PID)"
            fi

            notify_core_state 2 "会话闲置达标冻结"
            release_wakelock
            exit 0
        else
            if [ $((NOW - last_check_log)) -ge 300 ]; then
                if [ "$screen_on" = "0" ]; then
                    log_msg "CHECK" "闲置检测 (息屏中): 当前已闲置 $IDLE_SEC 秒 (息屏阈值 300 秒 / 5分钟)，暂不满足，保持避让"
                else
                    log_msg "CHECK" "闲置检测 (亮屏中): 当前已闲置 $IDLE_SEC 秒 (亮屏阈值 1800 秒 / 30分钟)，暂不满足，继续运行"
                fi
                last_check_log=$NOW
            fi
        fi
    done
}

do_freeze_now() {
    local TRIGGER="${1:-手动测试}"
    if [ ! -f "$PID_FILE" ]; then
        echo "ERR: No pid file"
        exit 1
    fi
    local MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -z "$MAIN_PID" ] || ! kill -0 "$MAIN_PID" 2>/dev/null; then
        echo "ERR: Process $MAIN_PID is not alive"
        exit 1
    fi
    freeze_process_tree "$MAIN_PID"
    echo "FROZEN" > "$STATE_FILE"
    echo "FROZEN" > "$DSH_DIR/freezer.state" 2>/dev/null || true
    log_msg "FREEZE_TEST" "主进程已执行深度休眠冻结 (PID: $MAIN_PID) | 触发源: [$TRIGGER]"

    notify_core_state 2 "测试冻结触发源: $TRIGGER"
    release_wakelock
    echo "OK: Frozen PID $MAIN_PID"
}

case "$1" in
    start)
        release_wakelock
        if [ ! -f "$ENABLED_FILE" ]; then exit 0; fi

        if ! mkdir "$START_LOCK" 2>/dev/null; then
            local L_AGE=$(( $(date +%s) - $(stat -c %Y "$START_LOCK" 2>/dev/null || echo 0) ))
            if [ "$L_AGE" -gt 3 ]; then
                rm -rf "$START_LOCK" 2>/dev/null
                mkdir "$START_LOCK" 2>/dev/null || exit 0
            else
                exit 0
            fi
        fi

        if is_daemon_alive; then
            rm -rf "$START_LOCK" 2>/dev/null
            exit 0
        fi

        for pid in $(pgrep -f "idle-freezer.sh daemon" 2>/dev/null); do
            if [ "$pid" != "$$" ] && [ "$pid" != "$PPID" ]; then
                kill -9 "$pid" 2>/dev/null || true
            fi
        done

        log_msg "START" "闲置冻结守护已拉起 (心跳: 30s/60s自适应, 阈值: 息屏5分钟/亮屏30分钟)"
        sh "$SCRIPT_PATH" daemon >/dev/null 2>&1 &
        echo "$!" > "$FREEZER_PID_FILE"

        rm -rf "$START_LOCK" 2>/dev/null
        ;;
    stop)
        do_stop
        ;;
    wake)
        do_wake "$2"
        ;;
    freeze-now)
        do_freeze_now "$2"
        ;;
    daemon)
        do_daemon
        ;;
    *)
        echo "Usage: $0 {start|stop|wake [trigger]|freeze-now [trigger]|daemon}"
        ;;
esac