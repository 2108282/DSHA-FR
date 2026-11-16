#!/system/bin/sh
# Lite 在通用 overlay 覆盖后执行容器迁移；不依赖 Android 宿主的 Python。
set -eu
ROOTFS="${1:-/data/adb/dsha/rootfs}"
for migration in "$ROOTFS/root/.dsh/rootfs-migrations/"*.py; do
    [ -f "$migration" ] || continue
    name=$(basename "$migration")
    chroot "$ROOTFS" /usr/bin/python3 "/root/.dsh/rootfs-migrations/$name"
done
