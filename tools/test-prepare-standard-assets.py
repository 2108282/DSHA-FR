#!/usr/bin/env python3
"""Exercise asset synchronization with temporary fixtures, without an APK build."""
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("prepare-standard-assets.py")


class StandardAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="dsha-assets-test-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.source = self.base / "source"
        self.output = self.base / "build" / "assets"
        self.source.mkdir()

    def put(self, root, relative, content="fixture"):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def prepare(self, source=None, output=None, success=True):
        result = subprocess.run(
            [sys.executable, "-B", str(SCRIPT), "--source", str(source or self.source),
             "--output", str(output or self.output)],
            capture_output=True, text=True, timeout=30)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_removes_deleted_assets_and_updates_current_files(self):
        current = self.put(self.source, "nested/current.sh", "original")
        obsolete = self.put(self.source, "obsolete.sh")
        self.prepare()
        obsolete.unlink()
        current.write_text("updated", encoding="utf-8")
        self.put(self.output, "old-directory/stale.sh")
        self.prepare()
        self.assertFalse((self.output / "obsolete.sh").exists())
        self.assertFalse((self.output / "old-directory").exists())
        self.assertEqual((self.output / "nested/current.sh").read_text(), "updated")
        self.assertEqual(current.read_text(), "updated")

    def test_excludes_legacy_python_and_bytecode(self):
        self.put(self.source, "runtime-python/python3.14")
        self.put(self.source, "__pycache__/stale.pyc")
        self.put(self.output, "runtime-python/python3.14")
        self.put(self.output, "runtime-python/python-runtime.tgz")
        self.prepare()
        self.assertEqual(list(self.output.iterdir()), [])
        self.assertTrue((self.source / "runtime-python/python3.14").is_file())

    def test_archive_remapping_and_removed_archive(self):
        archive = self.put(self.source, "glibc-python.tar.gz", "compressed fixture")
        self.prepare()
        self.assertEqual((self.output / "glibc-python.bin").read_text(), "compressed fixture")
        self.assertFalse((self.output / "glibc-python.tar.gz").exists())
        archive.unlink()
        self.prepare()
        self.assertFalse((self.output / "glibc-python.bin").exists())

    def test_cached_rootfs_still_prunes_obsolete_assets(self):
        with tarfile.open(self.source / "offline-rootfs.bin", "w:gz") as archive:
            data = b"native fixture"
            entry = tarfile.TarInfo("root/current.txt")
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
        self.prepare()
        optimized = self.output / "offline-rootfs.bin"
        before = optimized.read_bytes()
        report = self.output.parent / "standard-assets-report.json"
        self.assertIn("inputs", json.loads(report.read_text()))
        self.put(self.output, "retired-patch.sh")
        result = self.prepare()
        self.assertIn("复用", result.stdout)
        self.assertEqual(optimized.read_bytes(), before)
        self.assertFalse((self.output / "retired-patch.sh").exists())
        (self.source / "offline-rootfs.bin").unlink()
        self.prepare()
        self.assertFalse(optimized.exists())
        self.assertFalse(report.exists())

    def test_rejects_overlapping_directories_without_deleting_inputs(self):
        sentinel = self.put(self.source, "keep.txt")
        for output in (self.source, self.source / "nested", self.base):
            with self.subTest(output=output):
                self.prepare(output=output, success=False)
                self.assertEqual(sentinel.read_text(), "fixture")

    def test_missing_source_does_not_clean_existing_output(self):
        sentinel = self.put(self.output, "keep.txt")
        self.prepare(source=self.base / "missing", success=False)
        self.assertEqual(sentinel.read_text(), "fixture")

    def test_stale_symlink_does_not_delete_its_target(self):
        target = self.put(self.base, "outside/keep.txt")
        self.output.mkdir(parents=True)
        link = self.output / "obsolete"
        link.symlink_to(target.parent, target_is_directory=True)
        self.prepare()
        self.assertFalse(link.is_symlink())
        self.assertEqual(target.read_text(), "fixture")


if __name__ == "__main__":
    unittest.main()
