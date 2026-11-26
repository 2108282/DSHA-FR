#!/usr/bin/env python3
"""少量插件回归检查：仅操作临时目录，不调用网络/pnpm，不碰真实用户数据。"""
import contextlib
import importlib.util
import io
import json
import os
import shutil
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
        self.command_calls = []
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
        self.command_calls.append(list(args))
        name = args[5]
        if args[4] == 'remove':
            doc.get('dependencies', {}).pop(name, None)
            bundles = doc.get('dsh', {}).get('profile', {}).get('bundles', [])
            if name in bundles:
                bundles.remove(name)
            self.remove_profile_entry(name)
        else:
            spec = args[5]
            if spec.startswith('file:'):
                with tempfile.TemporaryDirectory(dir=self.root) as staging:
                    self.manager.extract_archive(spec[5:], staging)
                    roots = self.manager.find_plugin_roots(staging)
                    self.assertEqual(1, len(roots))
                    metadata = self.manager.plugin_package(roots[0])
                    name = metadata['name']
                    self.remove_profile_entry(name)
                    target = Path(self.manager.local(self.manager.builtin.NODE_MODULES)) / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copytree(roots[0], target, symlinks=True)
            else:
                name, separator, version = spec.rpartition('@')
                if not separator or not self.manager.builtin.valid_name(name):
                    raise AssertionError('测试未定义该安装规格：' + spec)
                self.remove_profile_entry(name)
                self.package(Path(self.manager.local(self.manager.builtin.NODE_MODULES)) / name,
                             name, version or '1.0.0')
            newly_added = name not in doc.get('dependencies', {})
            doc.setdefault('dependencies', {})[name] = spec
            bundles = doc.setdefault('dsh', {}).setdefault('profile', {}).setdefault('bundles', [])
            # 官方 reconcile 只自动选择新增依赖；更新已有依赖保留其禁用状态。
            if newly_added and name not in bundles:
                bundles.append(name)
        self.manager.builtin.write_manifest(doc)
        return self.manager.subprocess.CompletedProcess(args, 0, '', '')

    def remove_profile_entry(self, name):
        entry = Path(self.manager.local(self.manager.builtin.NODE_MODULES)) / name
        if entry.is_symlink() or entry.is_file():
            entry.unlink()
        elif entry.is_dir():
            shutil.rmtree(entry)

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
        actual = Path(self.manager.resolve_profile_plugin_dir('dsh-demo'))
        self.assertEqual('2.0.0', json.loads((actual / 'package.json').read_text())['version'])
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


