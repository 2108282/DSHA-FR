#!/usr/bin/env python3
"""Approval origins with isolated plugin fixtures and a local, inert HTTP recorder."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[1]


class ApprovalSourceTest(unittest.TestCase):
    def test_plugin_resolves_request_local_root_and_child_labels(self):
        plugin = ROOT / 'rootfs-overlay/root/dsha-device-shell-guide/lib/index.js'
        probe = r'''
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
(async () => {
  const module = new vm.SourceTextModule(fs.readFileSync(process.argv[1], 'utf8'));
  await module.link(async () => {
    const stub = new vm.SyntheticModule(['createUserMessage'], function() {
      this.setExport('createUserMessage', value => value);
    });
    return stub;
  });
  await module.evaluate();
  let contributor;
  const roots = new Map(), values = new Map();
  const ctx = {
    inject(keys, fn) {
      if (keys.includes('shellEnv')) fn({
        shellEnv: { register(value) { contributor = value; } },
        subagents: { rootOf(agent) { if (!roots.has(agent)) throw Error('missing owner'); return roots.get(agent); } },
        sessionProjections: { stateOf(session, key) { return values.get(session)[key]; } },
      });
    },
    on() {},
  };
  module.namespace.apply(ctx);
  const make = id => ({ session: { header: { id }, isOwnSeq: seq => seq === 10 } });
  const main = make('root-A'), child = make('child-A'), nested = make('nested-A'), other = make('root-B');
  for (const agent of [main, child, nested]) roots.set(agent, main);
  roots.set(other, other);
  values.set(main.session, { title: '主对话 A' });
  values.set(other.session, { title: '主对话 B' });
  values.set(child.session, { title: '继承的主标题', subagent: { identity: { label: '检查子任务', seq: 10 } } });
  values.set(nested.session, { subagent: { identity: { label: '二级子任务', seq: 10 } } });
  const resolve = agent => contributor.resolve({ agent });
  assert.equal(resolve(main).DSH_APPROVAL_AGENT_TITLE, '');
  assert.equal(resolve(child).DSH_APPROVAL_CONVERSATION_TITLE, '主对话 A');
  assert.equal(resolve(child).DSH_APPROVAL_AGENT_TITLE, '检查子任务');
  assert.equal(resolve(nested).DSH_APPROVAL_ROOT_SESSION_ID, 'root-A');
  assert.equal(resolve(nested).DSH_APPROVAL_AGENT_TITLE, '二级子任务');
  const captured = resolve(child);
  assert.equal(resolve(other).DSH_APPROVAL_CONVERSATION_TITLE, '主对话 B');
  values.get(main.session).title = '重命名后的主对话';
  assert.equal(resolve(child).DSH_APPROVAL_CONVERSATION_TITLE, '重命名后的主对话');
  assert.equal(captured.DSH_APPROVAL_CONVERSATION_TITLE, '主对话 A');
  // An ordinary fork is its own tree, regardless of its historical parent header.
  other.session.header.parentSession = 'root-A';
  assert.equal(resolve(other).DSH_APPROVAL_ROOT_SESSION_ID, 'root-B');
  values.get(child.session).subagent.identity = { label: '继承的任务', seq: 1 };
  assert.equal(resolve(child).DSH_APPROVAL_AGENT_TITLE, '未命名子代理');
  values.get(main.session).title = '\u202e第一行\n第二行\t & "内容"';
  assert.equal(resolve(main).DSH_APPROVAL_CONVERSATION_TITLE, '第一行 第二行 & "内容"');
  values.get(main.session).title = '😀'.repeat(250);
  assert.equal(Array.from(resolve(main).DSH_APPROVAL_CONVERSATION_TITLE).length, 201);
  values.get(main.session).title = null;
  assert.equal(resolve(main).DSH_APPROVAL_CONVERSATION_TITLE, '未命名对话');
  assert.equal(Object.keys(resolve(make('orphan'))).length, 0);
  assert.equal(Object.keys(contributor.resolve({})).length, 0);
})().catch(error => { console.error(error); process.exitCode = 1; });
'''
        subprocess.run(['node', '--experimental-vm-modules', '-e', probe, str(plugin)], check=True)

    def test_all_http_clients_preserve_origin_and_command(self):
        paths = [ROOT / 'rootfs-overlay/root/dsh-confirm.sh', ROOT / 'scripts/start.sh',
                 ROOT / 'magisk-module/scripts/start.sh']
        # The sibling APK checkout is optional when this test runs in standalone CI.
        apk = ROOT.parent / 'DSHA_repo'
        if apk.is_dir():
            paths += [apk / 'scripts/start.sh', apk / 'app/src/main/assets/rootfs-confirm-install.sh']
        requests = []

        class Recorder(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append((parse_qs(urlsplit(self.path).query, keep_blank_values=True), self.headers.get('X-Token')))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"result":"NO"}')

            def log_message(self, *args):
                pass

        server = HTTPServer(('127.0.0.1', 0), Recorder)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for path in paths:
                text = path.read_text().split('http_confirm() {', 1)[1].split('\n}\n', 1)[0]
                function = 'http_confirm() {' + text + '\n}\n'
                for client in ('curl', 'python3', 'node'):
                    with self.subTest(path=path, client=client):
                        select = ('command() { if [ "$1" = -v ]; then [ "$2" = ' + shlex.quote(client)
                                  + ' ] || return 1; fi; builtin command "$@"; }\n')
                        args = [str(server.server_port), 'fixture-token', 'inert & "命令" + % $x', '2']
                        if 'local host=' in text:
                            args.insert(0, '127.0.0.1')
                        env = dict(os.environ, BASH_ENV='', DSH_SESSION_ID='child-id',
                                   DSH_APPROVAL_ROOT_SESSION_ID='root-id',
                                   DSH_APPROVAL_CONVERSATION_TITLE='主对话 & + % "标题" 😀',
                                   DSH_APPROVAL_AGENT_TITLE='子任务\n第二行 & =')
                        result = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-c',
                                                 select + function + 'http_confirm ' + shlex.join(args)],
                                                env=env, capture_output=True, text=True, timeout=10)
                        self.assertEqual(result.returncode, 0, result.stderr)
                        self.assertEqual(json.loads(result.stdout), {'result': 'NO'})
                        query, token = requests[-1]
                        self.assertEqual(token, 'fixture-token')
                        self.assertEqual(query, {'cmd': [args[-2]], 'force': ['2'], 'session_id': ['child-id'],
                                                'root_session_id': ['root-id'],
                                                'conversation_title': [env['DSH_APPROVAL_CONVERSATION_TITLE']],
                                                'agent_title': [env['DSH_APPROVAL_AGENT_TITLE']]})
        finally:
            server.shutdown()
            thread.join()
            server.server_close()


if __name__ == '__main__':
    unittest.main()
