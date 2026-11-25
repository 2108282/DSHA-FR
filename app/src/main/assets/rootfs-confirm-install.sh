#!/bin/bash
# 安装 rootfs 危险命令确认包装器（DSHA 安全）
# 包装器：DSH_CONFIRM=1 时危险命令先确认（App 弹窗 / 终端交互），否则直接放行（安装流程不受影响）
set -e
DSH_BIN=/root/dsh-bin
mkdir -p "$DSH_BIN"

cat > /root/dsh-confirm.sh <<'EOF'
#!/bin/bash
# 用法：dsh-confirm.sh [--force|--guard] <命令...>
#   --force：所有命令都弹确认（设备 shell 报备用；守卫开时）
#   --guard：终端已判危，桥仍检查开关与临时免审租约
#   不带：仅危险命令确认（守卫内部行为由 3090 桥 confirmEnabled 决定）
FORCE=0
case "${1:-}" in --force) FORCE=1; shift ;; --guard) FORCE=2; shift ;; esac
CMD="$*"
# 3090 桥有 token 鉴权（防其他 App 冒充 agent 弹确认框）：
# 必须带 X-Token 头（= /root/.dsh/.bridge_token 内容），否则一律 [UNAUTHORIZED] 被拒
TOKEN=$(cat /root/.dsh/.bridge_token 2>/dev/null)
# 桥的监听地址随 App 版本不同（新版绑 127.0.0.1 并附加 [::1]，旧版只绑 [::1]）：
http_confirm() {
  local host="$1"
  local port="$2"
  local token="$3"
  local cmd="$4"
  local force="$5"

  if command -v curl >/dev/null 2>&1; then
    curl -s -m 65 -G "http://$host:$port/confirm" --data-urlencode "cmd=$cmd" --data-urlencode "force=$force" \
      --data-urlencode "root_session_id=${DSH_APPROVAL_ROOT_SESSION_ID:-}" \
      --data-urlencode "session_id=${DSH_SESSION_ID:-}" \
      --data-urlencode "conversation_title=${DSH_APPROVAL_CONVERSATION_TITLE:-}" \
      --data-urlencode "agent_title=${DSH_APPROVAL_AGENT_TITLE:-}" -H "X-Token: $token" 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c "
import os, sys, urllib.request, urllib.parse
host, port, token, cmd, force = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
h = '127.0.0.1' if host == '[::1]' else host
params = urllib.parse.urlencode({'cmd': cmd, 'force': force,
    'root_session_id': os.environ.get('DSH_APPROVAL_ROOT_SESSION_ID', ''),
    'session_id': os.environ.get('DSH_SESSION_ID', ''),
    'conversation_title': os.environ.get('DSH_APPROVAL_CONVERSATION_TITLE', ''),
    'agent_title': os.environ.get('DSH_APPROVAL_AGENT_TITLE', '')})
url = f'http://{h}:{port}/confirm?{params}'
req = urllib.request.Request(url, headers={'X-Token': token})
try:
    with urllib.request.urlopen(req, timeout=65) as resp:
        print(resp.read().decode('utf-8', errors='ignore'))
except Exception:
    pass
" "$host" "$port" "$token" "$cmd" "$force" 2>/dev/null
  elif command -v node >/dev/null 2>&1; then
    node -e '
const http = require("http");
const [, host, port, token, cmd, force] = process.argv;
const h = host === "[::1]" ? "127.0.0.1" : host;
const params = new URLSearchParams({ cmd, force,
  root_session_id: process.env.DSH_APPROVAL_ROOT_SESSION_ID || "",
  session_id: process.env.DSH_SESSION_ID || "",
  conversation_title: process.env.DSH_APPROVAL_CONVERSATION_TITLE || "",
  agent_title: process.env.DSH_APPROVAL_AGENT_TITLE || "" });
const options = {
  hostname: h,
  port: Number(port),
  path: `/confirm?${params.toString()}`,
  method: "GET",
  headers: { "X-Token": token },
  timeout: 65000
};
const req = http.request(options, (res) => {
  let data = "";
  res.on("data", (chunk) => data += chunk);
  res.on("end", () => process.stdout.write(data));
});
req.on("error", () => {});
req.on("timeout", () => { req.destroy(); });
req.end();
' "$host" "$port" "$token" "$cmd" "$force" 2>/dev/null
  fi
}

