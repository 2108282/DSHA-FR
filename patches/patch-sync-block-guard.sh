#!/system/bin/sh
# ============================================================
# patch-sync-block-guard.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 确保物理块设备防砖隔离脚本 (block-guard.sh) 具备 755 可执行权限；
# 2. 初始化 block_guard_global 状态标记为 0（默认阻断反向穿透）；
# 3. 若检测到宿主 /dev/block 存在历史残留遮蔽，自动安全解挂放行宿主应用。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在同步物理块设备防砖隔离控制与挂载安全契约 ..."

RUN_DIR="$DATA_DIR/run"
mkdir -p "$RUN_DIR" 2>/dev/null || true
chmod 777 "$RUN_DIR" 2>/dev/null || true

# 1. 确保 block-guard.sh 存在并赋予可执行权限
if [ -f "$DATA_DIR/scripts/block-guard.sh" ]; then
    chmod 755 "$DATA_DIR/scripts/block-guard.sh" 2>/dev/null || true
fi

# 2. 默认保持全局穿透为 0（仅隔离容器内部，宿主不受影响）
GLOBAL_FLAG="$RUN_DIR/block_guard_global"
if [ ! -f "$GLOBAL_FLAG" ]; then
    echo "0" > "$GLOBAL_FLAG" 2>/dev/null || true
fi

# 3. 若全局穿透为 0 且宿主 /dev/block 存在 tmpfs 残留，执行安全解挂
if [ "$(cat "$GLOBAL_FLAG" 2>/dev/null)" != "1" ]; then
    while grep -E 'tmpfs[[:space:]]+/dev/block[[:space:]]' /proc/mounts >/dev/null 2>&1; do
        umount -l /dev/block 2>/dev/null || break
    done
fi

echo "✓ 增量补丁应用成功：物理块设备防砖隔离控制与宿主放行契约已就绪！"
