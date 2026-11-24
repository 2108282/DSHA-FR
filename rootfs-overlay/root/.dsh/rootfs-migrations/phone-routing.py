#!/usr/bin/env python3
"""Update known phone-routing instructions while retaining unrelated AGENTS content."""
import argparse
from pathlib import Path
import os
import stat
import tempfile

RULE = ('- 涉及手机操控与自动化任务时，使用 `mcp__phone__*`：先按任务选择原生接口，'
        '电话、短信、联系人、日历、媒体、闹钟、计时器直接调用对应原生工具，不先读屏，'
        '也不靠读屏确认原生结果；有事务状态接口则查同一请求ID。只有需要识别界面并点击、'
        '输入、滚动时才先 `screen`，编号只在该屏有效，界面动作后检查新屏幕。'
        '打开应用或链接用 `open`；登录、验证码、支付或没把握的界面步骤用 `handoff`；'
        '明确授权的原生拨号/发送/日历变更走工具及现有确认策略，被拒绝不绕过。'
        '银行 / 支付类 App、系统设置页、密码和验证码框默认不碰，被拦下就 `handoff`。')
MANAGED = ('- 原生任务直接调用对应工具，不以 `screen` 为前置或结果验证；通过事务状态、'
           'Provider回查或系统回执确认。只有界面点击、输入、滚动前才读 `screen`，'
           '编号只在该观察内有效，界面动作后检查新屏幕。请求发出不等于事务完成，未知结果不能重复写入。')


def migrate(root):
    root = Path(root).resolve()
    path = root / 'root/.dsh/AGENTS.md'
    if path.is_symlink() or path.parent.resolve() != path.parent:
        raise ValueError('Refusing redirected AGENTS path')
    before = path.read_text(encoding='utf-8') if path.exists() else ''
    lines = before.splitlines(keepends=True)
    found = False
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith('- 涉及手机操控与自动化任务时，使用 `mcp__phone__*`'):
            lines[i] = RULE + '\n'
            found = True
        elif stripped.startswith('- 先 `screen`，编号只在本次有效观察内使用；操作后检查返回的新屏幕与结果。'):
            lines[i] = MANAGED + '\n'
    after = ''.join(lines)
    if not found:
        after += ('\n' if after and not after.endswith('\n') else '') + '\n' + RULE + '\n'
    if after == before:
        print('PHONE_ROUTING: already current')
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o644
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                                     prefix='.phone-routing-', delete=False) as stream:
        temp = Path(stream.name)
        stream.write(after)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temp, mode)
    os.replace(temp, path)
    print('PHONE_ROUTING: updated known phone rules; other content retained')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default='/')
    parser.add_argument('--fresh', action='store_true')
    args = parser.parse_args()
    if args.fresh and Path(args.root).resolve() == Path('/'):
        parser.error('--fresh requires an offline build directory')
    migrate(args.root)
