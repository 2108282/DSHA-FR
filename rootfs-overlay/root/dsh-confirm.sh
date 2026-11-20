#!/bin/bash
# 用法：dsh-confirm.sh [--force|--guard] <命令...>
# --guard: 终端已判危，但仍保留动态开关、临时免审租约及任务豁免。

# 1. 检查统一的 10 分钟临时免审租约
if [ -f /root/.dsh/.auth_lease ]; then
  EXP=$(cat /root/.dsh/.auth_lease 2>/dev/null)
  NOW=$(date +%s)
  if [ -n "$EXP" ] && [ "${NOW:-0}" -lt "${EXP%.*}" ]; then
    exit 0
  fi
fi

# 2. 安全临时文件与工作区白名单（工作区与下载目录放行，根目录严加防范）
is_whitelisted_path() {
  local target="$1"
  [ -z "$target" ] && return 1
  if [ "$target" = "/root/.dsh/.auth_lease" ]; then return 0; fi

  local p
  p="$(realpath -m "$target" 2>/dev/null || echo "$target")"

  case "$p" in
    /|/root|/root/|/root/workspaces|/root/workspaces/|/sdcard|/sdcard/|/sdcard/Download|/sdcard/Download/|/sdcard/Download/DSHA|/sdcard/Download/DSHA/|/storage/emulated/0|/storage/emulated/0/|/storage/emulated/0/Download/DSHA|/storage/emulated/0/Download/DSHA/|/data/adb/dsha/rootfs/root/workspaces|/data/adb/dsha/rootfs/root/workspaces/|/data/adb/dsha/rootfs/root/内部存储|/data/adb/dsha/rootfs/root/内部存储/|/root/内部存储|/root/内部存储/|/tmp|/tmp/)
      return 1 ;;
  esac

  case "$p" in
    /root/workspaces/*|/data/adb/dsha/rootfs/root/workspaces/*)
      return 0 ;;
    /sdcard/Download/DSHA/*|/storage/emulated/0/Download/DSHA/*|/root/内部存储/*|/data/adb/dsha/rootfs/root/内部存储/*)
      return 0 ;;
    /tmp/*)
      return 0 ;;
    /sdcard/Download/*.png|/sdcard/Download/*.jpg|/sdcard/Download/*.jpeg|/sdcard/Download/*.tmp|\
    /storage/emulated/0/Download/*.png|/storage/emulated/0/Download/*.jpg|/storage/emulated/0/Download/*.jpeg|/storage/emulated/0/Download/*.tmp)
      return 0 ;;
  esac

  return 1
}

is_safe_cleanup() {
  local c="$1"
  local cmd_name="${c%% *}"
  case "$cmd_name" in
    rm|unlink|rmdir) ;;
    *) return 1 ;;
  esac

  local has_target=0
  for arg in $c; do
    case "$arg" in
      rm|unlink|rmdir|-f|-v|-i|-I|--force|--verbose|--interactive) continue ;;
      -r|-R|-rf|-fr|-rfi|-rif|-fir|-fRi|-Rfi|-frI|-rI|-Ir) continue ;;
      --recursive|--one-file-system) continue ;;
      -*) return 1 ;;
    esac
    has_target=1
    if ! is_whitelisted_path "$arg"; then
      return 1
    fi
  done

  [ "$has_target" -eq 1 ] && return 0
  return 1
}

FORCE=0
case "${1:-}" in --force) FORCE=1; shift ;; --guard) FORCE=2; shift ;; esac
CMD="$*"

if [ "$FORCE" != "1" ] && is_safe_cleanup "$CMD"; then
  exit 0
fi

http_confirm() {
  local port="$1"
  local token="$2"
  local cmd="$3"
  local force="$4"

  if command -v curl >/dev/null 2>&1; then
    curl -s -m 65 -G "http://127.0.0.1:$port/confirm" --data-urlencode "cmd=$cmd" --data-urlencode "force=$force" \
      --data-urlencode "root_session_id=${DSH_APPROVAL_ROOT_SESSION_ID:-}" \
      --data-urlencode "session_id=${DSH_SESSION_ID:-}" \
      --data-urlencode "conversation_title=${DSH_APPROVAL_CONVERSATION_TITLE:-}" \
      --data-urlencode "agent_title=${DSH_APPROVAL_AGENT_TITLE:-}" -H "X-Token: $token" 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c "
import os, sys, urllib.request, urllib.parse
port, token, cmd, force = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
params = urllib.parse.urlencode({'cmd': cmd, 'force': force,
    'root_session_id': os.environ.get('DSH_APPROVAL_ROOT_SESSION_ID', ''),
    'session_id': os.environ.get('DSH_SESSION_ID', ''),
    'conversation_title': os.environ.get('DSH_APPROVAL_CONVERSATION_TITLE', ''),
    'agent_title': os.environ.get('DSH_APPROVAL_AGENT_TITLE', '')})
url = f'http://127.0.0.1:{port}/confirm?{params}'
req = urllib.request.Request(url, headers={'X-Token': token})
try:
    with urllib.request.urlopen(req, timeout=65) as resp:
        print(resp.read().decode('utf-8', errors='ignore'))
except Exception:
    pass
" "$port" "$token" "$cmd" "$force" 2>/dev/null
  elif command -v node >/dev/null 2>&1; then
    node -e '
const http = require("http");
const [, port, token, cmd, force] = process.argv;
const params = new URLSearchParams({ cmd, force,
  root_session_id: process.env.DSH_APPROVAL_ROOT_SESSION_ID || "",
  session_id: process.env.DSH_SESSION_ID || "",
  conversation_title: process.env.DSH_APPROVAL_CONVERSATION_TITLE || "",
  agent_title: process.env.DSH_APPROVAL_AGENT_TITLE || "" });
const options = {
  hostname: "127.0.0.1",
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
' "$port" "$token" "$cmd" "$force" 2>/dev/null
  fi
}

TOKEN=$(cat /root/.dsh/.bridge_token 2>/dev/null)
RES=""
for PORT in 3095 3090; do
  RES=$(http_confirm "$PORT" "$TOKEN" "$CMD" "$FORCE")
  case "$RES" in
    *'"result":"YES"'*|*'"result":YES'*) exit 0 ;;
    *'"result":"NO"'*|*'"result":NO'*)  echo "已拒绝: $CMD（用户在手机端拒绝了该操作）" >&2; exit 1 ;;
  esac
  [ -n "$RES" ] && break
done

if [ -n "$DSH_INTERACTIVE" ]; then
  echo -n "确认执行危险操作 [$CMD] ? [y/N] " >&2
  read -t 10 ans
  case "$ans" in y|Y) exit 0 ;; esac
fi

echo "已拦截高危操作: $CMD (3090确认服务未就绪或超时)" >&2
exit 1
