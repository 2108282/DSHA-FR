#!/system/bin/sh
# ============================================================
# DSHA 30分钟闲置深度冻结守护程序 (DSHA Idle Freezer Daemon)
# 特性：
# 1. 10分钟超低频心跳检测；
# 2. 宿主与容器自适应路径匹配；
# 3. 显式检测 session-*.json 与会话缓存最新写入时间戳；
# 4. 连续 30 分钟无新步骤时执行 kill -STOP 冻结；
# 5. 冻结后守护进程自身立即 exit 0 彻底退出，实现 0 唤醒；
# 6. 唤醒时解冻主进程并重新拉起守护，无缝循环；
# 7. 精确记录北京时间操作日志；
# 8. POSIX 原子排他锁 (mkdir) 杜绝并发穿透，精准进程单例保障。
# ============================================================

RUN_DIR="/data/adb/dsha/run"
ENABLED_FILE="$RUN_DIR/idle_freeze_enabled"
PID_FILE="$RUN_DIR/dsh.pid"
FREEZER_PID_FILE="$RUN_DIR/idle-freezer.pid"
FREEZER_LOG="$RUN_DIR/idle-freezer.log"
STATE_FILE="$RUN_DIR/freezer.state"
START_LOCK="$RUN_DIR/freezer_start.lock"
WAKE_LOCK="$RUN_DIR/freezer_wake.lock"

# 路径自适应：检测宿主视角 vs 容器视角
if [ -d "/data/adb/dsha/rootfs/root/.dsh" ]; then
    DSH_DIR="/data/adb/dsha/rootfs/root/.dsh"
else
    DSH_DIR="/root/.dsh"
fi

IDLE_THRESHOLD=1800 # 30 分钟 (秒)
CHECK_INTERVAL=600 # 10 分钟检查一次 (秒)

log_msg() {
    ACTION="$1"
    MSG="$2"
    NOW_STR=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date)
    echo "[$NOW_STR] [$ACTION] $MSG" >> "$FREEZER_LOG" 2>/dev/null
}

# 严格检测是否有存活的有效 daemon
is_daemon_alive() {
    if [ -f "$FREEZER_PID_FILE" ]; then
        FPID=$(cat "$FREEZER_PID_FILE" 2>/dev/null)
        if [ -n "$FPID" ] && kill -0 "$FPID" 2>/dev/null; then
            if grep -q "idle-freezer" "/proc/$FPID/cmdline" 2>/dev/null; then
                return 0
            fi
        fi
    fi
    return 1
}

do_wake() {
    TRIGGER="${1:-手动执行或未指定来源}"
    if [ ! -f "$PID_FILE" ]; then return 0; fi
    MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -z "$MAIN_PID" ] || ! kill -0 "$MAIN_PID" 2>/dev/null; then
        # 进程已死亡
        if [ -f "$STATE_FILE" ] || [ -f "$DSH_DIR/freezer.state" ]; then
            rm -f "$STATE_FILE" "$DSH_DIR/freezer.state" 2>/dev/null
            log_msg "DEAD" "唤醒失败：主进程 (PID: $MAIN_PID) 已不存在/死亡 | 唤醒源: [$TRIGGER]"
            TOKEN=$(cat "$DSH_DIR/.bridge_token" 2>/dev/null)
            if [ -n "$TOKEN" ]; then
                curl -s -m 2 "http://127.0.0.1:3095/app/freeze/state?token=$TOKEN&dead=1" >/dev/null 2>&1 &
            fi
        fi
        return 0
    fi

    PROC_STAT=$(awk '{print $3}' "/proc/$MAIN_PID/stat" 2>/dev/null)
    # 严格判据：只有此前确实已被冻结（进程为 T 挂起态，或存在冻结标记）时才解冻并记录日志
    if [ "$PROC_STAT" = "T" ] || [ -f "$STATE_FILE" ] || [ -f "$DSH_DIR/freezer.state" ]; then
        # 原子排他抢占唤醒锁：防止并发 wake 瞬间重入
        if ! mkdir "$WAKE_LOCK" 2>/dev/null; then
            W_AGE=$(( $(date +%s) - $(stat -c %Y "$WAKE_LOCK" 2>/dev/null || echo 0) ))
            if [ "$W_AGE" -gt 3 ]; then
                rm -rf "$WAKE_LOCK" 2>/dev/null
                mkdir "$WAKE_LOCK" 2>/dev/null || return 0
            else
                return 0
            fi
        fi

        # 抢到锁后二次复核状态，避免并发先到者已解冻完成后重复操作
        PROC_STAT_NOW=$(awk '{print $3}' "/proc/$MAIN_PID/stat" 2>/dev/null)
        if [ "$PROC_STAT_NOW" != "T" ] && [ ! -f "$STATE_FILE" ] && [ ! -f "$DSH_DIR/freezer.state" ]; then
            rm -rf "$WAKE_LOCK" 2>/dev/null
            return 0
        fi

        kill -CONT "$MAIN_PID" 2>/dev/null
        rm -f "$STATE_FILE" "$DSH_DIR/freezer.state" 2>/dev/null
        log_msg "WAKE" "主进程已原地解冻恢复 (PID: $MAIN_PID) | 唤醒源: [$TRIGGER]"
        
        # 原地恢复常驻通知为运行状态 (走常驻通道，绝不弹窗打扰)
        TOKEN=$(cat "$DSH_DIR/.bridge_token" 2>/dev/null)
        if [ -n "$TOKEN" ]; then
            curl -s -m 2 "http://127.0.0.1:3095/app/freeze/state?token=$TOKEN&frozen=0" >/dev/null 2>&1 &
        fi
        
        # 唤醒后，若前端开关仍处于开启状态，重新拉起守护扫描开启新一轮计时
        if [ -f "$ENABLED_FILE" ]; then
            sh "/data/adb/dsha/scripts/idle-freezer.sh" start >/dev/null 2>&1 &
        fi

        rm -rf "$WAKE_LOCK" 2>/dev/null
    fi
    # 进程原本就在正常运行（未冻结），静默忽略，绝不重复打印解冻日志！
    return 0
}

