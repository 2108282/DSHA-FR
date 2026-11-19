#!/usr/bin/env python3
"""仅使用假 root 验证 Full 清理和 Lite 迁移，不访问运行中的 profile。"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import subprocess
import tarfile
import shutil
import zipfile
import unittest
import sys
import re
import shlex

sys.dont_write_bytecode = True
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "rootfs-overlay/root/.dsh"
NAME = "dsh-classic-chat"


def load(path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class OrdinaryMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / "root/.dsh"
        self.legacy = self.root / "root" / NAME
        self.managed = self.home / "plugin-src" / NAME
        self.manifest = self.home / "profiles/web/package.json"
        self.manifest.parent.mkdir(parents=True)
        self.doc = {"dependencies": {NAME: "link:/root/" + NAME, "other": "1"},
                    "dsh": {"profile": {"bundles": ["other", NAME]}}}
        self.manifest.write_text(json.dumps(self.doc))
        (self.root / "root/dsha-builtin.txt").write_text("# keep\ndsh-mobile-lite\n" + NAME + "\n")
        (self.root / "root/dsh-classic-chat-installed").touch()
        self.package(self.legacy, "active-version")
        self.module = load(ASSETS / "rootfs-migrations/ordinary-plugins.py")
        self.capture = contextlib.redirect_stdout(io.StringIO())
        self.capture.__enter__()
        self.addCleanup(self.capture.__exit__, None, None, None)

    def package(self, path, version):
        path.mkdir(parents=True)
        (path / "package.json").write_text(json.dumps({"name": NAME, "version": version,
            "dsh": {"bundle": {"patch": "cordis.patch.yml"}}}))
        (path / "cordis.patch.yml").write_text("[]")

    def migrate(self, fresh=False):
        self.module.migrate(self.root, fresh)
        return json.loads(self.manifest.read_text())

    def test_lite_preserves_authoritative_version_state_and_conflict_backup(self):
        self.package(self.managed, "stale-copy")
        marker = self.manifest.parent / "node_modules" / (NAME + ".disabled")
        marker.parent.mkdir()
        marker.touch()
        self.doc["dsh"]["profile"]["bundles"].remove(NAME)
        self.manifest.write_text(json.dumps(self.doc))
        result = self.migrate()
        self.assertEqual(self.doc["dsh"], result["dsh"])
        self.assertTrue(marker.exists())
        self.assertEqual("link:/root/.dsh/plugin-src/" + NAME, result["dependencies"][NAME])
        self.assertEqual("active-version", json.loads((self.managed / "package.json").read_text())["version"])
        backup = list((self.home / "plugin-migration-backups").glob("*/previous-plugin-src/package.json"))
        self.assertEqual(1, len(backup))
        self.assertEqual("stale-copy", json.loads(backup[0].read_text())["version"])
        self.assertFalse(self.legacy.exists())
        for path in [marker.parent / NAME, self.root / "usr/local/lib/node_modules" / NAME]:
            self.assertEqual("/root/.dsh/plugin-src/" + NAME, os.readlink(path))
        before = self.manifest.read_bytes()
        self.migrate()
        self.assertEqual(before, self.manifest.read_bytes())
        self.assertEqual(backup, list((self.home / "plugin-migration-backups").glob("*/previous-plugin-src/package.json")))

    def test_enabled_stays_enabled_and_is_classified_as_deletable(self):
        self.assertIn(NAME, self.migrate()["dsh"]["profile"]["bundles"])
        with patch.dict(os.environ, {"DSHA_TEST_ROOT": str(self.root), "DSH_HOME": "/root/.dsh"}):
            manager = load(ASSETS / "plugin-manager.py")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            manager.cmd_list()
        payload = json.loads(output.getvalue().split("PLUGIN_RESULT: ")[-1])
        item = next(item for item in payload["items"] if item["name"] == NAME)
        self.assertFalse(item["builtin"])
        self.assertTrue(item["deletable"])
        self.assertTrue(item["enabled"])
        self.assertEqual("/root/.dsh/plugin-src/" + NAME, manager.builtin.entity_dir(NAME))

    def test_classic_can_be_deleted_and_migration_does_not_restore_it(self):
        self.migrate()
        with patch.dict(os.environ, {"DSHA_TEST_ROOT": str(self.root), "DSH_HOME": "/root/.dsh"}):
            manager = load(ASSETS / "plugin-manager.py")
        def remove_dependency(*args, **kwargs):
            doc = manager.builtin.read_manifest()
            doc["dependencies"].pop(NAME)
            manager.builtin.write_manifest(doc)
        with patch.object(manager.subprocess, "run", side_effect=remove_dependency):
            manager.cmd_delete(NAME)
        self.assertFalse(self.managed.exists())
        self.assertNotIn(NAME, self.migrate()["dependencies"])
        self.assertFalse(self.managed.exists())
        self.assertFalse(self.legacy.exists())

    def test_fresh_removes_both_copies_and_only_classic_registration(self):
        self.package(self.managed, "stale-copy")
        result = self.migrate(fresh=True)
        self.assertFalse(self.legacy.exists())
        self.assertFalse(self.managed.exists())
        self.assertEqual({"other": "1"}, result["dependencies"])
        self.assertEqual(["other"], result["dsh"]["profile"]["bundles"])
        self.assertNotIn(NAME, (self.root / "root/dsha-builtin.txt").read_text())
        self.assertFalse((self.root / "root/dsh-classic-chat-installed").exists())
        self.assertEqual(result, self.migrate(fresh=True))

    def test_fresh_unlinks_absolute_targets_without_following_them(self):
        protected = self.root / "protected"
        protected.mkdir()
        links = [self.manifest.parent / "node_modules" / NAME,
                 self.root / "usr/local/lib/node_modules" / NAME]
        for link in links:
            link.parent.mkdir(parents=True, exist_ok=True)
            link.symlink_to(protected / "target")
        original_stat = Path.stat
        def protected_stat(path, *, follow_symlinks=True):
            if path in links and follow_symlinks:
                raise PermissionError("fixture: symlink target is inaccessible")
            return original_stat(path, follow_symlinks=follow_symlinks)
        protected.chmod(0)
        try:
            with patch.object(Path, "stat", protected_stat):
                self.module.migrate(self.root, fresh=True)
            for link in links:
                self.assertFalse(link.is_symlink())
            self.assertTrue(protected.exists())
        finally:
            protected.chmod(0o700)

    def test_deleted_plugin_is_not_reinstalled(self):
        self.migrate(fresh=True)
        self.assertNotIn(NAME, self.migrate()["dependencies"])
        self.assertFalse(self.managed.exists())

    def test_bad_manifest_and_symlinks_fail_before_moving_plugin(self):
        self.manifest.write_text("invalid")
        with self.assertRaises(ValueError):
            self.migrate()
        self.assertTrue(self.legacy.is_dir())
        self.manifest.write_text(json.dumps(self.doc))
        self.managed.parent.mkdir()
        self.managed.symlink_to(self.legacy)
        with self.assertRaises(ValueError):
            self.migrate()
        self.assertTrue(self.legacy.is_dir())

    def test_existing_ordinary_install_wins_over_retired_builtin(self):
        self.package(self.managed, "user-installed")
        self.doc["dependencies"][NAME] = "link:/root/.dsh/plugin-src/" + NAME
        self.manifest.write_text(json.dumps(self.doc))
        self.migrate()
        self.assertEqual("user-installed", json.loads((self.managed / "package.json").read_text())["version"])
        self.assertFalse(self.legacy.exists())
        self.assertEqual(1, len(list((self.home / "plugin-migration-backups").glob("*/previous-builtin/package.json"))))

    def test_npm_ordinary_install_is_untouched_but_excluded_from_fresh(self):
        self.migrate(fresh=True)
        npm = self.manifest.parent / "node_modules" / NAME
        self.package(npm, "npm-version")
        self.doc["dependencies"][NAME] = "^2.0.0"
        self.manifest.write_text(json.dumps(self.doc))
        self.assertEqual("^2.0.0", self.migrate()["dependencies"][NAME])
        self.assertTrue(npm.is_dir())
        self.migrate(fresh=True)
        self.assertFalse(npm.exists())

    def test_full_merge_cleans_old_base_before_plugin_scan(self):
        base = self.root / "base.tar.gz"
        output = self.root / "merged.tar.gz"
        with tarfile.open(base, "w:gz") as archive:
            archive.add(self.root / "root", arcname="root")
        subprocess.run(["bash", str(REPO / "tools/dynamic-rootfs-merge.sh"), str(base),
                        str(REPO / "rootfs-overlay"), str(output)], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        with tarfile.open(output) as archive:
            names = archive.getnames()
            self.assertFalse(any(NAME in name for name in names))
            manifest = json.load(archive.extractfile("./root/.dsh/profiles/web/package.json"))
            self.assertNotIn(NAME, manifest["dependencies"])
            self.assertTrue(any("dsh-mobile-lite/package.json" in name for name in names))

    def test_full_rebuild_drops_retired_entries_from_existing_zip(self):
        project = self.root / "project"
        module = project / "magisk-module"
        (module / "scripts").mkdir(parents=True)
        (module / "META-INF").mkdir()
        (project / "scripts").mkdir()
        (project / "dist").mkdir()
        for name in ["customize.sh", "service.sh", "action.sh", "uninstall.sh", "module.prop", "scripts/start.sh"]:
            (module / name).write_text("# fixture\n")
        shutil.copy2(REPO / "scripts/build-module.sh", project / "scripts/build-module.sh")
        with tarfile.open(project / "rootfs.tar.gz", "w:gz"):
            pass
        output = project / "dist/dsha_ksu_native_full.zip"
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("rootfs-overlay/root/" + NAME + "/package.json", "old")
        result = subprocess.run(["bash", str(project / "scripts/build-module.sh"), "full"],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.assertEqual(0, result.returncode, result.stdout)
        with zipfile.ZipFile(output) as archive:
            self.assertFalse(any(NAME in name for name in archive.namelist()))
            self.assertIn("rootfs.tar.gz", archive.namelist())
            self.assertFalse(any(name.startswith("patches/") for name in archive.namelist()))

    def test_missing_builtin_file_gets_nonclassic_defaults(self):
        (self.root / "root/dsha-builtin.txt").unlink()
        self.migrate()
        self.assertNotIn(NAME, (self.root / "root/dsha-builtin.txt").read_text())
        self.assertIn("dsh-quickchat-gesture", (self.root / "root/dsha-builtin.txt").read_text())


class DistributionCleanupTest(unittest.TestCase):
    """只打包临时项目、运行隔离的日志片段和假 root 迁移。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="dsha-cleanup-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_lite_contains_current_guards_without_retired_preinstall_or_templates(self):
        project = self.root / "project"
        module = project / "magisk-module"
        shutil.copytree(REPO / "magisk-module", module)
        shutil.copytree(REPO / "rootfs-overlay", project / "rootfs-overlay")
        (project / "scripts").mkdir()
        shutil.copy2(REPO / "scripts/build-module.sh", project / "scripts/build-module.sh")
        result = subprocess.run(["bash", str(project / "scripts/build-module.sh"), "--lite"],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        with zipfile.ZipFile(project / "dist/dsha_ksu_native_lite.zip") as archive:
            names = archive.namelist()
            self.assertIn("scripts/term.sh", names)
            self.assertIn("patches/patch-sync-builtin-single-source-and-bakein.sh", names)
            self.assertFalse(any("patch-sync-rm-one-file-system-guard" in name for name in names))
            self.assertFalse(any("patch-sync-quickchat-gesture-builtin" in name for name in names))
            self.assertFalse(any("dsh-classic-chat-installed" in name for name in names))
            builtin = archive.read("rootfs-overlay/root/dsha-builtin.txt").decode().splitlines()
            self.assertNotIn(NAME, builtin)
            self.assertIn("dsh-quickchat-gesture", builtin)
            guard = archive.read("rootfs-overlay/root/dsh-bin/rm").decode()
            self.assertIn("--one-file-system", guard)
            self.assertIn("__dsha_needs_confirm", guard)
            self.assertEqual((REPO / "scripts/term.sh").read_bytes(), archive.read("scripts/term.sh"))

    def test_log_layout_preserves_host_and_container_access_across_restarts(self):
        rootfs = self.root / "rootfs"
        run = self.root / "run"
        (rootfs / "root").mkdir(parents=True)
        run.mkdir()
        host_log = run / "dsh-web.log"
        container_log = rootfs / "root/dsh-web.log"
        host_log.write_text("previous log")
        container_log.symlink_to("/data/adb/dsha/run/dsh-web.log")
        source = (REPO / "scripts/start.sh").read_text()
        block = source.split("# 清空旧日志：", 1)[1].split("# 确保本机极速低功耗心跳", 1)[0]
        block = "# 清空旧日志：" + block
        env = dict(os.environ, ROOTFS=str(rootfs), RUN_DIR=str(run), LOG_FILE=str(host_log))
        for _ in range(2):
            result = subprocess.run(["bash", "-s"], input=block, env=env,
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertFalse(container_log.is_symlink())
            self.assertTrue(host_log.is_symlink())
            self.assertEqual(container_log.resolve(), host_log.resolve())
            self.assertEqual("", container_log.read_text())
            with host_log.open("a") as stream:
                stream.write("same log\n")
            self.assertEqual("same log\n", container_log.read_text())

    def test_export_preflight_leaves_official_dependency_layout_untouched(self):
        core = self.root / "official-dependencies"
        ignored = core / ".ignored_demo"
        ignored.mkdir(parents=True)
        (ignored / "package.json").write_text('{"name":"demo"}')
        original = core / "demo"
        original.symlink_to("unchanged-target")
        dependency = core / "other/node_modules/zod/index.js"
        dependency.parent.mkdir(parents=True)
        dependency.write_text("official dependency bytes")
        commands = self.root / "bin"
        commands.mkdir()
        dsh = commands / "dsh"
        dsh.write_text('#!/bin/sh\n[ "$1" = --version ] || exit 2\nprintf "fixture-version\\n"\n')
        dsh.chmod(0o755)
        source = (REPO / "tools/export-rootfs.sh").read_text()
        block = source.split('echo "==> 正在准备【公开发布纯净脱敏】打包..."', 1)[1]
        block = block.split("    # 1. 在临时 Stage", 1)[0]
        block = block.replace("/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai", str(core))
        env = dict(os.environ, PATH=str(commands) + os.pathsep + os.environ["PATH"])
        result = subprocess.run(["bash", "-s"], input="set -euo pipefail\n" + block,
                                env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(original.is_symlink())
        self.assertEqual("unchanged-target", os.readlink(original))
        self.assertEqual('{"name":"demo"}', (ignored / "package.json").read_text())
        self.assertEqual("official dependency bytes", dependency.read_text())
        install_root = self.root / "install-root"
        install_core = install_root / "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"
        install_core.parent.mkdir(parents=True)
        install_core.symlink_to(core, target_is_directory=True)
        installer = (REPO / "magisk-module/customize.sh").read_text()
        install_block = installer.split("# 保留数据安装也执行通用迁移；这里始终保留用户插件和启用状态。\n", 1)[1]
        install_block = install_block.split("# 确保 rootfs 基础目录与挂载保护点结构正确", 1)[0]
        env = dict(os.environ, ROOTFS_DIR=str(install_root))
        result = subprocess.run(["bash", "-s"], input="set -euo pipefail\n" + install_block,
                                env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(original.is_symlink())
        self.assertEqual("unchanged-target", os.readlink(original))
        self.assertEqual('{"name":"demo"}', (ignored / "package.json").read_text())
        self.assertEqual("official dependency bytes", dependency.read_text())

        official_html = install_root / "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html"
        official_html.parent.mkdir(parents=True)
        official_html.write_text("<html><head></head><body>official bytes</body></html>")
        startup = (REPO / "scripts/start.sh").read_text()
        bootstrap = startup.split("# 2.5 ", 1)[1].split("\n", 1)[1]
        bootstrap = bootstrap.split("# 3. 挂载原生虚拟文件系统", 1)[0]
        env = dict(os.environ, ROOTFS=str(install_root))
        result = subprocess.run(["bash", "-s"], input="set -euo pipefail\n" + bootstrap,
                                env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("<html><head></head><body>official bytes</body></html>", official_html.read_text())

    def test_publishing_replaces_only_old_generated_parts(self):
        clone = self.root / "clone"
        target = clone / "rootfs"
        target.mkdir(parents=True)
        for suffix in ["00", "01", "02"]:
            (target / ("rootfs.tar.gz.part-" + suffix)).write_text("old part")
        (target / "keep.txt").write_text("unrelated file")
        stage = self.root / "stage"
        stage.mkdir()
        (stage / "rootfs.tar.gz.part-00").write_text("new only part")
        source = (REPO / "scripts/publish-rootfs-asset.sh").read_text()
        block = 'mkdir -p "$CLONE_DIR/rootfs"' + source.split('mkdir -p "$CLONE_DIR/rootfs"', 1)[1]
        block = block.split('cd "$CLONE_DIR"', 1)[0]
        env = dict(os.environ, CLONE_DIR=str(clone), STAGE_DIR=str(stage))
        result = subprocess.run(["bash", "-s"], input="set -euo pipefail\n" + block,
                                env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["rootfs.tar.gz.part-00"], [p.name for p in target.glob("rootfs.tar.gz.part-*")])
        self.assertEqual("new only part", (target / "rootfs.tar.gz.part-00").read_text())
        self.assertEqual("unrelated file", (target / "keep.txt").read_text())

    def test_builtin_migration_uses_parent_stage_and_does_not_follow_stale_source_links(self):
        rootfs = self.root / "rootfs"
        home = rootfs / "root/.dsh"
        profile = home / "profiles/web/package.json"
        profile.parent.mkdir(parents=True)
        profile.write_text('{"dependencies":{},"dsh":{"profile":{"bundles":[]}}}')
        mobile = rootfs / "root/dsh-mobile-lite"
        stale = mobile / "src/client/effects/mobile-back-dispatcher.ts"
        stale.parent.mkdir(parents=True)
        stale.write_text("retired source")
        keep = stale.with_name("keep.ts")
        keep.write_text("unrelated source")
        protected = self.root / "protected/effects/mobile-back-dispatcher.js"
        protected.parent.mkdir(parents=True)
        protected.write_text("keep redirected source")
        (mobile / ".client-build").symlink_to(protected.parent.parent, target_is_directory=True)
        stage = self.root / "parent stage"
        engine = stage / "rootfs-overlay/root/.dsh/register-builtin-plugins.py"
        engine.parent.mkdir(parents=True)
        engine.write_text("# staged engine\n")
        installer = (REPO / "magisk-module/customize.lite.sh").read_text()
        stage_init = re.search(r'^TMP_STAGE=.*\n(?:export TMP_STAGE\n)?', installer, re.MULTILINE).group()
        stage_init = stage_init.replace('"/tmp/dsha_lite_stage_$$"', shlex.quote(str(stage)))
        command = stage_init + '\nexec sh "$1" "$2" "$3"\n'
        patch_path = REPO / "magisk-module/patches/patch-sync-builtin-single-source-and-bakein.sh"
        before_manifest = None
        for _ in range(2):
            result = subprocess.run(["sh", "-c", command, "fixture", str(patch_path),
                                     str(rootfs), str(self.root / "data")],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(engine.read_bytes(), (home / engine.name).read_bytes())
            self.assertFalse(stale.exists())
            self.assertEqual("unrelated source", keep.read_text())
            self.assertEqual("keep redirected source", protected.read_text())
            if before_manifest is not None:
                self.assertEqual(before_manifest, profile.read_bytes())
            before_manifest = profile.read_bytes()


if __name__ == "__main__":
    unittest.main()
