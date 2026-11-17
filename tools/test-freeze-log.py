#!/usr/bin/env python3
"""不编译 Java：提取真实命令表达式，验证临时夹具中的 Root 日志读/定位/删除。"""
import json
from pathlib import Path
import re
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / 'app/src/main/java/com/deepseekharness/app/core/FreezeLogRepository.java'
LITERAL = r'"(?:\\.|[^"\\])*"'


def strings(expression):
    tokens = re.findall(LITERAL, expression)
    residue = re.sub(LITERAL, '', expression).replace('+', '').strip()
    assert not residue, residue
    return ''.join(json.loads(token) for token in tokens)


def log_commands():
    text = SOURCE.read_text()
    selection = strings(text.split('private static final String SELECT_LOG =', 1)[1].split(';\n', 1)[0])
    commands = {}
    for mode in ('read', 'clear', 'location', 'delete'):
        body = text.split('static String ' + mode + 'Command()', 1)[1].split('\n    }', 1)[0]
        expression = body.split('return ', 1)[1].rstrip().removesuffix(';')
        expression = expression.replace('SELECT_LOG', json.dumps(selection)).replace('MAX_LOG_BYTES', '"65536"')
        commands[mode] = strings(expression)
    return commands


def main():
    commands = log_commands()
    with tempfile.TemporaryDirectory(prefix='dsha-freeze-log-test-') as directory:
        root = Path(directory)
        primary = root / 'run/idle-freezer.log'
        fallback = root / 'rootfs/root/.dsh/idle-freezer.log'
        primary.parent.mkdir()
        fallback.parent.mkdir(parents=True)

        def shell(mode, ok=True):
            command = commands[mode].replace('/data/adb/dsha', str(root))
            result = subprocess.run(['sh', '-c', command], capture_output=True)
            assert (result.returncode == 0) == ok, result.stderr
            return result

        assert shell('read').stdout.decode() == '暂无冻结日志'
        assert shell('location').stdout.decode() == str(primary)
        shell('delete')
        fallback.write_text('回退日志\n')
        assert shell('read').stdout.decode() == '回退日志\n'
        assert shell('location').stdout.decode() == str(fallback)
        shell('delete')
        assert not fallback.exists()
        primary.write_bytes(b'a' * 70000 + b'LATEST\n')
        assert len(shell('read').stdout) == 65536
        assert shell('read').stdout.endswith(b'LATEST\n')
        shell('clear')
        assert primary.read_bytes() == b''
        shell('delete')
        assert not primary.exists()

        fallback.write_text('软链接日志\n')
        primary.symlink_to(fallback)
        assert shell('location').stdout.decode() == str(fallback)
        shell('delete')
        assert primary.is_symlink() and not fallback.exists()
        # 守护的 >> 会沿保留的软链接重建实际日志。
        with primary.open('a') as stream:
            stream.write('再次追加\n')
        assert fallback.read_text() == '再次追加\n'
        shell('delete')
        shell('delete')  # 缺失日志允许重复删除。
        primary.unlink()  # exact temporary fixture
        victim = root / 'do-not-touch'
        victim.write_text('keep')
        primary.symlink_to(victim)
        for mode in ('read', 'clear', 'location', 'delete'):
            shell(mode, ok=False)
        assert victim.read_text() == 'keep'
        primary.unlink()  # exact temporary fixture
        primary.mkdir()
        shell('delete', ok=False)
        print('PASS 日志主/回退/软链接定位、64KiB读取、真实删除、保留软链接、追加重建、异常目标拒绝')

    helper = (REPO / 'app/src/main/java/com/deepseekharness/app/util/RootFileLocation.java').read_text()
    assert 'StrictMode.setVmPolicy(previous)' in helper and 'finally {' in helper
    assert 'Uri.fromFile(new File(absoluteFile)), "*/*"' in helper
    assert 'resource/folder' not in helper
    assert 'am start -a android.intent.action.VIEW -d ' in helper
    assert 'ShellQuote.arg("file://" + absoluteFile)' in helper
    assert 'openWithRoot(File cacheDir' in helper
    presenter = (REPO / 'app/src/main/java/com/deepseekharness/app/ui/contract/ConfigPresenter.java').read_text()
    assert presenter.count('RootFileLocation.openWithRoot(') == 2
    assert 'FileProvider' not in helper and '.exists()' not in helper
    assert 'return false;' in helper and 'isSupported(absoluteFile)' in helper
    print('PASS Root定位helper静态契约；未运行任何Java编译器')


if __name__ == '__main__':
    main()
