#!/system/bin/sh
# ============================================================
# patch-wake-storage-auto-heal.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 现场检查并修复当前可能已断连的外部存储挂载 (防范 ENOTCONN)；
# 2. 校验宿主与容器存储的 Device ID 一致性，遇断联自动执行惰性解挂与重绑定；
# 3. 确保 idle-freezer.sh 具备 755 可执行权限，保障唤醒期自愈能力正常运行。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在同步外部存储 FUSE 唤醒期探活自愈安全机制 ..."

# 1. 现场检查并修复当前可能已断连的外部存储挂载
HOST_STORAGE="/storage/emulated/0"
TARGET_SDCARD="$ROOTFS/sdcard"
TARGET_STORAGE="$ROOTFS/storage/emulated/0"

if [ -d "$HOST_STORAGE" ] && timeout 1 stat -t "$HOST_STORAGE" >/dev/null 2>&1; then
    NEED_REMOUNT=0
    HOST_DEV=$(stat -c %d "$HOST_STORAGE" 2>/dev/null || echo "")
    TARGET_DEV=$(stat -c %d "$TARGET_SDCARD" 2>/dev/null || echo "")

    if [ -z "$HOST_DEV" ] || [ -z "$TARGET_DEV" ] || [ "$HOST_DEV" != "$TARGET_DEV" ]; then
        NEED_REMOUNT=1
    elif ! timeout 1 ls -d "$TARGET_SDCARD/Download" >/dev/null 2>&1; then
        NEED_REMOUNT=1
    fi

    if [ "$NEED_REMOUNT" = "1" ]; then
        echo "  -> 检测到已有外部存储挂载脱机断联，正在现场自愈..."
        while grep -q " ${TARGET_SDCARD%/} " /proc/mounts 2>/dev/null; do
            umount -l "$TARGET_SDCARD" 2>/dev/null || break
        done
        while grep -q " ${TARGET_STORAGE%/} " /proc/mounts 2>/dev/null; do
            umount -l "$TARGET_STORAGE" 2>/dev/null || break
        done
        mkdir -p "$TARGET_SDCARD" "$TARGET_STORAGE" 2>/dev/null || true
        mount -o bind "$HOST_STORAGE" "$TARGET_SDCARD" 2>/dev/null || true
        mount -o bind "$HOST_STORAGE" "$TARGET_STORAGE" 2>/dev/null || true
        echo "  ✓ 现场外部存储重新绑定完成"
    fi
fi

# 2. 确保 idle-freezer.sh 具备 755 权限
if [ -f "$DATA_DIR/scripts/idle-freezer.sh" ]; then
    chmod 755 "$DATA_DIR/scripts/idle-freezer.sh" 2>/dev/null || true
fi

echo "✓ 增量补丁应用成功：唤醒期外部存储自愈机制已就绪！"
