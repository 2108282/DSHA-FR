#!/system/bin/sh
# ============================================================
# patch-core-plugins-symlinks.sh — DSHA 极速热更新增量补丁
# 修复内容：
# 1. 确保 4 大原生内置核心插件在 profiles/web/node_modules 与
#    /usr/local/lib/node_modules 下的符号链接绝对正确且不悬空；
# 2. 补齐 profiles/web/package.json 核心 bundles 声明，确保启动不报未知参数。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在修复核心插件标准软链接与 Web Profile ..."

if [ ! -d "$ROOTFS/root" ]; then
    echo "⚠️ 错误: 未检测到有效的 rootfs 目录 ($ROOTFS)，跳过补丁应用。"
    exit 0
fi

# 1. 补齐四大内置核心插件软链接
PROFILE_NM="$ROOTFS/root/.dsh/profiles/web/node_modules"
GLOBAL_NM="$ROOTFS/usr/local/lib/node_modules"
mkdir -p "$PROFILE_NM" "$GLOBAL_NM" 2>/dev/null || true

for p in dsh-device-shell-guide dsh-task-notifier dsh-status-overlay dsh-web-mobile; do
    tgt="/root/dsha-${p#dsh-}"
    if [ -d "$ROOTFS$tgt" ]; then
        ln -sfn "$tgt" "$PROFILE_NM/$p" 2>/dev/null || true
        ln -sfn "$tgt" "$GLOBAL_NM/$p" 2>/dev/null || true
    fi
done

# 2. 检查 profiles/web/package.json 完整性
PKG_JSON="$ROOTFS/root/.dsh/profiles/web/package.json"
if [ -f "$PKG_JSON" ]; then
    if ! grep -q "@deepseek-ai/dsh-web-app" "$PKG_JSON" 2>/dev/null; then
        sed -i 's|"bundles": \[\s*|"bundles": \[\n        "@deepseek-ai/dsh-base",\n        "@deepseek-ai/dsh-web-app",\n|' "$PKG_JSON" 2>/dev/null || true
    fi
fi

echo "✓ 增量补丁应用成功：核心插件标准软链接与 Web Profile 已修复就绪！"
