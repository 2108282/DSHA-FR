#!/system/bin/sh
# ============================================================
# DSHA 闲置深度冻结守护程序 (DSHA Idle Freezer Daemon - Turbo Edition)
# 
# 特性：
# 1. 动态双模阈值：息屏加速至 5 分钟 (300秒) 冻结；亮屏维持 30 分钟 (1800秒) 冻结；
# 2. 息屏内核电源锁：息屏期间临时持有 wake_lock，确保 5 分钟走时不被 CPU 挂起打断；
# 3. 每 10 分钟检查一次：检查时达到闲置阈值即冻结，释放唤醒锁并退出自身；
# 4. 毫秒级极速唤醒：所有广播通知全面后台异步化 (&)，解冻主进程耗时 < 0.2s；
# 5. 内存换页与 ZRAM 压缩协同，唤醒时自动恢复物理内存保护；
# 6. 精确记录北京时间操作日志。
# ============================================================

# 路径自适应：检测宿主视角 vs 容器视角
if [ -d "/data/adb/dsha/rootfs" ]; then
    ROOTFS="/data/adb/dsha/rootfs"
    RUN_DIR="/data/adb/dsha/run"
    DSH_DIR="/data/adb/dsha/rootfs/root/.dsh"
else
    ROOTFS=""
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
WHITELIST_FILE="$RUN_DIR/idle-freeze-whitelist.conf"

SCRIPT_PATH="/data/adb/dsha/scripts/idle-freezer.sh"
[ -f "$SCRIPT_PATH" ] || SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null || echo "$0")"

IDLE_THRESHOLD_SCREEN_ON=1800    # 亮屏闲置阈值：30 分钟 (秒)
IDLE_THRESHOLD_SCREEN_OFF=300    # 息屏闲置阈值：5 分钟 (秒)
CHECK_INTERVAL_SCREEN_ON=600     # 亮屏检查：10 分钟
CHECK_INTERVAL_SCREEN_OFF=600    # 息屏检查：10 分钟
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

notify_core_state() {
    local STATE="$1" # 1=RUNNING, 2=FROZEN, 3=STOPPED, 4=ERROR
    local REASON="$2"
    (
        am broadcast -a com.deepseekharness.app.ACTION_CORE_STATE --ei state "$STATE" --es reason "$REASON" -p com.dsha.fr >/dev/null 2>&1
    ) &
}

# 仅在需要输出日志时读取 JSON 元数据；宿主侧借用容器运行时，不依赖 Android 安装 jq/node。
# 只读取标题/审批标识，绝不把对话正文或完整命令参数写入日志。
describe_json_source() {
    local mode="$1" source_file="$2" json_path="$2"
    [ -n "$ROOTFS" ] && json_path="${source_file#"$ROOTFS"}"
    if [ -n "$ROOTFS" ]; then
        chroot "$ROOTFS" /usr/local/bin/node - "$mode" "$json_path" 2>/dev/null
    else
        node - "$mode" "$json_path" 2>/dev/null
    fi <<'FREEZER_METADATA_JS'
const fs = require('node:fs');
const [mode, file] = process.argv.slice(2);
const clean = value => typeof value === 'string'
    ? value.replace(/[\x00-\x1f\x7f-\x9f\u2028\u2029]/g, ' ').slice(0, 160) : '';
try {
    if (fs.statSync(file).size > 2 * 1024 * 1024) process.exit(0);
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (mode === 'session') {
        const record = data.record ?? data;
        const title = record.rows?.title?.val;
        const text = clean(typeof title === 'string' ? title : title?.title);
        if (text) process.stdout.write(`标题: ${text}`);
    } else {
        const fields = [];
        const tool = clean(data.tool);
        const session = clean(data.sessionId ?? data.session_id);
        const request = clean(data.requestId ?? data.request_id);
        if (tool) fields.push(`工具: ${tool}`);
        fields.push(session ? `会话ID: ${session}` : '会话ID: 审批状态文件未提供');
        if (request) fields.push(`审批ID: ${request}`);
        process.stdout.write(fields.join(' | '));
    }
} catch {}
FREEZER_METADATA_JS
}

