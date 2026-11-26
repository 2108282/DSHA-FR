#!/usr/bin/env python3
"""插件安装预览、更新发现、单版本回退与安全启动；由 plugin-manager 注入既有安装器。"""
import base64
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import time
import urllib.parse
import uuid


def version_parts(value):
    match = re.fullmatch(r"v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?", str(value))
    if not match:
        return None
    return tuple(int(match[i] or 0) for i in (1, 2, 3)), match[4]


def compare_versions(left, right):
    a, b = version_parts(left), version_parts(right)
    if a is None or b is None:
        return None
    if a[0] != b[0]:
        return (a[0] > b[0]) - (a[0] < b[0])
    if a[1] == b[1]:
        return 0
    if a[1] is None or b[1] is None:
        return 1 if a[1] is None else -1
    aa, bb = a[1].split('.'), b[1].split('.')
    for x, y in zip(aa, bb):
        if x == y:
            continue
        if x.isdigit() and y.isdigit():
            return (int(x) > int(y)) - (int(x) < int(y))
        if x.isdigit() != y.isdigit():
            return -1 if x.isdigit() else 1
        return (x > y) - (x < y)
    return (len(aa) > len(bb)) - (len(aa) < len(bb))


def accepts_version(installed, requirement):
    """识别常用精确/比较/^/~ 范围；不能确定时返回 None，交给用户查看声明。"""
    if requirement.strip() in ('*', 'latest', ''):
        return True
    parsed = version_parts(installed)
    if not parsed:
        return None
    unknown = False
    for group in requirement.split('||'):
        words = group.strip().split()
        outcomes = []
        for word in words:
            match = re.fullmatch(r'(\^|~|>=|<=|>|<|=)?(v?\d+(?:\.\d+){0,2}(?:-[0-9A-Za-z.-]+)?)', word)
            if not match:
                unknown = True; outcomes.append(None); continue
            operator, wanted = match[1] or '=', match[2]
            cmp = compare_versions(installed, wanted)
            if cmp is None:
                outcomes.append(None); continue
            if operator in ('^', '~'):
                target = version_parts(wanted)[0]
                if operator == '~':
                    upper = (target[0], target[1] + 1, 0)
                elif target[0]:
                    upper = (target[0] + 1, 0, 0)
                elif target[1]:
                    upper = (0, target[1] + 1, 0)
                else:
                    upper = (0, 0, target[2] + 1)
                outcomes.append(cmp >= 0 and parsed[0] < upper)
            else:
                outcomes.append({'=': cmp == 0, '>': cmp > 0, '<': cmp < 0, '>=': cmp >= 0, '<=': cmp <= 0}[operator])
        # 对预发布版不猜测 npm 的所有复杂区间规则，没有显式声明时标记待确认。
        if parsed[1] and '-' not in group:
            outcomes.append(None)
        if outcomes and all(value is True for value in outcomes):
            return True
        if None in outcomes and False not in outcomes:
            unknown = True
    return None if unknown else False


class HistoryChange:
    def __init__(self, life, name, old, work, source):
        self.life, self.old = life, old
        self.target = life.history_path(name)
        self.saved = os.path.join(work, 'previous-history')
        self.moved_old = False
        self.moved_history = False
        self.created = False
        try:
            if os.path.lexists(self.target):
                os.replace(self.target, self.saved); self.moved_history = True
            os.makedirs(self.target)
            self.created = True
            os.replace(old, os.path.join(self.target, 'package')); self.moved_old = True
            pkg = life.read(os.path.join(self.target, 'package', 'package.json'), {})
            life.write(os.path.join(self.target, 'state.json'), {'name': name, 'source': source,
                       'version': pkg.get('version', ''), 'savedAt': int(time.time())})
        except Exception:
            self.undo(); raise

    def undo(self):
        if self.moved_old and os.path.isdir(os.path.join(self.target, 'package')):
            os.replace(os.path.join(self.target, 'package'), self.old)
        if self.created and os.path.isdir(self.target):
            shutil.rmtree(self.target)
        if self.moved_history and os.path.exists(self.saved):
            os.replace(self.saved, self.target)


