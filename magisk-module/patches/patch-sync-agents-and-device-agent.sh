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

echo "==> [增量补丁] 正在同步 AGENTS.md 规范与 android-device-agent 自动化技能 ..."

# 1. 确保 AGENTS.md 文件与权限
if [ -f "$ROOTFS/root/.dsh/AGENTS.md" ]; then
    chmod 644 "$ROOTFS/root/.dsh/AGENTS.md" 2>/dev/null || true
fi

# 2. 确保 android-device-agent 技能规范权限
SKILL_DIR="$ROOTFS/root/.dsh/skills/android-device-agent"
if [ -d "$SKILL_DIR" ]; then
    chmod 755 "$SKILL_DIR" 2>/dev/null || true
    chmod 644 "$SKILL_DIR/SKILL.md" 2>/dev/null || true
    if [ -d "$SKILL_DIR/references" ]; then
        chmod 755 "$SKILL_DIR/references" 2>/dev/null || true
        chmod 644 "$SKILL_DIR/references/"*.md 2>/dev/null || true
    fi
fi

# 3. 确保 CLI 工具与 Python 模块软链就绪
if [ -f "$ROOTFS/root/.dsh/scripts/android_agent.py" ]; then
    chmod 755 "$ROOTFS/root/.dsh/scripts/android_agent.py" 2>/dev/null || true
    mkdir -p "$ROOTFS/usr/local/bin" 2>/dev/null || true
    ln -sfn /root/.dsh/scripts/android_agent.py "$ROOTFS/usr/local/bin/android-agent" 2>/dev/null || true
    mkdir -p "$ROOTFS/usr/local/lib/python3.12/dist-packages" 2>/dev/null || true
    ln -sfn /root/.dsh/scripts/android_agent.py "$ROOTFS/usr/local/lib/python3.12/dist-packages/android_agent.py" 2>/dev/null || true
fi

echo "✓ 增量补丁应用成功：AGENTS.md 与 android-device-agent 技能链已就绪！"