# 每轮只加载一次自定义规则；整行首尾空白可省略，规则不作为 Shell 或正则执行。
load_worker_whitelist() {
    WORKER_WHITELIST=""
    WHITELIST_ERROR=""
    [ -e "$WHITELIST_FILE" ] || return 0
    if [ ! -f "$WHITELIST_FILE" ] || [ ! -r "$WHITELIST_FILE" ]; then
        WHITELIST_ERROR="白名单不是可读普通文件"
        return 0
    fi
    local bytes=$(wc -c < "$WHITELIST_FILE" 2>/dev/null)
    case "$bytes" in ''|*[!0-9]*) WHITELIST_ERROR="无法读取白名单大小"; return 0 ;; esac
    if [ "$bytes" -gt 16384 ]; then
        WHITELIST_ERROR="白名单超过16KiB，已忽略全部自定义规则"
        return 0
    fi
    # awk 保留规则中的空格和正则字符；非法文件整体拒绝，不能只应用一部分。
    local parsed
    parsed=$(LC_ALL=C awk '
        { sub(/\r$/, ""); sub(/^[ \t]+/, ""); sub(/[ \t]+$/, "") }
        /^$/ || /^#/ { next }
        {
            if (length($0) > 512 || $0 ~ /[[:cntrl:]]/ || ++count > 128) { bad=1; exit }
            if (index($0, "name:") == 1) {
                value=substr($0, 6)
                if (!length(value) || length(value) > 80 || index(value, "/")) { bad=1; exit }
            } else if (index($0, "script:/") != 1) { bad=1; exit }
            rules=rules $0 "\n"
        }
        END { if (bad) exit 1; printf "%s", rules }
    ' "$WHITELIST_FILE" 2>/dev/null)
    if [ "$?" -ne 0 ]; then
        WHITELIST_ERROR="白名单格式无效，已忽略全部自定义规则"
    else
        WORKER_WHITELIST="$parsed"
    fi
}

# 使用 NUL 分隔的 argv 精确匹配脚本路径，避免子串/通配符误放行其他工作。
worker_whitelist_match() {
    local cpid="$1" comm="$2" rule="" value=""
    MATCHED_WORKER_RULE=""
    [ -n "$WORKER_WHITELIST" ] || return 1
    while IFS= read -r rule; do
        case "$rule" in
            name:*)
                value="${rule#name:}"
                [ "$comm" = "$value" ] || continue
                ;;
            script:*)
                value="${rule#script:}"
                # BusyBox awk 不可靠支持 NUL 记录分隔；逐字节转十六进制后按00分割argv。
                local wanted_hex=$(printf '%s' "$value" | od -An -v -tx1 | tr -d ' \n')
                od -An -v -tx1 "/proc/$cpid/cmdline" 2>/dev/null | FREEZER_MATCH_HEX="$wanted_hex" awk '
                    BEGIN { wanted=ENVIRON["FREEZER_MATCH_HEX"]; arg="" }
                    { for (i=1; i<=NF; i++) {
                        if ($i == "00") {
                            if (arg == wanted) found=1
                            arg=""
                        } else arg=arg $i
                    } }
                    END { exit !found }
                ' || continue
                ;;
            *) continue ;;
        esac
        MATCHED_WORKER_RULE="$rule"
        return 0
    done <<FREEZER_WHITELIST_RULES
$WORKER_WHITELIST
FREEZER_WHITELIST_RULES
    return 1
}

