#!/system/bin/sh
# ============================================================
# patch-sync-dsh-mobile-lite-v101.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 现场物理升级内置插件 dsh-mobile-lite 至 v1.0.1 生产闭包：
#    - 底部性能与用量组件单排紧凑自适应 (activity / usage / stats-ring 极限窄屏不换行)；
#    - 原生线条风格回形针文件上传触发器 (就地对齐权限盾牌按钮)；
# 2. 现场校准插件权限与文件时间戳，击穿浏览器 HMR 与静态缓存；
# 3. 确保 profiles/web/package.json 与双向软链接就绪。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在现场升级 dsh-mobile-lite 至 v1.0.1 (单排Dock自适应 + 原生文件上传) ..."

ROOT_DIR="$ROOTFS/root"
DSH_DIR="$ROOT_DIR/.dsh"
PROFILE_DIR="$DSH_DIR/profiles/web"
PROFILE_PKG="$PROFILE_DIR/package.json"
BUILTIN_TXT="$ROOT_DIR/dsha-builtin.txt"
TMP_STAGE="${TMP_STAGE:-/tmp/dsha_lite_stage_$$}"

# 1. 检测安装包随附的 rootfs-overlay 增量资产源
OVERLAY_SRC=""
if [ -d "$TMP_STAGE/rootfs-overlay/root" ]; then
    OVERLAY_SRC="$TMP_STAGE/rootfs-overlay/root"
elif [ -d "/tmp/rootfs-overlay/root" ]; then
    OVERLAY_SRC="/tmp/rootfs-overlay/root"
fi

if [ -n "$OVERLAY_SRC" ] && [ -d "$OVERLAY_SRC/dsh-mobile-lite" ]; then
    echo "  -> 正在从安装包同步 dsh-mobile-lite (v1.0.1) 最新代码..."
    mkdir -p "$ROOT_DIR/dsh-mobile-lite"
    cp -af "$OVERLAY_SRC/dsh-mobile-lite/." "$ROOT_DIR/dsh-mobile-lite/"
    chmod -R 755 "$ROOT_DIR/dsh-mobile-lite" 2>/dev/null || true
    echo "     ✓ dsh-mobile-lite v1.0.1 生产代码已同步至容器"

    # 若存在 plugin-src 目录且为独立实体目录，同步更新
    if [ -d "$DSH_DIR/plugin-src/dsh-mobile-lite" ] && [ ! -L "$DSH_DIR/plugin-src/dsh-mobile-lite" ]; then
        cp -af "$OVERLAY_SRC/dsh-mobile-lite/." "$DSH_DIR/plugin-src/dsh-mobile-lite/" 2>/dev/null || true
    fi
fi

# 2. 确保固有插件标记立牌
touch "$ROOT_DIR/dsh-mobile-lite-installed" 2>/dev/null || true
if [ -f "$BUILTIN_TXT" ]; then
    if ! grep -q "^dsh-mobile-lite$" "$BUILTIN_TXT"; then
        echo "dsh-mobile-lite" >> "$BUILTIN_TXT"
        echo "  + 已将 dsh-mobile-lite 补齐至 dsha-builtin.txt"
    fi
fi

# 3. 确保 profiles/web 依赖与软链接
PROFILE_NM="$PROFILE_DIR/node_modules"
GLOBAL_NM="$ROOTFS/usr/local/lib/node_modules"
mkdir -p "$PROFILE_NM" "$GLOBAL_NM" 2>/dev/null || true

if [ -d "$ROOT_DIR/dsh-mobile-lite" ]; then
    ln -sfn "/root/dsh-mobile-lite" "$PROFILE_NM/dsh-mobile-lite" 2>/dev/null || true
    ln -sfn "/root/dsh-mobile-lite" "$GLOBAL_NM/dsh-mobile-lite" 2>/dev/null || true
fi

# 4. 刷新客户端入口文件时间戳，击穿前端 HMR 与静态缓存
if [ -f "$ROOT_DIR/dsh-mobile-lite/lib/client.js" ]; then
    touch "$ROOT_DIR/dsh-mobile-lite/lib/client.js" 2>/dev/null || true
fi

echo "✓ dsh-mobile-lite (v1.0.1) 现场热升级完毕！"
