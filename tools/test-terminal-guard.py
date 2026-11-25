#!/usr/bin/env python3
"""Terminal guard regressions; only temporary paths and inert command stubs execute."""
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import unittest

REPO = Path(__file__).resolve().parents[1]
OVERLAY = REPO / 'rootfs-overlay/root'
MIRROR = REPO / 'magisk-module/scripts/start.sh'
START = REPO / 'scripts/start.sh'
COMMANDS = 'rm rmdir unlink truncate find dd mkfs mkfs.ext4 mkfs.vfat fdisk reboot shutdown halt poweroff wipe mv adb'.split()


def start_heredoc(text, marker):
    return text.split("<< '" + marker + "'", 1)[1].split('\n', 1)[1].split('\n' + marker, 1)[0] + '\n'


class TerminalGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = (OVERLAY / 'dsh-guard-policy.sh').read_text()
        cls.guard = (OVERLAY / 'dsh-guard.sh').read_text()
        cls.wrapper = (OVERLAY / 'dsh-bin/mv').read_text()
        cls.adb = (OVERLAY / 'dsh-bin/adb').read_text()
        cls.find = (OVERLAY / 'dsh-bin/find').read_text()
        cls.confirm = (OVERLAY / 'dsh-confirm.sh').read_text()
        start = START.read_text()
        cls.embedded = (start_heredoc(start, 'POLICY_EOF'), start_heredoc(start, 'GUARD_EOF'), start_heredoc(start, 'WRAPPER_EOF'))

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='dsha-terminal-guard-')
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / 'root'
        self.bin = self.base / 'stub-bin'
        self.bin.mkdir()
        (self.root / '.dsh').mkdir(parents=True)
        self.flag = self.root / '.dsh/.guard_enabled'
        self.flag.write_text('1\n')
        self.log = self.base / 'calls'
        self.confirm_log = self.base / 'confirmations'
        self.env = dict(os.environ, HOME=str(self.root), BASH_ENV='', DSH_CONFIRM='0', DSH_SHELL='1', CALLS=str(self.log), CONFIRMS=str(self.confirm_log), DENY='0', TMPDIR=str(self.base))
        for command in COMMANDS:
            self.executable(self.bin / command, '#!/bin/bash\nif [ "$1" = -f ]; then case "$2" in "$TMPDIR"/tmp.*) exit 0 ;; esac; fi\nprintf "%s\\n" "${0##*/}:$*" >> "$CALLS"\n')
        self.executable(self.root / 'dsh-confirm.sh', '#!/bin/bash\nprintf "%s\\n" "$*" >> "$CONFIRMS"\n[ "$DENY" != 1 ]\n')
        (self.root / 'dsh-guard-policy.sh').write_text(self.rewrite(self.policy))
        (self.root / 'dsh-guard.sh').write_text(self.rewrite(self.guard))
        self.wrappers = self.base / 'wrappers'
        self.wrappers.mkdir()
        for command in COMMANDS:
            self.executable(self.wrappers / command, self.rewrite((OVERLAY / 'dsh-bin' / command).read_text()))
        for directory in ('etc', 'usr/lib', 'usr/local/bin', 'system', 'data/data'):
            (self.base / directory).mkdir(parents=True, exist_ok=True)

    def executable(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def rewrite(self, text):
        # All writable/protected paths are temporary. Real commands are replaced by
        # inert stubs; realpath is the sole executable helper (read-only resolution).
        text = text.replace('/usr/bin/realpath', '__READ_ONLY_REALPATH__')
        text = text.replace('/root', str(self.root))
        for path in ('/usr/local/lib/node_modules', '/usr/local/bin', '/usr/lib', '/etc', '/system', '/data/data'):
            text = text.replace(path, str(self.base) + path)
        for directory in ('/usr/bin', '/usr/sbin', '/bin', '/sbin'):
            # Only original absolute command locations/search directories.
            text = re.sub(r'(?<![A-Za-z0-9_./-])' + re.escape(directory) + r'(?=/|[ ;\n])', str(self.bin), text)
        # /usr/local/bin in wrappers was mapped to a temp directory; leave that inert.
        for command in COMMANDS:
            text = text.replace(str(self.base / 'usr/local/bin') + '/' + command, str(self.bin / command))
        return text.replace('__READ_ONLY_REALPATH__', '/usr/bin/realpath').replace(str(self.bin) + '/bash', '/bin/bash')

    def run_shell(self, code, guarded=True, **env):
        prefix = 'source ' + shlex.quote(str(self.root / 'dsh-guard.sh')) + '\n' if guarded else ''
        proc = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-c', prefix + code], env=dict(self.env, **env), cwd=self.base, text=True, capture_output=True, timeout=10)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        return proc

    def lines(self, path):
        return path.read_text().splitlines() if path.exists() else []

    def test_syntax_and_native_embedding_equal(self):
        paths = [START, MIRROR, OVERLAY / 'dsh-confirm.sh', OVERLAY / 'dsh-guard.sh', OVERLAY / 'dsh-guard-policy.sh']
        paths += [OVERLAY / 'dsh-bin' / command for command in COMMANDS]
        for path in paths:
            subprocess.run(['/bin/bash', '-n', str(path)], check=True)
        subprocess.run(['/bin/sh', '-n', str(START)], check=True)
        self.assertEqual(START.read_bytes(), MIRROR.read_bytes())
        self.assertEqual((self.policy, self.guard, self.wrapper), self.embedded)
        source = START.read_text()
        self.assertEqual(self.adb, start_heredoc(source, 'ADB_WRAPPER_EOF'))
        self.assertEqual(self.find, start_heredoc(source, 'FIND_WRAPPER_EOF'))
        generic_commands = re.search(r'^for C in (.+); do\ncat << \'WRAPPER_EOF\'', source, re.M).group(1).split()
        self.assertNotIn('find', generic_commands)
        self.assertEqual(self.confirm, start_heredoc(source, 'CONFIRM_EOF'))
        self.assertEqual((OVERLAY / 'dsh-bin/rm').read_text(), start_heredoc(source, 'RM_WRAPPER_EOF'))
        self.assertNotIn('if [ ! -f "$ROOTFS/root/dsh-guard.sh" ]', source)
        self.assertNotIn('if [ ! -f "$DSH_BIN/rmdir" ]', source)
        subprocess.run(['python3', str(REPO / 'tools/sync-terminal-guard.py'), '--check'], check=True)

    def test_dynamic_flag_and_environment_fallback(self):
        # V13：DSH_SHELL 是唯一激活来源，flag 只做减法。
        self.run_shell(f'''rm one
printf 0 > {self.flag}
rm two
printf 1 > {self.flag}
rm three
: > {self.flag}
rm legacy
''')
        self.assertEqual(len(self.lines(self.confirm_log)), 5)
        self.assertEqual(len(self.lines(self.log)), 4)
        self.flag.unlink()
        self.run_shell('rm shell', DSH_SHELL='1')
        # DSH_CONFIRM 会继承给 pnpm/插件/系统脚本，不得单独激活守卫。
        self.run_shell('rm confirm', DSH_CONFIRM='1', DSH_SHELL='0')
        # 无 DSH_SHELL = 用户自己的终端 / 插件子进程，一律放行。
        self.run_shell('rm install', DSH_SHELL='0')
        self.assertEqual(len(self.lines(self.confirm_log)), 6)
        self.assertEqual(len(self.lines(self.log)), 7)

    def test_every_dangerous_function_is_inert_and_guarded(self):
        self.run_shell('\n'.join(c + ' synthetic' for c in COMMANDS if c not in ('find', 'mv', 'adb')))
        self.assertEqual(len(self.lines(self.confirm_log)), 14)
        self.assertEqual(len(self.lines(self.log)), 14)

    def test_denial_never_executes_command(self):
        self.run_shell('rm synthetic || :\nprintf "alive\\n"', DENY='1')
        self.assertEqual(self.lines(self.log), [])
        self.assertEqual(len(self.lines(self.confirm_log)), 1)

    def test_mv_normal_critical_relative_symlink_and_target_option(self):
        (self.base / 'link').symlink_to(self.root / '.dsh', target_is_directory=True)
        self.run_shell(f'''mv ordinary renamed
mv ordinary {self.root}/.dsh/config
mv root/.dsh/config ordinary
mv link/config ordinary
mv --target-directory={self.root}/.dsh ordinary
mv -t{self.root}/.dsh ordinary
mv {self.root} moved
''')
        self.assertEqual(len(self.lines(self.confirm_log)), 6)
        self.assertTrue(all(line.startswith('--guard mv ') for line in self.lines(self.confirm_log)))
        self.assertEqual(len(self.lines(self.log)), 7)

    def test_find_read_only_and_mutating_options(self):
        self.run_shell('find synthetic -name x\nfind synthetic -delete\nfind synthetic -execdir echo inert \\;')
        self.assertEqual(len(self.lines(self.confirm_log)), 2)

    def test_find_wrapper_power_guard_blocks_before_confirmation(self):
        wrapper = self.wrappers / 'find'
        # rewrite() redirects every real-command lookup to our inert stub dirs.
        # Root/FUSE paths below are arguments only; no real find ever executes.
        self.assertIn(f'for p in {self.base}/usr/local/bin {self.bin} ', wrapper.read_text())
        roots = ('/', '/sdcard', '/storage', '/storage/emulated', '/storage/emulated/0', '/data', '/data/adb')
        # V13：全盘扫盘拦截跟随开关，flag=1 且 agent 上下文才拦。
        self.flag.write_text('1')
        for root in roots:
            for depth in ('', '-maxdepth 4'):
                with self.subTest(enabled='1', root=root, depth=depth):
                    proc = self.run_shell(f'{wrapper} {shlex.quote(root)} {depth} -delete || test $? -eq 1', guarded=False)
                    self.assertIn('[POLICY_BLOCKED]', proc.stderr)
        self.assertEqual(self.lines(self.log), [])
        self.assertEqual(self.lines(self.confirm_log), [])

    def test_find_wrapper_power_guard_honors_scope(self):
        wrapper = self.wrappers / 'find'
        # 用户自己的终端（无 DSH_SHELL）与 App 关掉开关时都不再生效；
        # agent 的 shell 调用（DSH_SHELL=1）仍被拦下。
        self.flag.write_text('1')
        self.run_shell(f'{wrapper} / -delete', guarded=False, DSH_SHELL='0')
        self.run_shell(f'{wrapper} /sdcard -maxdepth 4 -delete', guarded=False, DSH_SHELL='0')
        self.flag.write_text('0')
        proc = self.run_shell(f'{wrapper} / -delete', guarded=False)
        self.assertNotIn('[POLICY_BLOCKED]', proc.stderr)
        self.assertEqual(len(self.lines(self.log)), 3)
        self.assertEqual(self.lines(self.confirm_log), [])

    def test_find_wrapper_bounded_and_scoped_queries_use_only_stub(self):
        wrapper = self.wrappers / 'find'
        for root in ('/', '/sdcard', '/storage', '/storage/emulated', '/storage/emulated/0', '/data', '/data/adb'):
            for depth in (2, 3):
                self.run_shell(f'{wrapper} {shlex.quote(root)} -maxdepth {depth} -name synthetic', guarded=False)
        self.run_shell(f'{wrapper} /sdcard/Download/DSHA -name synthetic', guarded=False)
        self.run_shell(f'{wrapper}', guarded=False)
        self.assertEqual(len(self.lines(self.log)), 16)
        self.assertTrue(all(line.startswith('find:') for line in self.lines(self.log)))
        self.assertEqual(self.lines(self.confirm_log), [])

    def test_find_wrapper_mutation_confirm_denial_and_dynamic_flag(self):
        wrapper = self.wrappers / 'find'
        self.run_shell(f'{wrapper} synthetic -delete', guarded=False)
        self.run_shell(f'{wrapper} synthetic -execdir echo inert \\;', guarded=False)
        self.assertEqual(len(self.lines(self.confirm_log)), 2)
        self.assertEqual(len(self.lines(self.log)), 2)
        self.run_shell(f'{wrapper} synthetic -delete || test $? -eq 1', guarded=False, DENY='1')
        self.assertEqual(len(self.lines(self.confirm_log)), 3)
        self.assertEqual(len(self.lines(self.log)), 2)
        self.flag.write_text('0')
        self.run_shell(f'{wrapper} synthetic -delete', guarded=False, DSH_CONFIRM='1')
        self.assertEqual(len(self.lines(self.confirm_log)), 3)
        self.assertEqual(len(self.lines(self.log)), 3)

    def test_path_wrappers_share_dynamic_and_mv_rules(self):
        for command in ('rm', 'mv', 'find'):
            self.run_shell(f'{self.wrappers / command} synthetic renamed', guarded=False)
        self.assertEqual(len(self.lines(self.confirm_log)), 1)
        self.run_shell(f'{self.wrappers / "mv"} synthetic {self.root}/.dsh/config', guarded=False)
        self.flag.write_text('0')
        self.run_shell(f'{self.wrappers / "rm"} synthetic', guarded=False, DSH_CONFIRM='1')
        self.assertEqual(len(self.lines(self.confirm_log)), 2)
        self.assertEqual(len(self.lines(self.log)), 5)

    def test_direct_overwrite_denied_before_truncation_and_shell_continues(self):
        target = self.root / '.dsh/config'
        target.write_text('preserved')
        proc = self.run_shell(f'printf changed > {target}\nprintf "alive\\n"', DENY='1')
        self.assertEqual(target.read_text(), 'preserved')
        self.assertTrue(self.lines(self.confirm_log)[0].startswith('--guard '))
        self.assertIn('alive', proc.stdout)
        self.assertEqual(len(self.lines(self.confirm_log)), 1)

    def test_redirections_variables_quotes_noclobber_and_ordinary_output(self):
        target = self.root / '.dsh/config with space'
        self.run_shell(f'''target={shlex.quote(str(target))}
printf first > "$target"
printf second >| "${{target}}"
printf ordinary > ordinary
printf '%s\\n' 'quoted > {target}'
printf '%s\\n' '{target}' > ordinary
printf append >> "$target"
printf stderr >&2
[[ z > a ]]
''')
        self.assertEqual(len(self.lines(self.confirm_log)), 2)
        self.assertEqual(target.read_text(), 'secondappend')

    def test_repeated_source_functions_subshell_and_command_status(self):
        target = self.base / 'etc/config'
        target.write_text('before')
        proc = self.run_shell(f'''source {self.root}/dsh-guard.sh
f() {{ printf changed > {target}; printf 'function-alive\\n'; }}
f
( printf changed > {target}; printf 'subshell-alive\\n' )
false
saved=$?
printf 'status=%s\\n' "$saved"
for ((i=0; i<200; i++)); do :; done
''', DENY='1')
        self.assertEqual(target.read_text(), 'before')
        self.assertEqual(len(self.lines(self.confirm_log)), 2)
        self.assertIn('status=1', proc.stdout)
        self.assertIn('function-alive', proc.stdout)
        self.assertIn('subshell-alive', proc.stdout)

    def test_strict_modes_and_regex_match_survive_redirection(self):
        proc = self.run_shell("""set -eu
[[ hello =~ (hello) ]]
printf safe > ordinary
printf '%s' "${BASH_REMATCH[1]}"
""")
        self.assertEqual(proc.stdout, 'hello')

    def test_path_wrapper_denial_and_legacy_empty_flag(self):
        self.flag.write_text('')
        self.run_shell(f'{self.wrappers / "rm"} synthetic || :', guarded=False, DENY='1')
        self.assertEqual(len(self.lines(self.confirm_log)), 1)
        self.assertEqual(self.lines(self.log), [])

    def test_adb_dynamic_policy_and_stub_execution(self):
        real = self.root / '.dsh-real/adb'
        real.parent.mkdir()
        self.executable(real, '#!/bin/bash\nprintf "adb:%s\\n" "$*" >> "$CALLS"\n')
        self.executable(self.wrappers / 'adb', self.rewrite(self.adb))
        self.run_shell(f"{self.wrappers / 'adb'} shell 'rm synthetic'", guarded=False)
        self.flag.write_text('0')
        self.run_shell(f"{self.wrappers / 'adb'} shell 'rm synthetic'", guarded=False, DSH_SHELL='1')
        self.assertEqual(len(self.lines(self.confirm_log)), 1)
        self.assertEqual(len(self.lines(self.log)), 2)

    def test_confirmation_modes_use_stub_transport(self):
        # Exercise both generated confirm scripts without contacting any bridge.
        curl = self.base / 'transport/curl'
        curl.parent.mkdir()
        self.executable(curl, '#!/bin/bash\nprintf "%s\\n" "$*" >> "$CALLS"\nprintf \'{"result":"YES"}\'\n')
        scripts = [self.confirm, start_heredoc(START.read_text(), 'CONFIRM_EOF')]
        for index, source in enumerate(scripts):
            path = self.base / ('confirm-' + str(index))
            self.executable(path, self.rewrite(source))
            for option, expected in (('--guard', 2), ('--force', 1), ('', 0)):
                self.run_shell(f'{path} {option} "mv synthetic critical"', guarded=False, PATH=str(curl.parent) + ':' + os.environ['PATH'])
                self.assertIn('force=' + str(expected), self.lines(self.log)[-1])

    def test_module_rm_keeps_cross_device_guard_and_dynamic_flag(self):
        wrapper = self.wrappers / 'rm'
        self.run_shell(f'{wrapper} -r synthetic', guarded=False)
        self.assertIn('--one-file-system -r synthetic', self.lines(self.log)[-1])
        self.assertEqual(len(self.lines(self.confirm_log)), 1)
        self.flag.write_text('0')
        self.run_shell(f'{wrapper} -rf synthetic', guarded=False, DSH_CONFIRM='1')
        self.assertEqual(len(self.lines(self.confirm_log)), 1)
        self.assertIn('--one-file-system -rf synthetic', self.lines(self.log)[-1])
        self.flag.write_text('1')
        before = self.lines(self.log)
        self.run_shell(f'{wrapper} -r synthetic || :', guarded=False, DENY='1')
        self.assertEqual(self.lines(self.log), before)

    def test_confirm_local_lease_and_cleanup_whitelist_preserved(self):
        path = self.base / 'confirm-local'
        self.executable(path, self.rewrite(self.confirm))
        # An active local lease needs no bridge or capability probe.
        (self.root / '.dsh/.auth_lease').write_text(str(int(time.time()) + 600))
        self.run_shell(f'{path} --guard "mv synthetic critical"', guarded=False)
        self.assertEqual(self.lines(self.log), [])
        (self.root / '.dsh/.auth_lease').unlink()
        self.run_shell(f'{path} "rm --one-file-system /tmp/synthetic-cleanup"', guarded=False)
        self.assertEqual(self.lines(self.log), [])

    def test_existing_debug_trap_preserved(self):
        proc = self.run_shell(f'''set -T
trap ':' DEBUG
source {self.root}/dsh-guard.sh
trap -p DEBUG
''', guarded=False)
        self.assertIn("trap -- ':' DEBUG", proc.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
