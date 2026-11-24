SKIPUNZIP=1
ui_print "*****************************************"
ui_print "      DSHA Native 极速热更新包           "
ui_print "      基础脚本 + overlay · 无现场补丁    "
ui_print "*****************************************"

DATA_DIR="/data/adb/dsha"
ROOTFS_DIR="$DATA_DIR/rootfs"
SCRIPTS_DIR="$DATA_DIR/scripts"

choose_step() {
    local title="$1"
    local timeout=15
    local start_time
    start_time=$(date +%s)
    ui_print ""
    ui_print "-----------------------------------------"
    ui_print " $title"
    ui_print " 音量 +：应用；音量 -：跳过；超时默认跳过"
    ui_print "-----------------------------------------"
    timeout 0.3 getevent -l >/dev/null 2>&1 || true
    while [ $(( $(date +%s) - start_time )) -lt $timeout ]; do
        local events
        events=$(timeout 1 getevent -l 2>/dev/null || true)
        case "$events" in
            *KEY_VOLUMEUP*|*"0001 0073"*|*key_volumeup*) return 0 ;;
            *KEY_VOLUMEDOWN*|*"0001 0072"*|*key_volumedown*) return 1 ;;
        esac
    done
    ui_print "⏱ 超时，跳过此步骤。"
    return 1
}

mkdir -p "$MODPATH" "$SCRIPTS_DIR" "$MODPATH/scripts"
unzip -o "$ZIPFILE" 'module.prop' 'service.sh' 'action.sh' 'uninstall.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'scripts/*' 'rootfs-overlay/*' -d "$MODPATH" >&2

ACTIVE_MOD="/data/adb/modules/dsha_native"
if [ -d "$ACTIVE_MOD" ] && [ "$MODPATH" != "$ACTIVE_MOD" ]; then
    cp -f "$MODPATH/module.prop" "$ACTIVE_MOD/module.prop" 2>/dev/null || true
    cp -f "$MODPATH/action.sh" "$ACTIVE_MOD/action.sh" 2>/dev/null || true
    chmod 755 "$ACTIVE_MOD/action.sh" 2>/dev/null || true
fi

if choose_step "【第 1 步】：是否应用 rootfs-overlay 容器文件更新？"; then
    if [ -d "$MODPATH/rootfs-overlay" ] && [ -n "$(ls -A "$MODPATH/rootfs-overlay" 2>/dev/null)" ]; then
        mkdir -p "$ROOTFS_DIR"
        cp -af "$MODPATH/rootfs-overlay/." "$ROOTFS_DIR/"
        find "$MODPATH/rootfs-overlay" -type f \( -name "*.sh" -o -name "*.js" -o -name "*.py" \) 2>/dev/null | while read -r f; do
            rel_path="${f#$MODPATH/rootfs-overlay/}"
            [ -f "$ROOTFS_DIR/$rel_path" ] && chmod 755 "$ROOTFS_DIR/$rel_path" 2>/dev/null || true
        done
        ui_print "✓ rootfs-overlay 已应用。"
    else
        ui_print "包内没有 rootfs-overlay 文件。"
    fi
else
    ui_print "已跳过 rootfs-overlay。"
fi

if choose_step "【第 2 步】：是否覆盖基础控制脚本？"; then
    for base_script in start.sh stop.sh status.sh term.sh lan-proxy.sh idle-freezer.sh block-guard.sh; do
        if [ -f "$MODPATH/scripts/$base_script" ]; then
            cp -f "$MODPATH/scripts/$base_script" "$SCRIPTS_DIR/$base_script"
            chmod 755 "$SCRIPTS_DIR/$base_script" "$MODPATH/scripts/$base_script"
            ui_print "  ✓ 已更新基础脚本: $base_script"
        fi
    done
else
    ui_print "已跳过基础脚本覆盖。"
fi

chmod 755 "$MODPATH/service.sh" "$MODPATH/action.sh" "$MODPATH/uninstall.sh" 2>/dev/null || true
mkdir -p "$ROOTFS_DIR/root/.agents/skills" 2>/dev/null || true
ui_print "✓ Lite 更新完成（无现场 patches）。"
ui_print "*****************************************"
