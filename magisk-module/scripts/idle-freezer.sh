#!/system/bin/sh
# ============================================================
# DSHA 30分钟闲置深度冻结守护程序 (DSHA Idle Freezer Daemon)
# 特性：
# 1. 10分钟超低频心跳检测；
# 2. 真实会话活跃时间戳比对，连续 30 分钟无新对话时冻结；
# 3. 冻结后守护进程自身立即 exit 0 彻底退出，实现 0 唤醒；
# 4. 唤醒时解冻主进程并重新拉起守护，无缝循环；
# 5. 精确记录北京时间操作日志。
# ============================================================

RUN_DIR="/data/adb/dsha/run"
ENABLED_FILE="$RUN_DIR/idle_freeze_enabled"
PID_FILE="$RUN_DIR/dsh.pid"
FREEZER_PID_FILE="$RUN_DIR/idle-freezer.pid"
FREEZER_LOG="$RUN_DIR/idle-freezer.log"
STATE_FILE="$RUN_DIR/freezer.state"

IDLE_THRESHOLD=1800 # 30 分钟 (秒)
CHECK_INTERVAL=600 # 10 分钟检查一次 (秒)

log_msg() {
    ACTION="$1"
    MSG="$2"
    NOW_STR=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date)
    echo "[$NOW_STR] [$ACTION] $MSG" >> "$FREEZER_LOG" 2>/dev/null
}

do_wake() {
    rm -f "$STATE_FILE" 2>/dev/null
    if [ -f "$PID_FILE" ]; then
        MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$MAIN_PID" ]; then
            # 瞬间解冻恢复主进程运行 (0.1毫秒原地复苏)
            kill -CONT "$MAIN_PID" 2>/dev/null
            log_msg "WAKE" "已执行 SIGCONT 原地解冻唤醒主进程 (PID: $MAIN_PID)"
        fi
    fi
    # 唤醒后，若前端开关仍处于开启状态，重新拉起守护扫描开启新一轮计时
    if [ -f "$ENABLED_FILE" ]; then
        sh "/data/adb/dsha/scripts/idle-freezer.sh" start >/dev/null 2>&1 &
    fi
}

do_stop() {
    if [ -f "$FREEZER_PID_FILE" ]; then
        FPID=$(cat "$FREEZER_PID_FILE" 2>/dev/null)
        [ -n "$FPID" ] && kill -9 "$FPID" 2>/dev/null
        rm -f "$FREEZER_PID_FILE" 2>/dev/null
    fi
    pkill -9 -f "idle-freezer.sh daemon" 2>/dev/null || true
    do_wake
}

do_daemon() {
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
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            exit 0
        fi
        MAIN_PID=$(cat "$PID_FILE" 2>/dev/null)
        if [ -z "$MAIN_PID" ] || ! kill -0 "$MAIN_PID" 2>/dev/null; then
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
        
        # 4. 任务避让保护：检查是否有未完成的审批或任务在跑
        IS_ACTIVE=0
        if [ -f "/root/.dsh/.approval_status.json" ]; then
            if grep -q '"active":true' "/root/.dsh/.approval_status.json" 2>/dev/null; then
                IS_ACTIVE=1
            fi
        fi
        [ "$IS_ACTIVE" = "1" ] && continue
        
        # 5. 精准计算真实会话活跃时间（取 session 缓存或 agy 账本最新写入时间戳）
        NOW=$(date +%s)
        LAST_WRITE=0
        LATEST_SESSION=$(ls -t /root/.dsh/storages/session_projcache/sessions/*.json 2>/dev/null | head -1)
        if [ -n "$LATEST_SESSION" ] && [ -f "$LATEST_SESSION" ]; then
            LAST_WRITE=$(stat -c %Y "$LATEST_SESSION" 2>/dev/null || echo 0)
        fi
        if [ "${LAST_WRITE:-0}" -le 0 ] && [ -f "/root/.dsh/agy/agy-recent.json" ]; then
            LAST_WRITE=$(stat -c %Y "/root/.dsh/agy/agy-recent.json" 2>/dev/null || echo 0)
        fi
        if [ "${LAST_WRITE:-0}" -le 0 ]; then
            LAST_WRITE=$NOW
        fi
        
        IDLE_SEC=$((NOW - LAST_WRITE))
        
        # 6. 达到 30 分钟闲置阈值：执行冻结并彻底停止自身
        if [ $IDLE_SEC -ge $IDLE_THRESHOLD ]; then
            kill -STOP "$MAIN_PID" 2>/dev/null
            echo "FROZEN" > "$STATE_FILE"
            rm -f "$FREEZER_PID_FILE" 2>/dev/null
            log_msg "FREEZE" "会话已闲置 $IDLE_SEC 秒 (满30分钟)，已执行 SIGSTOP 深度休眠冻结 (PID: $MAIN_PID)"
            
            # 尝试通过 3095 桥通知用户已休眠
            TOKEN=$(cat /root/.dsh/.bridge_token 2>/dev/null)
            if [ -n "$TOKEN" ]; then
                curl -s -m 2 "http://127.0.0.1:3095/app/notify?token=$TOKEN&title=DSH已休眠&text=闲置已满30分钟，已进入0功耗休眠，访问即可唤醒。" >/dev/null 2>&1
            fi
            
            # 关键设计：冻结后自身立即退出，后台 0 轮询 0 唤醒！
            exit 0
        fi
    done
}

case "$1" in
    start)
        if [ ! -f "$ENABLED_FILE" ]; then exit 0; fi
        if [ -f "$FREEZER_PID_FILE" ]; then
            FPID=$(cat "$FREEZER_PID_FILE" 2>/dev/null)
            if [ -n "$FPID" ] && kill -0 "$FPID" 2>/dev/null; then exit 0; fi
        fi
        sh "/data/adb/dsha/scripts/idle-freezer.sh" daemon >/dev/null 2>&1 &
        ;;
    stop)
        do_stop
        ;;
    wake)
        do_wake
        ;;
    daemon)
        do_daemon
        ;;
    *)
        echo "Usage: $0 {start|stop|wake|daemon}"
        ;;
esac