# 检测主进程是否有活跃的工作子进程（排除僵尸进程 Z 与自身白名单）
has_active_workers() {
    ACTIVE_WORKER_COUNT=0
    ACTIVE_WORKER_DETAILS=""
    IGNORED_WORKER_COUNT=0
    IGNORED_WORKER_DETAILS=""
    load_worker_whitelist
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
        # 排除常驻 MCP stdio 桥接与中继守护 (如 phone relay.cjs)
        local cmdline=$(cat "/proc/$cpid/cmdline" 2>/dev/null | tr '\0' ' ')
        if echo "$cmdline" | grep -qE "relay\.cjs"; then
            continue
        fi

        # comm 允许含空格/括号，从 stat 最后一个右括号后提取进程状态。
        local cstat=$(sed 's/.*) //' "/proc/$cpid/stat" 2>/dev/null | cut -d ' ' -f1)
        if [ -n "$cstat" ] && [ "$cstat" != "Z" ]; then
            local raw_comm=$(cat "/proc/$cpid/comm" 2>/dev/null)
            local comm=$(printf '%s' "$raw_comm" | tr '\n\r\t|' '    ' | cut -c1-80)
            if worker_whitelist_match "$cpid" "$raw_comm"; then
                IGNORED_WORKER_COUNT=$((IGNORED_WORKER_COUNT + 1))
                if [ "$IGNORED_WORKER_COUNT" -le 8 ]; then
                    IGNORED_WORKER_DETAILS="${IGNORED_WORKER_DETAILS}${IGNORED_WORKER_DETAILS:+; }PID=$cpid 进程=${comm:-未知} 规则=$MATCHED_WORKER_RULE"
                fi
                continue
            fi
            ACTIVE_WORKER_COUNT=$((ACTIVE_WORKER_COUNT + 1))
            # 只保留前 8 个对象，避免常驻子进程较多时刷满日志。
            if [ "$ACTIVE_WORKER_COUNT" -le 8 ]; then
                local exe=$(readlink "/proc/$cpid/exe" 2>/dev/null)
                exe="${exe##*/}"
                ACTIVE_WORKER_DETAILS="${ACTIVE_WORKER_DETAILS}${ACTIVE_WORKER_DETAILS:+; }PID=$cpid PPID=$mpid 状态=$cstat 进程=${comm:-未知} 可执行文件=${exe:-不可读}"
            fi
        fi
    done
    [ "$ACTIVE_WORKER_COUNT" -gt 0 ]
}

# 挂起主进程及其残留休眠子进程
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

    # 放开压缩权限：允许系统在内存紧张时将闲置页面压缩进 zram
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

