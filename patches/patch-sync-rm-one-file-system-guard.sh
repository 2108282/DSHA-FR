#!/system/bin/sh
# ============================================================
# patch-sync-rm-one-file-system-guard.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 现场升级容器 /root/dsh-bin/rm 命令安全守卫包装器；
# 2. 递归删除自动注入 --one-file-system 防护，杜绝跨文件系统穿透；
# 3. 遇到跨设备挂载点被跳过时，输出清晰纯文本提示。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在现场升级 rm 命令防穿透安全守卫 (自动注入 --one-file-system) ..."

DSH_BIN="$ROOTFS/root/dsh-bin"
TMP_STAGE="${TMP_STAGE:-/tmp/dsha_lite_stage_$$}"

OVERLAY_RM=""
if [ -f "$TMP_STAGE/rootfs-overlay/root/dsh-bin/rm" ]; then
    OVERLAY_RM="$TMP_STAGE/rootfs-overlay/root/dsh-bin/rm"
elif [ -f "/tmp/rootfs-overlay/root/dsh-bin/rm" ]; then
    OVERLAY_RM="/tmp/rootfs-overlay/root/dsh-bin/rm"
fi

mkdir -p "$DSH_BIN"
if [ -n "$OVERLAY_RM" ] && [ -f "$OVERLAY_RM" ]; then
    cp -f "$OVERLAY_RM" "$DSH_BIN/rm"
    chmod 755 "$DSH_BIN/rm"
    echo "  ✓ 已同步最新 rm 安全包装器"
else
    cat << 'EOF' > "$DSH_BIN/rm"
#!/bin/bash
SELF="rm"
REAL="/bin/rm"
[ ! -x "$REAL" ] && REAL="/usr/bin/rm"

IS_RECURSIVE=0
for arg in "$@"; do
  case "$arg" in
    -*r*|-*R*|--recursive) IS_RECURSIVE=1 ;;
  esac
done

if [ "$IS_RECURSIVE" = "1" ]; then
  case " $* " in
    *" --one-file-system "*) ;;
    *) set -- --one-file-system "$@" ;;
  esac
fi

if [ "${DSH_CONFIRM:-0}" = "1" ] || [ "${DSH_SHELL:-0}" = "1" ]; then
  if ! /root/dsh-confirm.sh "$SELF $*"; then
    echo "已拒绝: $SELF $*" >&2
    exit 1
  fi
fi

ERR_LOG=$(mktemp 2>/dev/null || echo "/tmp/.rm_err_$$")
"$REAL" "$@" 2>"$ERR_LOG"
STATUS=$?

if [ -s "$ERR_LOG" ]; then
  cat "$ERR_LOG" >&2
  if grep -E -q "different device|different file system" "$ERR_LOG"; then
    echo "[DSHA 提示]: 检测到目标路径包含活动挂载目录，已安全跳过，请检查是否需要进一步手动卸载清理！" >&2
  fi
fi
"$REAL" -f "$ERR_LOG" 2>/dev/null || true
exit $STATUS
EOF
    chmod 755 "$DSH_BIN/rm"
    echo "  ✓ 现场生成并注入增强版 rm 守卫"
fi

echo "✓ rm 命令防穿透安全守卫升级完成！"
