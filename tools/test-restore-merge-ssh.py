#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Host-side tests for opt-in transactional SSH restore; only temporary data is used."""
import importlib.util
import os
import shutil
import stat
import tempfile
import contextlib
import io

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "..", "app", "src", "main", "assets", "restore-merge.py")
SPEC = importlib.util.spec_from_file_location("restore_merge", SCRIPT)
rm = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rm)

passed = 0


def check(name, cond):
    global passed
    if cond:
        passed += 1
        print("PASS", name)
    else:
        print("FAIL", name)


def tree():
    base = tempfile.mkdtemp(prefix="dsha_ssh_restore_test_")
    return base, os.path.join(base, "stage"), os.path.join(base, "root")


def set_up_archive(stage):
    ssh = os.path.join(stage, ".ssh")
    os.makedirs(os.path.join(ssh, "keys"))
    with open(os.path.join(ssh, "config"), "w", encoding="utf-8") as f:
        f.write("Host test-host\n    HostName example.invalid\n")
    with open(os.path.join(ssh, "passwords.json"), "w", encoding="utf-8") as f:
        f.write('{"test-host":"synthetic-test-only"}')
    with open(os.path.join(ssh, "keys", "id_test"), "w", encoding="utf-8") as f:
        f.write("synthetic-private-key-test-fixture\n")
    with open(os.path.join(ssh, "keys", "id_test.pub"), "w", encoding="utf-8") as f:
        f.write("ssh-ed25519 synthetic-public-test-fixture\n")
    with open(os.path.join(ssh, "askpass.sh"), "w", encoding="utf-8") as f:
        f.write("#!/bin/sh\\nprintf '%s\\\\n' \\\"$SSHPASS\\\"\\n")
    return ssh


# Explicit consent restores every file byte-for-byte and applies usable permissions.
base, stage, root = tree()
os.makedirs(stage)
os.makedirs(root)
source = set_up_archive(stage)
rm.report.clear()
check("1.1 explicit include flag reports restoration", rm.restore_ssh(stage, root, {"includeSshKey": True}) is True)
restored = os.path.join(root, ".ssh")
check("1.2 host config preserved", open(os.path.join(restored, "config"), encoding="utf-8").read()
      == open(os.path.join(source, "config"), encoding="utf-8").read())
check("1.3 password map preserved", open(os.path.join(restored, "passwords.json"), encoding="utf-8").read()
      == open(os.path.join(source, "passwords.json"), encoding="utf-8").read())
check("1.4 private key bytes preserved", open(os.path.join(restored, "keys", "id_test"), "rb").read()
      == open(os.path.join(source, "keys", "id_test"), "rb").read())
check("1.5 directory/private/public modes", stat.S_IMODE(os.stat(restored).st_mode) == 0o700
      and stat.S_IMODE(os.stat(os.path.join(restored, "keys", "id_test")).st_mode) == 0o600
      and stat.S_IMODE(os.stat(os.path.join(restored, "keys", "id_test.pub")).st_mode) == 0o644
       and stat.S_IMODE(os.stat(os.path.join(restored, "askpass.sh")).st_mode) == 0o700)
shutil.rmtree(base)

# No explicit opt-in (including legacy manifest) leaves live SSH untouched.
for manifest, label in (({}, "missing opt-in"), (None, "legacy archive"),
                        ({"includeSshKey": False}, "explicit opt-out")):
    base, stage, root = tree()
    os.makedirs(stage)
    live = os.path.join(root, ".ssh")
    os.makedirs(live)
    with open(os.path.join(live, "config"), "w", encoding="utf-8") as f:
        f.write("live-only")
    check("2 %s does not restore" % label, rm.restore_ssh(stage, root, manifest) is False)
    check("2 %s leaves live config" % label,
          open(os.path.join(live, "config"), encoding="utf-8").read() == "live-only")
    shutil.rmtree(base)

# The explicit flag with missing data fails visibly and preserves live config.
base, stage, root = tree()
os.makedirs(stage)
os.makedirs(os.path.join(root, ".ssh"))
with open(os.path.join(root, ".ssh", "config"), "w", encoding="utf-8") as f:
    f.write("live-preserved")
try:
    rm.restore_ssh(stage, root, {"includeSshKey": True})
    missing_failed = False
