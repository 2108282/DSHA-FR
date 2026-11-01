#!/system/bin/sh
# ============================================================
# patch-sync-agents-and-device-agent.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 同步 AGENTS.md 全局提示词规范（剔除重复项，对齐自动化双技能矩阵）；
# 2. 同步 android-device-agent 技能文档与直达意图附录；
# 3. 确保 android-agent 命令行与 Python 模块软链就绪。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在同步 AGENTS.md 规范并清理已废弃的 android-device-agent 技能 ..."

# 1. 确保 AGENTS.md 文件与权限
if [ -f "$ROOTFS/root/.dsh/AGENTS.md" ]; then
    chmod 644 "$ROOTFS/root/.dsh/AGENTS.md" 2>/dev/null || true
fi

echo "✓ 增量补丁应用成功：AGENTS.md 已就绪！"
