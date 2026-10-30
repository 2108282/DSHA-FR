#!/system/bin/sh
# ============================================================
# patch-fuse-mount-timeout-guard.sh — DSHA 极速热更新增量补丁
# 包含内容：
# 1. 实体化 dsha-client-combo-cache 进容器 node_modules，切断跨 /sdcard 符号链接；
# 2. 清理 profiles/web/package.json 中指向外部存储的失效 link 声明；
# 3. 若存在断开的 /sdcard 或 /storage 僵尸 FUSE 挂载点，执行惰性解挂以允许自动重连。
# ============================================================
set -eu

ROOTFS="${1:-/data/adb/dsha/rootfs}"
DATA_DIR="${2:-/data/adb/dsha}"

echo "==> [增量补丁] 正在加固外部存储 FUSE 挂载安全与启动依赖隔离 ..."

# 1. 解除历史残留的僵死挂载点（防范 Transport endpoint is not connected）
for m in "$ROOTFS/sdcard" "$ROOTFS/storage/emulated/0"; do
    if grep -q " $m " /proc/mounts 2>/dev/null; then
        if ! timeout 1 stat -t "$m" >/dev/null 2>&1; then
            echo "  -> 检测到挂载点失效: $m，执行惰性解挂..."
            umount -l "$m" 2>/dev/null || true
        fi
    fi
done

# 2. 检查 profiles/web 依赖声明并清理外部存储 softlink
WEB_PF="$ROOTFS/root/.dsh/profiles/web/package.json"
WEB_NM="$ROOTFS/root/.dsh/profiles/web/node_modules"

if [ -d "$WEB_NM" ]; then
    # 移除指向 /sdcard 的软链接
    for lnk in "$WEB_NM/dsha-client-combo-cache" "$WEB_NM/dsha-marketplace"; do
        if [ -L "$lnk" ]; then
            target=$(readlink "$lnk" 2>/dev/null || true)
            case "$target" in
                *sdcard*|*storage*)
                    echo "  -> 移除跨界软链接: $(basename "$lnk") -> $target"
                    rm -f "$lnk" 2>/dev/null || true
                    ;;
            esac
        fi
    done
fi

# 3. 实体化 dsha-client-combo-cache 模块至容器内部
COMBO_DIR="$WEB_NM/dsha-client-combo-cache"
if [ ! -d "$COMBO_DIR" ]; then
    mkdir -p "$COMBO_DIR" 2>/dev/null || true
fi

cat << 'CACHE_PKG_EOF' > "$COMBO_DIR/package.json"
{"name":"dsha-client-combo-cache","version":"1.0.0","type":"module","exports":"./index.js"}
CACHE_PKG_EOF

cat << 'CACHE_JS_EOF' > "$COMBO_DIR/index.js"
/** 换行只需搜索 LF；避免为整份网页脚本逐个创建 Unicode 字符。 */
export function countNewlines(value) {
  let count = 0, offset = -1;
  while ((offset = value.indexOf('\n', offset + 1)) !== -1) count++;
  return count;
}

/** 仅复用上一轮图中相同的脚本与映射；本轮未使用的结果随旧图释放。 */
export function createComboCache(build, previous = new Map()) {
  const next = new Map();
  function cached(records, revision) {
    const key = JSON.stringify([revision, records.map(record => [record.entry.id, record.entry.rev])]);
    let saved = next.get(key) || previous.get(key);
    if (!saved || saved.inputs.length !== records.length || !records.every((record, index) =>
      saved.inputs[index].bundle === record.bundle && saved.inputs[index].sourceMap === record.sourceMap)) {
      saved = { inputs: records.map(record => ({bundle: record.bundle, sourceMap: record.sourceMap})),
        artifact: build(records, revision) };
    }
    next.set(key, saved);
    return saved.artifact;
  }
  cached.cache = next;
  return cached;
}
CACHE_JS_EOF

# 4. 同步至全局 node_modules，保障全局加载一致性
GLOBAL_COMBO="$ROOTFS/usr/local/lib/node_modules/dsha-client-combo-cache"
if [ -L "$GLOBAL_COMBO" ]; then
    rm -f "$GLOBAL_COMBO" 2>/dev/null || true
fi
mkdir -p "$GLOBAL_COMBO" 2>/dev/null || true
cp -f "$COMBO_DIR/package.json" "$GLOBAL_COMBO/package.json" 2>/dev/null || true
cp -f "$COMBO_DIR/index.js" "$GLOBAL_COMBO/index.js" 2>/dev/null || true

# 5. 清洗 package.json 中的无效 link 声明
if [ -f "$WEB_PF" ]; then
    sed -i '/"dsha-client-combo-cache": "link:/d' "$WEB_PF" 2>/dev/null || true
    sed -i '/"dsha-marketplace": "link:/d' "$WEB_PF" 2>/dev/null || true
fi

echo "✓ 增量补丁应用成功：外部存储 FUSE 安全熔断与开机依赖本地化已就绪！"