# 解冻主进程及其直接子进程
thaw_process_tree() {
    local mpid="$1"
    [ -z "$mpid" ] && return 0

    kill -CONT "$mpid" 2>/dev/null

    for cpid in $(pgrep -P "$mpid" 2>/dev/null); do
        local cstat=$(awk '{print $3}' "/proc/$cpid/stat" 2>/dev/null)
        if [ "$cstat" = "T" ]; then
            kill -CONT "$cpid" 2>/dev/null || true
        fi
    done

    # 异步恢复物理内存保护
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

        thaw_process_tree "$MAIN_PID"
        rm -f "$STATE_FILE" "$DSH_DIR/freezer.state" 2>/dev/null
        log_msg "WAKE" "主进程已原地解冻恢复 (PID: $MAIN_PID) | 唤醒源: [$TRIGGER]"
        
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

    while true; do
        local screen_on=1
        local check_interval=$CHECK_INTERVAL_SCREEN_ON
        local idle_threshold=$IDLE_THRESHOLD_SCREEN_ON

        if is_screen_on; then
            screen_on=1
            check_interval=$CHECK_INTERVAL_SCREEN_ON
            idle_threshold=$IDLE_THRESHOLD_SCREEN_ON
            # 亮屏正常使用，不持锁
            release_wakelock
        else
            screen_on=0
            check_interval=$CHECK_INTERVAL_SCREEN_OFF
            idle_threshold=$IDLE_THRESHOLD_SCREEN_OFF
            # 息屏持锁，保障 5 分钟走时不被系统硬件挂起打断
            acquire_wakelock
        fi

        sleep $check_interval

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
                local approval_details=$(describe_json_source approval "$DSH_DIR/.approval_status.json")
                log_msg "CHECK" "未冻结: 等待活跃审批 | 主进程PID: $MAIN_PID | ${approval_details:-审批元数据不可读} | 状态文件: $DSH_DIR/.approval_status.json"
                continue
            fi
        fi

        local workers_active=0
        has_active_workers "$MAIN_PID" && workers_active=1
        if [ -n "$WHITELIST_ERROR" ]; then
            log_msg "WHITELIST" "$WHITELIST_ERROR | 配置: $WHITELIST_FILE"
        fi
        if [ "$IGNORED_WORKER_COUNT" -gt 0 ]; then
            log_msg "WHITELIST" "自定义白名单忽略 $IGNORED_WORKER_COUNT 个工作子进程 (最多列8个，冻结时仍会挂起) | $IGNORED_WORKER_DETAILS"
        fi
        if [ "$workers_active" = "1" ]; then
            log_msg "CHECK" "未冻结: 存在非白名单且非僵尸的工作子进程 (等待/挂起态也会阻止冻结) | 数量: $ACTIVE_WORKER_COUNT (最多列8个) | $ACTIVE_WORKER_DETAILS"
            continue
        fi

        local NOW=$(date +%s)
        local LAST_WRITE=0
        local activity_source=""

        local LATEST_SESSION=$(ls -t "$DSH_DIR/storages/session_projcache/sessions/session-"*.json \
                               "$DSH_DIR/storages/session_projcache/sessions/"*.json 2>/dev/null | head -1)
        if [ -n "$LATEST_SESSION" ] && [ -f "$LATEST_SESSION" ]; then
            LAST_WRITE=$(stat -c %Y "$LATEST_SESSION" 2>/dev/null || echo 0)
            [ "${LAST_WRITE:-0}" -gt 0 ] && activity_source="session"
        fi

        if [ "${LAST_WRITE:-0}" -le 0 ] && [ -f "$DSH_DIR/agy/agy-recent.json" ]; then
            LAST_WRITE=$(stat -c %Y "$DSH_DIR/agy/agy-recent.json" 2>/dev/null || echo 0)
            [ "${LAST_WRITE:-0}" -gt 0 ] && activity_source="agy"
        fi

        if [ "${LAST_WRITE:-0}" -le 0 ]; then
            # 保留原有保守策略，但明确说明无法计时，避免误报“刚有对话活动”。
            log_msg "CHECK" "未冻结: 无法取得有效活动时间戳，按原策略保持运行 | 主进程PID: $MAIN_PID | 会话目录: $DSH_DIR/storages/session_projcache/sessions | 回退账本: $DSH_DIR/agy/agy-recent.json"
            continue
        fi

        local IDLE_SEC=$((NOW - LAST_WRITE))

        # 只要达到对应闲置阈值（息屏 5 分钟 / 亮屏 30 分钟），坚决执行冻结！不做任何多余拦截！
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
            local source_details=""
            if [ "$activity_source" = "session" ]; then
                local session_id="${LATEST_SESSION##*/}"
                session_id="${session_id%.json}"
                local session_title=$(describe_json_source session "$LATEST_SESSION")
                source_details="会话ID: $session_id | ${session_title:-标题不可用} | 文件: $LATEST_SESSION"
            else
                source_details="活动来源: agy 调用账本 (无会话时间戳，无法归属具体对话) | 文件: $DSH_DIR/agy/agy-recent.json"
            fi
            local screen_label="亮屏"
            [ "$screen_on" = "0" ] && screen_label="息屏"
            log_msg "CHECK" "未冻结: $screen_label 闲置 $IDLE_SEC 秒，未满阈值 $idle_threshold 秒 | 最近写入时间戳: $LAST_WRITE | $source_details"
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

        log_msg "START" "闲置冻结守护已拉起 (检查周期: 10分钟, 阈值: 息屏5分钟/亮屏30分钟，达标后在检查时冻结)"
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