class Lifecycle:
    def __init__(self, manager):
        self.g = manager
        self.builtin = manager['builtin']
        self.local = manager['local']
        self.home = self.local(manager['DSH_HOME'])
        self.read, self.write = manager['read_json'], manager['write_json']

    def path(self, relative):
        path = os.path.join(self.home, relative)
        if os.path.commonpath([os.path.realpath(self.home), os.path.realpath(path)]) != os.path.realpath(self.home):
            raise ValueError('插件管理目录指向外部，已停止操作')
        return path

    def history_path(self, name):
        if not self.builtin.valid_name(name):
            raise ValueError('无效插件名')
        parent = self.path('plugin-history')
        os.makedirs(parent, exist_ok=True)
        return self.path('plugin-history/' + hashlib.sha256(name.encode()).hexdigest()[:32])

    def retain(self, name, old, work, source):
        return HistoryChange(self, name, old, work, source)

    def history_info(self, name):
        path = self.history_path(name)
        if os.path.islink(path):
            raise ValueError('插件历史目录是软链接，已停止读取')
        state = self.read(os.path.join(path, 'state.json'), {})
        if not isinstance(state, dict) or state.get('name') != name:
            return {}
        if state.get('schema') == 2:
            self._snapshot_archive(state, verify_digest=False)
            return state
        package = os.path.join(path, 'package', 'package.json')
        return state if os.path.isfile(package) else {}

    def _snapshot_archive(self, state, verify_digest=True):
        """历史只引用稳定归档区，不能把事务目录或任意外部文件当作恢复包。"""
        archive = state.get('archive', '')
        root = os.path.realpath(self.path('plugin-archives'))
        if not isinstance(archive, str) or not os.path.isabs(archive) or not archive.endswith('.tgz') \
                or os.path.islink(archive) or not os.path.isfile(archive) \
                or os.path.commonpath([root, os.path.realpath(archive)]) != root:
            raise ValueError('插件恢复归档不存在或路径异常，请保留历史并检查：' + str(archive))
        if not 0 < os.path.getsize(archive) <= self.g.get('MAX_DOWNLOAD', 256 * 1024 * 1024):
            raise ValueError('插件恢复归档为空或超过容量限制：' + archive)
        digest = state.get('sha256', '')
        if not isinstance(digest, str) or not re.fullmatch('[a-fA-F0-9]{64}', digest) \
                or (verify_digest and self.digest(archive).lower() != digest.lower()):
            raise ValueError('插件恢复归档校验失败，已停止操作：' + archive)
        return archive

    def _sources(self):
        sources = self.read(self.local(self.g['SOURCES']), {})
        if not isinstance(sources, dict):
            raise ValueError('插件来源记录格式错误，已停止操作')
        return sources

    def _capture_state(self, name, directory, doc):
        sources = self._sources()
        bundles = doc.get('dsh', {}).get('profile', {}).get('bundles', [])
        dependencies = self.g['profile_dependencies'](doc)
        dependency_groups = {section: doc[section][name] for section in
                             ('dependencies', 'devDependencies', 'optionalDependencies')
                             if name in (doc.get(section) or {})}
        marker = self.builtin.marker_path(name)
        marked = os.path.lexists(marker)
        marker_content = ''
        if marked:
            if os.path.islink(marker) or not os.path.isfile(marker):
                raise ValueError('插件禁用标记类型异常：' + marker)
            with open(marker, 'rb') as stream:
                marker_content = base64.b64encode(stream.read()).decode('ascii')
        known = name in dependencies or name in bundles
        state = {'schema': 2, 'name': name, 'version': '', 'archive': '', 'sha256': '',
                 'source': sources.get(name, ''), 'sourcePresent': name in sources,
                 'dependency': dependencies.get(name), 'dependencyPresent': name in dependencies,
                 'dependencyGroups': dependency_groups,
                 'enabled': name in bundles if known else not marked, 'disabledMarker': marked,
                 'disabledMarkerContent': marker_content, 'originalDirectory': directory or '',
                 'savedAt': int(time.time()), 'existed': bool(directory)}
        if directory:
            pkg = self.g['plugin_package'](directory)
            if pkg.get('name') != name or not str(pkg.get('version', '')):
                raise ValueError('当前插件名称或版本无法确认，已停止覆盖：' + name)
            archive = self.g['store_plugin_archive'](directory)
            state.update(version=str(pkg['version']), archive=archive, sha256=self.digest(archive))
            self._snapshot_archive(state, verify_digest=False)
        return state

    def _apply_state(self, name, state):
        """只恢复该包的来源、bundle 选择与标记；依赖规格由官方安装器和锁文件管理。"""
        sources = self._sources()
        if state.get('sourcePresent', True):
            sources[name] = state.get('source', '')
        else:
            sources.pop(name, None)
        self.write(self.local(self.g['SOURCES']), sources)
        doc = self.builtin.read_manifest()
        enabled = bool(state.get('enabled', True))
        if not isinstance(doc, dict):
            if enabled:
                raise ValueError('官方安装后 profile 清单不存在，无法恢复启用状态')
        else:
            bundles = doc.setdefault('dsh', {}).setdefault('profile', {}).setdefault('bundles', [])
            if enabled and name not in bundles:
                bundles.append(name)
                self.builtin.write_manifest(doc)
            elif not enabled and name in bundles:
                doc['dsh']['profile']['bundles'] = [item for item in bundles if item != name]
                self.builtin.write_manifest(doc)
        marker = self.builtin.marker_path(name)
        if os.path.lexists(marker) and (os.path.islink(marker) or not os.path.isfile(marker)):
            raise ValueError('插件禁用标记类型异常：' + marker)
        if state.get('disabledMarker', False):
            content = base64.b64decode(state.get('disabledMarkerContent', ''), validate=True)
            os.makedirs(os.path.dirname(marker), exist_ok=True)
            temporary = marker + '.transaction-' + uuid.uuid4().hex
            try:
                with open(temporary, 'xb') as stream:
                    stream.write(content)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, marker)
            finally:
                if os.path.isfile(temporary) and not os.path.islink(temporary):
                    os.unlink(temporary)
        elif os.path.isfile(marker):
            os.unlink(marker)

    def _verify_profile(self, name, version):
        directory = self.g['resolve_profile_plugin_dir'](name)
        if not directory or not os.path.isdir(directory):
            raise ValueError('官方安装后 profile 实际插件目录缺失：' + name)
        pkg = self.g['plugin_package'](directory)
        if pkg.get('name') != name:
            raise ValueError('官方安装后 profile 实际包名不匹配：' + name)
        if version and str(pkg.get('version', '')) != str(version):
            raise ValueError('官方安装后 profile 实际版本不匹配：' + name)
        doc = self.builtin.read_manifest() or {}
        if name not in self.g['profile_dependencies'](doc):
            raise ValueError('官方安装后 profile 未登记插件依赖：' + name)
        return directory, pkg

    def _uncertain(self, error):
        kind = self.g.get('OfficialInstallUncertain')
        return isinstance(kind, type) and isinstance(error, kind)

    def _remove_failed_install(self, name):
        """新安装失败时仅调用官方 remove；不进入会拿同一锁并清历史的 cmd_delete。"""
        try:
            process = subprocess.run(
                ['dsh', 'plugin', '--profile', 'web', 'remove', name],
                cwd=self.local(self.builtin.PROFILE),
                env=dict(os.environ, DSH_HOME=self.home), stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True, timeout=120)
        except subprocess.TimeoutExpired as error:
            kind = self.g.get('OfficialInstallUncertain', ValueError)
            raise kind('官方清理超时，需先回查 profile 状态，禁止自动重做') from error
        if process.returncode:
            detail = ((process.stderr or '') + '\n' + (process.stdout or '')).strip()[-1200:]
            raise ValueError('新安装失败后的官方清理失败：' + (detail or str(process.returncode)))
        doc = self.builtin.read_manifest() or {}
        if name in self.g['profile_dependencies'](doc) \
                or name in doc.get('dsh', {}).get('profile', {}).get('bundles', []):
            raise ValueError('新安装失败后的官方清理回查未通过：' + name)

    def _commit_history(self, name, backup, work):
        target = self.history_path(name)
        previous = os.path.join(work, 'previous-history')
        if os.path.islink(target) or (os.path.lexists(target) and not os.path.isdir(target)):
            raise ValueError('插件历史目录类型异常，已停止提交')
        moved = False
        if os.path.isdir(target):
            os.replace(target, previous)
            moved = True
        try:
            os.replace(backup, target)
        except Exception as error:
            if moved:
                try:
                    os.replace(previous, target)
                except Exception as restore_error:
                    raise RuntimeError('历史提交失败：' + str(error) + '；原历史恢复失败：' +
                                       str(restore_error) + '；原历史保留于：' + previous) from error
            raise

    def _check_pending_transactions(self):
        """安装、回退与注册/开关共用同一未完成事务检查。"""
        try:
            self.builtin.assert_no_pending_plugin_transactions()
        except RuntimeError as error:
            raise ValueError(str(error)) from error

    def _record_operation(self, work, operation, phase, strict=True, **details):
        operation.update(details)
        operation.update(phase=phase, updatedAt=int(time.time()))
        try:
            self.write(os.path.join(work, 'operation.json'), operation)
            self._sync_directory(work)
        except Exception as error:
            message = '事务诊断记录写入失败：' + str(error) + '；事务：' + work
            if strict:
                raise RuntimeError(message) from error
            # 诊断写入失败不能阻断旧包恢复，也不能把已提交的安装误判成失败。
            print('DSHA: ' + message, flush=True)

    @staticmethod
    def _sync_directory(path):
        descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    @staticmethod
    def installation_message(message, installed):
        details = list(installed.get('warnings') or [])
        if installed.get('retiredPath'):
            details.append('已将未引用的旧目录移入备份区：' + installed['retiredPath'])
        return message + ('\n' + '\n'.join(details) if details else '')

    def install_package(self, spec, name, version, source='', restore_state=None):
        """官方安装的唯一事务入口；调用方不可在已持有 operation_lock 时再次进入。"""
        with self.builtin.operation_lock():
            return self._install_package_locked(spec, name, version, source, restore_state)

    def _install_package_locked(self, spec, name, version, source='', restore_state=None):
        self._check_pending_transactions()
        if not self.builtin.valid_name(name) or self.builtin.is_official_bundle(name):
            raise ValueError('官方核心或无效插件名不能由此安装')
        if restore_state is not None and (not isinstance(restore_state, dict) or restore_state.get('name') != name):
            raise ValueError('恢复状态的插件名称不匹配')
        doc = self.builtin.read_manifest() or {}
        registered = name in self.g['profile_dependencies'](doc) \
            or name in doc.get('dsh', {}).get('profile', {}).get('bundles', [])
        directory = self.g['resolve_plugin_dir'](name)
        if registered and not self.g['resolve_profile_plugin_dir'](name):
            raise ValueError('插件已登记但 profile 实际包缺失，无法备份，已停止覆盖：' + name)
        if directory and not os.path.isdir(directory):
            raise ValueError('当前插件目录缺失，无法备份：' + name)
        transaction_root = self.path('plugin-transactions')
        os.makedirs(transaction_root, mode=0o700, exist_ok=True)
        work = self.path('plugin-transactions/' + uuid.uuid4().hex)
        os.mkdir(work, mode=0o700)
        backup = os.path.join(work, 'backup')
        os.mkdir(backup, mode=0o700)
        # 快照必须先落盘，再允许官方 add 修改 profile 或 pnpm 链接。
        operation = {'name': name, 'spec': spec, 'version': str(version), 'source': source,
                     'createdAt': int(time.time())}
        self._record_operation(work, operation, 'preparing')
        try:
            previous = self._capture_state(name, directory, doc)
            self.write(os.path.join(backup, 'state.json'), previous)
            self._sync_directory(backup)
            if previous.get('archive'):
                self._sync_directory(self.path('plugin-archives'))
            self._record_operation(work, operation, 'prepared')
            self._sync_directory(work)
            self._sync_directory(transaction_root)
            self._sync_directory(self.home)
        except Exception as error:
            self._record_operation(work, operation, 'snapshot-failed', strict=False, error=str(error))
            raise ValueError('安装前快照未完成，未执行官方安装：' + str(error) + '；事务：' + work) from error
        self._record_operation(work, operation, 'installing')
        try:
            raw = self.g['cmd_official_add'](spec, name, str(version))
            active, pkg = self._verify_profile(name, version)
            self._record_operation(work, operation, 'verified')
            if restore_state is None:
                desired = dict(previous)
                desired.update(source=source or self.g['repository_url'](pkg) or previous.get('source', ''),
                               sourcePresent=True)
            else:
                desired = dict(restore_state)
                if desired.get('name') != name:
                    raise ValueError('恢复状态的插件名称不匹配')
            self._apply_state(name, desired)
            # 再核对实际包，防止状态保存期间路径已被外部变动。
            self._verify_profile(name, version)
            self._record_operation(work, operation, 'committing-history')
            if previous.get('existed'):
                self._commit_history(name, backup, work)
        except Exception as error:
            if self._uncertain(error):
                self._record_operation(work, operation, 'uncertain', strict=False, error=str(error))
                raise type(error)(str(error) + '；快照已保留，需先回查，禁止自动恢复或重做；事务：' + work) from error
            recovery_errors = []
            self._record_operation(work, operation, 'restoring', strict=False, error=str(error))
            try:
                if previous.get('existed'):
                    archive = self._snapshot_archive(previous)
                    self.g['cmd_official_add']('file:' + archive, name, previous['version'])
                    self._verify_profile(name, previous['version'])
                else:
                    self._remove_failed_install(name)
                restored = dict(previous)
                if not previous.get('existed'):
                    restored['enabled'] = False
                self._apply_state(name, restored)
                held_history = os.path.join(work, 'previous-history')
                if os.path.lexists(held_history):
                    raise RuntimeError('插件代码和状态已恢复，但原历史尚未归位，备份保留于：' + held_history)
            except Exception as recovery_error:
                recovery_errors.append(str(recovery_error))
                phase = 'uncertain' if self._uncertain(recovery_error) else 'recovery-required'
                self._record_operation(work, operation, phase, strict=False, recoveryError=str(recovery_error))
                if self._uncertain(recovery_error):
                    raise type(recovery_error)(str(error) + '；恢复结果不确定，需先回查：' + str(recovery_error)
                                               + '；快照和原历史已保留；事务：' + work) from recovery_error
            if recovery_errors:
                raise RuntimeError(str(error) + '；恢复未完成：' + '；'.join(recovery_errors)
                                   + '；快照和原历史已保留；事务：' + work) from error
            self._record_operation(work, operation, 'restored', strict=False)
            raise ValueError(str(error) + '；已恢复安装前的插件和状态；事务快照保留于：' + work) from error
        self._record_operation(work, operation, 'committed', strict=False)
        result = dict(raw) if isinstance(raw, dict) else {'result': raw}
        result.update(name=name, version=str(pkg.get('version', '')))
        warnings = list(result.get('warnings') or [])
        original = previous.get('originalDirectory', '')
        managed = self.local(os.path.join(self.g.get('PLUGIN_SRC', os.path.join(self.g['DSH_HOME'], 'plugin-src')), name))
        if original and os.path.realpath(original) != os.path.realpath(active) \
                and os.path.abspath(original) != os.path.abspath(managed):
            display_path = self.g.get('container_path', lambda path: path)(original)
            warnings.append('旧版目录不属于可退役的 DSHA plugin-src 精确路径，未搬动该目录：' + display_path)
        # 旧目录只有在新包核验和历史提交均完成后，才允许退役。
        try:
            retired = self.g['retire_legacy_directory'](name, active) or {}
            warnings.extend(retired.get('warnings') or [])
            if retired.get('retiredPath'):
                result['retiredPath'] = retired['retiredPath']
        except Exception as error:
            warnings.append('插件已安装，但旧目录退役失败：' + str(error))
        try:
            # 归档区不随事务清理；当前 file: 依赖可能仍引用其中任一 tgz。
            shutil.rmtree(work)
        except OSError as error:
            warnings.append('插件已安装，但事务目录清理失败：' + work + '：' + str(error))
        if warnings:
            result['warnings'] = warnings
        return result

    def clear_history(self, name):
        path = self.history_path(name)
        if os.path.islink(path):
            raise ValueError('插件历史目录是软链接，已停止清理')
        if os.path.isdir(path):
            shutil.rmtree(path)

    def dsh_version(self):
        for path in ('/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json', '/root/dsh-src/package.json'):
            value = self.read(self.local(path), {})
            if value.get('name') == '@deepseek-ai/dsh':
                return value.get('version', '')
        return ''

    def metadata(self, root):
        pkg = self.g['plugin_package'](root)
        author = pkg.get('author', '')
        if isinstance(author, dict):
            author = author.get('name', '')
        requirement = (pkg.get('peerDependencies') or {}).get('@deepseek-ai/dsh') or (pkg.get('engines') or {}).get('dsh') or ''
        if not isinstance(requirement, str):
            requirement = ''
        installed = self.dsh_version()
        accepted = accepts_version(installed, requirement) if installed and requirement else None
        state = 'compatible' if accepted is True else 'incompatible' if accepted is False else 'unknown'
        text = ('适用 dsh：' + requirement if requirement else '作者未声明 dsh 兼容范围')
        text += '；当前：' + (installed or '版本未知')
        if accepted is False:
            text += '。版本不匹配，安装前请确认作者说明'
        elif accepted is None:
            text += '。兼容性需要确认'
        return {'name': pkg['name'], 'version': str(pkg.get('version', '')), 'author': str(author)[:200] or '未注明',
                'description': str(pkg.get('description', ''))[:1500], 'requiredDsh': requirement,
                'installedDsh': installed, 'compatibility': state, 'compatibilityMessage': text,
                'repository': self.g['repository_url'](pkg)}

    @staticmethod
    def digest(path):
        digest = hashlib.sha256()
        with open(path, 'rb') as stream:
            for chunk in iter(lambda: stream.read(65536), b''):
                digest.update(chunk)
        return digest.hexdigest()

    def preview_path(self, key):
        if not re.fullmatch('[a-f0-9]{32}', key):
            raise ValueError('无效安装预览，请重新解析链接')
        return self.path('plugin-previews/' + key)

    def prune_previews(self):
        root = self.path('plugin-previews')
        os.makedirs(root, exist_ok=True)
        for key in os.listdir(root):
            if not re.fullmatch('[a-f0-9]{32}', key):
                continue
            path = self.preview_path(key)
            if os.path.isdir(path) and not os.path.islink(path) and time.time() - os.path.getmtime(path) > 7200:
                shutil.rmtree(path)

    def inspect_npm(self, spec, path, key, request, receive):
        """锁定 npm 的实际版本，再把 tarball 交给统一归档预览流程。"""
        if not shutil.which('npm'):
            raise ValueError('npm 尚未就绪，请关闭终端后重新打开')
        res = subprocess.run(['npm', 'view', spec, '--json'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)
        if res.returncode != 0:
            err = (res.stderr or res.stdout).strip()
            raise ValueError('npm 插件查询失败：' + (err[-600:] if err else '包不存在或网络错误'))
        try:
            pkg = json.loads(res.stdout)
            if isinstance(pkg, list) and pkg:
                pkg = pkg[-1]
        except Exception:
            raise ValueError('npm 返回数据无法解析')
        if not isinstance(pkg, dict) or not pkg.get('name') or not pkg.get('version'):
            raise ValueError('npm 插件信息无效：未返回明确版本')
        name = str(pkg['name'])
        version = str(pkg['version'])
        item = self.metadata_from_dict(pkg)
        if request.get('name') and item['name'] != request['name']:
            raise ValueError('下载包的名称与选中的插件不一致')
        if request.get('version') and item['version'] != request['version']:
            raise ValueError('下载包版本与网页说明不一致')
        # 预览和确认必须针对同一个不可变版本，不能让 latest 在两次操作间漂移。
        exact_spec = name + '@' + version
        self.g['cmd_npm'](exact_spec,
                           consume=lambda archive, source='': receive(archive, source=source, official_spec=exact_spec))
        prepared = self.read(os.path.join(path, 'preview.json'), {})
        actual_items = prepared.get('items') or []
        if len(actual_items) != 1 or actual_items[0].get('name') != name \
                or str(actual_items[0].get('version', '')) != version:
            raise ValueError('npm 下载包的名称或版本与查询结果不一致')
        return prepared

    def annotate_existing_install(self, prepared):
        """若安装包中的插件已存在，智能对比版本并标记为更新/重新覆盖，绝不生硬终止。"""
        items = prepared.get('items') or []
        for item in items:
            name = item.get('name')
            if not name:
                continue
            cur_dir = self.g['resolve_plugin_dir'](name)
            if not cur_dir or not os.path.isdir(cur_dir):
                continue
            cur_pkg = self.read(os.path.join(cur_dir, 'package.json'), {})
            cur_ver = str(cur_pkg.get('version', '')).strip()
            if not cur_ver:
                continue
            new_ver = str(item.get('version', '')).strip()
            cmp = compare_versions(new_ver, cur_ver)
            if cmp is not None and cmp > 0:
                item['updateStatus'] = 'upgrade'
                item['installedVersion'] = cur_ver
                item['compatibilityMessage'] = (item.get('compatibilityMessage', '') +
                    f"\n【版本升级】当前已安装 v{cur_ver}，确认后将交由 DSH 官方 profile 安装器更新至 v{new_ver}。")
            elif cmp == 0:
                item['updateStatus'] = 'reinstall'
                item['installedVersion'] = cur_ver
                item['compatibilityMessage'] = (item.get('compatibilityMessage', '') +
                    f"\n【重新安装】当前已安装相同版本 v{cur_ver}，确认后将交由 DSH 官方 profile 安装器重新安装。")
            else:
                item['updateStatus'] = 'downgrade'
                item['installedVersion'] = cur_ver
                item['compatibilityMessage'] = (item.get('compatibilityMessage', '') +
                    f"\n【版本降级】⚠ 警告：当前已安装较新版本 v{cur_ver}，确认后交由 DSH 官方 profile 安装器处理降级至 v{new_ver}。")
            if not prepared.get('updateFor'):
                prepared['updateFor'] = name
                prepared['fromVersion'] = cur_ver

    def metadata_from_dict(self, pkg):
        author = pkg.get('author', '')
        if isinstance(author, dict):
            author = author.get('name', '')
        requirement = (pkg.get('peerDependencies') or {}).get('@deepseek-ai/dsh') or (pkg.get('engines') or {}).get('dsh') or ''
        if not isinstance(requirement, str):
            requirement = ''
        installed = self.dsh_version()
        accepted = accepts_version(installed, requirement) if installed and requirement else None
        state = 'compatible' if accepted is True else 'incompatible' if accepted is False else 'unknown'
        text = ('适用 dsh：' + requirement if requirement else '作者未声明 dsh 兼容范围')
        text += '；当前：' + (installed or '版本未知')
        if accepted is False:
            text += '。版本不匹配，安装前请确认作者说明'
        elif accepted is None:
            text += '。兼容性需要确认'
        return {'name': pkg['name'], 'version': str(pkg.get('version', '')), 'author': str(author)[:200] or '未注明',
                'description': str(pkg.get('description', ''))[:1500], 'requiredDsh': requirement,
                'installedDsh': installed, 'compatibility': state, 'compatibilityMessage': text,
                'repository': self.g['repository_url'](pkg)}

    def inspect(self, request, emit=True):
        if isinstance(request, str):
            request = json.loads(request)
        if not isinstance(request, dict):
            raise ValueError('安装请求格式错误')
        command = request.get('command', '')
        if not isinstance(command, str) or len(command) > 8192:
            raise ValueError('安装链接过长或格式错误')
        parts = shlex.split(command)
        if not parts or parts[0] not in ('download', 'github', 'release', 'npm', 'file'):
            raise ValueError('链接只支持下载和插件包解析')
        expected_hash = request.get('sha256', '')
        if expected_hash and not re.fullmatch('[a-fA-F0-9]{64}', expected_hash):
            raise ValueError('插件校验码格式错误')
        self.prune_previews()
        key = uuid.uuid4().hex
        path = self.preview_path(key); os.makedirs(path)
        prepared = None

        def receive(archive, subdir='', source='', official_spec=''):
            nonlocal prepared
            if not os.path.isfile(archive) or os.path.getsize(archive) > self.g['MAX_DOWNLOAD']:
                raise ValueError('插件包不存在或超过 256 MiB')
            digest = self.digest(archive)
            if expected_hash and digest.lower() != expected_hash.lower():
                raise ValueError('插件 SHA-256 与网站提供的校验码不一致')
            destination = os.path.join(path, 'archive')
            shutil.copyfile(archive, destination)
            staging = os.path.join(path, 'contents')
            self.g['extract_archive'](destination, staging)
            roots = self.g['find_plugin_roots'](staging, subdir)
            items = [self.metadata(root) for root in roots]
            names = [item['name'] for item in items]
            if len(set(names)) != len(names):
                raise ValueError('归档中包含同名插件')
            if request.get('name') and (len(items) != 1 or items[0]['name'] != request['name']):
                raise ValueError('下载包的名称与选中的插件不一致')
            if request.get('version') and (len(items) != 1 or items[0]['version'] != request['version']):
                raise ValueError('下载包版本与网页说明不一致')
            prepared = {'previewId': key, 'source': source, 'command': command,
                        'sha256': digest, 'officialSpec': str(official_spec or request.get('officialSpec', '') or ''),
                        'items': items, 'createdAt': int(time.time()), 'subdir': subdir,
                        'updateFor': request.get('updateFor', ''), 'fromVersion': request.get('fromVersion', '')}
            self.annotate_existing_install(prepared)
            self.write(os.path.join(path, 'preview.json'), prepared)
            shutil.rmtree(staging)
            return 0
        try:
            if parts[0] == 'download' and len(parts) == 2:
                self.g['cmd_download'](parts[1], consume=receive)
            elif parts[0] == 'github' and len(parts) in (3, 4):
                self.g['cmd_github'](*parts[1:], consume=receive)
            elif parts[0] == 'release' and len(parts) == 4:
                self.g['cmd_release'](*parts[1:], consume=receive)
            elif parts[0] == 'npm' and len(parts) == 2:
                spec = parts[1].removeprefix("npm:")
                prepared = self.inspect_npm(spec, path, key, request, receive)
            elif parts[0] == 'file' and len(parts) == 2:
                receive(self.local(parts[1]))
            else:
                raise ValueError('不支持的安装参数')
            if prepared is None:
                raise ValueError('未获得可安装的插件包')
        except Exception:
            shutil.rmtree(path); raise
        if emit:
            self.g['result']('ok', '插件包已解析，请核对作者、版本和兼容信息后确认安装', preview=prepared)
            return 0
        return prepared

    def show_preview(self, key):
        path = self.preview_path(key)
        preview = self.read(os.path.join(path, 'preview.json'), {})
        if not preview or time.time() - preview.get('createdAt', 0) > 3600:
            raise ValueError('安装预览已过期，请重新检查更新')
        self.g['result']('ok', '请确认插件版本与兼容范围', preview=preview)
        return 0

    def discard_preview(self, key):
        path = self.preview_path(key)
        if os.path.isdir(path):
            shutil.rmtree(path)
        self.g['result']('ok', '已取消安装并清理临时包')
        return 0

    def install_preview(self, key):
        path = self.preview_path(key)
        preview = self.read(os.path.join(path, 'preview.json'), {})
        if not preview or time.time() - preview.get('createdAt', 0) > 3600:
            raise ValueError('安装预览已过期，请重新解析链接')

        if preview.get('type') == 'npm':
            shutil.rmtree(path, ignore_errors=True)
            raise ValueError('旧版 npm 预览已失效，请重新解析链接')

        try:
            archive = os.path.join(path, 'archive')
            if not os.path.isfile(archive) or self.digest(archive) != preview.get('sha256'):
                raise ValueError('暂存包已变化，请重新解析链接')
            if preview.get('updateFor'):
                directory = self.g['resolve_plugin_dir'](preview['updateFor'])
                current = self.read(os.path.join(directory, 'package.json'), {}) if directory else {}
                if current.get('version') != preview.get('fromVersion'):
                    raise ValueError('插件已被其他操作更新，请重新检查版本')
            items = preview.get('items') or []
            official_spec = str(preview.get('officialSpec', '') or '').strip()
            if official_spec:
                if len(items) != 1 or not items[0].get('name'):
                    raise ValueError('官方安装器一次只支持一个插件包')
                name = str(items[0]['name'])
                installed = self.install_package(official_spec, name, str(items[0].get('version', '')),
                                                 source=preview.get('source', ''))
                extra = {key: installed[key] for key in ('warnings', 'retiredPath') if key in installed}
                message = self.installation_message('已通过 DSH 官方 profile 安装器安装 ' + name + '；重启 Web 后生效。', installed)
                self.g['result']('ok', message,
                                 installed=[name], official=True, application='restart-required',
                                 restartReason='no-hmr', packageResult=installed, **extra)
                return 0
            outcome = self.g['cmd_official_archive'](
                archive, preview.get('subdir', ''), items, preview.get('sha256', ''),
                source=preview.get('source', ''))
            extra = {key: outcome[key] for key in ('warnings', 'retiredPath') if key in outcome}
            self.g['result'](outcome['status'], outcome['message'],
                             installed=outcome['installed'], official=True,
                             application=outcome['application'], restartReason=outcome['restartReason'],
                             packageResults=outcome['packageResults'], **extra)
            return 0 if outcome['status'] == 'ok' else 1
        finally:
            shutil.rmtree(path, ignore_errors=True)

    def source_command(self, name):
        sources = self.read(self.local(self.g['SOURCES']), {})
        directory = self.g['resolve_plugin_dir'](name)
        pkg = self.read(os.path.join(directory, 'package.json'), {}) if directory else {}
        repo_url = self.g['repository_url'](pkg)
        source = sources.get(name, '')
        if not source or (name in self.builtin.builtin_names() and (source.startswith('npm:') or not source) and repo_url):
            source = repo_url or source
        if source.startswith('npm:'):
            match = re.fullmatch(r'((?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*)(?:@[^/]+)?', source[4:])
            if not match:
                raise ValueError('npm 来源无法识别，请重新导入')
            return 'npm ' + shlex.quote(match[1] + '@latest')
        uri = urllib.parse.urlsplit(source)
        if uri.hostname in ('registry.npmjs.org', 'registry.npmmirror.com') and '/-/' in uri.path:
            package = urllib.parse.unquote(uri.path.split('/-/')[0]).strip('/')
            if self.builtin.valid_name(package):
                return 'npm ' + shlex.quote(package + '@latest')
        pieces = uri.path.strip('/').split('/')
        if uri.hostname in ('github.com', 'www.github.com') and len(pieces) >= 2:
            owner, repository = pieces[:2]
            if len(pieces) > 2 and pieces[2] == 'releases':
                return 'release ' + shlex.join([owner, repository, 'latest'])
            if len(pieces) > 3 and pieces[2] == 'tree':
                return 'github ' + shlex.join([owner, repository, '/'.join(pieces[3:])])
            if len(pieces) > 2 and pieces[2] == 'archive':
                return 'download ' + shlex.quote(source)
            return 'github ' + shlex.join([owner, repository])
        if uri.scheme == 'https' and uri.hostname:
            return 'download ' + shlex.quote(source)
        raise ValueError('此插件没有可检查的来源，请从作者页面取得新包后导入')

    def check_updates(self, name=''):
        doc = self.builtin.read_manifest() or {}
        candidates = [name] if name else list(doc.get('dependencies', {}))
        states = self.read(self.path('plugin-updates.json'), {})
        checked = []
        for item in candidates:
            if self.builtin.is_official_bundle(item):
                continue
            if not self.builtin.valid_name(item):
                continue
            directory = self.g['resolve_plugin_dir'](item)
            pkg = self.read(os.path.join(directory, 'package.json'), {}) if directory else {}
            if not (pkg.get('dsh') or {}).get('bundle'):
                continue
            status = {'name': item, 'installedVersion': str(pkg.get('version', '')), 'checkedAt': int(time.time()), 'available': False}
            try:
                preview = self.inspect({'command': self.source_command(item), 'name': item,
                                       'updateFor': item, 'fromVersion': pkg.get('version', '')}, False)
                latest = preview['items'][0]
                comparison = compare_versions(latest['version'], status['installedVersion'])
                status.update(latestVersion=latest['version'], previewId=preview['previewId'],
                              available=comparison is not None and comparison > 0,
                              compatibility=latest['compatibilityMessage'], source=preview['source'])
                status['message'] = '有新版本' if status['available'] else '来源未提供更高版本' if comparison is not None else '版本格式不同，请手动核对'
            except Exception as error:
                status['message'] = str(error)
            states[item] = status; checked.append(status)
        with self.builtin.operation_lock():
            latest_states = self.read(self.path('plugin-updates.json'), {})
            latest_states.update({item['name']: item for item in checked})
            self.write(self.path('plugin-updates.json'), latest_states)
        updates = sum(1 for item in checked if item['available'])
        self.g['result']('ok', '已检查 %d 个插件，%d 个可更新；详情见插件卡片' % (len(checked), updates), updates=checked)
        return 0

    def update(self, name):
        """命令行或一键更新：自动解析插件来源最新版本并执行就地无缝升级。"""
        if not self.builtin.valid_name(name):
            raise ValueError("无效的插件名称")
        directory = self.g['resolve_plugin_dir'](name)
        if not directory or not os.path.isdir(directory):
            raise ValueError(f"插件 {name} 尚未安装，请先使用 install 命令安装")
        pkg = self.read(os.path.join(directory, 'package.json'), {})
        cur_ver = str(pkg.get('version', '')).strip()
        cmd = self.source_command(name)
        preview = self.inspect({'command': cmd, 'name': name,
                               'updateFor': name, 'fromVersion': cur_ver}, emit=False)
        latest = (preview.get('items') or [{}])[0]
        latest_ver = str(latest.get('version', '')).strip()
        cmp = compare_versions(latest_ver, cur_ver)
        if cmp is not None and cmp <= 0:
            self.g['result']('ok', f"{name} 已是最新版本 (v{cur_ver})，无需更新。")
            return 0
        return self.install_preview(preview['previewId'])

    def rollback(self, name, expected=''):
        if not self.builtin.valid_name(name) or self.builtin.is_official_bundle(name):
            raise ValueError('官方核心或无效插件名不能回退')
        with self.builtin.operation_lock():
            self._check_pending_transactions()
            previous = self.history_info(name)
            if not previous:
                raise ValueError('没有可回退的上一版')
            if expected and str(previous.get('version', '')) != str(expected):
                raise ValueError('上一版已发生变化，请刷新后重新确认')
            previous = dict(previous)
            if previous.get('schema') != 2:
                # 兼容旧版 history/package，但恢复仍交给官方安装器，不能重入旧注册器。
                directory = os.path.join(self.history_path(name), 'package')
                pkg = self.g['plugin_package'](directory)
                if pkg.get('name') != name or str(pkg.get('version', '')) != str(previous.get('version', '')):
                    raise ValueError('旧版历史的包名或版本不匹配，已停止回退')
                archive = self.g['store_plugin_archive'](directory)
                doc = self.builtin.read_manifest() or {}
                marker = self.builtin.marker_path(name)
                marked = os.path.lexists(marker)
                content = ''
                if marked:
                    if os.path.islink(marker) or not os.path.isfile(marker):
                        raise ValueError('插件禁用标记类型异常：' + marker)
                    with open(marker, 'rb') as stream:
                        content = base64.b64encode(stream.read()).decode('ascii')
                previous.update(schema=2, archive=archive, sha256=self.digest(archive),
                                sourcePresent='source' in previous,
                                enabled=name in doc.get('dsh', {}).get('profile', {}).get('bundles', []),
                                disabledMarker=marked, disabledMarkerContent=content)
            archive = self._snapshot_archive(previous)
            installed = self._install_package_locked('file:' + archive, name, str(previous.get('version', '')),
                                                     source=previous.get('source', ''), restore_state=previous)
        extra = {key: installed[key] for key in ('warnings', 'retiredPath') if key in installed}
        message = self.installation_message('已回退 ' + name + ' 至 ' + str(previous.get('version', ''))
                                            + '；来源和备份启用状态已恢复，重启 Web 生效', installed)
        self.g['result']('ok', message,
                         installed=[name], official=True, application='restart-required',
                         restartReason='no-hmr', packageResult=installed, **extra)
        return 0

    def safe_mode(self, action):
        path = self.path('plugin-safe-mode.json')
        with self.builtin.operation_lock():
            if action != 'status':
                self._check_pending_transactions()
            state = self.read(path, {'active': False, 'names': []})
            doc = self.builtin.read_manifest() or {}
            if action == 'on':
                names = [n for n in doc.get('dsh', {}).get('profile', {}).get('bundles', [])
                         if self.builtin.valid_name(n) and not self.builtin.is_official_bundle(n) and n not in self.builtin.builtin_names()]
                remembered = list(dict.fromkeys(state.get('names', []) + names))
                self.write(path, {'active': True, 'names': remembered})
                for name in names:
                    if self.builtin.disable_plugin(name):
                        raise ValueError('无法停用第三方插件：' + name + '；其他插件保持已停用，可重试安全启动')
                    marker = self.builtin.marker_path(name)
                    os.makedirs(os.path.dirname(marker), exist_ok=True)
                    with open(marker, 'w', encoding='utf-8') as out:
                        out.write('DSHA_SAFE_MODE\n')
                self.g['result']('ok', '安全模式已启用，第三方插件暂时停用，数据保留；可重启 Web', safeMode=True, count=len(remembered))
                return 0
            if action == 'off':
                restored = []
                remaining = []
                for name in state.get('names', []):
                    if not self.builtin.valid_name(name):
                        continue
                    marker = self.builtin.marker_path(name)
                    if not os.path.isfile(marker):
                        continue
                    with open(marker, encoding='utf-8') as stream:
                        held = stream.read(128) == 'DSHA_SAFE_MODE\n'
                    # 用户在安全模式期间手动禁用的插件不被批量恢复覆盖。
                    if held and self.g['resolve_plugin_dir'](name):
                        if self.builtin.enable_plugin(name): remaining.append(name)
                        else: restored.append(name)
                self.write(path, {'active': bool(remaining), 'names': remaining})
                self.g['result']('partial' if remaining else 'ok', '已恢复 %d 个插件的启用状态；重启 Web 生效%s' %
                                 (len(restored), ('；仍需修复：' + '、'.join(remaining)) if remaining else ''), safeMode=bool(remaining))
                return 1 if remaining else 0
            if action == 'status':
                self.g['result']('ok', '安全模式已启用' if state.get('active') else '正常启动模式', safeMode=bool(state.get('active')))
                return 0
            raise ValueError('安全模式只支持 on / off / status')