except ValueError:
    missing_failed = True
check("3.1 missing opted-in payload fails", missing_failed)
check("3.2 missing payload preserves live config",
      open(os.path.join(root, ".ssh", "config"), encoding="utf-8").read() == "live-preserved")
shutil.rmtree(base)

# Symlink entries are rejected before commit.
base, stage, root = tree()
os.makedirs(os.path.join(stage, ".ssh"))
os.makedirs(root)
os.symlink("/tmp", os.path.join(stage, ".ssh", "unsafe"))
try:
    rm.restore_ssh(stage, root, {"includeSshKey": True})
    unsafe_failed = False
except ValueError:
    unsafe_failed = True
check("4 unsafe symlink rejected", unsafe_failed and not os.path.lexists(os.path.join(root, ".ssh")))
shutil.rmtree(base)

# Exercise main ordering and manifest gating with a synthetic empty full backup.
def run_main_case(include_flag, include_payload, live_exists):
    base, stage, root = tree()
    os.makedirs(stage)
    os.makedirs(root)
    if include_payload:
        set_up_archive(stage)
    with open(os.path.join(stage, ".dsha-backup-manifest.json"), "w", encoding="utf-8") as f:
        import json
        json.dump({"scope": "full", "includeSshKey": include_flag}, f)
    os.makedirs(os.path.join(stage, ".dsh"))
    with open(os.path.join(stage, ".dsh", "settings.yaml"), "w", encoding="utf-8") as f:
        f.write("synthetic-settings")
    if live_exists:
        os.makedirs(os.path.join(root, ".ssh"))
        with open(os.path.join(root, ".ssh", "config"), "w", encoding="utf-8") as f:
            f.write("preexisting-live")
    old_argv = rm.sys.argv
    output = io.StringIO()
    try:
        rm.sys.argv = [SCRIPT, "--stage", stage, "--root", root, "--workdir", os.path.join(root, "work")]
        with contextlib.redirect_stdout(output):
            rc = rm.main()
    finally:
        rm.sys.argv = old_argv
    stdout = output.getvalue()
    return base, root, rc, stdout


base, root, rc, stdout = run_main_case(True, True, False)
check("4.1 merger success invokes opted-in SSH restore", rc == 0
      and os.path.isfile(os.path.join(root, ".ssh", "config"))
      and "已恢复 SSH 主机配置与密钥" in stdout)
shutil.rmtree(base)

base, root, rc, stdout = run_main_case(False, True, True)
check("4.2 merger respects explicit opt-out", rc == 0
      and open(os.path.join(root, ".ssh", "config"), encoding="utf-8").read() == "preexisting-live"
      and "已恢复 SSH" not in stdout)
shutil.rmtree(base)

base, root, rc, stdout = run_main_case(True, False, True)
check("4.3 missing opted-in SSH is visible partial result", rc == 0
      and "SSH 恢复失败" in stdout and "RESTORE_PARTIAL" in stdout
      and open(os.path.join(root, ".ssh", "config"), encoding="utf-8").read() == "preexisting-live")
shutil.rmtree(base)

# Simulate the final rename failure; original SSH directory is rolled back.
base, stage, root = tree()
os.makedirs(stage)
os.makedirs(os.path.join(root, ".ssh"))
with open(os.path.join(root, ".ssh", "config"), "w", encoding="utf-8") as f:
    f.write("old-live-config")
set_up_archive(stage)
real_rename = rm.os.rename

def fail_publish(src, dst):
    if src.startswith(os.path.join(root, ".ssh.restore-stage-")) and dst == os.path.join(root, ".ssh"):
        raise OSError("synthetic publish failure")
    return real_rename(src, dst)

rm.os.rename = fail_publish
try:
    try:
        rm.restore_ssh(stage, root, {"includeSshKey": True})
        publish_failed = False
    except OSError:
        publish_failed = True
finally:
    rm.os.rename = real_rename
check("5.1 publish failure surfaced", publish_failed)
check("5.2 publish failure restores original SSH",
      open(os.path.join(root, ".ssh", "config"), encoding="utf-8").read() == "old-live-config")
shutil.rmtree(base)

print("RESULT: %d passed" % passed)
raise SystemExit(0 if passed == 19 else 1)
