#!/usr/bin/env python3
"""不调用编译器：从真实 Java 提取命令表达式，在临时目录验证 shell 原子读写与转义。"""
import json
import re
import subprocess
import tempfile
from pathlib import Path
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[1]
JAVA = REPO / 'app/src/main/java/com/deepseekharness/app/core/FreezeWhitelistRepository.java'


def quote(value):
    return "'" + value.replace("'", "'\\''") + "'"


def expression(text, method, normalized=''):
    body = text.split('static String ' + method + '(', 1)[1]
    expression = body.split('return ', 1)[1].split(';\n    }', 1)[0]
    constants = {'PATH': '/data/adb/dsha/run/idle-freeze-whitelist.conf', 'normalized': normalized}
    for key in ('GUIDE_PATH', 'INITIAL_RULES', 'GUIDE'):
        value = text.split('String ' + key + ' =', 1)[1].split(';\n', 1)[0]
        constants[key] = ''.join(json.loads(token) for token in re.findall(r'"(?:\\.|[^"\\])*"', value))
    tokens = re.compile(r'\s*("(?:\\.|[^"\\])*"|ShellQuote\.arg\((?:PATH|normalized|GUIDE_PATH|INITIAL_RULES|GUIDE)\)|\+)')
    output, offset = '', 0
    while offset < len(expression):
        match = tokens.match(expression, offset)
        assert match, expression[offset:]
        token = match.group(1)
        if token.startswith('"'):
            output += json.loads(token)
        elif token.startswith('ShellQuote'):
            output += quote(constants[token.split('(', 1)[1][:-1]])
        offset = match.end()
    return output


def main():
    text = JAVA.read_text()
    with tempfile.TemporaryDirectory(prefix='freeze-whitelist-static-') as name:
        root = Path(name)
        target = root / 'run/idle-freeze-whitelist.conf'
        fixture = "# comment\nname:worker (one)\nscript:/tmp/a 'quote' $(touch INJECTION) `touch INJECTION` ;.py\n"
        def command(method, data=''):
            return expression(text, method, data).replace('/data/adb/dsha', str(root))
        def run(method, data='', ok=True):
            result = subprocess.run(['sh', '-c', command(method, data)], cwd=root, capture_output=True)
            if ok:
                assert result.returncode == 0, result.stderr
            else:
                assert result.returncode != 0
            return result
        run('readCommand')
        assert not target.exists()
        prepared = run('prepareLocationCommand')
        assert prepared.stdout.decode() == str(target)
        guide = target.parent / '冻结白名单教程.md'
        assert guide.exists() and '128' in guide.read_text() and '16 KiB' in guide.read_text()
        initial = target.read_text()
        assert initial and all(line.startswith('#') for line in initial.splitlines())
        assert target.stat().st_mode & 0o777 == 0o600
        run('saveCommand', fixture)
        original_guide = guide.read_bytes()
        run('prepareLocationCommand')
        assert target.read_text() == fixture and guide.read_bytes() == original_guide
        assert target.read_text() == fixture
        assert target.stat().st_mode & 0o777 == 0o600
        assert not (root / 'INJECTION').exists()
        assert run('readCommand').stdout.decode() == fixture
        assert not list(target.parent.glob('.idle-freeze-whitelist.*'))
        run('saveCommand', '')
        assert target.read_bytes() == b''
        target.write_bytes(b'x' * 16385)
        result = run('readCommand', ok=False)
        assert result.stdout == b'' and '16KiB' in result.stderr.decode()
        target.unlink()  # exact temporary fixture verified above
        victim = root / 'victim'
        victim.write_text('unchanged')
        target.symlink_to(victim)
        run('readCommand', ok=False)
        run('saveCommand', fixture, ok=False)
        run('prepareLocationCommand', ok=False)
        assert victim.read_text() == 'unchanged'
        target.unlink()  # exact temporary symlink, not its destination
        target.mkdir()
        run('saveCommand', fixture, ok=False)
        assert target.is_dir() and not list(target.iterdir())
        print('PASS shell夹具：缺失读取、原子写入、600权限、引号/命令替换不执行、清空、过大拒读、符号链接拒绝')
    xml = ET.parse(REPO / 'app/src/main/res/layout/fragment_config.xml')
    android = '{http://schemas.android.com/apk/res/android}'
    ids = [node.get(android + 'id') for node in xml.iter()]
    assert ids.index('@+id/config_btn_freeze_whitelist') > ids.index('@+id/config_btn_view_freeze_log')
    assert '@+id/config_btn_clear_freeze_log' not in ids
    assert 'ShellQuote.arg(normalized)' in text and 'normalize(text)' in text
    assert 'getBytes(StandardCharsets.UTF_8).length > 80' in text
    assert 'getBytes(StandardCharsets.UTF_8).length > 512' in text
    assert 'if (++rules > 128)' in text
    assert 'new byte[limit + 1]' in text and 'CodingErrorAction.REPORT' in text
    assert 'Character.isISOControl' in text and 'line.indexOf(\'\\t\') >= 0' in text
    print('PASS XML按钮位置、纯Java校验边界及严格UTF8/超限检测静态检查；未编译或运行Java')


if __name__ == '__main__':
    main()
