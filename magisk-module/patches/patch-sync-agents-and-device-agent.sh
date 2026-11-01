#!/system/bin/sh
# ============================================================
# patch-sync-agents-and-device-agent.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 同步 AGENTS.md 全局提示词规范（对齐原生 mcp__phone__* 手机操控工具集）；
# 2. 清理已废弃的 android-device-agent 技能与 android_agent.py 脚本；
# 3. 确保 @deepseek-ai/dsh-mcp-client 双层软链接就绪。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在同步 AGENTS.md 规范并清理已废弃的 android-device-agent 技能 ..."

# 1. 确保 AGENTS.md 文件与权限
if [ -f "$ROOTFS/root/.dsh/AGENTS.md" ]; then
    chmod 644 "$ROOTFS/root/.dsh/AGENTS.md" 2>/dev/null || true
fi

# 2. 清理已废弃的 android-device-agent 技能与 android_agent.py
rm -rf "$ROOTFS/root/.dsh/skills/android-device-agent" \
       "$ROOTFS/root/.dsh/scripts/android_agent.py" \
       "$ROOTFS/usr/local/bin/android-agent" \
       "$ROOTFS/usr/local/lib/python3.12/dist-packages/android_agent.py" 2>/dev/null || true

# 3. 确保原生手机操控 MCP 客户端双层软链就绪
mkdir -p "$ROOTFS/root/.dsh/profiles/web/node_modules/@deepseek-ai" "$ROOTFS/usr/local/lib/node_modules/@deepseek-ai" 2>/dev/null || true
ln -sfn /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-mcp-client "$ROOTFS/root/.dsh/profiles/web/node_modules/@deepseek-ai/dsh-mcp-client" 2>/dev/null || true
ln -sfn /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-mcp-client "$ROOTFS/usr/local/lib/node_modules/@deepseek-ai/dsh-mcp-client" 2>/dev/null || true

echo "✓ 增量补丁应用成功：AGENTS.md 与原生手机操控 MCP 环境已就绪！"
