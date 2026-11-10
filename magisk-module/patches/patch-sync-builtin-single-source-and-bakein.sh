#!/system/bin/sh
# ============================================================
# patch-sync-builtin-single-source-and-bakein.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 现场部署内置插件单源物理熔铸架构脚本 (register-builtin-plugins/plugin-manager/plugin-lifecycle/dsha-plugin-heal)；
# 2. 彻底拔除内置插件在 plugin-src 下建立覆盖软链的双目录旧逻辑；
# 3. 现场执行 dsha-plugin-heal.sh 规范化单源权威软链接并校准权限；
# 4. 确保 dsha-builtin.txt 与立牌 100% 完整就绪。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在现场部署内置插件单源物理熔铸引擎与权威软链自愈 ..."

ROOT_DIR="$ROOTFS/root"
DSH_DIR="$ROOT_DIR/.dsh"
BUILTIN_TXT="$ROOT_DIR/dsha-builtin.txt"
TMP_STAGE="${TMP_STAGE:-/tmp/dsha_lite_stage_$$}"

# 1. 检测安装包随附的 rootfs-overlay 增量资产源并同步最新 Python 与 Shell 引擎
OVERLAY_SRC=""
if [ -d "$TMP_STAGE/rootfs-overlay/root/.dsh" ]; then
    OVERLAY_SRC="$TMP_STAGE/rootfs-overlay/root/.dsh"
elif [ -d "/tmp/rootfs-overlay/root/.dsh" ]; then
    OVERLAY_SRC="/tmp/rootfs-overlay/root/.dsh"
fi

if [ -n "$OVERLAY_SRC" ]; then
    echo "  -> 正在同步单源物理熔铸核心脚本至 $DSH_DIR ..."
    mkdir -p "$DSH_DIR"
    for script_name in register-builtin-plugins.py plugin-manager.py plugin-lifecycle.py dsha-plugin-heal.sh; do
        if [ -f "$OVERLAY_SRC/$script_name" ]; then
            cp -f "$OVERLAY_SRC/$script_name" "$DSH_DIR/$script_name"
            chmod 755 "$DSH_DIR/$script_name" 2>/dev/null || true
            echo "     ✓ 已更新: $script_name"
        fi
    done
fi

# 2. 确保 dsha-builtin.txt 清单完整包含全部 6 大内置固有插件
if [ -f "$BUILTIN_TXT" ]; then
    for bp in dsh-device-shell-guide dsh-task-notifier dsh-status-overlay dsh-mobile-lite dsh-classic-chat dsh-quickchat-gesture; do
        if ! grep -q "$bp" "$BUILTIN_TXT"; then
            echo "$bp" >> "$BUILTIN_TXT"
            echo "  + 补齐清单: $bp"
        fi
    done
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

# 3. 清理历史残留在 plugin-src 下针对内置插件的错误软链与断链，并重建单源权威软链接
PROFILE_NM="$DSH_DIR/profiles/web/node_modules"
GLOBAL_NM="$ROOTFS/usr/local/lib/node_modules"
mkdir -p "$PROFILE_NM" "$GLOBAL_NM" 2>/dev/null || true

for bp in dsh-device-shell-guide dsh-task-notifier dsh-status-overlay dsh-mobile-lite dsh-classic-chat dsh-quickchat-gesture; do
    if [ "$bp" = "dsh-mobile-lite" ] || [ "$bp" = "dsh-classic-chat" ] || [ "$bp" = "dsh-quickchat-gesture" ]; then
        container_tgt="/root/$bp"
        host_tgt="$ROOT_DIR/$bp"
    else
        container_tgt="/root/dsha-${bp#dsh-}"
        host_tgt="$ROOT_DIR/dsha-${bp#dsh-}"
    fi

    link_target="$PROFILE_NM/$bp"
    global_target="$GLOBAL_NM/$bp"

    # 若旧软链指向 plugin-src 或断链，拔除
    if [ -L "$link_target" ]; then
        dest=$(readlink "$link_target" || true)
        if [ "$dest" = "/root/.dsh/plugin-src/$bp" ] || [ "$dest" = "$DSH_DIR/plugin-src/$bp" ] || [ ! -e "$link_target" ]; then
            rm -f "$link_target" 2>/dev/null || true
            echo "  - 拔除旧版软链: $bp"
        fi
    fi

    # 只要底模实体存在，无条件建立标准单源符号链接 (指向容器内绝对路径)
    if [ -d "$host_tgt" ]; then
        ln -sfn "$container_tgt" "$link_target" 2>/dev/null || true
        ln -sfn "$container_tgt" "$global_target" 2>/dev/null || true
        echo "  + 重建单源软链: $bp -> $container_tgt"
    fi
done

# 4. 同步更新 profiles/web/package.json 权威注册
PROFILE_PKG="$DSH_DIR/profiles/web/package.json"
if [ -f "$PROFILE_PKG" ] && command -v python3 >/dev/null 2>&1; then
    python3 -c "
import json
try:
    with open('$PROFILE_PKG', 'r', encoding='utf-8') as f:
        doc = json.load(f)
    deps = doc.setdefault('dependencies', {})
    deps['dsh-device-shell-guide'] = 'link:/root/dsha-device-shell-guide'
    deps['dsh-task-notifier'] = 'link:/root/dsha-task-notifier'
    deps['dsh-status-overlay'] = 'link:/root/dsha-status-overlay'
    deps['dsh-mobile-lite'] = 'link:/root/dsh-mobile-lite'
    deps['dsh-classic-chat'] = 'link:/root/dsh-classic-chat'
    deps['dsh-quickchat-gesture'] = 'link:/root/dsh-quickchat-gesture'
    bundles = doc.setdefault('dsh', {}).setdefault('profile', {}).setdefault('bundles', [])
    for b in ['dsh-device-shell-guide', 'dsh-task-notifier', 'dsh-status-overlay', 'dsh-mobile-lite', 'dsh-classic-chat', 'dsh-quickchat-gesture']:
        if b not in bundles:
            bundles.append(b)
    with open('$PROFILE_PKG', 'w', encoding='utf-8') as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
    print('  + profiles/web/package.json 已同步注册全部 6 大内置插件')
except Exception as e:
    print('  ⚠️ 更新 package.json 出现异常:', e)
" 2>/dev/null || true
fi

# 5. 现场触发自愈脚本重建单源权威软链接与权限校准
if [ -f "$DSH_DIR/dsha-plugin-heal.sh" ]; then
    echo "  -> 正在执行容器内置插件自愈与权限校准..."
    chroot "$ROOTFS" /bin/bash /root/.dsh/dsha-plugin-heal.sh 2>/dev/null || true
fi

echo "✓ 内置插件单源物理熔铸增量补丁执行完毕！"
