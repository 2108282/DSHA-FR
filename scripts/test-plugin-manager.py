#!/usr/bin/env python3
"""少量插件回归检查：仅操作临时目录，不调用网络/pnpm，不碰真实用户数据。"""
import contextlib
import importlib.util
import io
import json
import os
import sys
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

p1 = Path(__file__).resolve().parents[1] / "app/src/main/assets/plugin-manager.py"
p2 = Path(__file__).resolve().parents[1] / "rootfs-overlay/root/.dsh/plugin-manager.py"
p3 = Path(__file__).resolve().parents[2] / "DSHA_repo/app/src/main/assets/plugin-manager.py"
ASSET = p1 if p1.exists() else (p2 if p2.exists() else p3)

# 全进程审计兜底：任何测试意外写出临时根目录都立即失败，包括 dir_fd 删除。
_ACTIVE_ROOT = None


def guard_test_writes(event, args):
    if _ACTIVE_ROOT is None:
        return
    paths = []
    if event == 'open':
        path, mode, flags = args
        if isinstance(path, int):
            return
        if (mode and any(letter in mode for letter in 'wax+')) or flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND):
            paths = [(path, None)]
    elif event in ('os.remove', 'os.rmdir', 'os.mkdir', 'os.chmod'):
        paths = [(args[0], args[-1] if isinstance(args[-1], int) else None)]
    elif event == 'os.symlink':
        paths = [(args[1], args[2])]
    elif event == 'os.rename':
        paths = [(args[0], args[2]), (args[1], args[3])]
    for path, descriptor in paths:
        path = os.fsdecode(path)
        if not os.path.isabs(path) and descriptor is not None and descriptor >= 0:
            path = os.path.join(os.readlink('/proc/self/fd/' + str(descriptor)), path)
        # 删除/替换的是链接本身，校验其父目录，不解引用最后一段。
        absolute = os.path.abspath(path)
        parent = os.path.realpath(os.path.dirname(absolute))
        resolved = os.path.join(parent, os.path.basename(absolute))
        if os.path.commonpath([_ACTIVE_ROOT, resolved]) != _ACTIVE_ROOT:
            raise AssertionError('测试试图写入临时目录之外：' + resolved)


sys.addaudithook(guard_test_writes)


class PluginTestBase(unittest.TestCase):
    def setUp(self):
        global _ACTIVE_ROOT
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        _ACTIVE_ROOT = str(self.root.resolve())
        self.env = patch.dict(os.environ, {"DSHA_TEST_ROOT": str(self.root), "DSH_HOME": "/root/.dsh"})
        self.env.start()
        spec = importlib.util.spec_from_file_location("manager", ASSET)
        self.manager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.manager)
        self.home = self.root / "root/.dsh"
        self.home.mkdir(parents=True)
        self.commands = patch.object(self.manager.subprocess, 'run', side_effect=self.fake_command)
        self.commands.start()
        self.network = patch.object(self.manager.urllib.request, 'urlopen', side_effect=AssertionError('测试禁止真实网络'))
        self.network.start()
        self.out = io.StringIO()
        self.capture = contextlib.redirect_stdout(self.out)
        self.capture.__enter__()

    def fake_command(self, args, **kwargs):
        prefix = ['dsh', 'plugin', '--profile', 'web']
        if args[:4] != prefix or len(args) != 6 or args[4] not in ('add', 'remove'):
            raise AssertionError('测试禁止真实外部命令：' + str(args))
        self.assertEqual(str(self.home / 'profiles/web'), kwargs['cwd'])
        self.assertEqual(str(self.home), kwargs['env']['DSH_HOME'])
        doc = self.manager.builtin.read_manifest()
        if doc is None:
            doc = self.manager.builtin.new_manifest({})
        name = args[5]
        if args[4] == 'remove':
            doc.get('dependencies', {}).pop(name, None)
            bundles = doc.get('dsh', {}).get('profile', {}).get('bundles', [])
            if name in bundles:
                bundles.remove(name)
        else:
            spec = args[5]
            if spec.startswith('file:'):
                with tarfile.open(spec[5:], 'r:gz') as archive:
                    metadata = json.load(archive.extractfile('package/package.json'))
                name, version = metadata['name'], metadata.get('version', '1.0.0')
            else:
                name, _, version = spec.rpartition('@')
            self.package(Path(self.manager.local(self.manager.builtin.NODE_MODULES)) / name,
                         name, version or '1.0.0')
            doc.setdefault('dependencies', {})[name] = spec
            bundles = doc.setdefault('dsh', {}).setdefault('profile', {}).setdefault('bundles', [])
            if name not in bundles:
                bundles.append(name)
        self.manager.builtin.write_manifest(doc)
        return self.manager.subprocess.CompletedProcess(args, 0, '', '')

    def tearDown(self):
        global _ACTIVE_ROOT
        try:
            self.capture.__exit__(None, None, None)
            self.network.stop()
            self.commands.stop()
            self.env.stop()
            self.temp.cleanup()
        finally:
            _ACTIVE_ROOT = None

    def package(self, path, name="dsh-demo", version="1"):
        path.mkdir(parents=True, exist_ok=True)
        (path / "package.json").write_text(json.dumps({
            "name": name, "version": version, "main": "index.js",
            "dsh": {"bundle": {"patch": "cordis.patch.yml"}}}), encoding="utf-8")
        (path / "index.js").write_text("export default {};", encoding="utf-8")
        (path / "cordis.patch.yml").write_text("[]", encoding="utf-8")
        return path

    def marker(self, name):
        marker = Path(self.manager.builtin.marker_path(name))
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.touch()

