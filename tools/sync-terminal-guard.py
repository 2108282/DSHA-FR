#!/usr/bin/env python3
"""Render only terminal guard overlay files from the module start heredocs.

Source ported from magisk-apk 79bdbc1. Module extensions retain rm's cross-device
protection and confirm's lease/cleanup whitelist. No APK checkout is required.
Run with --check to verify the tracked overlay without modifying files.
"""
import argparse
from pathlib import Path
import re

REPO = Path(__file__).resolve().parents[1]
START = REPO / 'scripts/start.sh'
MIRROR = REPO / 'magisk-module/scripts/start.sh'
OVERLAY = REPO / 'rootfs-overlay/root'


def heredoc(text, marker):
    return text.split("<< '" + marker + "'", 1)[1].split('\n', 1)[1].split('\n' + marker, 1)[0] + '\n'


def rendered_files():
    text = START.read_text()
    if text != MIRROR.read_text():
        raise ValueError('Both module start.sh copies must be identical')
    files = {
        'dsh-confirm.sh': heredoc(text, 'CONFIRM_EOF'),
        'dsh-guard-policy.sh': heredoc(text, 'POLICY_EOF'),
        'dsh-guard.sh': heredoc(text, 'GUARD_EOF'),
        'dsh-bin/rm': heredoc(text, 'RM_WRAPPER_EOF'),
        'dsh-bin/find': heredoc(text, 'FIND_WRAPPER_EOF'),
        'dsh-bin/adb': heredoc(text, 'ADB_WRAPPER_EOF'),
        'dsh-bin/.version': '12\n',
    }
    commands = re.search(r'^for C in (.+); do\ncat << \'WRAPPER_EOF\'', text, re.M).group(1).split()
    for command in commands:
        files['dsh-bin/' + command] = heredoc(text, 'WRAPPER_EOF')
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    files = rendered_files()
    mismatches = []
    for relative, content in files.items():
        path = OVERLAY / relative
        if args.check:
            if not path.is_file() or path.read_text() != content:
                mismatches.append(relative)
            elif relative != 'dsh-bin/.version' and path.stat().st_mode & 0o111 != 0o111:
                mismatches.append(relative + ' (not executable)')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            path.chmod(0o644 if relative == 'dsh-bin/.version' else 0o755)
    if mismatches:
        parser.exit(1, 'Guard overlay differs: ' + ', '.join(mismatches) + '\n')
    print(('Verified' if args.check else 'Rendered') + f' {len(files)} terminal guard overlay files')


if __name__ == '__main__':
    main()
