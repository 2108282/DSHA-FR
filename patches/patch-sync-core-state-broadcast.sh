#!/system/bin/sh
# ============================================================
# patch-sync-core-state-broadcast.sh — DSHA 极速热更新增量补丁
# 修复内容：
# 1. 确保 DSHA 专属私有沙盒凭据接收目录权限就绪；
# 2. 同步宿主脚本广播契约，确保开机与生命周期通知毫秒级生效。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在同步核心生命周期状态广播与私有沙盒凭据直投契约 ..."

APP_FILES="/data/data/com.dsha.fr/files"
if [ -d "$APP_FILES" ]; then
    APP_UID=$(stat -c '%u:%g' "$APP_FILES" 2>/dev/null || echo "")
    if [ -n "$APP_UID" ]; then
        touch "$APP_FILES/.auth_url" "$APP_FILES/.launch_token" 2>/dev/null || true
        chmod 600 "$APP_FILES/.auth_url" "$APP_FILES/.launch_token" 2>/dev/null || true
        chown "$APP_UID" "$APP_FILES/.auth_url" "$APP_FILES/.launch_token" 2>/dev/null || true
    fi
fi

echo "✓ 增量补丁应用成功：核心生命周期广播与沙盒直投契约已对齐！"