do_stop() {
    if [ -f "$FREEZER_PID_FILE" ]; then
        FPID=$(cat "$FREEZER_PID_FILE" 2>/dev/null)
        [ -n "$FPID" ] && kill -9 "$FPID" 2>/dev/null
        rm -f "$FREEZER_PID_FILE" 2>/dev/null
    fi
    # 高性能清理所有 daemon 实例（毫秒级内核匹配，杜绝逐个进程扫描卡顿）
    for pid in $(pgrep -f "idle-freezer.sh daemon" 2>/dev/null); do
        if [ "$pid" != "$$" ] && [ "$pid" != "$PPID" ]; then
            kill -9 "$pid" 2>/dev/null || true
        fi
    done
    rm -rf "$START_LOCK" "$WAKE_LOCK" 2>/dev/null
    # 若主进程当前处于休眠挂起态，安全解冻恢复，绝不重新拉起守护（杜绝死循环复活）
    if [ -f "$PID_FILE" ]; then
        MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$MAIN_PID" ] && kill -0 "$MAIN_PID" 2>/dev/null; then
            PROC_STAT=$(awk '{print $3}' "/proc/$MAIN_PID/stat" 2>/dev/null)
            if [ "$PROC_STAT" = "T" ]; then
                kill -CONT "$MAIN_PID" 2>/dev/null
            fi
        fi
    fi
    rm -f "$STATE_FILE" "$DSH_DIR/freezer.state" 2>/dev/null
}

