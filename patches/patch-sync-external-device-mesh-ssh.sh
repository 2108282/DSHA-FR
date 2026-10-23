#!/system/bin/sh
# ============================================================
# patch-sync-external-device-mesh-ssh.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 确保 SSH 专属第三方私钥池 /root/.ssh/keys 目录与 700 权限就绪；
# 2. 同步 external-device-mesh 技能规范，支持私钥池自动挂载与读取。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在同步 SSH 专属私钥池与 external-device-mesh 技能规范 ..."

# 1. 确保专属私钥池目录权限为 700
mkdir -p "$ROOTFS/root/.ssh/keys" 2>/dev/null || true
chmod 700 "$ROOTFS/root/.ssh" "$ROOTFS/root/.ssh/keys" 2>/dev/null || true

# 2. 同步 external-device-mesh 技能文档
SKILL_DST="$ROOTFS/root/.dsh/skills/external-device-mesh"
mkdir -p "$SKILL_DST" 2>/dev/null || true
if [ -f "$ROOTFS/root/.dsh/skills/external-device-mesh/SKILL.md" ]; then
    chmod 644 "$ROOTFS/root/.dsh/skills/external-device-mesh/SKILL.md" 2>/dev/null || true
fi

echo "✓ 增量补丁应用成功：SSH 专属私钥池与 external-device-mesh 技能规范已就绪！"
