#!/usr/bin/env python3
"""真实 dsh CLI/pnpm 离线冒烟：只安装临时 fixture 到独立 DSH_HOME。

不操作当前 profile，不访问网络，不安装真实插件，不构建 APK。
运行：PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-plugin-manager-integration.py
"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
BASE = Path(__file__).resolve().parents[1]
ASSET = BASE / 'app/src/main/assets/plugin-manager.py'
if not ASSET.exists():
    ASSET = BASE / 'rootfs-overlay/root/.dsh/plugin-manager.py'


@unittest.skipUnless(shutil.which('dsh') and shutil.which('pnpm'), '需要本机已安装的 dsh 和 pnpm')
class OfficialCliIntegrationTest(unittest.TestCase):
    def package(self, parent, version, sentinel):
        directory = parent / ('fixture-' + version)
        directory.mkdir()
        (directory / 'package.json').write_text(json.dumps({
            'name': 'dsha-transaction-offline-fixture', 'version': version, 'type': 'module',
            'main': 'index.js', 'dsh': {'bundle': {'patch': 'cordis.patch.yml'}}}), encoding='utf-8')
        (directory / 'cordis.patch.yml').write_text('[]\n', encoding='utf-8')
        (directory / 'index.js').write_text('export default {};\n', encoding='utf-8')
        (directory / 'payload.txt').write_bytes(sentinel)
        return directory

    def test_real_cli_update_and_bidirectional_rollback_in_isolated_profile(self):
        with tempfile.TemporaryDirectory(prefix='dsha-cli-integration-') as temporary:
            root = Path(temporary)
            home = root / 'root/.dsh'
            profile = home / 'profiles/web'
            profile.mkdir(parents=True)
            (profile / 'package.json').write_text(json.dumps({
                'name': 'dsha-isolated-plugin-test', 'private': True,
                'dependencies': {}, 'dsh': {'profile': {'bundles': [], 'patchReload': 'startup'}}}), encoding='utf-8')
            (profile / 'pnpm-workspace.yaml').write_text(
                'packages:\n  - .\nnodeLinker: hoisted\nautoInstallPeers: false\n', encoding='utf-8')
            (profile / 'cordis.patch.yml').write_text('[]\n', encoding='utf-8')
            environment = {
                'DSHA_TEST_ROOT': str(root), 'DSH_HOME': str(home),
                'npm_config_offline': 'true', 'npm_config_registry': 'http://127.0.0.1:1',
                'npm_config_store_dir': str(root / 'pnpm-store'),
                'npm_config_cache': str(root / 'npm-cache'),
                'npm_config_userconfig': str(root / 'empty.npmrc'),
                'npm_config_update_notifier': 'false', 'COREPACK_ENABLE_NETWORK': '0', 'CI': 'true',
            }
            (root / 'empty.npmrc').write_text('', encoding='utf-8')
            with patch.dict(os.environ, environment):
                spec = importlib.util.spec_from_file_location('isolated_manager', ASSET)
                manager = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(manager)
                self.assertEqual(str(home), manager.local(manager.DSH_HOME))
                name = 'dsha-transaction-offline-fixture'
                first = self.package(root, '1.0.0', b'old build payload\x00')
                second = self.package(root, '2.0.0', b'new build payload\x00')
                life = manager.lifecycle()
                for path, version in ((first, '1.0.0'), (second, '2.0.0')):
                    archive = manager.store_plugin_archive(str(path))
                    life.install_package('file:' + archive, name, version, source='https://example.invalid/fixture')
                    actual = Path(manager.resolve_profile_plugin_dir(name))
                    self.assertEqual(version, manager.read_json(actual / 'package.json')['version'])
                    self.assertEqual((path / 'payload.txt').read_bytes(), (actual / 'payload.txt').read_bytes())
                    if version == '1.0.0':
                        manager.builtin.disable_plugin(name)
                self.assertEqual('1.0.0', life.history_info(name)['version'])
                self.assertNotIn(name, manager.builtin.read_manifest()['dsh']['profile']['bundles'])
                for version, payload in (('1.0.0', b'old build payload\x00'), ('2.0.0', b'new build payload\x00')):
                    life.rollback(name, version)
                    actual = Path(manager.resolve_profile_plugin_dir(name))
                    self.assertEqual(version, manager.read_json(actual / 'package.json')['version'])
                    self.assertEqual(payload, (actual / 'payload.txt').read_bytes())
                    self.assertNotIn(name, manager.builtin.read_manifest()['dsh']['profile']['bundles'])
                    self.assertTrue(Path(manager.builtin.marker_path(name)).is_file())
                self.assertEqual('https://example.invalid/fixture', manager.read_json(home / 'plugin-sources.json')[name])
                # 让真实 CLI 安装有效包，但预期版本故意不匹配，验证真正修改后能恢复。
                previous_history = life.history_info(name)['version']
                archive = manager.store_plugin_archive(str(first))
                with self.assertRaisesRegex(ValueError, '已恢复'):
                    life.install_package('file:' + archive, name, '99.0.0', source='https://example.invalid/wrong')
                actual = Path(manager.resolve_profile_plugin_dir(name))
                self.assertEqual('2.0.0', manager.read_json(actual / 'package.json')['version'])
                self.assertEqual(b'new build payload\x00', (actual / 'payload.txt').read_bytes())
                self.assertEqual(previous_history, life.history_info(name)['version'])
                self.assertEqual('https://example.invalid/fixture', manager.read_json(home / 'plugin-sources.json')[name])
                self.assertNotIn(name, manager.builtin.read_manifest()['dsh']['profile']['bundles'])


if __name__ == '__main__':
    unittest.main()