do_daemon() {
    # 守护进程自身保持 PID 文件一致
    echo "$$" > "$FREEZER_PID_FILE"

    while true; do
        sleep $CHECK_INTERVAL # 10 分钟极简低频休眠
        
        # 1. 开关检查：若用户在前端关闭了该功能，立即退出自身
        if [ ! -f "$ENABLED_FILE" ]; then
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            exit 0
        fi
        
        # 2. 检查主进程是否存在
        if [ ! -f "$PID_FILE" ]; then
            TOKEN=$(cat "$DSH_DIR/.bridge_token" 2>/dev/null)
            [ -n "$TOKEN" ] && curl -s -m 2 "http://127.0.0.1:3095/app/freeze/state?token=$TOKEN&dead=1" >/dev/null 2>&1
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            exit 0
        fi
        MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
        if [ -z "$MAIN_PID" ] || ! kill -0 "$MAIN_PID" 2>/dev/null; then
            TOKEN=$(cat "$DSH_DIR/.bridge_token" 2>/dev/null)
            [ -n "$TOKEN" ] && curl -s -m 2 "http://127.0.0.1:3095/app/freeze/state?token=$TOKEN&dead=1" >/dev/null 2>&1
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            exit 0
        fi
        
        # 3. 检查进程是否已被冻结
        PROC_STAT=$(awk '{print $3}' "/proc/$MAIN_PID/stat" 2>/dev/null)
        if [ "$PROC_STAT" = "T" ]; then
            echo "FROZEN" > "$STATE_FILE"
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            exit 0 # 已经处于冻结态，守护进程功成身退，彻底退出自身！
        fi
        
        # 4. 任务避让保护：检查是否有未完成的审批挂起
        if [ -f "$DSH_DIR/.approval_status.json" ]; then
            if grep -q '"active":true' "$DSH_DIR/.approval_status.json" 2>/dev/null; then
                log_msg "CHECK" "闲置检测: 检测到活跃审批任务挂起，跳过冻结避让中"
                continue
            fi
        fi
        
        # 5. 精准提取 session-*.json 与会话文件的最新写入时间戳
        NOW=$(date +%s)
        LAST_WRITE=0
        
        # 显式包含 session-*.json 检索！
        LATEST_SESSION=$(ls -t "$DSH_DIR/storages/session_projcache/sessions/session-"*.json \
                               "$DSH_DIR/storages/session_projcache/sessions/"*.json 2>/dev/null | head -1)
        if [ -n "$LATEST_SESSION" ] && [ -f "$LATEST_SESSION" ]; then
            LAST_WRITE=$(stat -c %Y "$LATEST_SESSION" 2>/dev/null || echo 0)
        fi
        
        # 回退检查 agy 调用账本
        if [ "${LAST_WRITE:-0}" -le 0 ] && [ -f "$DSH_DIR/agy/agy-recent.json" ]; then
            LAST_WRITE=$(stat -c %Y "$DSH_DIR/agy/agy-recent.json" 2>/dev/null || echo 0)
        fi
        
        if [ "${LAST_WRITE:-0}" -le 0 ]; then
            LAST_WRITE=$NOW
        fi
        
        IDLE_SEC=$((NOW - LAST_WRITE))
        
        # 6. 判断是否达到 30 分钟 (1800秒) 闲置阈值
        if [ $IDLE_SEC -ge $IDLE_THRESHOLD ]; then
            kill -STOP "$MAIN_PID" 2>/dev/null
            echo "FROZEN" > "$STATE_FILE"
            echo "FROZEN" > "$DSH_DIR/freezer.state" 2>/dev/null || true
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            log_msg "FREEZE" "闲置检测: 会话已闲置 $IDLE_SEC 秒 (满30分钟)，满足冻结条件，已执行 SIGSTOP 深度休眠冻结 (PID: $MAIN_PID)"
            
            # 原地更新常驻通知为休眠状态 (绝不弹窗打扰、不亮屏，走同一通知通道)
            TOKEN=$(cat "$DSH_DIR/.bridge_token" 2>/dev/null)
            if [ -n "$TOKEN" ]; then
                curl -s -m 2 "http://127.0.0.1:3095/app/freeze/state?token=$TOKEN&frozen=1" >/dev/null 2>&1
            fi
            
            # 关键设计：冻结后自身立即退出，后台 0 轮询 0 唤醒！
            exit 0
        else
            log_msg "CHECK" "闲置检测: 当前已闲置 $IDLE_SEC 秒 (阈值 1800 秒 / 30分钟)，暂不满足冻结条件，继续保持运行"
        fi
    done
}

do_freeze_now() {
    TRIGGER="${1:-手动测试}"
    if [ ! -f "$PID_FILE" ]; then
        echo "ERR: No pid file"
        exit 1
    fi
    MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -z "$MAIN_PID" ] || ! kill -0 "$MAIN_PID" 2>/dev/null; then
        echo "ERR: Process $MAIN_PID is not alive"
        exit 1
    fi
    kill -STOP "$MAIN_PID" 2>/dev/null
    echo "FROZEN" > "$STATE_FILE"
    echo "FROZEN" > "$DSH_DIR/freezer.state" 2>/dev/null || true
    log_msg "FREEZE_TEST" "主进程已执行 SIGSTOP 冻结 (PID: $MAIN_PID) | 触发源: [$TRIGGER]"

    TOKEN=$(cat "$DSH_DIR/.bridge_token" 2>/dev/null)
    if [ -n "$TOKEN" ]; then
        curl -s -m 2 "http://127.0.0.1:3095/app/freeze/state?token=$TOKEN&frozen=1" >/dev/null 2>&1
    fi
    echo "OK: Frozen PID $MAIN_PID"
}

case "$1" in
    start)
        if [ ! -f "$ENABLED_FILE" ]; then exit 0; fi

        # 原子排他抢占锁：杜绝并发 start 穿透
        if ! mkdir "$START_LOCK" 2>/dev/null; then
            L_AGE=$(( $(date +%s) - $(stat -c %Y "$START_LOCK" 2>/dev/null || echo 0) ))
            if [ "$L_AGE" -gt 3 ]; then
                rm -rf "$START_LOCK" 2>/dev/null
                mkdir "$START_LOCK" 2>/dev/null || exit 0
            else
                exit 0
            fi
        fi

        # 检查是否已有存活的有效 daemon
        if is_daemon_alive; then
            rm -rf "$START_LOCK" 2>/dev/null
            exit 0
        fi

        # 清理可能残留的死锁或历史孤儿 daemon（毫秒级内核匹配）
        for pid in $(pgrep -f "idle-freezer.sh daemon" 2>/dev/null); do
            if [ "$pid" != "$$" ] && [ "$pid" != "$PPID" ]; then
                kill -9 "$pid" 2>/dev/null || true
            fi
        done

        log_msg "START" "闲置冻结守护已拉起 (检测周期: 10分钟, 闲置阈值: 30分钟)"
        sh "/data/adb/dsha/scripts/idle-freezer.sh" daemon >/dev/null 2>&1 &
        # 立即把新后台进程的 PID 写入，消除任何时间窗
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