class PluginManagerTest(PluginTestBase):
    def test_official_pnpm_plugin_toggle_preserves_dependency_and_clears_marker(self):
        name = "dsh-demo"
        self.package(self.home / "profiles/web/node_modules" / name, name, "1.0.0")
        dependency = "github:owner/repo#" + "a" * 40
        self.manager.builtin.write_manifest({
            "dependencies": {name: dependency},
            "dsh": {"profile": {"bundles": [name]}}})
        self.assertEqual(0, self.manager.builtin.disable_plugin(name))
        self.assertNotIn(name, self.manager.builtin.read_manifest()["dsh"]["profile"]["bundles"])
        self.assertEqual(dependency, self.manager.builtin.read_manifest()["dependencies"][name])
        marker = Path(self.manager.builtin.marker_path(name))
        self.assertTrue(marker.is_file())
        self.assertEqual(0, self.manager.builtin.enable_plugin(name))
        manifest = self.manager.builtin.read_manifest()
        self.assertIn(name, manifest["dsh"]["profile"]["bundles"])
        self.assertEqual(dependency, manifest["dependencies"][name])
        self.assertFalse(marker.exists())

    def test_scoped_multi_export_import_preserves_disabled_state_and_bytes(self):
        names = ["@sample/demo", "dsh-extra"]
        for name in names:
            self.package(self.home / "plugin-src" / name, name)
            self.marker(name)
        archive = self.home / "out.tar.gz"
        self.manager.cmd_export(json.dumps(names), str(archive))
        self.assertEqual(0, self.manager.cmd_import(str(archive)))
        manifest = self.manager.builtin.read_manifest()
        self.assertEqual(list(self.manager.builtin.OFFICIAL_BUNDLES), manifest["dsh"]["profile"]["bundles"])
        for name in names:
            self.assertEqual("link:/root/.dsh/plugin-src/" + name, manifest["dependencies"][name])
            self.assertEqual("export default {};", (self.home / "plugin-src" / name / "index.js").read_text())
        self.assertFalse(list((self.home / "plugin-src").glob(".install-*")))

    def test_bad_update_keeps_existing_plugin_and_manifest(self):
        name = "dsh-demo"
        self.package(self.home / "plugin-src" / name, name, "old")
        original = {"name": "existing", "dependencies": {}, "dsh": {"profile": {"bundles": []}}}
        self.manager.builtin.write_manifest(original)
        incoming = self.package(self.root / "incoming", name, "new")
        with patch.object(self.manager.os, "symlink", side_effect=OSError("link unavailable")):
            with self.assertRaises(OSError):
                self.manager.register_plugin(str(incoming), "")
        self.assertEqual(original, self.manager.builtin.read_manifest())
        self.assertEqual("old", json.loads((self.home / "plugin-src" / name / "package.json").read_text())["version"])

    def test_delete_scoped_plugin_only_and_protect_builtins(self):
        name = "@sample/demo"
        self.package(self.home / "plugin-src" / name, name)
        other = self.package(self.home / "plugin-src/dsh-other", "dsh-other")
        self.marker(name)
        config = self.home / "settings.json"
        config.write_text('{"keep":true}')
        self.manager.builtin.write_manifest({
            "dependencies": {name: "link:/root/.dsh/plugin-src/" + name, "dsh-other": "1"},
            "dsh": {"profile": {"bundles": [name, "dsh-other"]}}})
        sources = self.home / "plugin-sources.json"
        sources.write_text(json.dumps({name: "https://example.org/plugin.tgz"}))
        original = self.manager.builtin.read_manifest()
        with patch.object(self.manager.builtin, "write_manifest", side_effect=OSError("disk unavailable")):
            with self.assertRaises(OSError):
                self.manager.cmd_delete(name)
        self.assertTrue((self.home / "plugin-src" / name / "index.js").is_file())
        self.assertTrue(Path(self.manager.builtin.marker_path(name)).exists())
        self.assertEqual(original, self.manager.builtin.read_manifest())
        self.assertIn(name, json.loads(sources.read_text()))
        self.manager.cmd_delete(name)
        self.assertFalse((self.home / "plugin-src" / name).exists())
        self.assertFalse(Path(self.manager.builtin.marker_path(name)).exists())
        self.assertTrue(other.is_dir())
        self.assertNotIn(name, json.loads(sources.read_text()))
        self.assertEqual('{"keep":true}', config.read_text())
        self.assertEqual(["dsh-other"], self.manager.builtin.read_manifest()["dsh"]["profile"]["bundles"])
        for protected in ["@deepseek-ai/dsh-base", "dsh-web-mobile", "../settings.json"]:
            with self.assertRaises(ValueError):
                self.manager.cmd_delete(protected)

    def test_subdirectory_does_not_install_neighbor_plugins(self):
        staging = self.root / "staging"
        selected = self.package(staging / "repo-main/packages/one", "dsh-one")
        self.package(staging / "repo-main/packages/two", "dsh-two")
        self.assertEqual([str(selected)], self.manager.find_plugin_roots(str(staging), "packages/one"))
        with self.assertRaises(ValueError):
            self.manager.find_plugin_roots(str(staging), "packages/missing")

    def test_zip_tar_and_absolute_or_traversal_entries(self):
        for filename in ("../escape", "/root/escape", "C:/escape"):
            archive = self.root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr(filename, "bad")
            with self.assertRaises(ValueError):
                self.manager.extract_archive(str(archive), str(self.root / "unzip"))
        archive = self.root / "good.tar"
        with tarfile.open(archive, "w") as tar:
            entry = tarfile.TarInfo("package/index.js")
            entry.size, entry.mode = 2, 0o755
            tar.addfile(entry, io.BytesIO(b"ok"))
        self.manager.extract_archive(str(archive), str(self.root / "untar"))
        self.assertEqual(b"ok", (self.root / "untar/package/index.js").read_bytes())

    def test_invalid_bundle_and_missing_build_are_not_reported_as_success(self):
        incoming = self.package(self.root / "bad")
        (incoming / "index.js").unlink()
        with self.assertRaisesRegex(ValueError, "入口"):
            self.manager.plugin_package(str(incoming))
        manifest = incoming / "package.json"
        manifest.write_text(json.dumps({"name": "dsh-fake"}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "dsh.bundle.patch"):
            self.manager.plugin_package(str(incoming))
        # 一个包中一好一坏：结果必须是 partial，不能因为出现一个成功就吞掉失败。
        good = self.package(self.root / "good", "dsh-good")
        bad = self.package(self.root / "bad-build", "dsh-bad")
        (bad / "index.js").unlink()
        self.marker("dsh-good")
        archive = self.root / "mixed.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(good, arcname="plugins/good")
            tar.add(bad, arcname="plugins/bad")
        self.assertEqual(1, self.manager.cmd_import(str(archive)))
        last = json.loads(self.out.getvalue().strip().split("\n")[-1].removeprefix("PLUGIN_RESULT: "))
        self.assertEqual("partial", last["status"])
        self.assertEqual(["dsh-good"], last["installed"])

    def test_default_branch_is_pinned_for_official_install(self):
        revision = "b" * 40
        def response(url):
            if url.endswith("/repos/owner/repo"):
                return io.BytesIO(json.dumps({"default_branch": "main"}).encode())
            if url.endswith("/commits/main"):
                return io.BytesIO(json.dumps({"sha": revision}).encode())
            raise AssertionError(url)
        with patch.object(self.manager, "open_url", side_effect=response), \
                patch.object(self.manager, "cmd_download", return_value=0) as download:
            self.manager.cmd_github("owner", "repo")
        self.assertEqual("https://codeload.github.com/owner/repo/tar.gz/" + revision,
                         download.call_args.args[0])
        self.assertEqual("git+https://github.com/owner/repo.git#" + revision,
                         download.call_args.kwargs["official_spec"])

    def test_slash_branch_resolves_requested_commit_and_subdirectory(self):
        responses = []
        revision = "a" * 40
        def response(url):
            responses.append(url)
            if url.endswith("feature%2Fandroid"):
                return io.BytesIO(json.dumps({"sha": revision}).encode())
            raise self.manager.urllib.error.HTTPError(url, 404, "missing", {}, None)
        with patch.object(self.manager, "open_url", side_effect=response), \
                patch.object(self.manager, "cmd_download", return_value=0) as download:
            self.manager.cmd_github("owner", "repo", "feature/android/packages/demo")
            args = download.call_args.args
            self.assertEqual("https://codeload.github.com/owner/repo/tar.gz/" + revision, args[0])
            self.assertEqual("packages/demo", args[1])
            self.assertEqual("git+https://github.com/owner/repo.git#" + revision + "&path:packages/demo",
                             download.call_args.kwargs["official_spec"])

    def test_github_zip_uses_official_download_and_preserves_ref(self):
        rewrite = self.manager.archive_download_url
        self.assertEqual("https://codeload.github.com/Minglink/dsh-infinite-gen-3/zip/refs/heads/master",
                         rewrite("https://github.com/Minglink/dsh-infinite-gen-3/archive/refs/heads/master.zip"))
        self.assertEqual("https://codeload.github.com/o/r/tar.gz/refs/tags/v1.0",
                         rewrite("https://github.com/o/r/archive/refs/tags/v1.0.tar.gz"))
        external = "https://example.org/plugin.zip"
        self.assertEqual(external, rewrite(external))

    def test_npm_packs_only_requested_package_and_rejects_options(self):
        def pack(args, **kwargs):
            destination = Path(args[args.index("--pack-destination") + 1])
            archive = destination / "sample-demo-1.0.0.tgz"
            archive.write_bytes(b"test")
            self.assertEqual(["--", "@sample/demo@1.0.0"], args[-2:])
            self.assertIn("--ignore-scripts", args)
            return self.manager.subprocess.CompletedProcess(args, 0, '[{"filename":"sample-demo-1.0.0.tgz"}]', '')
        with patch.object(self.manager.shutil, "which", return_value="/usr/bin/npm"), \
                patch.object(self.manager.subprocess, "run", side_effect=pack), \
                patch.object(self.manager, "cmd_import", return_value=0) as install:
            self.assertEqual(0, self.manager.cmd_npm("@sample/demo@1.0.0"))
            self.assertEqual("npm:@sample/demo@1.0.0", install.call_args.kwargs["source"])
        for invalid in ["--global", "../package", "https://example.com/x", "demo;rm -rf /", ""]:
            with self.assertRaises(ValueError):
                self.manager.cmd_npm(invalid)


class PluginLifecycleTest(PluginTestBase):
    def test_imported_plugins_share_runtime_modules_without_overwriting_user_deps(self):
        runtime = self.root / 'usr/local/lib/node_modules/@deepseek-ai/dsh'
        self.package(runtime, name='@deepseek-ai/dsh')
        self.package(runtime / 'node_modules/@deepseek-ai/dsh-tools', name='@deepseek-ai/dsh-tools')
        self.package(runtime / 'node_modules/react', name='react')
        owned = self.package(self.home / 'node_modules/react', name='react', version='user')
        self.manager.register_plugin(self.package(self.root / 'plugin'), '')
        target = self.home / 'node_modules/@deepseek-ai/dsh-tools'
        self.assertTrue(target.is_symlink())
        self.assertEqual((runtime / 'node_modules/@deepseek-ai/dsh-tools').resolve(), target.resolve())
        self.assertEqual('user', json.loads((owned / 'package.json').read_text())['version'])
        self.assertEqual(0, self.manager.builtin.ensure_runtime_modules())

    def test_shared_runtime_modules_reject_scope_escape(self):
        runtime = self.root / 'usr/local/lib/node_modules/@deepseek-ai/dsh'
        self.package(runtime / 'node_modules/@deepseek-ai/dsh-tools', name='@deepseek-ai/dsh-tools')
        outside = self.root / 'outside-shared'; outside.mkdir()
        modules = self.home / 'node_modules'; modules.mkdir()
        os.symlink(outside, modules / '@deepseek-ai', target_is_directory=True)
        with self.assertRaises(RuntimeError): self.manager.builtin.ensure_runtime_modules()
        self.assertEqual([], list(outside.iterdir()))

    def test_npm_tarball_updates_use_latest_registry_package(self):
        self.manager.register_plugin(self.package(self.root / 'v1', name='@scope/demo'), 'https://registry.npmjs.org/@scope/demo/-/demo-1.0.0.tgz')
        self.assertEqual("npm @scope/demo@latest", self.manager.lifecycle().source_command('@scope/demo'))

    def archive(self, name='dsh-demo', version='1.0.0'):
        root = self.package(self.root / ('source-' + version), name, version)
        archive = self.root / ('archive-' + version + '.zip')
        with zipfile.ZipFile(archive, 'w') as out:
            for file in root.iterdir(): out.write(file, 'package/' + file.name)
        return archive

    def test_npm_preview_uses_locked_tarball_through_confirm(self):
        archive = self.archive(version='1.0.0')
        life = self.manager.lifecycle()
        def pack(spec, consume):
            self.assertEqual('dsh-demo@1.0.0', spec)
            return consume(str(archive), source='npm:dsh-demo@1.0.0')
        request = {'command': "npm dsh-demo", 'name': 'dsh-demo'}
        metadata = json.dumps({'name': 'dsh-demo', 'version': '1.0.0', 'main': 'index.js',
                               'dsh': {'bundle': {'patch': 'cordis.patch.yml'}}})
        completed = self.manager.subprocess.CompletedProcess([], 0, metadata, '')
        with patch.object(self.manager.shutil, 'which', return_value='/usr/bin/npm'), \
                patch.object(self.manager.subprocess, 'run', return_value=completed), \
                patch.object(self.manager, 'cmd_npm', side_effect=pack):
            preview = life.inspect(request, False)
        preview_path = Path(life.preview_path(preview['previewId']))
        self.assertEqual(life.digest(str(preview_path / 'archive')), preview['sha256'])
        self.assertEqual('1.0.0', preview['items'][0]['version'])
        self.assertFalse((self.home / 'profiles/web/node_modules/dsh-demo').exists())
        self.assertEqual(0, life.install_preview(preview['previewId']))
        self.assertTrue((self.home / 'profiles/web/node_modules/dsh-demo').exists())

    def test_rollback_retains_only_one_previous_version_and_disabled_state(self):
        for version in ('1.0.0', '2.0.0', '3.0.0'):
            self.manager.register_plugin(self.package(self.root / version, version=version), 'npm:dsh-demo@' + version)
        life = self.manager.lifecycle()
        self.assertEqual('2.0.0', life.history_info('dsh-demo')['version'])
        self.marker('dsh-demo')
        life.rollback('dsh-demo')
        self.assertEqual('2.0.0', json.loads((self.home / 'plugin-src/dsh-demo/package.json').read_text())['version'])
        self.assertEqual('3.0.0', life.history_info('dsh-demo')['version'])
        self.assertTrue(Path(self.manager.builtin.marker_path('dsh-demo')).is_file())
        self.manager.cmd_delete('dsh-demo')
        self.assertFalse(Path(life.history_path('dsh-demo')).exists())

    def test_failed_update_restores_current_package_and_prior_history(self):
        for version in ('1.0.0', '2.0.0'):
            self.manager.register_plugin(self.package(self.root / version, version=version), 'source-' + version)
        before = self.manager.builtin.read_manifest()
        with patch.object(self.manager.builtin, 'write_manifest', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                self.manager.register_plugin(self.package(self.root / '3.0.0', version='3.0.0'), 'new-source')
        self.assertEqual(before, self.manager.builtin.read_manifest())
        self.assertEqual('2.0.0', json.loads((self.home / 'plugin-src/dsh-demo/package.json').read_text())['version'])
        self.assertEqual('1.0.0', self.manager.lifecycle().history_info('dsh-demo')['version'])
        self.assertEqual('source-2.0.0', self.manager.read_json(self.home / 'plugin-sources.json')['dsh-demo'])

    def test_preview_does_not_install_until_confirmed_and_verifies_content(self):
        archive = self.archive()
        life = self.manager.lifecycle()
        def download(url, target):
            import shutil
            shutil.copyfile(archive, target)
        request = {'command': "download 'https://example.com/demo.zip'", 'name': 'dsh-demo', 'version': '1.0.0'}
        with patch.object(self.manager, 'download', side_effect=download):
            preview = life.inspect(request, False)
            self.assertFalse((self.home / 'plugin-src/dsh-demo').exists())
            self.assertEqual('1.0.0', preview['items'][0]['version'])
            self.assertEqual(0, life.install_preview(preview['previewId']))
            self.assertTrue((self.home / 'profiles/web/node_modules/dsh-demo').exists())
            with self.assertRaises(ValueError): life.inspect(dict(request, sha256='a' * 64), False)
            preview = life.inspect(request, False)
            (Path(life.preview_path(preview['previewId'])) / 'archive').write_bytes(b'changed')
            with self.assertRaises(ValueError): life.install_preview(preview['previewId'])

    def test_failed_preview_install_cleans_staged_archive(self):
        archive = self.archive(version='1.0.0')
        life = self.manager.lifecycle()
        def download(url, target):
            import shutil
            shutil.copyfile(archive, target)
        request = {'command': "download 'https://example.com/demo.zip'", 'name': 'dsh-demo', 'version': '1.0.0'}
        with patch.object(self.manager, 'download', side_effect=download):
            preview = life.inspect(request, False)
        staged = Path(life.preview_path(preview['previewId']))
        with patch.object(self.manager, 'cmd_official_add', side_effect=ValueError('official add failed')):
            self.assertEqual(1, life.install_preview(preview['previewId']))
        self.assertFalse(staged.exists())

    def test_update_preview_is_bound_to_plugin_identity_and_installed_version(self):
        self.manager.register_plugin(self.package(self.root / 'v1', version='1.0.0'), 'https://example.com/demo.zip')
        archive = self.archive(version='2.0.0')
        def download(url, target):
            import shutil
            shutil.copyfile(archive, target)
        life = self.manager.lifecycle()
        with patch.object(self.manager, 'download', side_effect=download):
            life.check_updates('dsh-demo')
            state = self.manager.read_json(self.home / 'plugin-updates.json')['dsh-demo']
            self.assertTrue(state['available'])
            self.assertEqual('2.0.0', state['latestVersion'])
            self.manager.register_plugin(self.package(self.root / 'v3', version='3.0.0'), 'https://example.com/demo.zip')
            self.manager.cmd_list()
            listing = json.loads(self.out.getvalue().split('PLUGIN_RESULT: ')[-1])
            changed = next(item for item in listing['items'] if item['name'] == 'dsh-demo')
            self.assertFalse(changed['updateAvailable'])
            self.assertEqual('', changed['updateMessage'])
            with self.assertRaises(ValueError): life.install_preview(state['previewId'])
            with self.assertRaises(ValueError): life.inspect({'command': "download 'https://example.com/demo.zip'", 'name': 'other-plugin'}, False)

    def test_safe_mode_preserves_manual_disables_and_restores_only_its_own_changes(self):
        for name in ('demo-one', 'demo-two', 'demo-three'):
            self.manager.register_plugin(self.package(self.root / name, name=name, version='1.0.0'), '')
        self.manager.builtin.disable_plugin('demo-three')
        life = self.manager.lifecycle()
        life.safe_mode('on')
        bundles = self.manager.builtin.read_manifest()['dsh']['profile']['bundles']
        self.assertFalse(set(bundles) & {'demo-one', 'demo-two', 'demo-three'})
        self.assertTrue(set(self.manager.builtin.OFFICIAL_BUNDLES).issubset(bundles))
        self.manager.builtin.disable_plugin('demo-two')
        life.safe_mode('off')
        bundles = self.manager.builtin.read_manifest()['dsh']['profile']['bundles']
        self.assertIn('demo-one', bundles)
        self.assertNotIn('demo-two', bundles)
        self.assertNotIn('demo-three', bundles)

    def test_rejects_import_through_scope_link_outside_managed_directory(self):
        outside = self.root / 'outside'; outside.mkdir()
        parent = self.home / 'plugin-src'; parent.mkdir()
        os.symlink(outside, parent / '@scope', target_is_directory=True)
        with self.assertRaises(ValueError):
            self.manager.register_plugin(self.package(self.root / 'scoped', name='@scope/demo'), '')
        self.assertEqual([], list(outside.iterdir()))


class PluginSafetyRegressionTest(PluginTestBase):
    def profile_package(self, name='dsh-demo'):
        return self.package(Path(self.manager.local(self.manager.builtin.NODE_MODULES)) / name, name)

    def register_fixture(self, name='dsh-demo'):
        self.manager.register_plugin(self.package(self.root / 'incoming', name=name), 'npm:' + name)
        return self.home / 'plugin-src' / name

    def test_hoisted_npm_entity_is_not_deleted_or_relinked(self):
        package = self.profile_package()
        before = (package / 'index.js').read_bytes()
        self.assertEqual(0, self.manager.ensure_plugin_symlinks('dsh-demo'))
        self.assertFalse(package.is_symlink())
        self.assertEqual(before, (package / 'index.js').read_bytes())
        self.assertFalse((self.root / 'usr/local/lib/node_modules/dsh-demo').exists())

    def test_pnpm_isolated_link_is_not_rewritten(self):
        actual = self.package(self.home / 'profiles/web/node_modules/.pnpm/demo/node_modules/dsh-demo')
        link = actual.parents[3] / 'dsh-demo'
        os.symlink(actual, link)
        self.assertEqual(0, self.manager.ensure_plugin_symlinks('dsh-demo'))
        self.assertEqual(str(actual), os.readlink(link))
        self.assertTrue((actual / 'index.js').is_file())

    def test_self_link_and_dangling_link_fail_without_being_deleted(self):
        root = Path(self.manager.local(self.manager.builtin.NODE_MODULES))
        root.mkdir(parents=True)
        link = root / 'dsh-demo'
        for target in (str(link), str(root / 'missing')):
            with self.subTest(target=target):
                os.symlink(target, link)
                with self.assertRaisesRegex(ValueError, '目标不存在'):
                    self.manager.ensure_plugin_symlinks('dsh-demo')
                self.assertEqual(target, os.readlink(link))
                link.unlink()

    def test_registration_keeps_existing_pnpm_entity_and_manifest(self):
        existing = self.profile_package()
        self.manager.builtin.write_manifest({'dependencies': {'dsh-demo': '1'}, 'dsh': {'profile': {'bundles': ['dsh-demo']}}})
        before = self.manager.builtin.read_manifest()
        with self.assertRaisesRegex(ValueError, '实体目录'):
            self.manager.register_plugin(self.package(self.root / 'incoming'), '')
        self.assertEqual(before, self.manager.builtin.read_manifest())
        self.assertTrue((existing / 'index.js').is_file())
        self.assertFalse(existing.is_symlink())

    def test_global_collision_does_not_remove_entity_or_publish_profile_link(self):
        name = 'dsh-demo'
        actual = self.package(self.home / 'plugin-src' / name)
        foreign = self.package(self.root / 'usr/local/lib/node_modules' / name)
        with self.assertRaisesRegex(ValueError, '占用'):
            self.manager.ensure_plugin_symlinks(name)
        self.assertTrue((foreign / 'index.js').is_file())
        self.assertTrue((actual / 'index.js').is_file())
        self.assertFalse(Path(self.manager.local(self.manager.builtin.NODE_MODULES)).joinpath(name).exists())

    def test_scope_parent_link_is_preserved_without_external_write(self):
        self.package(self.home / 'plugin-src/@scope/demo', '@scope/demo')
        root = self.root / 'usr/local/lib/node_modules'; root.mkdir(parents=True)
        outside = self.root / 'other-scope'; outside.mkdir()
        os.symlink(outside, root / '@scope')
        with self.assertRaisesRegex(ValueError, '父目录'):
            self.manager.ensure_plugin_symlinks('@scope/demo')
        self.assertTrue((root / '@scope').is_symlink())
        self.assertEqual([], list(outside.iterdir()))

    def test_failed_manifest_commit_rolls_back_new_global_link(self):
        incoming = self.package(self.root / 'incoming')
        with patch.object(self.manager.builtin, 'write_manifest', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                self.manager.register_plugin(incoming, '')
        self.assertFalse(os.path.lexists(self.root / 'usr/local/lib/node_modules/dsh-demo'))
        self.assertFalse(os.path.lexists(self.home / 'profiles/web/node_modules/dsh-demo'))
        self.assertFalse((self.home / 'plugin-src/dsh-demo').exists())

    def test_continuous_symlink_failure_restores_old_entity_before_links(self):
        source = self.register_fixture()
        before = self.manager.builtin.read_manifest()
        old_bytes = (source / 'package.json').read_bytes()
        incoming = self.package(self.root / 'upgrade', version='2')
        with patch.object(self.manager.os, 'symlink', side_effect=OSError('no space left')):
            with self.assertRaisesRegex(RuntimeError, '回滚未完成'):
                self.manager.register_plugin(incoming, 'new-source')
        self.assertEqual(old_bytes, (source / 'package.json').read_bytes())
        self.assertEqual(before, self.manager.builtin.read_manifest())
        self.assertTrue((self.root / 'usr/local/lib/node_modules/dsh-demo/package.json').is_file())
        self.assertFalse(list((self.home / 'plugin-src').glob('.install-*')))

    def test_failed_entity_restore_keeps_backup_outside_automatic_cleanup(self):
        source = self.register_fixture()
        old_bytes = (source / 'package.json').read_bytes()
        incoming = self.package(self.root / 'upgrade', version='2')
        replace = self.manager.os.replace

        def fail_old_restore(src, dst):
            if Path(src).name == 'old' and Path(dst) == source:
                raise OSError('restore denied')
            return replace(src, dst)

        with patch.object(self.manager.os, 'replace', side_effect=fail_old_restore), \
                patch.object(self.manager.builtin, 'write_manifest', side_effect=OSError('commit denied')):
            with self.assertRaisesRegex(RuntimeError, '回滚未完成'):
                self.manager.register_plugin(incoming, 'new-source')
        backups = list((self.home / 'plugin-src').glob('.install-*/old/package.json'))
        self.assertEqual(1, len(backups))
        self.assertEqual(old_bytes, backups[0].read_bytes())
        self.assertIn('回滚备份已保留', self.out.getvalue())

    def test_official_remove_failure_and_timeout_preserve_plugin(self):
        source = self.register_fixture()
        before = self.manager.builtin.read_manifest()
        for outcome in ('failed', 'timeout', 'unchanged'):
            with self.subTest(outcome=outcome):
                response = self.manager.subprocess.CompletedProcess([], 1 if outcome == 'failed' else 0, '', 'stop-profile')
                error = self.manager.subprocess.TimeoutExpired('dsh', 120) if outcome == 'timeout' else None
                with patch.object(self.manager.subprocess, 'run', side_effect=error, return_value=response):
                    with self.assertRaises(ValueError): self.manager.cmd_delete('dsh-demo')
                self.assertEqual(before, self.manager.builtin.read_manifest())
                self.assertTrue((source / 'index.js').is_file())
                self.assertTrue((self.root / 'usr/local/lib/node_modules/dsh-demo').is_symlink())

    def test_delete_cleans_own_global_and_bin_links_but_preserves_foreign_link(self):
        source = self.register_fixture()
        root = self.home / 'profiles/web/node_modules'
        binary = root / '.bin/dsh-demo'; binary.parent.mkdir()
        os.symlink('../dsh-demo/lib/cli.js', binary)
        other = self.package(self.root / 'foreign', 'other')
        global_link = self.root / 'usr/local/lib/node_modules/dsh-demo'
        global_link.unlink(); os.symlink(other, global_link)
        self.assertEqual(0, self.manager.cmd_delete('dsh-demo'))
        self.assertFalse(source.exists())
        self.assertFalse(os.path.lexists(binary))
        self.assertEqual(str(other), os.readlink(global_link))
        self.assertTrue((other / 'index.js').is_file())

    def test_delete_removes_self_loop_and_global_reference_for_missing_package(self):
        name = 'dsh-demo'
        self.manager.builtin.write_manifest({'dependencies': {name: '1'}, 'dsh': {'profile': {'bundles': []}}})
        profile, global_link = map(Path, self.manager.plugin_link_paths(name))
        for link in (profile, global_link): link.parent.mkdir(parents=True, exist_ok=True)
        os.symlink(profile, profile); os.symlink(profile, global_link)
        self.assertEqual(0, self.manager.cmd_delete(name))
        self.assertFalse(os.path.lexists(profile)); self.assertFalse(os.path.lexists(global_link))
        self.assertNotIn(name, self.manager.builtin.read_manifest()['dependencies'])

    def test_cleanup_failure_is_partial_not_success(self):
        source = self.register_fixture()
        with patch.object(self.manager, 'cleanup_plugin_links', side_effect=OSError('permission denied')):
            self.assertEqual(1, self.manager.cmd_delete('dsh-demo'))
        result = json.loads(self.out.getvalue().split('PLUGIN_RESULT: ')[-1])
        self.assertEqual('partial', result['status'])
        self.assertTrue(source.is_dir())
        self.assertNotIn('dsh-demo', self.manager.builtin.read_manifest()['dependencies'])

    def test_test_harness_rejects_real_global_write_and_external_command(self):
        with self.assertRaisesRegex(AssertionError, '临时目录之外'):
            os.symlink('/does-not-exist', '/usr/local/lib/node_modules/dsha-test-must-not-write')
        with self.assertRaisesRegex(AssertionError, '外部命令'):
            self.manager.subprocess.run(['pnpm', 'install'])


if __name__ == "__main__":
    unittest.main()
