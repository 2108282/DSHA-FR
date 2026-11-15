# DSHA-GUARD-V12: shared by function and PATH guards; no child process on flag reads.
__dsha_guard_active() {
  local value
  if [ -e /root/.dsh/.guard_enabled ]; then
    value=''
    IFS= read -r value < /root/.dsh/.guard_enabled || :
    case "$value" in 0) return 1 ;; 1|'') return 0 ;; *) return 1 ;; esac
  fi
  [ "${DSH_CONFIRM:-0}" = 1 ] || [ "${DSH_SHELL:-0}" = 1 ]
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
