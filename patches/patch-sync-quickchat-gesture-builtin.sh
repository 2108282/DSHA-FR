#!/system/bin/sh
# ============================================================
# patch-sync-quickchat-gesture-builtin.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 现场物理部署全新独立全面屏返回中枢插件 (dsh-quickchat-gesture)；
# 2. 现场物理清洗并升级老旧的 dsh-mobile-lite 插件（彻底移除旧手势残留）；
# 3. 将两者标记为系统固有插件并建立 -installed 立牌；
# 4. 自动注册到 root/dsha-builtin.txt 与 profiles/web/package.json；
# 5. 自动重建双向 node_modules 软链接并触发前端缓存刷新。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在现场部署 dsh-quickchat-gesture 并热升级 dsh-mobile-lite ..."

ROOT_DIR="$ROOTFS/root"
DSH_DIR="$ROOT_DIR/.dsh"
PROFILE_DIR="$DSH_DIR/profiles/web"
PROFILE_PKG="$PROFILE_DIR/package.json"
BUILTIN_TXT="$ROOT_DIR/dsha-builtin.txt"
TMP_STAGE="${TMP_STAGE:-/tmp/dsha_lite_stage_$$}"

# -----------------------------------------------------------------------------
# 1. 现场物理部署与热升级两款插件实体
# -----------------------------------------------------------------------------

# 1.1 若随包携带有 rootfs-overlay 增量资产，执行强保障物理安装
OVERLAY_SRC=""
if [ -d "$TMP_STAGE/rootfs-overlay/root" ]; then
    OVERLAY_SRC="$TMP_STAGE/rootfs-overlay/root"
elif [ -d "/tmp/rootfs-overlay/root" ]; then
    OVERLAY_SRC="/tmp/rootfs-overlay/root"
fi

if [ -n "$OVERLAY_SRC" ]; then
    echo "  -> 正在从安装包同步最新插件实体代码..."
    # 部署 dsh-quickchat-gesture
    if [ -d "$OVERLAY_SRC/dsh-quickchat-gesture" ]; then
        mkdir -p "$ROOT_DIR/dsh-quickchat-gesture"
        cp -af "$OVERLAY_SRC/dsh-quickchat-gesture/." "$ROOT_DIR/dsh-quickchat-gesture/"
        chmod -R 755 "$ROOT_DIR/dsh-quickchat-gesture" 2>/dev/null || true
        echo "     ✓ 全新插件 dsh-quickchat-gesture 实体代码部署就绪"
    fi

    # 升级 dsh-mobile-lite
    if [ -d "$OVERLAY_SRC/dsh-mobile-lite" ]; then
        mkdir -p "$ROOT_DIR/dsh-mobile-lite"
        # 显式清理旧手势残留代码，杜绝旧逻辑干扰
        rm -f "$ROOT_DIR/dsh-mobile-lite/src/client/effects/mobile-back-dispatcher.ts" \
             "$ROOT_DIR/dsh-mobile-lite/.client-build/effects/mobile-back-dispatcher.js" 2>/dev/null || true
        cp -af "$OVERLAY_SRC/dsh-mobile-lite/." "$ROOT_DIR/dsh-mobile-lite/"
        chmod -R 755 "$ROOT_DIR/dsh-mobile-lite" 2>/dev/null || true
        echo "     ✓ 原插件 dsh-mobile-lite 实体代码升级就绪 (已剔除手势分发)"
    fi

    # 同步更新 plugin-src (如果存在历史开发目录)
    if [ -d "$DSH_DIR/plugin-src/dsh-mobile-lite" ] && [ -d "$OVERLAY_SRC/dsh-mobile-lite" ]; then
        rm -f "$DSH_DIR/plugin-src/dsh-mobile-lite/src/client/effects/mobile-back-dispatcher.ts" 2>/dev/null || true
        cp -af "$OVERLAY_SRC/dsh-mobile-lite/." "$DSH_DIR/plugin-src/dsh-mobile-lite/" 2>/dev/null || true
    fi
    if [ -d "$DSH_DIR/plugin-src" ] && [ -d "$OVERLAY_SRC/dsh-quickchat-gesture" ]; then
        mkdir -p "$DSH_DIR/plugin-src/dsh-quickchat-gesture"
        cp -af "$OVERLAY_SRC/dsh-quickchat-gesture/." "$DSH_DIR/plugin-src/dsh-quickchat-gesture/" 2>/dev/null || true
    fi
