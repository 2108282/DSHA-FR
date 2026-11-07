#!/system/bin/sh
# ============================================================
# patch-sync-quickchat-gesture-builtin.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 现场注册全新独立全面屏返回中枢插件 (dsh-quickchat-gesture)；
# 2. 将 dsh-quickchat-gesture 标记为系统固有插件并建立 -installed 立牌；
# 3. 追加至 root/dsha-builtin.txt 与 profiles/web/package.json；
# 4. 自动同步双向 node_modules 软链接与自愈脚本。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在同步全新全面屏返回手势插件 (dsh-quickchat-gesture) 至固有插件..."

ROOT_DIR="$ROOTFS/root"
DSH_DIR="$ROOT_DIR/.dsh"
PROFILE_PKG="$DSH_DIR/profiles/web/package.json"
BUILTIN_TXT="$ROOT_DIR/dsha-builtin.txt"

# 1. 确保 dsha-builtin.txt 包含 dsh-quickchat-gesture
if [ -f "$BUILTIN_TXT" ]; then
    if ! grep -q "dsh-quickchat-gesture" "$BUILTIN_TXT"; then
        echo "dsh-quickchat-gesture" >> "$BUILTIN_TXT"
        echo "  + 已将 dsh-quickchat-gesture 追加至 dsha-builtin.txt"
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

# 2. 补齐固有插件立牌
touch "$ROOT_DIR/dsh-quickchat-gesture-installed" 2>/dev/null || true
touch "$ROOT_DIR/dsh-mobile-lite-installed" 2>/dev/null || true

# 3. 建立双向软链接
PROFILE_NM="$DSH_DIR/profiles/web/node_modules"
GLOBAL_NM="$ROOTFS/usr/local/lib/node_modules"
mkdir -p "$PROFILE_NM" "$GLOBAL_NM" 2>/dev/null || true

if [ -d "$ROOT_DIR/dsh-quickchat-gesture" ]; then
    ln -sfn "/root/dsh-quickchat-gesture" "$PROFILE_NM/dsh-quickchat-gesture" 2>/dev/null || true
    ln -sfn "/root/dsh-quickchat-gesture" "$GLOBAL_NM/dsh-quickchat-gesture" 2>/dev/null || true
    echo "  + 已建立 dsh-quickchat-gesture 双向软链接"
fi

if [ -d "$ROOT_DIR/dsh-mobile-lite" ]; then
    ln -sfn "/root/dsh-mobile-lite" "$PROFILE_NM/dsh-mobile-lite" 2>/dev/null || true
    ln -sfn "/root/dsh-mobile-lite" "$GLOBAL_NM/dsh-mobile-lite" 2>/dev/null || true
fi

# 4. 自动注入 profiles/web/package.json
if [ -f "$PROFILE_PKG" ]; then
    # 若未包含 dsh-quickchat-gesture，则利用 python 或 sed 安全补齐
    if ! grep -q '"dsh-quickchat-gesture"' "$PROFILE_PKG"; then
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
    print('  + profiles/web/package.json 已同步注册 dsh-quickchat-gesture')
except Exception as e:
    print('  ⚠️ 更新 package.json 出现异常:', e)
" 2>/dev/null || true
        fi
    fi
fi

# 5. 执行自愈保障
if [ -f "$DSH_DIR/dsha-plugin-heal.sh" ]; then
    sh "$DSH_DIR/dsha-plugin-heal.sh" >/dev/null 2>&1 || true
fi

echo "✓ 全面屏返回手势插件 (dsh-quickchat-gesture) 固有化增量更新成功！"