# 两个地址族都试，避免「桥活着但连不上 → 确认弹窗永不出现」。
RES=""
for P in 3095 3090; do
  for H in 127.0.0.1 '[::1]'; do
    RES=$(http_confirm "$H" "$P" "$TOKEN" "$CMD" "$FORCE")
    # 严格匹配 {"result":"YES"}：宽松的 grep YES 会被响应里的其它字段或命令回显
    # 带偏（吸收上游 PR#24）
    case "$RES" in
      *'"result":"YES"'*) exit 0 ;;
      *'"result":"NO"'*)  echo "已拒绝: $CMD（用户点了拒绝）" >&2; exit 1 ;;
      *'"result":YES'*)   exit 0 ;;  # 兼容老版 App 的非法 JSON 响应
    esac
    [ -n "$RES" ] && break 2
  done
done
if [ -n "$RES" ]; then
  # 有响应但既不是 YES 也不是 NO：桥说了别的（如 Shizuku 未就绪），一律拒绝
  echo "已拒绝: $CMD" >&2
  echo "  桥返回：$RES" >&2
  exit 1
fi
# 3090 不可达（终端场景未启动服务）：终端内交互确认，10 秒超时默认拒绝
if [ -n "$DSH_INTERACTIVE" ]; then
  echo -n "确认执行 [$CMD] ? [y/N] " >&2
  read -t 10 ans
  case "$ans" in
    y|Y) exit 0 ;;
  esac
fi
# 静默拒绝最难排查 —— 把原因写清楚（吸收上游 PR#24）
echo "已拒绝: $CMD" >&2
if [ -z "$TOKEN" ]; then
  echo "  原因：读不到 /root/.dsh/.bridge_token（App 未生成鉴权令牌）" >&2
else
  echo "  原因：3090 确认桥不可达（127.0.0.1 与 [::1] 都连不上）" >&2
fi
echo "  处理：在 App「配置」页启用 ADB 设备通道，确认桥运行后重试" >&2
exit 1
EOF
chmod +x /root/dsh-confirm.sh