class OfficialInstallTransactionTest(PluginTestBase):
    def legacy(self, version='1.0.0', name='dsh-demo', source='npm:dsh-demo@1.0.0'):
        incoming = self.package(self.root / ('legacy-' + version), name, version)
        (incoming / 'old-only.txt').write_bytes(b'old package bytes\x00')
        self.manager.register_plugin(incoming, source)
        return self.home / 'plugin-src' / name

    def installed_version(self, name='dsh-demo'):
        directory = self.manager.resolve_profile_plugin_dir(name)
        return self.manager.read_json(os.path.join(directory, 'package.json'))['version'] if directory else None

    def tree_state(self, path):
        """记录实体字节和链接本身，不跟随目录软链。"""
        path = Path(path)
        if path.is_symlink():
            return ('link', os.readlink(path))
        if not path.exists():
            return ('missing',)
        if path.is_file():
            return ('file', path.read_bytes())
        return ('directory', {child.name: self.tree_state(child) for child in sorted(path.iterdir())})

    def test_profile_wins_over_stale_legacy_for_list_and_export(self):
        self.legacy()
        self.fake_command(['dsh', 'plugin', '--profile', 'web', 'add', 'dsh-demo@2.0.0'],
                          cwd=str(self.home / 'profiles/web'), env={'DSH_HOME': str(self.home)})
        active = self.home / 'profiles/web/node_modules/dsh-demo'
        self.assertEqual(str(active), self.manager.resolve_plugin_dir('dsh-demo'))
        self.manager.cmd_list()
        item = next(item for item in json.loads(self.out.getvalue().split('PLUGIN_RESULT: ')[-1])['items']
                    if item['name'] == 'dsh-demo')
        self.assertEqual('2.0.0', item['version'])
        self.assertEqual('/root/.dsh/profiles/web/node_modules/dsh-demo', item['installPath'])
        export = self.root / 'actual.tar.gz'
        self.manager.cmd_export('["dsh-demo"]', str(export))
        with tarfile.open(export) as archive:
            self.assertEqual('2.0.0', json.load(archive.extractfile('plugins/dsh-demo/package.json'))['version'])

    def test_registered_missing_entry_does_not_fall_back_to_old_directory(self):
        self.legacy()
        self.remove_profile_entry('dsh-demo')
        self.assertIsNone(self.manager.resolve_plugin_dir('dsh-demo'))
        with self.assertRaisesRegex(ValueError, '无法备份'):
            self.manager.lifecycle().install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        self.assertEqual([], self.command_calls)

    def test_dev_or_optional_only_missing_profile_entry_does_not_use_stale_legacy(self):
        for section in ('devDependencies', 'optionalDependencies'):
            with self.subTest(section=section):
                name = 'dsh-missing-' + section.lower()
                legacy = self.legacy(name=name)
                doc = self.manager.builtin.read_manifest()
                dependency = doc['dependencies'].pop(name)
                doc.setdefault(section, {})[name] = dependency
                self.manager.builtin.write_manifest(doc)
                self.remove_profile_entry(name)
                before = self.tree_state(legacy)
                manifest = (self.home / 'profiles/web/package.json').read_bytes()
                self.assertIsNone(self.manager.resolve_plugin_dir(name))
                with patch.object(self.manager, 'cmd_official_add', wraps=self.manager.cmd_official_add) as add:
                    with self.assertRaisesRegex(ValueError, '无法备份|缺失'):
                        self.manager.lifecycle().install_package(name + '@2.0.0', name, '2.0.0')
                add.assert_not_called()
                self.assertEqual([], self.command_calls)
                self.assertEqual(before, self.tree_state(legacy))
                self.assertEqual(manifest, (self.home / 'profiles/web/package.json').read_bytes())
                self.assertFalse(os.path.lexists(self.home / 'profiles/web/node_modules' / name))

    def test_dev_or_optional_only_disabled_plugin_stays_disabled_after_install(self):
        for section in ('devDependencies', 'optionalDependencies'):
            with self.subTest(section=section):
                name = 'dsh-disabled-' + section.lower()
                self.package(self.home / 'profiles/web/node_modules' / name, name, '1.0.0')
                self.manager.builtin.write_manifest({section: {name: '1.0.0'},
                                                    'dsh': {'profile': {'bundles': []}}})
                self.assertFalse(Path(self.manager.builtin.marker_path(name)).exists())
                self.manager.lifecycle().install_package(name + '@2.0.0', name, '2.0.0')
                self.assertEqual('2.0.0', self.installed_version(name))
                self.assertNotIn(name, self.manager.builtin.read_manifest()['dsh']['profile']['bundles'])
                self.assertFalse(Path(self.manager.builtin.marker_path(name)).exists())

    def test_snapshot_precedes_add_and_migration_preserves_state_and_bytes(self):
        legacy = self.legacy()
        self.manager.builtin.disable_plugin('dsh-demo')
        old_marker = Path(self.manager.builtin.marker_path('dsh-demo'))
        old_marker.write_bytes(b'safe-mode-off')
        old_bytes = (legacy / 'old-only.txt').read_bytes()
        life = self.manager.lifecycle()
        def checked_add(args, **kwargs):
            snapshots = list((self.home / 'plugin-transactions').glob('*/backup/state.json'))
            self.assertEqual(1, len(snapshots))
            state = json.loads(snapshots[0].read_text())
            self.assertEqual('1.0.0', state['version'])
            self.assertEqual('npm:dsh-demo@1.0.0', state['source'])
            self.assertFalse(state['enabled'])
            with tarfile.open(state['archive']) as archive:
                self.assertEqual(old_bytes, archive.extractfile('package/old-only.txt').read())
            return self.fake_command(args, **kwargs)
        with patch.object(self.manager.subprocess, 'run', side_effect=checked_add):
            outcome = life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0', source='npm:dsh-demo@2.0.0')
        self.assertEqual('2.0.0', self.installed_version())
        self.assertFalse(legacy.exists())
        retired = Path(self.manager.local(outcome['retiredPath'])) / 'package'
        self.assertEqual(old_bytes, (retired / 'old-only.txt').read_bytes())
        self.assertEqual('1.0.0', life.history_info('dsh-demo')['version'])
        self.assertEqual('npm:dsh-demo@2.0.0', self.manager.read_json(self.home / 'plugin-sources.json')['dsh-demo'])
        self.assertEqual(b'safe-mode-off', old_marker.read_bytes())
        self.assertNotIn('dsh-demo', self.manager.builtin.read_manifest()['dsh']['profile']['bundles'])
        self.assertEqual((self.home / 'profiles/web/node_modules/dsh-demo').resolve(),
                         (self.root / 'usr/local/lib/node_modules/dsh-demo').resolve())
        self.manager.builtin.ensure_global_plugin_links()
        self.assertFalse(legacy.exists())

    def test_scoped_old_directory_is_isolated_without_touching_scope_siblings(self):
        name = '@sample/demo'
        legacy = self.legacy(name=name)
        sibling = self.package(self.home / 'plugin-src/@sample/keep', '@sample/keep')
        result = self.manager.lifecycle().install_package(name + '@2.0.0', name, '2.0.0')
        self.assertFalse(legacy.exists())
        self.assertTrue(sibling.is_dir())
        self.assertTrue((Path(self.manager.local(result['retiredPath'])) / 'package/old-only.txt').is_file())

    def test_shared_old_directory_is_preserved_with_warning(self):
        legacy = self.legacy()
        other = self.home / 'profiles/other'
        other.mkdir(parents=True)
        (other / 'package.json').write_text(json.dumps({'dependencies': {'dsh-demo': 'link:/root/.dsh/plugin-src/dsh-demo'}}))
        result = self.manager.lifecycle().install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        self.assertTrue(legacy.is_dir())
        self.assertTrue(any('仍被 profile 引用' in warning for warning in result['warnings']))
        self.assertEqual('2.0.0', self.installed_version())

    def test_other_profile_dependency_groups_and_home_links_preserve_old_directory(self):
        for section in ('dependencies', 'devDependencies', 'optionalDependencies'):
            for prefix in ('link:/root/.dsh/plugin-src/', 'link:~/.dsh/plugin-src/'):
                with self.subTest(section=section, prefix=prefix):
                    name = 'dsh-shared-' + section.lower() + ('-home' if '~' in prefix else '-absolute')
                    legacy = self.legacy(name=name)
                    before = self.tree_state(legacy)
                    other = self.home / 'profiles' / ('other-' + name)
                    other.mkdir(parents=True)
                    metadata = other / 'package.json'
                    metadata.write_text(json.dumps({section: {name: prefix + name}}))
                    manifest_bytes = metadata.read_bytes()
                    calls = len(self.command_calls)
                    result = self.manager.lifecycle().install_package(name + '@2.0.0', name, '2.0.0')
                    self.assertEqual(before, self.tree_state(legacy))
                    self.assertEqual(manifest_bytes, metadata.read_bytes())
                    self.assertFalse((other / 'node_modules').exists())
                    self.assertFalse(result.get('retiredPath'))
                    self.assertTrue(any('仍被 profile 引用' in warning for warning in result['warnings']))
                    self.assertEqual('2.0.0', self.installed_version(name))
                    self.assertEqual([['dsh', 'plugin', '--profile', 'web', 'add', name + '@2.0.0']],
                                     self.command_calls[calls:])

    def test_external_source_directory_is_preserved(self):
        external = self.package(self.root / 'workspace/source', version='1.0.0')
        entry = self.home / 'profiles/web/node_modules/dsh-demo'
        entry.parent.mkdir(parents=True)
        os.symlink(external, entry)
        self.manager.builtin.write_manifest({'dependencies': {'dsh-demo': 'link:' + str(external)},
                                            'dsh': {'profile': {'bundles': ['dsh-demo']}}})
        result = self.manager.lifecycle().install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        self.assertTrue((external / 'index.js').is_file())
        self.assertEqual('2.0.0', self.installed_version())
        self.assertTrue(result.get('warnings'))

    def test_failed_add_after_modification_restores_snapshot_and_old_history(self):
        life = self.manager.lifecycle()
        life.install_package('dsh-demo@1.0.0', 'dsh-demo', '1.0.0', source='npm:dsh-demo@1.0.0')
        life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0', source='npm:dsh-demo@2.0.0')
        self.manager.builtin.disable_plugin('dsh-demo')
        def fail_new(args, **kwargs):
            result = self.fake_command(args, **kwargs)
            if args[5] == 'dsh-demo@3.0.0':
                return self.manager.subprocess.CompletedProcess(args, 1, '', 'partial installation failed')
            return result
        with patch.object(self.manager.subprocess, 'run', side_effect=fail_new):
            with self.assertRaisesRegex(ValueError, '已恢复'):
                life.install_package('dsh-demo@3.0.0', 'dsh-demo', '3.0.0', source='npm:dsh-demo@3.0.0')
        self.assertEqual('2.0.0', self.installed_version())
        self.assertEqual('1.0.0', life.history_info('dsh-demo')['version'])
        self.assertEqual('npm:dsh-demo@2.0.0', self.manager.read_json(self.home / 'plugin-sources.json')['dsh-demo'])
        self.assertNotIn('dsh-demo', self.manager.builtin.read_manifest()['dsh']['profile']['bundles'])

    def test_success_with_wrong_version_is_rejected_and_restored(self):
        self.legacy()
        def wrong_version(args, **kwargs):
            result = self.fake_command(args, **kwargs)
            if args[5] == 'dsh-demo@2.0.0':
                metadata = self.home / 'profiles/web/node_modules/dsh-demo/package.json'
                pkg = json.loads(metadata.read_text()); pkg['version'] = '999.0.0'
                metadata.write_text(json.dumps(pkg))
            return result
        with patch.object(self.manager.subprocess, 'run', side_effect=wrong_version):
            with self.assertRaisesRegex(ValueError, '版本'):
                self.manager.lifecycle().install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        self.assertEqual('1.0.0', self.installed_version())
        self.assertEqual(b'old package bytes\x00', (Path(self.manager.resolve_plugin_dir('dsh-demo')) / 'old-only.txt').read_bytes())

    def test_timeout_preserves_snapshot_without_retry_or_cleanup(self):
        legacy = self.legacy()
        with patch.object(self.manager.subprocess, 'run', side_effect=self.manager.subprocess.TimeoutExpired('dsh', 600)) as command:
            with self.assertRaisesRegex(self.manager.OfficialInstallUncertain, '回查'):
                self.manager.lifecycle().install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        self.assertEqual(1, command.call_count)
        self.assertTrue(legacy.exists())
        self.assertTrue(list((self.home / 'plugin-transactions').glob('*/backup/state.json')))

    def test_recovery_failure_keeps_old_archive_and_prior_history(self):
        life = self.manager.lifecycle()
        life.install_package('dsh-demo@1.0.0', 'dsh-demo', '1.0.0')
        life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        failed = self.manager.subprocess.CompletedProcess([], 1, '', 'disk full')
        with patch.object(self.manager.subprocess, 'run', return_value=failed):
            with self.assertRaisesRegex(RuntimeError, '恢复未完成'):
                life.install_package('dsh-demo@3.0.0', 'dsh-demo', '3.0.0')
        self.assertEqual('1.0.0', life.history_info('dsh-demo')['version'])
        snapshots = list((self.home / 'plugin-transactions').glob('*/backup/state.json'))
        self.assertTrue(snapshots)
        self.assertTrue(Path(json.loads(snapshots[-1].read_text())['archive']).is_file())

    def test_rollback_uses_official_archive_and_exchanges_previous_version(self):
        self.legacy()
        life = self.manager.lifecycle()
        life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0', source='npm:dsh-demo@2.0.0')
        with patch.object(self.manager, 'register_plugin', side_effect=AssertionError('旧安装路径不可用于回退')):
            life.rollback('dsh-demo', '1.0.0')
            self.assertEqual('1.0.0', self.installed_version())
            self.assertEqual('2.0.0', life.history_info('dsh-demo')['version'])
            self.assertEqual('npm:dsh-demo@1.0.0', self.manager.read_json(self.home / 'plugin-sources.json')['dsh-demo'])
            life.rollback('dsh-demo', '2.0.0')
            self.assertEqual('2.0.0', self.installed_version())
            self.assertEqual('1.0.0', life.history_info('dsh-demo')['version'])
        self.assertTrue(self.command_calls[-1][5].startswith('file:'))
        count = len(self.command_calls)
        with self.assertRaises(ValueError): life.rollback('dsh-demo', 'other-version')
        self.assertEqual(count, len(self.command_calls))

    def test_rollback_rejects_changed_archive_before_installing(self):
        life = self.manager.lifecycle()
        life.install_package('dsh-demo@1.0.0', 'dsh-demo', '1.0.0')
        life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        state = life.history_info('dsh-demo')
        Path(state['archive']).write_bytes(b'corrupt')
        count = len(self.command_calls)
        with self.assertRaisesRegex(ValueError, '校验'):
            life.rollback('dsh-demo')
        self.assertEqual(count, len(self.command_calls))
        self.assertEqual('2.0.0', self.installed_version())

    def test_install_and_rollback_do_not_reenter_operation_lock(self):
        original = self.manager.builtin.operation_lock
        depth = 0
        @contextlib.contextmanager
        def guarded():
            nonlocal depth
            self.assertEqual(0, depth, 'operation_lock 不可重入')
            depth += 1
            try:
                with original(): yield
            finally:
                depth -= 1
        with patch.object(self.manager.builtin, 'operation_lock', guarded):
            life = self.manager.lifecycle()
            life.install_package('dsh-demo@1.0.0', 'dsh-demo', '1.0.0')
            life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
            life.rollback('dsh-demo')

    def test_builtin_refresh_and_toggle_preserve_official_profile_package(self):
        name = 'dsh-mobile-lite'
        old = self.package(self.root / 'root/dsh-mobile-lite', name, '1.0.0')
        actual = self.package(self.home / 'profiles/web/node_modules/.pnpm/mobile/node_modules' / name, name, '2.0.0')
        entry = self.home / 'profiles/web/node_modules' / name
        os.symlink(actual, entry)
        dependency = 'file:/root/.dsh/plugin-archives/mobile.tgz'
        self.manager.builtin.write_manifest({'dependencies': {name: dependency}, 'dsh': {'profile': {'bundles': [name]}}})
        before = os.readlink(entry)
        for operation in (lambda: self.manager.builtin.disable_plugin(name), self.manager.builtin.register,
                          lambda: self.manager.builtin.enable_plugin(name), self.manager.builtin.ensure_global_plugin_links):
            operation()
            self.assertEqual(before, os.readlink(entry))
            self.assertEqual(dependency, self.manager.builtin.read_manifest()['dependencies'][name])
            self.assertEqual('2.0.0', self.manager.read_json(actual / 'package.json')['version'])
        self.assertTrue(old.is_dir())

    def test_unrecoverable_bundled_or_local_dependencies_stop_before_add(self):
        legacy = self.legacy()
        metadata = legacy / 'package.json'
        original = json.loads(metadata.read_text())
        for extra in ({'bundledDependencies': ['private-helper']},
                      {'bundleDependencies': True},
                      {'dependencies': {'private-helper': 'file:./private-helper'}},
                      {'optionalDependencies': {'private-helper': 'link:../private-helper'}}):
            with self.subTest(extra=extra):
                metadata.write_text(json.dumps(dict(original, **extra)))
                with self.assertRaisesRegex(ValueError, '备份|快照'):
                    self.manager.lifecycle().install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
                self.assertEqual([], self.command_calls)
                self.assertTrue((legacy / 'old-only.txt').exists())

    def test_old_required_files_in_excluded_trees_stop_before_official_add(self):
        for field in ('main', 'cordis.entry', 'dsh.bundle.patch'):
            for excluded in ('node_modules', '.cache'):
                for route in ('direct', 'file-symlink', 'directory-symlink'):
                    with self.subTest(field=field, excluded=excluded, route=route):
                        name = 'dsh-excluded-' + field.replace('.', '-') + '-' \
                            + excluded.lstrip('.').replace('_', '-') + '-' + route
                        legacy = self.legacy(name=name)
                        leaf = 'patch.yml' if field == 'dsh.bundle.patch' else 'index.js'
                        required = legacy / excluded / 'required' / leaf
                        required.parent.mkdir(parents=True)
                        required.write_bytes(b'[]' if field == 'dsh.bundle.patch' else b'export default {};')
                        if route == 'direct':
                            relative = required.relative_to(legacy).as_posix()
                        elif route == 'file-symlink':
                            relative = 'required-' + leaf
                            os.symlink(required.relative_to(legacy), legacy / relative)
                        else:
                            os.symlink(Path(excluded) / 'required', legacy / 'required-link')
                            relative = 'required-link/' + leaf
                        metadata = legacy / 'package.json'
                        package = json.loads(metadata.read_text())
                        if field == 'main':
                            package['main'] = relative
                        elif field == 'cordis.entry':
                            package['cordis'] = {'entry': relative}
                        else:
                            package['dsh']['bundle']['patch'] = relative
                        metadata.write_text(json.dumps(package))
                        self.manager.plugin_package(str(legacy))
                        before = self.tree_state(legacy)
                        manifest = (self.home / 'profiles/web/package.json').read_bytes()
                        with patch.object(self.manager, 'cmd_official_add', wraps=self.manager.cmd_official_add) as add:
                            with self.assertRaisesRegex(ValueError, '备份|快照|排除|归档|恢复'):
                                self.manager.lifecycle().install_package(name + '@2.0.0', name, '2.0.0')
                        add.assert_not_called()
                        self.assertEqual([], self.command_calls)
                        self.assertEqual(before, self.tree_state(legacy))
                        self.assertEqual(manifest, (self.home / 'profiles/web/package.json').read_bytes())
                        self.assertEqual('1.0.0', self.installed_version(name))

    def test_symlink_chain_through_excluded_tree_stops_before_official_add(self):
        for excluded in ('node_modules', '.cache'):
            with self.subTest(excluded=excluded):
                name = 'dsh-excluded-chain-' + excluded.lstrip('.').replace('_', '-')
                legacy = self.legacy(name=name)
                index = legacy / 'index.js'
                index.unlink()
                library = legacy / 'lib/index.js'
                library.parent.mkdir()
                library.write_bytes(b'export default {recoverable: true};')
                intermediate = legacy / excluded / 'stub/entry.js'
                intermediate.parent.mkdir(parents=True)
                os.symlink('../../lib/index.js', intermediate)
                os.symlink(Path(excluded) / 'stub/entry.js', index)
                self.assertEqual(library.resolve(), index.resolve())
                self.assertNotIn('dependencies', self.manager.plugin_package(str(legacy)))
                before = self.tree_state(legacy)
                manifest = (self.home / 'profiles/web/package.json').read_bytes()
                with self.assertRaisesRegex(ValueError, '备份|快照|排除|归档|恢复'):
                    self.manager.store_plugin_archive(str(legacy))
                with patch.object(self.manager, 'cmd_official_add', wraps=self.manager.cmd_official_add) as add:
                    with self.assertRaisesRegex(ValueError, '备份|快照|排除|归档|恢复'):
                        self.manager.lifecycle().install_package(name + '@2.0.0', name, '2.0.0')
                add.assert_not_called()
                self.assertEqual([], self.command_calls)
                self.assertEqual(before, self.tree_state(legacy))
                self.assertEqual(manifest, (self.home / 'profiles/web/package.json').read_bytes())
                self.assertEqual('1.0.0', self.installed_version(name))

    def test_history_commit_failure_restores_current_and_keeps_previous(self):
        life = self.manager.lifecycle()
        life.install_package('dsh-demo@1.0.0', 'dsh-demo', '1.0.0')
        life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        with patch.object(life, '_commit_history', side_effect=OSError('history disk full')):
            with self.assertRaisesRegex(ValueError, '已恢复'):
                life.install_package('dsh-demo@3.0.0', 'dsh-demo', '3.0.0')
        self.assertEqual('2.0.0', self.installed_version())
        self.assertEqual('1.0.0', life.history_info('dsh-demo')['version'])

    def test_source_commit_failure_restores_previous_source_and_package(self):
        self.legacy()
        life = self.manager.lifecycle()
        write = life.write
        failed = False
        def fail_once(path, doc):
            nonlocal failed
            if Path(path) == self.home / 'plugin-sources.json' and not failed:
                failed = True
                raise OSError('source write failed')
            return write(path, doc)
        with patch.object(life, 'write', side_effect=fail_once):
            with self.assertRaisesRegex(ValueError, '已恢复'):
                life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0', source='npm:dsh-demo@2.0.0')
        self.assertEqual('1.0.0', self.installed_version())
        self.assertEqual('npm:dsh-demo@1.0.0', self.manager.read_json(self.home / 'plugin-sources.json')['dsh-demo'])

    def test_foreign_global_link_is_preserved_during_old_directory_retirement(self):
        legacy = self.legacy()
        foreign = self.package(self.root / 'external-global', 'foreign-plugin')
        link = self.root / 'usr/local/lib/node_modules/dsh-demo'
        link.unlink(); os.symlink(foreign, link)
        self.manager.lifecycle().install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        self.assertFalse(legacy.exists())
        self.assertEqual(str(foreign), os.readlink(link))
        self.assertTrue((foreign / 'index.js').is_file())

    def test_retirement_failure_preserves_old_directory_and_verified_new_package(self):
        legacy = self.legacy()
        replace = self.manager.os.replace
        def fail_retirement(source, target):
            if Path(source) == legacy:
                raise OSError('old directory busy')
            return replace(source, target)
        with patch.object(self.manager.os, 'replace', side_effect=fail_retirement):
            result = self.manager.lifecycle().install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        self.assertEqual('2.0.0', self.installed_version())
        self.assertTrue((legacy / 'old-only.txt').is_file())
        self.assertTrue(result.get('warnings'))
        self.assertEqual('1.0.0', self.manager.lifecycle().history_info('dsh-demo')['version'])

    def test_unfinished_transaction_blocks_subsequent_install_and_rollback(self):
        self.legacy()
        life = self.manager.lifecycle()
        with patch.object(self.manager.subprocess, 'run', side_effect=self.manager.subprocess.TimeoutExpired('dsh', 600)):
            with self.assertRaises(self.manager.OfficialInstallUncertain):
                life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        with self.assertRaisesRegex(ValueError, '未完成|不确定'):
            life.install_package('dsh-other@1.0.0', 'dsh-other', '1.0.0')
        with self.assertRaisesRegex(ValueError, '未完成|不确定'):
            life.rollback('dsh-demo')
        self.assertEqual([], self.command_calls)

    def test_pending_guard_rejects_unfinished_and_corrupt_operation_records(self):
        work = self.home / 'plugin-transactions' / ('a' * 32)
        work.mkdir(parents=True)
        record = work / 'operation.json'
        payloads = [(phase, json.dumps({'name': 'dsh-demo', 'phase': phase})) for phase in (
            'uncertain', 'recovery-required', 'installing', 'verified', 'committing-history', 'restoring')]
        payloads += [('invalid-json', '{'), ('not-object', '[]'), ('missing-phase', '{}'),
                     ('unknown-phase', '{"phase":"unrecognized"}')]
        for label, payload in payloads:
            with self.subTest(record=label):
                record.write_text(payload)
                before = self.tree_state(work)
                with self.manager.builtin.operation_lock():
                    with self.assertRaisesRegex(RuntimeError, '事务|回查'):
                        self.manager.builtin.assert_no_pending_plugin_transactions()
                self.assertEqual(before, self.tree_state(work))
        record.unlink()
        os.symlink('missing-operation.json', record)
        with self.assertRaisesRegex(RuntimeError, '事务|回查'):
            self.manager.builtin.assert_no_pending_plugin_transactions()
        self.assertEqual('missing-operation.json', os.readlink(record))
        record.unlink()
        record.mkdir()
        with self.assertRaisesRegex(RuntimeError, '事务|回查'):
            self.manager.builtin.assert_no_pending_plugin_transactions()
        self.assertTrue(record.is_dir())

    def test_lifecycle_pending_check_delegates_to_builtin_guard(self):
        with patch.object(self.manager.builtin, 'assert_no_pending_plugin_transactions',
                          side_effect=RuntimeError('pending transaction fixture')) as guard:
            with self.assertRaisesRegex(ValueError, 'pending transaction fixture'):
                self.manager.lifecycle()._check_pending_transactions()
        guard.assert_called_once_with()

    def test_main_list_with_patch_does_not_reenter_operation_lock(self):
        self.legacy()
        patch_file = self.home / 'profiles/web/cordis.patch.yml'
        content = b'# user prefix\n# >>> DSHA managed\n- disabled: true\n# <<< DSHA managed\n# user suffix\n'
        original = self.manager.builtin.operation_lock
        for pending in (False, True):
            with self.subTest(pending=pending):
                patch_file.write_bytes(content)
                if pending:
                    work = self.home / 'plugin-transactions' / ('b' * 32)
                    work.mkdir(parents=True)
                    (work / 'operation.json').write_text(json.dumps({'phase': 'uncertain'}))
                depth = maximum = calls = 0
                @contextlib.contextmanager
                def guarded():
                    nonlocal depth, maximum, calls
                    calls += 1
                    maximum = max(maximum, depth + 1)
                    self.assertEqual(0, depth, 'list operation_lock 不可重入')
                    depth += 1
                    try:
                        with original():
                            yield
                    finally:
                        depth -= 1
                with patch.object(self.manager.builtin, 'operation_lock', guarded), \
                        patch.object(self.manager.sys, 'argv', [str(ASSET), 'list']):
                    self.assertEqual(0, self.manager.main())
                self.assertEqual(1, calls)
                self.assertEqual(1, maximum)
                self.assertEqual(0, depth)
                listing = json.loads(self.out.getvalue().split('PLUGIN_RESULT: ')[-1])
                self.assertTrue(any(item['name'] == 'dsh-demo' for item in listing['items']))
                self.assertEqual(content if pending else b'# user prefix\n# user suffix\n', patch_file.read_bytes())
                self.assertEqual([], self.command_calls)

    def test_uncertain_transaction_blocks_all_mutators_but_list_remains_read_only(self):
        self.legacy()
        self.legacy(name='dsh-disabled')
        self.manager.builtin.disable_plugin('dsh-disabled')
        marker = Path(self.manager.builtin.marker_path('dsh-disabled'))
        marker.write_bytes(b'DSHA_SAFE_MODE\n')
        builtin_source = self.package(self.root / 'root/dsh-mobile-lite', 'dsh-mobile-lite')
        safe_mode = self.home / 'plugin-safe-mode.json'
        safe_mode.write_text(json.dumps({'active': True, 'names': ['dsh-disabled']}))
        patch_file = self.home / 'profiles/web/cordis.patch.yml'
        patch_file.write_bytes(b'# user prefix\n# >>> DSHA managed\n- disabled: true\n# <<< DSHA managed\n# user suffix\n')
        incoming = self.package(self.root / 'blocked-update', version='2.0.0')
        new_plugin = self.package(self.root / 'blocked-new', 'dsh-new')
        life = self.manager.lifecycle()
        with patch.object(self.manager.subprocess, 'run', side_effect=self.manager.subprocess.TimeoutExpired('dsh', 600)):
            with self.assertRaises(self.manager.OfficialInstallUncertain):
                life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0')
        operations = list((self.home / 'plugin-transactions').glob('*/operation.json'))
        self.assertEqual(1, len(operations))
        self.assertEqual('uncertain', json.loads(operations[0].read_text())['phase'])
        # history_info 目前会创建空历史根目录，预建以专注验证已有状态不变。
        (self.home / 'plugin-history').mkdir(exist_ok=True)
        protected = [self.home / 'profiles', self.home / 'plugin-src',
                     self.home / 'plugin-sources.json', self.home / 'plugin-history',
                     self.home / 'plugin-transactions', self.home / 'plugin-archives',
                     safe_mode, self.root / 'usr/local/lib/node_modules', builtin_source]
        before = {str(path): self.tree_state(path) for path in protected}
        mutators = [
            ('builtin-register', self.manager.builtin.register, True),
            ('builtin-enable', lambda: self.manager.builtin.enable_plugin('dsh-disabled'), True),
            ('builtin-disable', lambda: self.manager.builtin.disable_plugin('dsh-demo'), True),
            ('safe-mode-on', lambda: life.safe_mode('on'), False),
            ('safe-mode-off', lambda: life.safe_mode('off'), False),
            ('legacy-update', lambda: self.manager.register_plugin(incoming, 'new-source'), False),
            ('legacy-register', lambda: self.manager.register_plugin(new_plugin, 'new-source'), False),
            ('legacy-delete', lambda: self.manager.cmd_delete('dsh-demo'), False),
        ]
        for name, mutate, returns_failure in mutators:
            with self.subTest(operation=name):
                if returns_failure:
                    self.assertEqual(1, mutate())
                else:
                    with self.assertRaisesRegex((ValueError, RuntimeError), '未完成|不确定|回查'):
                        mutate()
                self.assertEqual(before, {str(path): self.tree_state(path) for path in protected})
                self.assertEqual([], self.command_calls)
        self.manager.cmd_list()
        listing = json.loads(self.out.getvalue().split('PLUGIN_RESULT: ')[-1])
        items = {item['name']: item for item in listing['items']}
        self.assertEqual('1.0.0', items['dsh-demo']['version'])
        self.assertTrue(items['dsh-demo']['enabled'])
        self.assertFalse(items['dsh-disabled']['enabled'])
        self.assertEqual(before, {str(path): self.tree_state(path) for path in protected})
        self.assertEqual([], self.command_calls)

    def test_source_absence_and_old_enabled_state_are_restored_by_rollback(self):
        self.legacy()
        (self.home / 'plugin-sources.json').write_text('{}')
        self.manager.builtin.disable_plugin('dsh-demo')
        life = self.manager.lifecycle()
        life.install_package('dsh-demo@2.0.0', 'dsh-demo', '2.0.0', source='npm:dsh-demo@2.0.0')
        self.manager.builtin.enable_plugin('dsh-demo')
        life.rollback('dsh-demo')
        self.assertEqual('1.0.0', self.installed_version())
        self.assertNotIn('dsh-demo', self.manager.read_json(self.home / 'plugin-sources.json'))
        self.assertNotIn('dsh-demo', self.manager.builtin.read_manifest()['dsh']['profile']['bundles'])
        self.assertTrue(Path(self.manager.builtin.marker_path('dsh-demo')).is_file())

    def test_test_root_mapping_is_idempotent_for_archives(self):
        life = self.manager.lifecycle()
        life.install_package('dsh-demo@1.0.0', 'dsh-demo', '1.0.0')
        archive = self.manager.store_plugin_archive(self.manager.resolve_profile_plugin_dir('dsh-demo'))
        self.assertEqual(archive, self.manager.local(archive))
        self.assertTrue(Path(archive).is_file())
        self.assertTrue(self.manager.container_path(archive).startswith('/root/.dsh/plugin-archives/'))


if __name__ == "__main__":
    unittest.main()
