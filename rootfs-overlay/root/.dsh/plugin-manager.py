#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 插件包导入/导出/链接安装。末行 PLUGIN_RESULT JSON 是唯一操作结果。
仅安装声明 dsh.bundle.patch 的发布包；安装依赖不执行 prepare/build 脚本。
"""
import importlib.util
from contextlib import contextmanager
import hashlib
import json
import os
import re
import shutil
import ssl
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

spec = importlib.util.spec_from_file_location("builtin", os.path.join(
    os.path.dirname(__file__), "register-builtin-plugins.py"))
builtin = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builtin)
local = builtin.local
DSH_HOME = builtin.DSH_HOME
PLUGIN_SRC = os.path.join(DSH_HOME, "plugin-src")
SOURCES = os.path.join(DSH_HOME, "plugin-sources.json")
MAX_DOWNLOAD = 256 * 1024 * 1024
MAX_EXPANDED = 768 * 1024 * 1024
MAX_FILES = 50000

_lifecycle = None


def lifecycle():
    global _lifecycle
    if _lifecycle is None:
        module_spec = importlib.util.spec_from_file_location("plugin_lifecycle", os.path.join(os.path.dirname(__file__), "plugin-lifecycle.py"))
        module = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(module)
        _lifecycle = module.Lifecycle(globals())
    return _lifecycle


def result(status, message, **extra):
    print("PLUGIN_RESULT: " + json.dumps(dict(status=status, message=message, **extra),
                                         ensure_ascii=False), flush=True)


def read_json(path, default=None):
    if not os.path.isfile(path):
        return default
    with open(path, encoding="utf-8-sig") as stream:
        return json.load(stream)


def write_json(path, doc):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as stream:
        json.dump(doc, stream, indent=2, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)


def repository_url(pkg):
    value = pkg.get("repository", "")
    if isinstance(value, dict):
        value = value.get("url", "")
    if not isinstance(value, str):
        return ""
    value = value.removeprefix("git+")
    if value.startswith("git@github.com:"):
        value = "https://github.com/" + value[len("git@github.com:"):]
    if value.endswith(".git"):
        value = value[:-4]
    uri = urllib.parse.urlsplit(value)
    return value if uri.scheme == "https" and uri.hostname and not uri.username else ""


def safe_target(root, name):
    name = name.replace("\\", "/")
    parts = name.split("/")
    if not name or name.startswith("/") or any(p == ".." or ":" in p for p in parts):
        raise ValueError("归档含越界路径：" + name)
    target = os.path.realpath(os.path.join(root, name))
    if os.path.commonpath([os.path.realpath(root), target]) != os.path.realpath(root):
        raise ValueError("归档路径越界：" + name)
    return target


def extract_archive(path, staging):
    """先校验所有条目，再解包；内部软链接延迟建立，不允许链接写出暂存目录。"""
    size, count, links = 0, 0, []
    os.makedirs(staging, exist_ok=True)

    def check(name, length):
        nonlocal size, count
        target = safe_target(staging, name)
        size += length
        count += 1
        if size > MAX_EXPANDED or count > MAX_FILES:
            raise ValueError("插件包解压后过大（上限 768 MiB / 50000 个文件）")
        return target

    def copy(src, target, mode):
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.lexists(target):
            raise ValueError("归档包含重复文件：" + os.path.relpath(target, staging))
        with open(target, "wb") as dst:
            shutil.copyfileobj(src, dst, 65536)
        os.chmod(target, 0o755 if mode & 0o111 else 0o644)

    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            entries = [(i, check(i.filename, i.file_size)) for i in archive.infolist()]
            for entry, target in entries:
                mode = entry.external_attr >> 16
                if entry.is_dir():
                    os.makedirs(target, exist_ok=True)
                elif stat.S_ISLNK(mode):
                    if entry.file_size > 4096:
                        raise ValueError("软链接目标过长")
                    links.append((target, archive.read(entry).decode("utf-8")))
                else:
                    with archive.open(entry) as src:
                        copy(src, target, mode)
    else:
        with tarfile.open(path, "r:*") as archive:
            entries = [(i, check(i.name, i.size)) for i in archive]
            for entry, target in entries:
                if entry.isdir():
                    os.makedirs(target, exist_ok=True)
                elif entry.isfile():
                    with archive.extractfile(entry) as src:
                        copy(src, target, entry.mode)
                elif entry.issym():
                    links.append((target, entry.linkname))
                else:
                    raise ValueError("归档包含不支持的设备文件或硬链接：" + entry.name)
    for target, link in links:
        if os.path.isabs(link) or "\\" in link or ":" in link:
            raise ValueError("归档软链接必须指向包内相对路径")
        safe_target(staging, os.path.relpath(os.path.normpath(
            os.path.join(os.path.dirname(target), link)), staging))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.lexists(target):
            raise ValueError("归档包含重复链接")
        os.symlink(link, target)
    for target, _ in links:
        safe_target(staging, os.path.relpath(target, staging))


def plugin_package(root):
    pkg = read_json(os.path.join(root, "package.json"))
    if not isinstance(pkg, dict) or not builtin.valid_name(pkg.get("name")):
        raise ValueError("package.json 缺少合法的 npm 插件名称")
    bundle = (pkg.get("dsh") or {}).get("bundle")
    if not isinstance(bundle, dict) or "patch" not in bundle:
        raise ValueError(pkg["name"] + " 未声明 dsh.bundle.patch（普通 npm 包不是 dsh 插件）")
    patch = bundle["patch"]
    # 上游允许内联 patch 数组或包内 YAML 文件。
    if isinstance(patch, str):
        if not os.path.isfile(safe_target(root, patch)):
            raise ValueError(pkg["name"] + " 缺少 patch 文件，请下载构建后的发布包")
    elif not isinstance(patch, list):
        raise ValueError(pkg["name"] + " 的 dsh.bundle.patch 格式无效")
    main = pkg.get("main")
    if isinstance(main, str) and not os.path.isfile(safe_target(root, main)):
        raise ValueError(pkg["name"] + " 缺少入口 " + main + "，请下载 Release 中的构建包")
    entry = (pkg.get("cordis") or {}).get("entry")
    if isinstance(entry, str) and not os.path.isfile(safe_target(root, entry)):
        raise ValueError(pkg["name"] + " 缺少 Cordis 入口，请下载构建后的发布包")
    if builtin.is_official_bundle(pkg["name"]):
        raise ValueError("官方核心请通过 dsh 环境更新，不支持用第三方归档覆盖")
    return pkg


def find_plugin_roots(staging, subdir=""):
    root = staging
    children = [os.path.join(staging, p) for p in os.listdir(staging)]
    if len(children) == 1 and os.path.isdir(children[0]):
        root = children[0]
    if subdir:
        root = safe_target(root, subdir)
        if not os.path.isdir(root):
            raise ValueError("仓库中不存在指定的插件目录：" + subdir)
    found = []
    for base, dirs, files in os.walk(root):
        depth = os.path.relpath(base, root).count(os.sep)
        dirs[:] = sorted(d for d in dirs if d not in (
            "node_modules", ".git", ".cache", "__pycache__") and not os.path.islink(os.path.join(base, d)))
        if depth > 5:
            dirs[:] = []
            continue
        if "package.json" in files:
            pkg = read_json(os.path.join(base, "package.json"), {})
            if isinstance(pkg, dict) and isinstance(pkg.get("dsh"), dict) and "bundle" in pkg["dsh"]:
                found.append(base)
                dirs[:] = []  # 找到发布包后不把它的内部目录当额外插件
    if not found:
        raise ValueError("未找到 dsh 插件：需要 package.json 中的 dsh.bundle.patch")
    if len(found) > 30:
        raise ValueError("仓库包含过多插件，请粘贴具体插件子目录链接")
    return found


def prepare_dependencies(root, pkg):
    deps = pkg.get("dependencies") or {}
    if not isinstance(deps, dict) or any(not builtin.valid_name(n) for n in deps):
        raise ValueError("插件 dependencies 格式无效")
    missing = [n for n in deps if not os.path.isfile(os.path.join(root, "node_modules", n, "package.json"))]
    if not missing:
        return
    if any(str(v).startswith(("workspace:", "link:", "file:")) for v in deps.values()):
        raise ValueError("插件仍引用本地工作区依赖，请使用包含依赖的发布包")
    if not shutil.which("pnpm"):
        raise ValueError("缺少 pnpm，无法安装插件依赖")
    # 在隔离依赖目录安装，避免源码里的 workspace/.npmrc 或 devDependencies 改变安装范围。
    with tempfile.TemporaryDirectory(prefix="plugin-deps-", dir=local(DSH_HOME)) as work:
        write_json(os.path.join(work, "package.json"), {
            "name": "dsha-plugin-deps", "private": True, "dependencies": deps,
            "optionalDependencies": pkg.get("optionalDependencies") or {}})
        process = subprocess.run(["pnpm", "install", "--prod", "--ignore-scripts",
                                  "--no-frozen-lockfile", "--config.node-linker=hoisted",
                                  "--config.package-import-method=copy",
                                  "--config.auto-install-peers=false", "--reporter=append-only"],
                                 cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, timeout=120)
        if process.returncode:
            detail = (process.stdout or "")[-1200:].strip()
            raise ValueError("插件依赖安装失败：" + detail)
        modules = os.path.join(root, "node_modules")
        # root 是本次独立解包目录，绝不移除正在使用的插件依赖。
        if os.path.islink(modules):
            os.unlink(modules)
        elif os.path.isdir(modules):
            shutil.rmtree(modules)
        shutil.move(os.path.join(work, "node_modules"), modules)


def owned_plugin_dir(name):
    """只认 DSHA 的精确实体映射；npm/pnpm 的实体与 scope 父级不归本脚本所有。"""
    if not builtin.valid_name(name):
        raise ValueError("无效的插件名称")
    if name in builtin.builtin_names():
        directory = builtin.entity_dir(name)
        candidates = ["/root/" + name, "/root/dsha-" + name]
        if name.startswith("dsh-"):
            candidates.append("/root/dsha-" + name[4:])
        if not directory or local(directory) not in [local(path) for path in candidates]:
            return None
        return local(directory)
    if builtin.is_official_bundle(name):
        return None
    return local(os.path.join(PLUGIN_SRC, name))


def plugin_link_paths(name):
    return [os.path.join(local(base), name) for base in
            (builtin.NODE_MODULES, "/usr/local/lib/node_modules")]


def check_link_parent(link, base):
    """拒绝 scope 或 node_modules 经软链跳转；绝不摘掉父级来强行建链。"""
    parent = os.path.dirname(link)
    current = parent
    while current != os.path.dirname(base):
        if os.path.islink(current):
            raise ValueError("插件模块父目录是软链接，已停止操作：" + current)
        if os.path.lexists(current) and not os.path.isdir(current):
            raise ValueError("插件模块父目录类型异常：" + current)
        if current == base:
            break
        current = os.path.dirname(current)


def ensure_plugin_symlinks(name):
    """只为 DSHA own 实体补链；相同路径/实体先 noop，碰撞绝不删除或吞错。"""
    target = resolve_plugin_dir(name)
    owned = owned_plugin_dir(name)
    if not target:
        if any(os.path.islink(link) for link in plugin_link_paths(name)):
            raise ValueError("插件链接目标不存在：" + name)
        return 0
    package = read_json(os.path.join(target, "package.json"), {})
    if package.get("name") != name or not os.path.isdir(target):
        raise ValueError("插件链接目标名称或类型不匹配：" + name)
    # 官方/npm/pnpm 解析出的目录不属于 DSHA；尤其 hoisted 的 target 就是 profile link。
    if not owned or os.path.realpath(target) != os.path.realpath(owned):
        return 0
    if os.path.islink(owned) or os.path.realpath(owned) != os.path.abspath(owned):
        raise ValueError("插件实体目录经软链接跳转，已停止补链：" + name)
    paths = plugin_link_paths(name)
    # 全部预检后再写入，避免 profile 已改而 global 碰撞才失败。
    missing = []
    for link in paths:
        base = os.path.dirname(link) if not name.startswith("@") else os.path.dirname(os.path.dirname(link))
        check_link_parent(link, base)
        if os.path.abspath(link) == os.path.abspath(target) or (
                os.path.exists(link) and os.path.samefile(link, target)):
            continue
        if os.path.lexists(link):
            raise ValueError("插件模块路径已被非 DSHA 映射占用：" + link)
        missing.append(link)
    created = []
    try:
        for link in missing:
            os.makedirs(os.path.dirname(link), exist_ok=True)
            os.symlink(target, link, target_is_directory=True)
            created.append(link)
            if not os.path.isdir(link) or not os.path.samefile(link, target):
                raise ValueError("插件链接回查失败：" + link)
    except Exception:
        for link in reversed(created):
            if os.path.islink(link) and os.path.realpath(link) == os.path.realpath(target):
                os.unlink(link)
        raise
    return len(created)


@contextmanager
def installation_workspace(parent):
    """失败时尚未归位的旧版本/历史备份必须保留，不能随临时目录自动销毁。"""
    work = tempfile.mkdtemp(prefix='.install-', dir=parent)
    failed = False
    try:
        yield work
    except BaseException:
        failed = True
        raise
    finally:
        if failed and any(os.path.lexists(os.path.join(work, name)) for name in ('old', 'previous-history')):
            print('DSHA: 回滚备份已保留：' + work, flush=True)
        else:
            shutil.rmtree(work)


def register_plugin(root, source, expected_version=None):
    pkg = plugin_package(root)
    name = pkg["name"]
    prepare_dependencies(root, pkg)
    is_builtin = name in builtin.builtin_names()
    if is_builtin:
        entity = builtin.entity_dir(name)
        if not entity:
            raise ValueError(f"无法确定内置插件 {name} 的底模安装目录")
        dest = local(entity)
        dep_target = entity
    else:
        dest = local(os.path.join(PLUGIN_SRC, name))
        managed_root = os.path.realpath(local(PLUGIN_SRC))
        if os.path.commonpath([os.path.realpath(local(DSH_HOME)), managed_root]) != os.path.realpath(local(DSH_HOME)) \
                or os.path.commonpath([managed_root, os.path.realpath(os.path.dirname(dest))]) != managed_root:
            raise ValueError("插件目标目录越界，已停止安装")
        dep_target = os.path.join(PLUGIN_SRC, name).replace("\\", "/")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    # 新目录先准备完整再切换；第三方插件只保留唯一上一版，供显式回退。
    with installation_workspace(os.path.dirname(dest)) as work:
        prepared = os.path.join(work, "new")
        shutil.copytree(root, prepared, symlinks=True)
        # 复制后再检查软链接：插件不能依赖源仓库中没有被带走的相邻目录。
        for base, dirs, files in os.walk(prepared, followlinks=False):
            for item in dirs + files:
                candidate = os.path.join(base, item)
                if os.path.islink(candidate):
                    safe_target(prepared, os.path.relpath(candidate, prepared))
        old = os.path.join(work, "old")
        history_change = None
        with builtin.operation_lock():
            lifecycle()._check_pending_transactions()
            builtin.ensure_runtime_modules()
            if expected_version is not None:
                current_dir = resolve_plugin_dir(name)
                current_package = read_json(os.path.join(current_dir, 'package.json'), {}) if current_dir else {}
                if current_package.get('version') != expected_version:
                    raise ValueError('插件版本在确认后发生变化，请重新检查更新')
            doc = builtin.read_manifest()
            if doc is None:
                builtin.ensure_profile_files()
                doc = builtin.new_manifest({})
            sources = read_json(local(SOURCES), {})
            if not isinstance(sources, dict):
                sources = {}
            previous_sources = dict(sources)
            source_written = False
            existed = os.path.lexists(dest)
            link = os.path.join(local(builtin.NODE_MODULES), name)
            global_link = plugin_link_paths(name)[1]
            for candidate, base in zip(plugin_link_paths(name),
                                       (local(builtin.NODE_MODULES), local('/usr/local/lib/node_modules'))):
                check_link_parent(candidate, base)
                if os.path.lexists(candidate) and not os.path.islink(candidate):
                    raise ValueError(name + " 的模块路径是实体目录，请先通过官方卸载再导入")
                if os.path.islink(candidate):
                    target = os.path.abspath(os.path.join(os.path.dirname(candidate), os.readlink(candidate)))
                    if target != os.path.abspath(dest):
                        raise ValueError(name + " 的模块路径由其他安装器管理，请先通过官方卸载再导入")
            old_link = os.readlink(link) if os.path.islink(link) else None
            had_global_link = os.path.lexists(global_link)
            marker = builtin.marker_path(name)
            previous_bundles = doc.get("dsh", {}).get("profile", {}).get("bundles", [])
            known = name in doc.get("dependencies", {}) or name in previous_bundles
            enabled = not os.path.isfile(marker) and (not known or name in previous_bundles)
            if existed:
                if os.path.islink(dest) or not os.path.isdir(dest):
                    raise ValueError("插件目标目录类型异常：" + name)
                os.replace(dest, old)
            try:
                os.replace(prepared, dest)
                if os.path.islink(link):
                    os.unlink(link)
                if enabled:
                    parent_dir = os.path.dirname(link)
                    os.makedirs(parent_dir, exist_ok=True)
                    os.symlink(dest, link, target_is_directory=True)
                    ensure_plugin_symlinks(name)
                bundles = doc.setdefault("dsh", {}).setdefault("profile", {}).setdefault("bundles", [])
                if enabled and name not in bundles:
                    bundles.append(name)
                if not enabled and name in bundles:
                    bundles.remove(name)
                doc["dsh"]["profile"]["patchReload"] = "startup"
                doc.setdefault("dependencies", {})[name] = "link:" + dep_target
                # 来源属于辅助信息，先写入；注册清单是最后的提交点。
                sources[name] = source or repository_url(pkg) or sources.get(name, "")
                write_json(local(SOURCES), sources)
                source_written = True
                if existed:
                    history_change = lifecycle().retain(name, old, work, previous_sources.get(name, ""))
                builtin.write_manifest(doc)
            except Exception as install_error:
                rollback_errors = []

                def rollback(label, action):
                    try:
                        action()
                    except Exception as error:
                        rollback_errors.append(label + '：' + str(error))

                # 旧实体优先归位；软链恢复即使持续失败，也不能阻断数据恢复。
                if history_change is not None:
                    rollback('恢复历史备份', history_change.undo)

                def restore_entity():
                    if os.path.isdir(dest) and not os.path.islink(dest):
                        shutil.rmtree(dest)
                    if existed:
                        if not os.path.isdir(old):
                            raise OSError('旧版本备份尚未归位，请检查安装工作目录和插件历史')
                        os.replace(old, dest)

                rollback('恢复插件实体', restore_entity)
                if source_written:
                    rollback('恢复来源记录', lambda: write_json(local(SOURCES), previous_sources))

                def restore_global_link():
                    if not had_global_link and os.path.islink(global_link):
                        target = os.path.abspath(os.path.join(os.path.dirname(global_link), os.readlink(global_link)))
                        if target == os.path.abspath(dest):
                            os.unlink(global_link)

                def restore_profile_link():
                    if os.path.islink(link):
                        os.unlink(link)
                    if old_link is not None:
                        os.symlink(old_link, link, target_is_directory=True)

                rollback('恢复全局链接', restore_global_link)
                rollback('恢复 profile 链接', restore_profile_link)
                if rollback_errors:
                    pending_backup = any(os.path.lexists(os.path.join(work, item)) for item in ('old', 'previous-history'))
                    location = work if pending_backup else dest
                    raise RuntimeError(str(install_error) + '；回滚未完成：' + '；'.join(rollback_errors)
                                       + '；请检查插件实体或备份：' + location) from install_error
                raise
    return name


def cmd_import(archive, subdir="", source="", expected_versions=None):
    archive = local(archive)
    if not os.path.isfile(archive):
        raise ValueError("所选插件包不存在")
    if os.path.getsize(archive) > MAX_DOWNLOAD:
        raise ValueError("插件包过大（上限 256 MiB）")
    with tempfile.TemporaryDirectory(prefix="plugin-import-", dir=local(DSH_HOME)) as staging:
        extract_archive(archive, staging)
        found = find_plugin_roots(staging, subdir)
        names, failures, seen = [], [], set()
        # 重名包必须在任何写入之前拒绝，避免顺序覆盖。
        for root in found:
            name = read_json(os.path.join(root, "package.json"), {}).get("name")
            if name in seen:
                raise ValueError("归档含多个同名插件，请只保留一份：" + str(name))
            seen.add(name)
        for root in found:
            try:
                name = read_json(os.path.join(root, 'package.json'), {}).get('name')
                names.append(register_plugin(root, source, (expected_versions or {}).get(name)))
            except Exception as error:
                failures.append(os.path.basename(root) + "：" + str(error))
        status = "partial" if names and failures else ("error" if failures else "ok")
        message = "已安装 " + "、".join(names) + "；重启 Web 后生效。" if names else ""
        if failures:
            message += "\n未安装：" + "\n".join(failures)
        result(status, message, installed=names)
        return 0 if not failures else 1


def container_path(path):
    """输出容器内路径；测试根前缀不应进入 APK 的路径显示。"""
    path = os.path.abspath(path)
    test_root = os.path.abspath(builtin.ROOT) if builtin.ROOT else ""
    if test_root and os.path.commonpath([test_root, path]) == test_root:
        return "/" + os.path.relpath(path, test_root).replace(os.sep, "/")
    return path


def profile_dependencies(doc):
    """与官方管理器一样识别全部直接依赖，optional 对同名依赖优先。"""
    result = {}
    for section in ("devDependencies", "dependencies", "optionalDependencies"):
        values = doc.get(section) or {}
        if not isinstance(values, dict):
            raise ValueError("profile " + section + " 格式无效")
        result.update(values)
    return result


def resolve_profile_plugin_dir(name):
    """以 profile 的实际模块入口为准，跟随 pnpm/link 链接但核对包名。"""
    if not builtin.valid_name(name):
        raise ValueError("无效的插件名称")
    path = os.path.join(local(builtin.NODE_MODULES), name)
    package = read_json(os.path.join(path, "package.json"), {})
    if isinstance(package, dict) and package.get("name") == name:
        return os.path.realpath(path)
    return None


def resolve_plugin_dir(name):
    if not builtin.valid_name(name):
        raise ValueError("无效的插件名称")
    directory = resolve_profile_plugin_dir(name)
    if directory:
        return directory
    doc = builtin.read_manifest() or {}
    registered = name in profile_dependencies(doc) or name in doc.get("dsh", {}).get("profile", {}).get("bundles", [])
    # 已登记但入口缺失/损坏时，不用旧目录冒充当前安装。官方随附包仍可由宿主安装提供。
    if registered and not builtin.is_official_bundle(name):
        return None
    if os.path.lexists(os.path.join(local(builtin.NODE_MODULES), name)):
        return None
    if name in builtin.builtin_names():
        directory = builtin.entity_dir(name)
        if directory and os.path.isfile(local(os.path.join(directory, "package.json"))):
            return local(directory)
    src_dir = local(os.path.join(PLUGIN_SRC, name))
    package = read_json(os.path.join(src_dir, "package.json"), {})
    if isinstance(package, dict) and package.get("name") == name:
        return src_dir
    return builtin.find_official_pkg_dir(name)


def cmd_export(names, out):
    names = json.loads(names)
    if not isinstance(names, list) or not names or len(names) > 30:
        raise ValueError("请选择 1–30 个插件")
    out = local(out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    total, count = 0, 0
    EXCLUDE_DIRS = {"node_modules", ".git", ".cache", "__pycache__", ".DS_Store", ".npm", ".pnpm-store"}

    def add_tree(archive, path, arc, root_real):
        nonlocal total, count
        if not os.path.exists(path):
            return

        for child in sorted(os.listdir(path)):
            if child in EXCLUDE_DIRS:
                continue
            child_path = os.path.join(path, child)
            child_arc = arc + "/" + child

            # 遇到软链接：若指向插件外部或循环指向自身，坚决不解引用展开，直接安全跳过！
            if os.path.islink(child_path):
                resolved = os.path.realpath(child_path)
                if resolved == root_real or not resolved.startswith(root_real):
                    continue
                info = archive.gettarinfo(child_path, arcname=child_arc)
                archive.addfile(info)
                continue

            info = archive.gettarinfo(child_path, arcname=child_arc)
            if not (info.isdir() or info.isfile()):
                continue
            count += 1
            total += info.size
            if total > MAX_EXPANDED or count > MAX_FILES:
                raise ValueError("导出内容过大，请减少所选插件")

            if info.isfile():
                with open(child_path, "rb") as stream:
                    archive.addfile(info, stream)
            elif info.isdir():
                archive.addfile(info)
                add_tree(archive, child_path, child_arc, root_real)

    try:
        with tarfile.open(out, "w:gz", dereference=False) as archive:
            for name in dict.fromkeys(names):
                if builtin.is_official_bundle(name):
                    raise ValueError("官方核心不提供独立导出")
                directory = resolve_plugin_dir(name)
                if not directory:
                    raise ValueError("找不到插件实体：" + str(name))
                real_root = os.path.realpath(directory)
                arc_root = "plugins/" + name
                archive.addfile(archive.gettarinfo(real_root, arcname=arc_root))
                add_tree(archive, real_root, arc_root, real_root)
        if os.path.getsize(out) > MAX_DOWNLOAD:
            raise ValueError("导出包超过 256 MiB，请减少插件数量")
    except Exception:
        if os.path.isfile(out):
            os.remove(out)
        raise
    result("ok", "插件包已生成", path=out)
    return 0


def cleanup_plugin_links(name, package=None):
    """只摘除精确指向此插件的自有链接；不碰实体、其他包或 scope 父目录。"""
    src = os.path.abspath(local(os.path.join(PLUGIN_SRC, name)))
    profile = os.path.abspath(os.path.join(local(builtin.NODE_MODULES), name))
    for link, base in zip(plugin_link_paths(name),
                          (local(builtin.NODE_MODULES), local('/usr/local/lib/node_modules'))):
        check_link_parent(link, base)
        if os.path.islink(link):
            target = os.path.abspath(os.path.join(os.path.dirname(link), os.readlink(link)))
            if target in (src, profile):
                os.unlink(link)
    names = {name.rsplit('/', 1)[-1]}
    bins = (package or {}).get('bin', {})
    if isinstance(bins, dict):
        names.update(key for key in bins if isinstance(key, str) and key not in ('.', '..') and '/' not in key)
    for binary in names:
        link = os.path.join(local(builtin.NODE_MODULES), '.bin', binary)
        check_link_parent(link, local(builtin.NODE_MODULES))
        if os.path.islink(link):
            target = os.path.abspath(os.path.join(os.path.dirname(link), os.readlink(link)))
            if any(os.path.commonpath([root, target]) == root for root in (src, profile)):
                os.unlink(link)


def managed_directory(relative):
    """仅创建 DSH_HOME 内的管理目录，拒绝目录经软链接跳出管理区。"""
    home = os.path.realpath(local(DSH_HOME))
    path = os.path.join(home, relative)
    if os.path.commonpath([home, os.path.realpath(path)]) != home or os.path.islink(path):
        raise ValueError("插件管理目录指向外部：" + relative)
    os.makedirs(path, mode=0o700, exist_ok=True)
    return path


def store_plugin_archive(root):
    """把已校验的包保存为稳定 file: 安装源；不包含依赖树或执行构建。"""
    root = os.path.realpath(root)
    package = plugin_package(root)
    # 这些包依赖原目录布局/随包依赖，排除 node_modules 后不能保证可恢复；先停止而非产生假备份。
    if package.get("bundledDependencies") or package.get("bundleDependencies"):
        raise ValueError("插件含随包依赖，当前归档备份无法保证完整恢复，未执行安装")
    for section in ("dependencies", "optionalDependencies"):
        dependencies = package.get(section) or {}
        if not isinstance(dependencies, dict):
            raise ValueError("插件 " + section + " 格式无效，未执行安装")
        if any(not isinstance(value, str) or value.startswith(("file:", "link:", "workspace:", "/", "./", "../"))
               for value in dependencies.values()):
            raise ValueError("插件依赖本地路径，当前归档备份无法保证完整恢复，未执行安装")
    excluded = {"node_modules", ".git", ".cache", "__pycache__", ".pnpm-store"}

    def ensure_archived(relative):
        target = safe_target(root, relative)
        # 同时核对声明路径与软链接实际目标，防止备份时把入口或 patch 排除。
        paths = (os.path.normpath(relative), os.path.relpath(target, root))
        if any(excluded.intersection(path.split(os.sep)) for path in paths):
            raise ValueError("插件备份所需文件位于排除目录，无法保证完整恢复，未执行安装：" + relative)

    for required in ("package.json", package.get("main"),
                     (package.get("cordis") or {}).get("entry"),
                     (package.get("dsh") or {}).get("bundle", {}).get("patch")):
        if isinstance(required, str):
            ensure_archived(required)
    archive_root = managed_directory("plugin-archives")
    descriptor, temporary = tempfile.mkstemp(prefix=".snapshot-", suffix=".tgz", dir=archive_root)
    os.close(descriptor)
    size = count = 0
    try:
        with tarfile.open(temporary, "w:gz", format=tarfile.PAX_FORMAT, dereference=False) as output:
            output.add(root, arcname="package", recursive=False)
            for base, dirs, files in os.walk(root, topdown=True, followlinks=False):
                dirs[:] = sorted(directory for directory in dirs if directory not in excluded)
                for filename in list(dirs) + sorted(files):
                    if filename in excluded:
                        continue
                    path = os.path.join(base, filename)
                    relative = os.path.relpath(path, root)
                    safe_target(root, relative)
                    if os.path.islink(path):
                        ensure_archived(relative)
                        link = os.readlink(path)
                        if os.path.isabs(link) or "\\" in link or ":" in link:
                            raise ValueError("插件备份含不可移植软链接：" + relative)
                        # 即使随后由 .. 抵消，内核仍须访问原始目标中的中间目录。
                        if excluded.intersection(link.split("/")):
                            raise ValueError("插件备份软链接经过排除目录，未执行安装：" + relative)
                        ensure_archived(os.path.relpath(os.path.normpath(os.path.join(base, link)), root))
                        if filename in dirs:
                            dirs.remove(filename)
                    info = output.gettarinfo(path, arcname=os.path.join("package", relative))
                    if not (info.isfile() or info.isdir() or info.issym()):
                        raise ValueError("插件备份含不支持的文件：" + relative)
                    count += 1
                    size += info.size
                    if count > MAX_FILES or size > MAX_EXPANDED:
                        raise ValueError("插件备份超过容量限制，未执行安装")
                    if info.isfile():
                        with open(path, "rb") as stream:
                            output.addfile(info, stream)
                    else:
                        output.addfile(info)
        if os.path.getsize(temporary) > MAX_DOWNLOAD:
            raise ValueError("插件备份超过 256 MiB，未执行安装")
        with open(temporary, "rb") as stream:
            os.fsync(stream.fileno())
        digest = lifecycle().digest(temporary)
        target = os.path.join(archive_root, digest + ".tgz")
        if os.path.lexists(target):
            if os.path.islink(target) or not os.path.isfile(target) or lifecycle().digest(target) != digest:
                raise ValueError("插件备份归档路径异常，未执行安装")
        else:
            os.replace(temporary, target)
        return target
    finally:
        if os.path.isfile(temporary):
            os.unlink(temporary)


def legacy_directory_references(directory):
    """检查各 profile 的依赖/模块入口；无法确认安全时保留旧目录。"""
    directory = os.path.realpath(directory)
    profiles = local(os.path.join(DSH_HOME, "profiles"))
    references = []
    for profile in sorted(os.listdir(profiles)) if os.path.isdir(profiles) else []:
        base = os.path.join(profiles, profile)
        if not os.path.isdir(base):
            continue
        doc = read_json(os.path.join(base, "package.json"), {})
        groups = [doc.get(section) or {} for section in ("dependencies", "devDependencies", "optionalDependencies")]
        names = set(doc.get("dsh", {}).get("profile", {}).get("bundles", []))
        for dependencies in groups:
            names.update(dependencies)
        for name in names:
            if not builtin.valid_name(name):
                continue
            entry = os.path.join(base, "node_modules", name)
            targets = [os.path.realpath(entry)] if os.path.lexists(entry) else []
            for dependencies in groups:
                dependency = dependencies.get(name, "")
                if isinstance(dependency, str) and dependency.startswith(("link:", "file:")):
                    source = os.path.expanduser(dependency.split(":", 1)[1])
                    target = local(source) if os.path.isabs(source) else os.path.join(base, source)
                    targets.append(os.path.realpath(target))
            if any(os.path.commonpath([directory, target]) == directory for target in targets):
                references.append(profile + ":" + name)
    return references


def retire_legacy_directory(name, active):
    """仅隔离未被引用的 DSHA plugin-src 实体；不删外部目录或 pnpm 存储。"""
    result = {"warnings": [], "retiredPath": ""}
    legacy = local(os.path.join(PLUGIN_SRC, name))
    if not os.path.lexists(legacy) or os.path.realpath(legacy) == os.path.realpath(active):
        return result
    try:
        check_link_parent(legacy, local(PLUGIN_SRC))
        if os.path.islink(legacy) or not os.path.isdir(legacy):
            raise ValueError("旧路径不是可确认归属的 DSHA 实体")
        package = read_json(os.path.join(legacy, "package.json"), {})
        if package.get("name") != name:
            raise ValueError("旧目录包名不匹配")
        references = legacy_directory_references(legacy)
        if references:
            raise ValueError("仍被 profile 引用：" + "、".join(references))
        profile_entry, global_link = plugin_link_paths(name)
        check_link_parent(global_link, local("/usr/local/lib/node_modules"))
        if os.path.islink(global_link):
            raw_target = os.path.abspath(os.path.join(os.path.dirname(global_link), os.readlink(global_link)))
            if raw_target == os.path.abspath(legacy):
                temporary = global_link + ".dsha-" + uuid.uuid4().hex
                try:
                    os.symlink(profile_entry, temporary, target_is_directory=True)
                    os.replace(temporary, global_link)
                finally:
                    if os.path.islink(temporary):
                        os.unlink(temporary)
            elif os.path.commonpath([os.path.realpath(legacy), os.path.realpath(global_link)]) == os.path.realpath(legacy):
                raise ValueError("全局链接仍引用旧目录，且无法确认归属")
        identifier = hashlib.sha256(name.encode()).hexdigest()[:32] + "/" + uuid.uuid4().hex
        destination = managed_directory("plugin-retired/" + identifier)
        write_json(os.path.join(destination, "state.json"), {"name": name, "version": package.get("version", ""),
                   "originalDirectory": container_path(legacy), "activeDirectory": container_path(active)})
        os.replace(legacy, os.path.join(destination, "package"))
        result["retiredPath"] = container_path(destination)
    except Exception as error:
        result["warnings"].append("新版已安装，旧目录保留（" + container_path(legacy) + "）：" + str(error))
    return result


class OfficialInstallUncertain(ValueError):
    """命令超时不代表其子进程已经停止；回查前不得启动第二次安装。"""


def cmd_official_add(spec, expected_name, expected_version=""):
    """通过官方 dsh plugin add 安装单个 bundle，并回查 profile 提交结果。"""
    if not isinstance(spec, str) or not spec.strip() or len(spec) > 2048 \
            or re.search(r'[\x00-\x1f\x7f]', spec) or spec.lstrip().startswith('-'):
        raise ValueError("官方插件规格无效")
    if not builtin.valid_name(expected_name):
        raise ValueError("官方插件名称无效")
    env = dict(os.environ, DSH_HOME=local(DSH_HOME))
    try:
        process = subprocess.run(
            ["dsh", "plugin", "--profile", "web", "add", spec.strip()],
            cwd=local(builtin.PROFILE), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, timeout=600,
        )
    except subprocess.TimeoutExpired as error:
        raise OfficialInstallUncertain("官方安装超时，结果未确认；请先回查 profile 和安装事务，勿直接重试") from error
    output = ((process.stdout or "") + ("\n" + process.stderr if process.stderr else "")).strip()
    if process.returncode != 0:
        detail = output[-1600:] if output else str(process.returncode)
        raise ValueError("官方安装失败：" + detail)
    doc = builtin.read_manifest() or {}
    dependencies = profile_dependencies(doc)
    # 已禁用插件更新后仍可保持禁用；是否启用由安装事务恢复，不能当作安装成功条件。
    if expected_name not in dependencies:
        raise ValueError("官方安装已返回成功，但 profile 未登记依赖 " + expected_name)
    directory = resolve_profile_plugin_dir(expected_name)
    package = read_json(os.path.join(directory, "package.json"), {}) if directory else {}
    if package.get("name") != expected_name:
        raise ValueError("官方安装回查的 profile 插件目录名称不匹配：" + expected_name)
    if expected_version and str(package.get("version", "")) != expected_version:
        raise ValueError("官方安装回查的插件版本与预览不匹配：" + expected_name)
    plugin_package(directory)
    return {
        "exitCode": process.returncode,
        "output": output[-1600:],
        "dependency": dependencies.get(expected_name, ""),
        "version": package.get("version", ""),
    }


def cmd_official_archive(archive, subdir, expected_items, archive_digest, source=""):
    """Normalize a checked archive into a stable local tarball for official pnpm install."""
    if not isinstance(expected_items, list) or not expected_items or len(expected_items) > 30:
        raise ValueError("归档插件清单无效")
    if lifecycle().digest(archive) != archive_digest:
        raise ValueError("安装归档已变化，请重新解析")
    staging = tempfile.mkdtemp(prefix="plugin-official-", dir=local(DSH_HOME))
    installed, failures, package_results = [], [], []
    try:
        extracted = os.path.join(staging, "contents")
        extract_archive(archive, extracted)
        roots = find_plugin_roots(extracted, subdir)
        expected = {str(item.get("name", "")): str(item.get("version", "")) for item in expected_items}
        actual = {}
        for root in roots:
            pkg = plugin_package(root)
            if pkg["name"] in actual:
                raise ValueError("归档含多个同名插件：" + pkg["name"])
            actual[pkg["name"]] = (root, str(pkg.get("version", "")))
        if set(actual) != set(expected):
            raise ValueError("预览后的插件清单与归档不一致，请重新解析")
        for name, (root, version) in actual.items():
            if expected[name] and version != expected[name]:
                raise ValueError("插件版本与预览不一致：" + name)
            tarball = store_plugin_archive(root)
            try:
                result = lifecycle().install_package("file:" + tarball, name, version, source=source)
                installed.append(name)
                package_results.append({"name": name, **result})
            except OfficialInstallUncertain as error:
                failures.append(name + "：" + str(error) + "；已停止后续插件安装")
                break
            except Exception as error:
                failures.append(name + "：" + str(error))
        status = "partial" if installed and failures else ("error" if failures else "ok")
        message = "已通过 DSH 官方 profile 安装器安装 " + "、".join(installed) + "；重启 Web 后生效。" if installed else ""
        if failures:
            message += ("\n" if message else "") + "未安装：" + "\n".join(failures)
        warnings = [warning for entry in package_results for warning in entry.get("warnings", [])]
        retired = [entry["retiredPath"] for entry in package_results if entry.get("retiredPath")]
        if warnings:
            message += "\n" + "\n".join(warnings)
        if retired:
            message += "\n已将未引用的旧目录移入备份区：" + "、".join(retired)
        return {"status": status, "message": message, "installed": installed,
                "application": "restart-required", "restartReason": "no-hmr",
                "warnings": warnings, "retiredPaths": retired, "packageResults": package_results}
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def cmd_delete(name):
    """官方卸载成功且回查通过后，才清理该插件的 DSHA 自有残留。"""
    if not builtin.valid_name(name):
        raise ValueError("无效的插件名称")
    if builtin.is_official_bundle(name) or name in builtin.builtin_names():
        raise ValueError("官方核心和内置插件请使用禁用开关，不能删除")
    with builtin.operation_lock():
        lifecycle()._check_pending_transactions()
        doc = builtin.read_manifest()
        if doc is None:
            raise ValueError("插件配置不存在")
        bundles = doc.get("dsh", {}).get("profile", {}).get("bundles", [])
        deps = profile_dependencies(doc)
        sources = read_json(local(SOURCES), {})
        src_path = local(os.path.join(PLUGIN_SRC, name))
        if name not in deps and name not in bundles and name not in (sources or {}) and not os.path.lexists(src_path):
            raise ValueError("插件未安装或已经删除，请刷新列表")
        directory = resolve_plugin_dir(name)
        package = read_json(os.path.join(directory, 'package.json'), {}) if directory else {}
        # 不吞掉官方失败或超时；它可能要求先停止仍在运行的 profile。
        if name in deps:
            try:
                env = dict(os.environ, DSH_HOME=local(DSH_HOME))
                removed = subprocess.run(["dsh", "plugin", "--profile", "web", "remove", name],
                                         cwd=local(os.path.join(DSH_HOME, 'profiles', 'web')), env=env,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=120)
            except subprocess.TimeoutExpired as error:
                raise ValueError("官方卸载超时，尚未清理插件文件，请核对状态后重试") from error
            if removed.returncode != 0:
                detail = (removed.stderr or removed.stdout or '').strip()[-1200:]
                raise ValueError("官方卸载失败，保留插件文件：" + (detail or str(removed.returncode)))
            doc = builtin.read_manifest()
            if doc is None or name in profile_dependencies(doc) or name in doc.get('dsh', {}).get('profile', {}).get('bundles', []):
                raise ValueError("官方卸载回查未通过，保留插件文件，请刷新后重试")
        elif name in bundles:
            # 只有 bundle 选择、没有包依赖的历史残项，不运行无关的 pnpm 操作。
            doc['dsh']['profile']['bundles'] = [item for item in bundles if item != name]
            builtin.write_manifest(doc)

        try:
            managed = os.path.realpath(local(PLUGIN_SRC))
            home = os.path.realpath(local(DSH_HOME))
            if os.path.commonpath([home, managed]) != home or os.path.realpath(src_path) != os.path.abspath(src_path):
                raise ValueError("插件源码路径经软链接跳转，已停止残留清理")
            if os.path.lexists(src_path) and not os.path.isdir(src_path):
                raise ValueError("插件源码目录类型异常，已停止残留清理")
            cleanup_plugin_links(name, package)
            if os.path.isdir(src_path):
                owned_package = read_json(os.path.join(src_path, 'package.json'), {})
                if owned_package.get('name') != name:
                    raise ValueError("插件源码名称不匹配，已停止残留清理")
                shutil.rmtree(src_path)
                scope_dir = os.path.dirname(src_path)
                if os.path.basename(scope_dir).startswith('@') and not os.listdir(scope_dir):
                    os.rmdir(scope_dir)
            marker = builtin.marker_path(name)
            if os.path.isfile(marker) and not os.path.islink(marker):
                os.remove(marker)
            lifecycle().clear_history(name)
            if isinstance(sources, dict) and name in sources:
                sources.pop(name)
                write_json(local(SOURCES), sources)
        except Exception as error:
            result('partial', "插件依赖已移除，但残留清理失败：" + str(error), removed=[name])
            return 1
    result("ok", "已删除 " + name + "；重启 Web 后停止加载。对话和其他插件保留。")
    return 0


def open_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("下载地址必须是 HTTPS 链接")
    response = urllib.request.urlopen(urllib.request.Request(url, headers={
        "User-Agent": "DSHA-plugin-manager", "Accept": "application/vnd.github+json"
        if parsed.hostname == "api.github.com" else "*/*"}), timeout=30)
    if urllib.parse.urlsplit(response.url).scheme != "https":
        response.close()
        raise ValueError("下载被重定向到非 HTTPS 地址")
    return response


def archive_download_url(url):
    """GitHub 归档直接使用官方 codeload，保留 refs/heads 或 refs/tags。"""
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme == "https" and parsed.hostname in ("github.com", "www.github.com") \
            and not parsed.username and not parsed.password and not parsed.port:
        match = re.fullmatch(r"/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/archive/(.+)\.(zip|tar\.gz)", parsed.path)
        if match:
            owner, repo, revision, extension = match.groups()
            return "https://codeload.github.com/%s/%s/%s/%s" % (owner, repo,
                    "zip" if extension == "zip" else "tar.gz", revision)
    return url


def download(url, target):
    with open_url(archive_download_url(url)) as response, open(target, "wb") as stream:
        if int(response.headers.get("Content-Length", "0")) > MAX_DOWNLOAD:
            raise ValueError("插件包超过 256 MiB")
        size = 0
        while True:
            data = response.read(65536)
            if not data:
                break
            size += len(data)
            if size > MAX_DOWNLOAD:
                raise ValueError("插件包超过 256 MiB")
            stream.write(data)


def cmd_download(url, subdir="", source="", *, consume=None, official_spec=""):
    with tempfile.TemporaryDirectory(prefix="plugin-download-", dir=local(DSH_HOME)) as staging:
        target = os.path.join(staging, "archive")
        download(url, target)
        if consume:
            return consume(target, subdir, source or url, official_spec)
        return cmd_import(target, subdir, source or url)


def cmd_github(owner, repo, tree="", *, consume=None):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}", owner) or not re.fullmatch(
            r"[A-Za-z0-9_.-]+", repo) or repo in (".", ".."):
        raise ValueError("无效的 GitHub 仓库")
    revision, subdir = "", ""
    if tree:
        parts = tree.split("/")
        if len(parts) > 16 or any(p in ("", ".", "..") for p in parts):
            raise ValueError("无效的仓库目录")
    else:
        with open_url("https://api.github.com/repos/%s/%s" % (owner, repo)) as response:
            repository = json.loads(response.read(1024 * 1024))
        default_branch = repository.get("default_branch")
        if not isinstance(default_branch, str) or not default_branch:
            raise ValueError("GitHub 仓库未返回默认分支")
        parts = [default_branch]
    # 最长匹配支持 feature/foo 等带斜杠分支；不默默改成默认分支。
    for end in range(len(parts), 0, -1):
        candidate = "/".join(parts[:end])
        api = "https://api.github.com/repos/%s/%s/commits/%s" % (
            owner, repo, urllib.parse.quote(candidate, safe=""))
        try:
            with open_url(api) as response:
                commit = json.loads(response.read(1024 * 1024))
            revision = commit["sha"]
            subdir = "/".join(parts[end:])
            break
        except urllib.error.HTTPError as error:
            if error.code not in (404, 422):
                raise
    else:
        raise ValueError("GitHub 分支或标签不存在")
    if not re.fullmatch(r"[a-fA-F0-9]{40}", revision):
        raise ValueError("GitHub 未返回有效 commit SHA")
    url = "https://codeload.github.com/%s/%s/tar.gz/%s" % (
        owner, repo, urllib.parse.quote(revision, safe=""))
    source = "https://github.com/%s/%s" % (owner, repo) + ("/tree/" + tree if tree else "")
    official_spec = "git+https://github.com/%s/%s.git#%s" % (owner, repo, revision)
    if subdir:
        official_spec += "&path:" + urllib.parse.quote(subdir, safe="/")
    return cmd_download(url, subdir, source, official_spec=official_spec,
                        **({"consume": consume} if consume else {}))


def cmd_release(owner, repo, tag, *, consume=None):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}", owner) or not re.fullmatch(
            r"[A-Za-z0-9_.-]+", repo) or repo in (".", ".."):
        raise ValueError("无效的 GitHub 仓库")
    suffix = "latest" if tag == "latest" else "tags/" + urllib.parse.quote(tag, safe="")
    with open_url("https://api.github.com/repos/%s/%s/releases/%s" % (owner, repo, suffix)) as response:
        release = json.loads(response.read(4 * 1024 * 1024))
    archives = [asset for asset in release.get("assets", [])
                if re.search(r"\.(zip|tar|tar\.gz|tgz)$", asset.get("name", ""), re.I)]
    if not archives:
        raise ValueError("这个 Release 没有插件压缩包附件，请使用作者提供的构建包或仓库链接")
    if len(archives) != 1:
        raise ValueError("这个 Release 含多个压缩包，请复制所需附件的下载链接：\n"
                         + "\n".join(a["name"] for a in archives))
    return cmd_download(archives[0]["browser_download_url"], **({"consume": consume} if consume else {}))


def cmd_list():
    doc = builtin.read_manifest() or {}
    bundles = doc.get("dsh", {}).get("profile", {}).get("bundles", [])
    deps = profile_dependencies(doc)
    sources = read_json(local(SOURCES), {})
    if not isinstance(sources, dict):
        sources = {}
    official_bundles = builtin.get_official_bundles()
    names = list(dict.fromkeys(list(official_bundles) + builtin.builtin_names()
                              + list(deps) + bundles))
    items = []
    updates = lifecycle().read(lifecycle().path('plugin-updates.json'), {})

    # 自动清理历史 PatchToggle 在 cordis.patch.yml 中留下的残留区块
    patch_file = local(os.path.join(DSH_HOME, "profiles", "web", "cordis.patch.yml"))
    if os.path.isfile(patch_file):
        try:
            # CLI main 已持有 operation_lock；flock 不能在此用新 fd 重入。
            builtin.assert_no_pending_plugin_transactions()
            with open(patch_file, "r", encoding="utf-8") as pf:
                content = pf.read()
            if "# >>> DSHA managed" in content:
                lines_clean = []
                in_block = False
                for line in content.splitlines(True):
                    if "# >>> DSHA managed" in line:
                        in_block = True
                        continue
                    if "# <<< DSHA managed" in line:
                        in_block = False
                        continue
                    if not in_block:
                        lines_clean.append(line)
                with open(patch_file, "w", encoding="utf-8") as pf:
                    pf.write("".join(lines_clean))
        except Exception:
            pass

    for name in names:
        if not builtin.valid_name(name):
            continue
        official = builtin.is_official_bundle(name)
        directory = resolve_plugin_dir(name)
        pkg = read_json(os.path.join(directory, "package.json"), {}) if directory else {}
        if not official and name not in builtin.builtin_names() \
                and name not in bundles and not (pkg.get("dsh") or {}).get("bundle"):
            continue
        available = official or directory is not None

        # 权威启用状态：对齐 DSH 官方规范，只要在 manifest 的 bundles 列表中即为已启用
        plugin_enabled = name in bundles

        desc = pkg.get("description", "")
        if official:
            OFFICIAL_DESC_MAP = {
                "@deepseek-ai/dsh-base": "官方核心基础运行时",
                "@deepseek-ai/dsh-web-app": "官方沉浸式 Web 桌面客户端应用",
                "@deepseek-ai/dsh-experimental-agent-team-profile": "智能体团队协作核心引擎与多智能体通信协议 (Beta)",
                "@deepseek-ai/dsh-experimental-agent-team-web-profile": "智能体团队 Web 界面、任务协同看板与成员监控 (Beta)",
            }
            if name in OFFICIAL_DESC_MAP:
                desc = OFFICIAL_DESC_MAP[name]
        items.append(dict(name=name, enabled=plugin_enabled, builtin=name in builtin.builtin_names(),
                          official=official, available=available,
                          version=pkg.get("version", ""), description=desc,
                          installPath=container_path(os.path.realpath(directory)) if directory else "",
                          source=sources.get(name, "") or repository_url(pkg),
                          exportable=not official and directory is not None,
                          deletable=not official and name not in builtin.builtin_names()))
        update = updates.get(name, {})
        previous = lifecycle().history_info(name) if not official else {}
        items[-1].update(latestVersion=update.get('latestVersion', ''), updateAvailable=bool(update.get('available'))
                         and update.get('installedVersion') == str(pkg.get('version', '')),
                         updatePreviewId=update.get('previewId', ''), updateMessage=update.get('message', '')
                         if update.get('installedVersion') == str(pkg.get('version', '')) else '',
                         rollbackVersion=previous.get('version', ''), compatibility=update.get('compatibility', ''))
    mode = lifecycle().read(lifecycle().path('plugin-safe-mode.json'), {})
    result("ok", "插件状态已同步", items=items, safeMode=bool(mode.get('active')))
    return 0


def cmd_npm(package, *, consume=None):
    """npm pack 只下载发布包；再走与界面完全相同的校验、依赖准备和登记。"""
    spec = package.removeprefix("npm:")
    match = re.fullmatch(r"((?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*)(?:@([A-Za-z0-9][A-Za-z0-9._+~-]*))?", spec)
    if not match or not builtin.valid_name(match.group(1)):
        raise ValueError("请使用 npm 包名或 包名@版本，例如 @作者/插件@1.0.0")
    if not shutil.which("npm"):
        raise ValueError("npm 尚未就绪，请关闭终端后重新打开")
    with tempfile.TemporaryDirectory(prefix="plugin-npm-", dir=local(DSH_HOME)) as staging:
        process = subprocess.run(["npm", "pack", "--json", "--ignore-scripts", "--pack-destination", staging, "--", spec],
                                 cwd=staging, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=120)
        if process.returncode:
            raise ValueError("npm 插件下载失败：" + (process.stderr or process.stdout)[-1600:].strip())
        packed = json.loads(process.stdout)
        if not isinstance(packed, list) or len(packed) != 1:
            raise ValueError("npm 未返回唯一插件包")
        filename = packed[0].get("filename", "")
        target = safe_target(staging, filename)
        if not filename or not os.path.isfile(target) or os.path.getsize(target) > MAX_DOWNLOAD:
            raise ValueError("npm 插件归档无效或超过 256 MiB")
        return (consume or cmd_import)(target, source="npm:" + spec)


def main():
    os.makedirs(local(DSH_HOME), exist_ok=True)
    args = sys.argv[1:]
    try:
        if args[0] == "inspect" and len(args) == 2:
            return lifecycle().inspect(args[1])
        if args[0] == "install-preview" and len(args) == 2:
            return lifecycle().install_preview(args[1])
        if args[0] == "show-preview" and len(args) == 2:
            return lifecycle().show_preview(args[1])
        if args[0] == "discard-preview" and len(args) == 2:
            return lifecycle().discard_preview(args[1])
        if args[0] == "check-updates" and len(args) in (1, 2):
            return lifecycle().check_updates(args[1] if len(args) == 2 else '')
        if args[0] == "update" and len(args) == 2:
            return lifecycle().update(args[1])
        if args[0] == "rollback" and len(args) in (2, 3):
            return lifecycle().rollback(*args[1:])
        if args[0] == "safe-mode" and len(args) == 2:
            return lifecycle().safe_mode(args[1])
        if args[0] == "import" and len(args) == 2:
            return cmd_import(args[1])
        if args[0] == "export" and len(args) == 3:
            with builtin.operation_lock():
                return cmd_export(args[1], args[2])
        if args[0] == "delete" and len(args) == 2:
            return cmd_delete(args[1])
        if args[0] == "download" and len(args) == 2:
            return cmd_download(args[1])
        if args[0] == "npm" and len(args) == 2:
            return cmd_npm(args[1])
        if args[0] == "github" and len(args) in (3, 4):
            return cmd_github(*args[1:])
        if args[0] == "release" and len(args) == 4:
            return cmd_release(*args[1:])
        if args[0] == "list":
            with builtin.operation_lock():
                return cmd_list()
        raise ValueError("不支持的插件操作")
    except urllib.error.HTTPError as error:
        hint = {403: "访问受限或 GitHub API 额度已用完", 404: "链接或指定版本不存在",
                429: "请求过于频繁，请稍后重试"}.get(error.code, "服务器返回错误")
        result("error", "下载失败：%s（HTTP %s），也可下载压缩包后本地导入" % (hint, error.code))
    except urllib.error.URLError as error:
        if isinstance(error.reason, ssl.SSLCertVerificationError):
            result("error", "HTTPS 证书校验失败。请更新 DSHA 并重新进入插件页，检查设备日期及网络代理；未关闭证书校验。")
        else:
            result("error", "网络连接失败：" + str(error.reason))
    except Exception as error:
        result("error", str(error))
    return 1


if __name__ == "__main__":
    sys.exit(main())