# 生成可由 shell 显式 source 的函数级守卫 /root/dsh-guard.sh。
# 不用 BASH_ENV —— 它会污染插件初始化时的子 shell，导致 dsh web 加载插件失败。
# 函数优先于 PATH 查找，PATH 被覆盖/哈希缓存都无法绕过命令名拦截
cat > /root/dsh-guard-policy.sh <<'POLICY_EOF'
# DSHA-GUARD-V13: shared by function and PATH guards; no child process on flag reads.
# 作用域仅限 agent 发起的模型 shell 调用，不得扩散到全环境：
#  - DSH_SHELL 由 DSH 核心的 shellEnv 每次调用时注入，只贴给 model 的 bash/pwsh 执行，
#    不继承给 pnpm、插件、start.sh/stop.sh 等程序派生的子进程 —— 唯一可靠的判据。
#  - DSH_CONFIRM 是 start.sh 传给 node 主进程的进程级变量，会继承给上述所有子进程，
#    用它判危会让插件安装和系统清理脚本一起弹窗，故不参与判定。
#  - .guard_enabled 是 App 的开关，只做减法（写 0 才关），不能让守卫在无 DSH_SHELL 时激活。
__dsha_guard_active() {
  [ "${DSH_SHELL:-0}" = 1 ] || return 1
  local value
  if [ -e /root/.dsh/.guard_enabled ]; then
    value=''
    IFS= read -r value < /root/.dsh/.guard_enabled || :
    [ "$value" = 0 ] && return 1
  fi
  return 0
}
__dsha_critical_path() {
  local target="$1" path
  [ -n "$target" ] || return 1
  target="$(/usr/bin/realpath -m -- "$target" 2>/dev/null)" || return 1
  for path in /root/.dsh /usr/local/lib/node_modules /usr/local/bin /etc /usr/lib /system /data/data; do
    case "$target" in "$path"|"$path"/*) return 0 ;; esac
    # Moving an ancestor also moves its protected descendants.
    case "$path/" in "${target%/}/"*) return 0 ;; esac
  done
  return 1
}
__dsha_needs_confirm() {
  local name="$1" arg
  shift
  __dsha_guard_active || return 1
  case "$name" in
    mv)
      for arg in "$@"; do
        case "$arg" in --target-directory=*) arg="${arg#*=}" ;; -t?*) arg="${arg#-t}" ;; -*) continue ;; esac
        __dsha_critical_path "$arg" && return 0
      done
      return 1 ;;
    find)
      for arg in "$@"; do
        case "$arg" in -delete|-exec|-execdir|-ok|-okdir) return 0 ;; esac
      done
      return 1 ;;
    *) return 0 ;;
  esac
}
__dsha_adb_danger() {
  local arg found=0 text=''
  __dsha_guard_active || return 1
  for arg in "$@"; do
    [ "$found" = 1 ] && text="$text $arg"
    case "$arg" in shell|exec-out|exec-in) found=1 ;; esac
  done
  [ -n "$text" ] || return 1
  [[ "$text" =~ (^|[^a-z])(rm|rmdir|unlink|truncate|wipe|dd|mkfs|fdisk|format|reboot|shutdown|poweroff|halt)([^a-z]|$)|-delete|base64|\|[[:space:]]*(sh|bash)|eval|sh[[:space:]]+-c|toybox|pm[[:space:]]+clear|uninstall ]]
}
POLICY_EOF

cat > /root/dsh-guard.sh <<'EOF'
# DSHA-GUARD-V12: source repeatedly without stacking traps or changing BASH_ENV.
source /root/dsh-guard-policy.sh
__dsha_run_guarded() {
  local name="$1" executable="$2"
  shift 2
  if __dsha_needs_confirm "$name" "$@"; then
    if [ "$name" = mv ]; then /root/dsh-confirm.sh --guard "$name $*" || return 1
    else /root/dsh-confirm.sh "$name $*" || return 1; fi
  fi
  "$executable" "$@"
}
rm() { __dsha_run_guarded rm /usr/bin/rm "$@"; }
rmdir() { __dsha_run_guarded rmdir /usr/bin/rmdir "$@"; }
unlink() { __dsha_run_guarded unlink /usr/bin/unlink "$@"; }
truncate() { __dsha_run_guarded truncate /usr/bin/truncate "$@"; }
find() { __dsha_run_guarded find /usr/bin/find "$@"; }
dd() { __dsha_run_guarded dd /usr/bin/dd "$@"; }
mkfs() { __dsha_run_guarded mkfs /usr/sbin/mkfs "$@"; }
mkfs.ext4() { __dsha_run_guarded mkfs.ext4 /usr/sbin/mkfs.ext4 "$@"; }
mkfs.vfat() { __dsha_run_guarded mkfs.vfat /usr/sbin/mkfs.vfat "$@"; }
fdisk() { __dsha_run_guarded fdisk /usr/sbin/fdisk "$@"; }
reboot() { __dsha_run_guarded reboot /usr/sbin/reboot "$@"; }
shutdown() { __dsha_run_guarded shutdown /usr/sbin/shutdown "$@"; }
halt() { __dsha_run_guarded halt /usr/sbin/halt "$@"; }
poweroff() { __dsha_run_guarded poweroff /usr/sbin/poweroff "$@"; }
wipe() { __dsha_run_guarded wipe /usr/sbin/wipe "$@"; }
mv() { __dsha_run_guarded mv /usr/bin/mv "$@"; }
adb() {
  if __dsha_adb_danger "$@"; then /root/dsh-confirm.sh "adb $*" || return 1; fi
  # The PATH wrapper performs the same check; call the real binary only once.
  if [ -x /root/.dsh-real/adb ]; then /root/.dsh-real/adb "$@"; else command adb "$@"; fi
}
# Lex only redirection operands, never eval the command. Quoted text containing '>'
# is data. >, >| and &> overwrite; >> appends and >& duplicates descriptors.
# Simple $var/${var} operands are resolved with indirect expansion, never eval.
__dsha_guard_redirection() {
  local __dsha_text="$1" __dsha_i=0 __dsha_ch __dsha_quote='' __dsha_word='' __dsha_target=0 __dsha_escaped=0 __dsha_var __dsha_rest
  [[ "$__dsha_text" == *'>'* ]] || return 0
  [[ "$__dsha_text" != '[['* ]] || return 0
  __dsha_guard_active || return 0
  while (( __dsha_i < ${#__dsha_text} )); do
    __dsha_ch="${__dsha_text:__dsha_i:1}"
    ((__dsha_i+=1))
    if [ "$__dsha_escaped" = 1 ]; then __dsha_word+="$__dsha_ch"; __dsha_escaped=0; continue; fi
    if [ "$__dsha_ch" = '\' ] && [ "$__dsha_quote" != "'" ]; then __dsha_escaped=1; continue; fi
    if [ -n "$__dsha_quote" ]; then
      if [ "$__dsha_ch" = "$__dsha_quote" ]; then __dsha_quote=''; continue; fi
    else
      case "$__dsha_ch" in
        "'"|'"') __dsha_quote="$__dsha_ch"; continue ;;
        '>')
          if [ "$__dsha_target" = 1 ] && __dsha_critical_path "$__dsha_word"; then
            /root/dsh-confirm.sh --guard "$__dsha_text"; return $?
          fi
          __dsha_word=''; __dsha_target=1
          case "${__dsha_text:__dsha_i:1}" in '>'|'&') __dsha_target=0; ((__dsha_i+=1)) ;; '|') ((__dsha_i+=1)) ;; esac
          continue ;;
        ' '|$'\t'|$'\n'|';'|'|'|'&'|'<'|')'|'(')
          if [ "$__dsha_target" = 1 ] && [ -n "$__dsha_word" ]; then
            if __dsha_critical_path "$__dsha_word"; then /root/dsh-confirm.sh --guard "$__dsha_text"; return $?; fi
            __dsha_target=0
          fi
          __dsha_word=''; continue ;;
      esac
    fi
    if [ "$__dsha_target" = 1 ] && [ "$__dsha_ch" = '$' ] && [ "$__dsha_quote" != "'" ]; then
      __dsha_rest="${__dsha_text:__dsha_i}"
      if [[ "$__dsha_rest" =~ ^\{([a-zA-Z_][a-zA-Z_0-9]*)\} ]]; then
        __dsha_var="${BASH_REMATCH[1]}"; __dsha_word+="${!__dsha_var-}"; ((__dsha_i+=${#__dsha_var}+2)); continue
      elif [[ "$__dsha_rest" =~ ^([a-zA-Z_][a-zA-Z_0-9]*) ]]; then
        __dsha_var="${BASH_REMATCH[1]}"; __dsha_word+="${!__dsha_var-}"; ((__dsha_i+=${#__dsha_var})); continue
      fi
    fi
    __dsha_word+="$__dsha_ch"
  done
  if [ "$__dsha_target" = 1 ] && __dsha_critical_path "$__dsha_word"; then
    /root/dsh-confirm.sh --guard "$__dsha_text"; return $?
  fi
  return 0
}
__dsha_guard_debug() {
  [[ "$1" == *'>'* ]] || return 0
  # Regex matching in the parser must not clobber the caller's BASH_REMATCH.
  local -a __dsha_saved_match=("${BASH_REMATCH[@]}")
  local __dsha_result=0
  __dsha_guard_redirection "$1" || __dsha_result=$?
  BASH_REMATCH=("${__dsha_saved_match[@]}")
  return "$__dsha_result"
}
# extdebug is required for a nonzero DEBUG result to skip the pending command.
# Bash suppresses DEBUG re-entry while its handler runs; do not untrap/retrap.
# Preserve an inherited DEBUG trap (bash hides non-inherited traps while sourcing).
if [ -z "$(trap -p DEBUG)" ] || [[ "$(trap -p DEBUG)" == *'__dsha_guard_debug'* ]]; then
  shopt -s extdebug
  trap '__dsha_guard_debug "$BASH_COMMAND"' DEBUG
fi
EOF
chmod +x /root/dsh-guard.sh


# adb 特殊包装：远端危险命令确认，与函数级守卫使用相同动态开关。
# 真实 adb 被移到 /root/.dsh-real/，绝对路径调用也命中包装器
cat > "$DSH_BIN/adb" <<'EOF2'
#!/bin/bash
SELF=$(basename "$0")
REAL=""
for p in /root/.dsh-real /usr/local/bin /usr/bin /bin /system/bin /data/data/com.termux/files/usr/bin; do
  # 只需排除「自己」，不能顺手排除 /root/.dsh-real/adb —— 那恰好是真实 adb 的标准存放位置，
  # 排掉之后只能靠后面的 ls 兜底，兜不到就直接报「找不到真实 adb」
  if [ -x "$p/$SELF" ] && [ "$p/$SELF" != "$0" ]; then REAL="$p/$SELF"; break; fi
done
[ -z "$REAL" ] && REAL=$(ls /root/.dsh-real/adb /usr/local/bin/adb /usr/bin/adb /system/bin/adb 2>/dev/null | head -1)
if [ -z "$REAL" ]; then
  REAL=$(PATH=/usr/local/bin:/usr/bin:/bin:/system/bin command -v "$SELF" 2>/dev/null | grep -v "^$DSH_BIN" | head -1)
fi
if [ -z "$REAL" ]; then
  echo "找不到真实 $SELF：rootfs 里没装它（App 的 ADB 功能走 Android 侧，不依赖这个）。" >&2
  echo "想看系统日志可以直接用 /system/bin/logcat" >&2
  exit 127
fi
source /root/dsh-guard-policy.sh
if __dsha_adb_danger "$@"; then
  /root/dsh-confirm.sh "adb $*" || exit 1
fi
exec "$REAL" "$@"
EOF2
chmod +x "$DSH_BIN/adb"

# 把真实 adb 藏到 /root/.dsh-real/（幂等：已藏过则跳过）
REAL_ADB=$(command -v adb 2>/dev/null | grep -v dsh-bin | head -1)
if [ -n "$REAL_ADB" ] && [ ! -e /root/.dsh-real/adb ]; then
  mkdir -p /root/.dsh-real
  mv "$REAL_ADB" /root/.dsh-real/adb 2>/dev/null || true
fi

for C in rm rmdir unlink truncate dd mkfs mkfs.ext4 mkfs.vfat fdisk reboot shutdown halt poweroff wipe find mv; do
cat > "$DSH_BIN/$C" <<'EOF2'
#!/bin/bash
SELF=${0##*/}
REAL=""
for p in /usr/local/bin /usr/bin /bin /usr/sbin /sbin; do
  if [ -x "$p/$SELF" ] && [ "$p/$SELF" != "$0" ]; then REAL="$p/$SELF"; break; fi
done
if [ -z "$REAL" ]; then echo "找不到真实命令: $SELF" >&2; exit 127; fi
source /root/dsh-guard-policy.sh
if __dsha_needs_confirm "$SELF" "$@"; then
  if [ "$SELF" = mv ]; then /root/dsh-confirm.sh --guard "$SELF $*" || exit 1
  else /root/dsh-confirm.sh "$SELF $*" || exit 1; fi
fi
exec "$REAL" "$@"
EOF2
chmod +x "$DSH_BIN/$C"
done

echo "OK dsh-bin: $(ls "$DSH_BIN" | tr '\n' ' ')"
echo 12 > "$DSH_BIN/.version"