fi

# 1.2 再次确保旧手势文件被彻底铲除
rm -f "$ROOT_DIR/dsh-mobile-lite/src/client/effects/mobile-back-dispatcher.ts" 2>/dev/null || true

# -----------------------------------------------------------------------------
# 2. 标记固有插件凭证 (立牌与清单)
# -----------------------------------------------------------------------------
touch "$ROOT_DIR/dsh-quickchat-gesture-installed" 2>/dev/null || true
touch "$ROOT_DIR/dsh-mobile-lite-installed" 2>/dev/null || true

if [ -f "$BUILTIN_TXT" ]; then
    if ! grep -q "dsh-quickchat-gesture" "$BUILTIN_TXT"; then
        echo "dsh-quickchat-gesture" >> "$BUILTIN_TXT"
        echo "  + 已将 dsh-quickchat-gesture 追加至 dsha-builtin.txt"
    fi
    if ! grep -q "dsh-mobile-lite" "$BUILTIN_TXT"; then
        echo "dsh-mobile-lite" >> "$BUILTIN_TXT"
    fi
else
    cat > "$BUILTIN_TXT" << 'EOF'
dsh-device-shell-guide
dsh-task-notifier
dsh-status-overlay
dsh-mobile-lite
dsh-classic-chat
dsh-quickchat-gesture
EOF
fi

# -----------------------------------------------------------------------------
# 3. 注册到 Web Profile (package.json)
# -----------------------------------------------------------------------------
if [ -f "$PROFILE_PKG" ]; then
    if command -v python3 >/dev/null 2>&1; then
        python3 -c "
import json
try:
    with open('$PROFILE_PKG', 'r', encoding='utf-8') as f:
        doc = json.load(f)
    deps = doc.setdefault('dependencies', {})
    deps['dsh-quickchat-gesture'] = 'link:/root/dsh-quickchat-gesture'
    deps['dsh-mobile-lite'] = 'link:/root/dsh-mobile-lite'
    bundles = doc.setdefault('dsh', {}).setdefault('profile', {}).setdefault('bundles', [])
    if 'dsh-quickchat-gesture' not in bundles:
        bundles.append('dsh-quickchat-gesture')
    if 'dsh-mobile-lite' not in bundles:
        bundles.append('dsh-mobile-lite')
    with open('$PROFILE_PKG', 'w', encoding='utf-8') as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
    print('  + profiles/web/package.json 已同步注册双插件')
except Exception as e:
    print('  ⚠️ 更新 package.json 出现异常:', e)
" 2>/dev/null || true
    fi
fi

# -----------------------------------------------------------------------------
# 4. 重建双向符号链接并触发行自愈
# -----------------------------------------------------------------------------
PROFILE_NM="$PROFILE_DIR/node_modules"
GLOBAL_NM="$ROOTFS/usr/local/lib/node_modules"
mkdir -p "$PROFILE_NM" "$GLOBAL_NM" 2>/dev/null || true

for p in dsh-quickchat-gesture dsh-mobile-lite; do
    if [ -d "$ROOT_DIR/$p" ]; then
        ln -sfn "/root/$p" "$PROFILE_NM/$p" 2>/dev/null || true
        ln -sfn "/root/$p" "$GLOBAL_NM/$p" 2>/dev/null || true
        echo "  + 重建软链接: $p -> /root/$p"
    fi
done

# 同步执行最新的自愈脚本
if [ -f "$DSH_DIR/dsha-plugin-heal.sh" ]; then
    sh "$DSH_DIR/dsha-plugin-heal.sh" >/dev/null 2>&1 || true
fi

# 刷新 client.js 时间戳以击穿前端 HMR 与静态缓存
for cjs in "$ROOT_DIR/dsh-quickchat-gesture/lib/client.js" "$ROOT_DIR/dsh-mobile-lite/lib/client.js"; do
    [ -f "$cjs" ] && touch "$cjs" 2>/dev/null || true
done

echo "✓ 全新全面屏手势独立插件 (dsh-quickchat-gesture) 现场安装与 dsh-mobile-lite 升级完毕！"
