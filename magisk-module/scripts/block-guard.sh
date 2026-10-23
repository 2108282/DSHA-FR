#!/system/bin/sh
# DSHA Block Guard - 物理块设备防砖隔离即时控制与内核真实扫描脚本
# 用法:
#   sh block-guard.sh on         # 立即开启容器防砖隔离
#   sh block-guard.sh off        # 立即关闭所有隔离（临时放行）
#   sh block-guard.sh global-on  # 即时开启全局穿透（整机屏蔽，毫秒级生效）
#   sh block-guard.sh global-off # 即时关闭全局穿透（释放宿主，毫秒级生效）
#   sh block-guard.sh status     # 真实扫描内核挂载表 (输出 ROOT:0/1 HOST:0/1)

ROOTFS="/data/adb/dsha/rootfs"
RUN_DIR="/data/adb/dsha/run"
GLOBAL_FLAG="$RUN_DIR/block_guard_global"
STATE_FILE="$RUN_DIR/block_guard_state"

mkdir -p "$RUN_DIR" 2>/dev/null

is_mounted() {
    local target="${1%/}"
    grep -E "tmpfs[[:space:]]+$target[[:space:]]" /proc/mounts >/dev/null 2>&1
}

clean_umount() {
    local target="$1"
    while is_mounted "$target"; do
        umount -l "$target" 2>/dev/null || break
    done
}

ensure_rslave() {
    mount -o rslave none "$ROOTFS/dev" 2>/dev/null || /data/adb/ksu/bin/busybox mount --make-rslave "$ROOTFS/dev" 2>/dev/null || true
}

ACTION="${1:-status}"

case "$ACTION" in
    on|enable|1)
        # 1. 确保在默认非全局穿透时切断反向传播
        if [ ! -f "$GLOBAL_FLAG" ] || [ "$(cat "$GLOBAL_FLAG" 2>/dev/null)" != "1" ]; then
            ensure_rslave
        fi

        # 2. 确保容器内的 /dev/block 挂载只读 mode=000 tmpfs
        mkdir -p "$ROOTFS/dev/block" 2>/dev/null
        if ! is_mounted "$ROOTFS/dev/block"; then
            mount -t tmpfs tmpfs -o ro,mode=000 "$ROOTFS/dev/block" 2>/dev/null || true
        fi

        # 3. 如果开启了全局穿透，则宿主 /dev/block 也立即挂载屏蔽
        if [ -f "$GLOBAL_FLAG" ] && [ "$(cat "$GLOBAL_FLAG" 2>/dev/null)" = "1" ]; then
            mkdir -p "/dev/block" 2>/dev/null
            if ! is_mounted "/dev/block"; then
                mount -t tmpfs tmpfs -o ro,mode=000 "/dev/block" 2>/dev/null || true
            fi
        fi

        echo "1" > "$STATE_FILE" 2>/dev/null
        rm -f "/data/data/com.dsha.fr/files/.block_guard_disabled" 2>/dev/null || true
        echo "ACTIVE"
        ;;

    off|disable|0)
        # 立即解挂容器与宿主上的所有物理块设备遮蔽
        clean_umount "$ROOTFS/dev/block"
        clean_umount "/dev/block"

        echo "0" > "$STATE_FILE" 2>/dev/null
        echo "INACTIVE"
        ;;

    global-on)
        echo "1" > "$GLOBAL_FLAG" 2>/dev/null
        # 宿主 /dev/block 立即挂载屏蔽（免重启热生效）
        mkdir -p "/dev/block" 2>/dev/null
        if ! is_mounted "/dev/block"; then
            mount -t tmpfs tmpfs -o ro,mode=000 "/dev/block" 2>/dev/null || true
        fi
        echo "GLOBAL_ACTIVE"
        ;;

    global-off)
        echo "0" > "$GLOBAL_FLAG" 2>/dev/null
        # 必须先确保容器为 rslave，切断反向传播，防止解挂宿主带崩容器
        ensure_rslave
        # 立即解挂宿主 /dev/block，释放宿主
        clean_umount "/dev/block"
        echo "GLOBAL_INACTIVE"
        ;;

    status)
        ROOT_M="0"
        HOST_M="0"
        is_mounted "$ROOTFS/dev/block" && ROOT_M="1"
        is_mounted "/dev/block" && HOST_M="1"
        echo "ROOT:$ROOT_M HOST:$HOST_M"
        ;;

    *)
        echo "Usage: $0 {on|off|global-on|global-off|status}"
        exit 1
        ;;
esac
exit 0
