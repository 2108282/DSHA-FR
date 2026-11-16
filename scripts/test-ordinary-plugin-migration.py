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


if __name__ == "__main__":
    unittest.main()
