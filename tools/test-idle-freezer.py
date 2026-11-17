#!/usr/bin/env python3
"""冻结日志回归：使用临时文件及伪 /proc，所有信号/电源/通知操作均替换为桩。"""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import unittest

REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / 'scripts/idle-freezer.sh'
MIRROR = REPO / 'magisk-module/scripts/idle-freezer.sh'


class IdleFreezerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='dsha-freezer-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.proc = self.root / 'proc'
        self.proc.mkdir()
        self.sessions = self.root / 'storages/session_projcache/sessions'
        self.sessions.mkdir(parents=True)
        self.log = self.root / 'test.log'
        (self.root / 'enabled').touch()
        (self.root / 'dsh.pid').write_text('55555')
        self.process(55555, 'dsh', 'S', 'node')
        text = SCRIPT.read_text()
        self.functions = text[text.index('acquire_wakelock() {'):text.index('\ncase "$1" in')]
        self.functions = self.functions.replace('/proc/', str(self.proc) + '/')
        self.setup = f'''
ROOTFS=""
RUN_DIR={shlex.quote(str(self.root))}
DSH_DIR="$RUN_DIR"
FREEZER_LOG="$RUN_DIR/test.log"
FREEZER_PID_FILE="$RUN_DIR/freezer.pid"
PID_FILE="$RUN_DIR/dsh.pid"
STATE_FILE="$RUN_DIR/freezer.state"
ENABLED_FILE="$RUN_DIR/enabled"
WHITELIST_FILE="$RUN_DIR/idle-freeze-whitelist.conf"
CHECK_INTERVAL_SCREEN_ON=600
CHECK_INTERVAL_SCREEN_OFF=600
IDLE_THRESHOLD_SCREEN_ON=1800
IDLE_THRESHOLD_SCREEN_OFF=300
{self.functions}
# Never execute real signals, power operations, notifications or a daemon wait.
kill() {{ [ "$1" = '-0' ]; }}
release_wakelock() {{ :; }}
acquire_wakelock() {{ :; }}
notify_core_state() {{ :; }}
freeze_process_tree() {{ printf 'FAKE_FREEZE %s\\n' "$1" >> "$FREEZER_LOG"; }}
is_screen_on() {{ [ "${{SCREEN_OFF:-0}}" != 1 ]; }}
pgrep() {{ printf '%s' "${{WORKERS:-}}"; }}
sleep() {{
    checks=$((${{checks:-0}} + 1))
    [ "$checks" -lt 2 ] || exit 0
}}
'''

    def process(self, pid, comm, state, command):
        entry = self.proc / str(pid)
        entry.mkdir()
        (entry / 'stat').write_text(f'{pid} ({comm}) {state} 55555 0 0\n')
        (entry / 'comm').write_text(comm + '\n')
        (entry / 'cmdline').write_bytes(command.encode().replace(b' ', b'\0') + b'\0')
        (entry / 'exe').symlink_to('/usr/bin/' + command.split()[0])

    def session(self, name='session-abc', title='测试对话', age=10):
        path = self.sessions / (name + '.json')
        path.write_text(json.dumps({'version': 7, 'record': {'rows': {'title': {'val': title}}}}))
        stamp = time.time() - age
        os.utime(path, (stamp, stamp))
        return path

    def run_shell(self, suffix='do_daemon', **env):
        result = subprocess.run(['sh', '-c', self.setup + '\n' + suffix],
                                env=dict(os.environ, **env), text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, '')
        return self.log.read_text() if self.log.exists() else result.stdout

    def test_mirror_and_syntax(self):
        self.assertEqual(SCRIPT.read_bytes(), MIRROR.read_bytes())
        subprocess.run(['sh', '-n', str(SCRIPT)], check=True)

    def test_full_and_lite_ship_same_host_freezer(self):
        build = (REPO / 'scripts/build-module.sh').read_text()
        lite = build.split('# 完整包编译流程', 1)[0]
        full = build.split('# 完整包编译流程', 1)[1]
        self.assertIn('cp -rf "$MODULE_DIR/scripts/"* "$STAGE_LITE/scripts/"', lite)
        self.assertIn('uninstall.sh scripts rootfs.tar.gz', full)
        installer = (REPO / 'magisk-module/customize.lite.sh').read_text()
        self.assertIn('lan-proxy.sh idle-freezer.sh block-guard.sh', installer)
        self.assertIn('"$SCRIPTS_DIR/$base_script"', installer)
        workflow = (REPO / '.github/workflows/magisk-module-build.yml').read_text()
        self.assertIn('./scripts/build-module.sh --lite', workflow)
        self.assertIn('./scripts/build-module.sh full', workflow)

    def test_ten_minute_check_interval_for_both_screen_states(self):
        source = SCRIPT.read_text()
        self.assertIn('CHECK_INTERVAL_SCREEN_ON=600 ', source)
        self.assertIn('CHECK_INTERVAL_SCREEN_OFF=600 ', source)
        for screen_off in ('0', '1'):
            with self.subTest(screen_off=screen_off):
                self.setup += '\nsleep() { printf "interval=%s" "$1"; exit 0; }\n'
                self.assertEqual(self.run_shell(SCREEN_OFF=screen_off), 'interval=600')

    def test_latest_conversation_and_title(self):
        self.session('session-older', '不应出现', age=90)
        self.session('session-current', '最近写入的对话\n下一行', age=10)
        log = self.run_shell()
        self.assertIn('未冻结: 亮屏', log)
        self.assertIn('阈值 1800 秒', log)
        self.assertIn('会话ID: session-current', log)
        self.assertIn('标题: 最近写入的对话 下一行', log)
        self.assertNotIn('不应出现', log)
        self.assertEqual(len(log.splitlines()), 1)

    def test_screen_off_threshold(self):
        self.session()
        self.assertIn('未满阈值 300 秒', self.run_shell(SCREEN_OFF='1'))

    def test_corrupt_metadata_keeps_id(self):
        path = self.session()
        path.write_text('{broken')
        log = self.run_shell()
        self.assertIn('会话ID: session-abc', log)
        self.assertIn('标题不可用', log)

    def test_new_title_object(self):
        self.session(title={'title': '新结构标题'})
        self.assertIn('标题: 新结构标题', self.run_shell())

    def test_large_metadata_keeps_id(self):
        path = self.session()
        path.write_text(' ' * (2 * 1024 * 1024 + 1))
        self.assertIn('会话ID: session-abc | 标题不可用', self.run_shell())

    def test_missing_activity_explicit(self):
        log = self.run_shell()
        self.assertIn('无法取得有效活动时间戳', log)
        self.assertNotIn('闲置 0 秒', log)
        self.assertNotIn('FAKE_FREEZE', log)

    def test_agy_fallback_has_no_false_conversation(self):
        (self.root / 'agy').mkdir()
        (self.root / 'agy/agy-recent.json').write_text('{}')
        self.assertIn('agy 调用账本 (无会话时间戳，无法归属具体对话)', self.run_shell())

    def test_approval_identity_and_no_command_leak(self):
        (self.root / '.approval_status.json').write_text(json.dumps(
            {'active': True, 'tool': 'bash', 'sessionId': 'session-own',
             'requestId': 'req-7', 'reason': 'secret-command'}, separators=(',', ':')))
        log = self.run_shell()
        self.assertIn('等待活跃审批', log)
        self.assertIn('工具: bash | 会话ID: session-own | 审批ID: req-7', log)
        self.assertNotIn('secret-command', log)

    def test_host_metadata_maps_rootfs_path(self):
        path = self.session(title='宿主映射标题')
        self.setup += '''
# Simulate chroot without entering any real rootfs.
chroot() {
    [ "$1" = '/fake-rootfs' ] && [ "$2" = '/usr/local/bin/node' ] || return 1
    shift 2
    node "$@"
}
ROOTFS='/fake-rootfs'
'''
        self.assertEqual(self.run_shell('describe_json_source session ' + shlex.quote('/fake-rootfs' + str(path))),
                         '标题: 宿主映射标题')

    def test_unavailable_metadata_runtime_keeps_identity(self):
        self.session()
        self.setup += '\nnode() { return 127; }\n'
        self.assertIn('会话ID: session-abc | 标题不可用', self.run_shell())

    def test_old_approval_without_session(self):
        (self.root / '.approval_status.json').write_text('{"active":true,"tool":"edit"}')
        self.assertIn('会话ID: 审批状态文件未提供', self.run_shell())

    def test_workers_include_waiting_exclude_zombie_and_relay(self):
        self.process(70001, 'build (worker)', 'S', 'python3 secret-script --token secret-value')
        self.process(70002, 'zombie', 'Z', 'dead')
        self.process(70003, 'node', 'S', 'node relay.cjs')
        self.process(70004, 'paused', 'T', 'bash')
        log = self.run_shell(WORKERS='70001\n70002\n70003\n70004\n')
        self.assertIn('数量: 2', log)
        self.assertIn('PID=70001 PPID=55555 状态=S 进程=build (worker)', log)
        self.assertIn('PID=70004 PPID=55555 状态=T', log)
        self.assertNotIn('70002', log)
        self.assertNotIn('70003', log)
        self.assertNotIn('secret', log)

    def test_worker_log_cap_and_whitelist(self):
        for pid in range(71000, 71011):
            self.process(pid, 'worker', 'S', 'sh')
        (self.root / 'lan-proxy.pid').write_text('71000')
        log = self.run_shell(WORKERS='\n'.join(map(str, range(71000, 71011))))
        self.assertIn('数量: 10', log)
        self.assertNotIn('PID=71000', log)
        self.assertIn('PID=71008', log)
        self.assertNotIn('PID=71009', log)

    def whitelist(self, text):
        (self.root / 'idle-freeze-whitelist.conf').write_text(text)

    def test_exact_name_whitelist(self):
        self.session()
        self.process(72001, 'bridge', 'S', 'node')
        self.process(72002, 'bridge-extra', 'S', 'node')
        self.whitelist('# only this name\n name:bridge \n')
        log = self.run_shell(WORKERS='72001\n72002')
        self.assertIn('规则=name:bridge', log)
        self.assertIn('数量: 1', log)
        self.assertIn('PID=72002 PPID=55555', log)

    def test_script_argv_exact_with_spaces_and_metacharacters(self):
        self.session()
        self.process(72001, 'node', 'S', 'node')
        self.process(72002, 'node', 'S', 'node')
        script = '/work dir/bridge[1]$x.cjs'
        (self.proc / '72001/cmdline').write_bytes(b'node\0' + script.encode() + b'\0')
        (self.proc / '72002/cmdline').write_bytes(b'node\0' + (script + '.other').encode() + b'\0')
        self.whitelist('script:' + script)
        log = self.run_shell(WORKERS='72001\n72002')
        self.assertIn('PID=72001 进程=node 规则=script:' + script, log)
        self.assertIn('PID=72002 PPID=55555', log)

    def test_busybox_awk_exact_argv_compatibility(self):
        import shutil
        if not shutil.which('busybox'):
            self.skipTest('busybox unavailable')
        self.session()
        self.process(72001, 'node', 'S', 'node /srv/relay-extra.cjs')
        self.whitelist('script:/srv/relay-extra.cjs\n')
        self.setup += '\nawk() { busybox awk "$@"; }\n'
        log = self.run_shell(WORKERS='72001')
        self.assertIn('规则=script:/srv/relay-extra.cjs', log)
        self.assertNotIn('存在非白名单', log)

    def test_invalid_rule_and_control_bytes_rejected(self):
        for text in ('name:', 'name:node/foo', 'script:relative', 'shell:node',
                     'name:no\tde', 'name:no\x00de', 'name:' + 'x' * 81):
            with self.subTest(rule=repr(text)):
                self.whitelist(text)
                result = self.run_shell('load_worker_whitelist; printf "%s|%s" "$WHITELIST_ERROR" "$WORKER_WHITELIST"')
                self.assertIn('格式无效', result)
                self.assertTrue(result.endswith('|'))

    def test_rules_never_execute_shell_text(self):
        self.session()
        self.process(72001, 'node', 'S', 'node')
        payload = '/srv/$(touch SHOULD_NOT_EXIST);`id`\\x.cjs'
        (self.proc / '72001/cmdline').write_bytes(b'node\0' + payload.encode() + b'\0')
        self.whitelist('script:' + payload)
        log = self.run_shell(WORKERS='72001')
        self.assertIn('规则=script:' + payload, log)
        self.assertFalse((self.root / 'SHOULD_NOT_EXIST').exists())

    def test_bad_file_never_partially_whitelists(self):
        self.process(72001, 'bridge', 'S', 'node')
        self.whitelist('name:bridge\nunknown:node\n')
        log = self.run_shell(WORKERS='72001')
        self.assertIn('格式无效', log)
        self.assertIn('PID=72001 PPID=55555', log)
        self.assertNotIn('规则=name:bridge', log)

    def test_whitelist_size_and_rule_count_limits(self):
        for rules in ('#' + 'x' * 16384, 'name:bridge\n' * 129, 'script:/' + 'x' * 510):
            with self.subTest(size=len(rules)):
                self.whitelist(rules)
                output = self.run_shell('load_worker_whitelist; printf "%s|%s" "$WHITELIST_ERROR" "$WORKER_WHITELIST"')
                self.assertIn('白名单', output)
                self.assertTrue(output.endswith('|'))

    def test_crlf_empty_and_comment_rules(self):
        self.session(age=1900)
        self.process(72001, 'bridge', 'S', 'node')
        self.whitelist('  # comment\r\n\tname:bridge\t\r\n\r\n')
        log = self.run_shell(WORKERS='72001')
        self.assertIn('规则=name:bridge', log)
        self.assertIn('FAKE_FREEZE 55555', log)

    def test_whitelist_is_reloaded_each_check(self):
        self.process(72001, 'bridge', 'S', 'node')
        self.whitelist('name:bridge')
        result = self.run_shell('''
WORKERS=72001
has_active_workers 55555
printf '%s:%s\\n' "$ACTIVE_WORKER_COUNT" "$IGNORED_WORKER_COUNT"
printf '' > "$WHITELIST_FILE"
has_active_workers 55555
printf '%s:%s\\n' "$ACTIVE_WORKER_COUNT" "$IGNORED_WORKER_COUNT"
''')
        self.assertEqual(result, '0:1\n1:0\n')

    def test_every_check_logs_even_under_five_minutes(self):
        self.session()
        self.setup += '''
sleep() {
    checks=$((${checks:-0} + 1))
    [ "$checks" -lt 3 ] || exit 0
}
'''
        log = self.run_shell()
        self.assertEqual(log.count('[CHECK]'), 2)

    def test_idle_still_reaches_freeze_decision(self):
        self.session(age=1900)
        self.assertIn('FAKE_FREEZE 55555', self.run_shell())
        self.assertEqual((self.root / 'freezer.state').read_text().strip(), 'FROZEN')


if __name__ == '__main__':
    unittest.main(verbosity=2)
