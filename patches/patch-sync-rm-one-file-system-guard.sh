#!/system/bin/sh
# ============================================================
# patch-sync-rm-one-file-system-guard.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 现场升级容器 /root/dsh-bin/rm 命令安全守卫包装器；
# 2. 注入 --one-file-system 自动防护逻辑，杜绝递归删除穿透挂载点；
# 3. 增加活动挂载点扫描警告，提示人工检查未卸载目录。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在现场升级 rm 命令防穿透安全守卫 (one-file-system + 挂载扫描) ..."

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
    # 现场生成增强版 rm
    cat << 'EOF' > "$DSH_BIN/rm"
#!/bin/bash
SELF="rm"
REAL=""
for p in /usr/local/bin /usr/bin /bin /usr/sbin /sbin; do
  if [ -x "$p/$SELF" ] && [ "$p/$SELF" != "$0" ]; then REAL="$p/$SELF"; break; fi
done
[ -z "$REAL" ] && REAL=$(ls /usr/local/bin/$SELF /usr/bin/$SELF /bin/$SELF 2>/dev/null | head -1)
if [ -z "$REAL" ]; then echo "找不到真实命令: $SELF" >&2; exit 127; fi

IS_RECURSIVE=0
HAS_ONE_FS=0
for arg in "$@"; do
  case "$arg" in
    -*r*|-*R*|--recursive) IS_RECURSIVE=1 ;;
    --one-file-system) HAS_ONE_FS=1 ;;
  esac
done

if [ "$IS_RECURSIVE" = "1" ]; then
  for target in "$@"; do
    case "$target" in -*) continue ;; esac
    abs_target=$(realpath -m "$target" 2>/dev/null || echo "$target")
    mount_hits=$(grep -F " ${abs_target%/}/" /proc/mounts 2>/dev/null | awk '{print $2}' || true)
    if [ -n "$mount_hits" ]; then
      echo "⚠️ [DSHA 存储安全提示]: 检测到目标路径下存在活动挂载目录：" >&2
      for m in $mount_hits; do echo "   -> 挂载点: $m" >&2; done
      echo "   已自动启用 --one-file-system 保护，挂载点内容已被跳过，请检查是否需要进一步卸载清理。" >&2
    fi
  done

  if [ "$HAS_ONE_FS" = "0" ]; then
    set -- --one-file-system "$@"
  fi
fi

if [ "${DSH_CONFIRM:-0}" != "1" ] && [ "${DSH_SHELL:-0}" != "1" ]; then
  exec "$REAL" "$@"
fi
if /root/dsh-confirm.sh "$SELF $*"; then
  exec "$REAL" "$@"
fi
echo "已拒绝: $SELF $*" >&2
exit 1
EOF
    chmod 755 "$DSH_BIN/rm"
    echo "  ✓ 现场生成并注入增强版 rm 守卫"
fi

echo "✓ rm 命令防穿透安全守卫升级完成！"
