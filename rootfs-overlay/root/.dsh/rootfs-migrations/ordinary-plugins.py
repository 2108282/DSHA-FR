#!/usr/bin/env python3
"""将退役的固有插件迁为普通插件；--fresh 仅用于离线 Full 构建目录。"""
import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import shutil
import tempfile

NAME = "dsh-classic-chat"


def save(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     prefix=".ordinary-", delete=False) as stream:
        staged = Path(stream.name)
        try:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        except BaseException:
            staged.unlink(missing_ok=True)
            raise
    os.replace(staged, path)


@contextmanager
def operation_lock(root):
    home = Path(root).resolve() / "root/.dsh"
    if home.resolve() != home:
        raise ValueError("DSH 目录包含重定向软链")
    home.mkdir(parents=True, exist_ok=True)
    with (home / ".plugins.lock").open("a") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(stream, fcntl.LOCK_UN)


def migrate(root, fresh=False):
    with operation_lock(root):
        _migrate(root, fresh)


def _migrate(root, fresh=False):
    root = Path(root).resolve()
    if fresh and root == Path("/"):
        raise ValueError("--fresh 只能用于离线构建目录")
    home = root / "root/.dsh"
    legacy = root / "root" / NAME
    managed = home / "plugin-src" / NAME
    manifest = home / "profiles/web/package.json"
    builtin = root / "root/dsha-builtin.txt"
    links = [home / "profiles/web/node_modules" / NAME,
             root / "usr/local/lib/node_modules" / NAME]
    # 先验证配置，出错时不移动实体。迁移保持 bundles 与 disabled 标记原样。
    doc = json.loads(manifest.read_text()) if manifest.is_file() else None
    retired = (root / "root/dsha-classic-chat-installed").exists()
    names = builtin.read_text().splitlines() if builtin.is_file() else [
        "dsh-device-shell-guide", "dsh-task-notifier", "dsh-status-overlay",
        "dsh-mobile-lite", "dsh-quickchat-gesture",
    ]
    retired = retired or bool(names and NAME in [line.strip() for line in names])
    if legacy.is_symlink() or managed.is_symlink():
        raise ValueError("插件实体为软链，请先核对真实目录：" + NAME)
    for path in [legacy, managed, *links, manifest, builtin,
                 home / "plugin-migration-backups" / NAME]:
        if path.parent.resolve() != path.parent:
            raise ValueError("插件目录包含重定向软链，停止迁移：" + str(path))
    needs_links = not fresh and (legacy.is_dir() or managed.is_dir())
    for link in links:
        if needs_links and link.exists() and not link.is_symlink():
            raise ValueError("模块链接位置存在实体目录，停止迁移：" + str(link))

    if fresh:
        # 公开 Full 镜像不携带退役插件，也不带入旧底包的注册和源码。
        for path in [legacy, managed]:
            if path.exists():
                shutil.rmtree(path)
        if doc is not None:
            doc.get("dependencies", {}).pop(NAME, None)
            profile = doc.get("dsh", {}).get("profile", {})
            if "bundles" in profile:
                profile["bundles"] = [item for item in profile["bundles"] if item != NAME]
    elif legacy.is_dir() and (retired or (doc or {}).get("dependencies", {}).get(NAME) == "link:/root/" + NAME):
        # 固有插件原来以 /root 实体为权威；不拿旧 plugin-src 副本覆盖当前版本。
        managed.parent.mkdir(parents=True, exist_ok=True)
        dep = (doc or {}).get("dependencies", {}).get(NAME, "")
        # 用户若已经显式安装普通版，保留该版本，旧底模只留备份。
        use_managed = managed.is_dir() and dep == "link:/root/.dsh/plugin-src/" + NAME
        backup_root = home / "plugin-migration-backups"
        backup = None
        if managed.exists():
            backup_root.mkdir(parents=True, exist_ok=True)
            backup = Path(tempfile.mkdtemp(prefix=NAME + "-", dir=backup_root))
            if use_managed:
                os.replace(legacy, backup / "previous-builtin")
            else:
                backup = backup / "previous-plugin-src"
                os.replace(managed, backup)
        if not use_managed:
            try:
                os.replace(legacy, managed)
            except OSError:
                if backup is not None:
                    os.replace(backup, managed)
                raise

    if not fresh and doc is not None:
        deps = doc.get("dependencies", {})
        if deps.get(NAME) == "link:/root/" + NAME and managed.is_dir():
            deps[NAME] = "link:/root/.dsh/plugin-src/" + NAME
        # 仅修复已安装插件，不重新安装用户删掉的插件。
        installed = NAME in deps or NAME in doc.get("dsh", {}).get("profile", {}).get("bundles", [])
        if installed and managed.is_dir():
            for link in links:
                link.parent.mkdir(parents=True, exist_ok=True)
                link.unlink(missing_ok=True)
                link.symlink_to("/root/.dsh/plugin-src/" + NAME, target_is_directory=True)
    if fresh:
        for link in links:
            if link.is_dir() and not link.is_symlink():
                shutil.rmtree(link)
            else:
                link.unlink(missing_ok=True)
        (home / "profiles/web/node_modules" / (NAME + ".disabled")).unlink(missing_ok=True)
    if doc is not None:
        text = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
        if manifest.read_text() != text:
            save(manifest, text)
    if names is not None:
        text = "\n".join(line for line in names if line.strip() != NAME) + "\n"
        if not builtin.is_file() or builtin.read_text() != text:
            save(builtin, text)
    (root / "root/dsh-classic-chat-installed").unlink(missing_ok=True)
    print("ORDINARY_PLUGIN_MIGRATION_OK: " + NAME + (" (Full: 不预装)" if fresh else ""))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default="/")
    parser.add_argument("--fresh", action="store_true")
    args = parser.parse_args()
    migrate(args.root, args.fresh)
