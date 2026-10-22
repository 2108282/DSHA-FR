#!/system/bin/sh
# ============================================================
# patch-sync-lan-proxy-watchdog.sh — DSHA 极速热更新增量补丁
# 修复内容：
# 1. 动态自适应探测并同步宿主 App 真实 UID 至运行时文件 (app.uid)；
# 2. 授权 666 权限，确保护栏看门狗精准放行浏览器并拦截 App 探活心跳；
# 3. 若当前已拉起 lan-proxy 代理服务，平滑重启使其立即热生效。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在同步宿主 App 动态 UID 与看门狗防卫契约 ..."

RUN_DIR="$DATA_DIR/run"
mkdir -p "$RUN_DIR" 2>/dev/null || true
mkdir -p "$ROOTFS/root/.dsh" 2>/dev/null || true

APP_UID=""
if [ -d "/data/data/com.dsha.fr" ]; then
    APP_UID=$(stat -c "%u" /data/data/com.dsha.fr 2>/dev/null || echo "")
fi
if [ -z "$APP_UID" ]; then
    APP_UID=$(pm list packages -U 2>/dev/null | grep "package:com.dsha.fr " | cut -d: -f3 2>/dev/null || echo "")
fi

if [ -n "$APP_UID" ]; then
    echo -n "$APP_UID" > "$RUN_DIR/app.uid" 2>/dev/null || true
    echo -n "$APP_UID" > "$ROOTFS/root/.dsh/app.uid" 2>/dev/null || true
    chmod 666 "$RUN_DIR/app.uid" 2>/dev/null || true
    chmod 666 "$ROOTFS/root/.dsh/app.uid" 2>/dev/null || true
    echo "  -> 已成功动态注入 App UID: $APP_UID"
fi

# 若局域网代理正在运行，平滑重载使其即刻应用 1.5s 周期与 UID 过滤
LAN_PID_FILE="$RUN_DIR/lan-proxy.pid"
if [ -f "$LAN_PID_FILE" ]; then
    PID=$(cat "$LAN_PID_FILE" 2>/dev/null || echo "")
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        echo "  -> 检测到局域网代理正在运行，正在平滑重载..."
        if [ -f "$DATA_DIR/scripts/lan-proxy.sh" ]; then
            sh "$DATA_DIR/scripts/lan-proxy.sh" restart >/dev/null 2>&1 || true
        fi
    fi
fi

echo "✓ 增量补丁应用成功：宿主 App 动态 UID 契约与看门狗平滑重载就绪！"
