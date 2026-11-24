#!/usr/bin/env python3
"""Regression checks for the public exporter; uses only an isolated tar fixture."""
import json
import pathlib
import re
import subprocess
import tempfile
import unittest

EXPORTER = pathlib.Path(__file__).resolve().parents[1] / 'tools/export-rootfs.sh'
SOURCE = EXPORTER.read_text()


class PublicRootfsExportTest(unittest.TestCase):
    def test_clean_excludes_secrets_and_local_extras_but_keeps_runtime(self):
        section = SOURCE.split('CLEAN_EXCLUDES=(', 1)[1].split('\n    )', 1)[0]
        excludes = re.findall(r'"(--exclude=[^"]+)"', section)
        excluded = [
            'root/.git-credentials', 'root/.git-credentials.backup',
            'root/.claude.json', 'root/.claude/credentials.json',
            'root/.dsh/mobile-access/tls/ca-key.pem',
            'root/.dsh/mobile-access/devices.json',
            'root/.dsh/.credentials.yaml', 'root/.dsh/.bridge_token',
            'root/.dsh/app.uid', 'root/.dsh/prompt-custom.json',
            'root/.dsh/profiles/web/pnpm-lock.yaml',
            'root/.dsh/profiles/headless/.plugin-manager/logs/pnpm.log',
            'root/.dsh/profiles/sdk/.npmrc',
            'root/dsh-classic-chat/package.json',
            'root/dsh-classic-chat-installed',
            'usr/local/lib/node_modules/dsh-classic-chat/package.json',
            'root/.dsh/plugin-src/dsh-classic-chat/lib/index.js',
            'root/.dsh/plugin-upload-demo.bin',
            'root/.dsh/maintenance/backup/config.yml',
            'root/.dsh/skill-backups/private/SKILL.md',
            'root/.dsh/fujiang-skills/private/SKILL.md',
            'root/.dsh/speech-to-text/sensevoice/model.onnx',
            'root/.dsh/planners-form/state/user.json',
            'root/.dsh/planners-form/state.json',
            'root/obscura/v0.2.6/obscura',
            'usr/local/n/versions/node/24.22.3/bin/node',
            'root/workspaces/project/private.txt',
        ]
        preserved = [
            'usr/local/bin/node',
            'usr/local/lib/node_modules/@deepseek-ai/dsh/package.json',
            'usr/local/lib/node_modules/rea-agents/package.json',
            'root/.dsh/node_modules/runtime/index.js',
            'root/.dsh/profiles/node_modules/runtime/index.js',
            'root/.dsh/planners-form/lib/index.js',
            'root/dsha-device-shell-guide/lib/index.js',
            'root/dsha-status-overlay/lib/index.js',
            'root/dsha-task-notifier/lib/index.js',
            'root/dsh-mobile-lite/lib/client.js',
            'root/dsh-quickchat-gesture/lib/client.js',
            'opt/jadx/lib/jadx.jar',
            'usr/lib/jvm/java-21-openjdk-arm64/lib/modules',
        ]
        with tempfile.TemporaryDirectory(prefix='dsha-export-test-') as temp:
            root = pathlib.Path(temp) / 'fixture'
            root.mkdir()
            for name in excluded + preserved:
                f = root / name
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text('fixture')
            archive = pathlib.Path(temp) / 'fixture.tar'
            subprocess.run(['tar', '-cf', str(archive), *excludes, '-C', str(root), '.'], check=True)
            members = set(subprocess.check_output(['tar', '-tf', str(archive)], text=True).splitlines())
            for name in excluded:
                self.assertNotIn('./' + name, members, name)
            for name in preserved:
                self.assertIn('./' + name, members, name)

    def test_clean_profile_registers_exactly_five_builtins(self):
        raw = SOURCE.split("<< 'EOF_PKG'\n", 1)[1].split('\nEOF_PKG', 1)[0]
        pkg = json.loads(raw)
        expected = {'dsh-device-shell-guide', 'dsh-task-notifier', 'dsh-status-overlay',
                    'dsh-mobile-lite', 'dsh-quickchat-gesture'}
        self.assertEqual(set(pkg['dependencies']), expected)
        self.assertEqual(set(pkg['dsh']['profile']['bundles']),
                         expected | {'@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'})
        self.assertNotIn('classic-chat', raw)

    def test_links_into_excluded_profile_modules_are_filtered(self):
        condition = next(line.strip() for line in SOURCE.splitlines()
                         if line.strip().startswith('if [[ "$target" == *"plugin-src"* ]]'))
        with tempfile.TemporaryDirectory(prefix='dsha-export-links-') as temp:
            existing = pathlib.Path(temp) / 'existing'
            existing.write_text('fixture')
            link = pathlib.Path(temp) / 'link'
            link.symlink_to(existing)
            for target, expected in [
                ('/root/.dsh/profiles/web/node_modules/dsh-ops', 'excluded'),
                ('/root/.dsh/plugin-src/dsh-classic-chat', 'excluded'),
                ('/root/dsh-mobile-lite', 'kept'),
                ('/usr/local/lib/node_modules/@deepseek-ai/dsh', 'kept'),
            ]:
                script = 'target="$1"; link_entry="$2"; ' + condition + \
                         ' printf excluded; else printf kept; fi'
                value = subprocess.check_output(['bash', '-c', script, 'fixture', target, str(link)], text=True)
                self.assertEqual(value, expected, target)

    def test_script_syntax_and_guarded_cleanup(self):
        subprocess.run(['bash', '-n', str(EXPORTER)], check=True)
        self.assertIn('mktemp -d /tmp/dsha_export_stage.', SOURCE)
        self.assertIn('resolved="$(realpath -- "$STAGE_DIR")"', SOURCE)
        self.assertNotIn('shutil.rmtree', SOURCE)


if __name__ == '__main__':
    unittest.main()
