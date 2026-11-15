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
