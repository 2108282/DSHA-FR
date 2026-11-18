#!/usr/bin/env python3
"""Fail the Android build if application dialogs bypass the shared themed entry point."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "app" / "src" / "main" / "java"

RULES = [
    (re.compile(r"new\s+(?:(?:android\.app|androidx\.appcompat\.app)\.)?AlertDialog\.Builder\s*\("),
     "Use DshaDialogBuilder instead of creating AlertDialog.Builder directly."),
    (re.compile(r"new\s+(?:com\.google\.android\.material\.dialog\.)?MaterialAlertDialogBuilder\s*\("),
     "MaterialAlertDialogBuilder bypasses the shared app-dialog theme; use DshaDialogBuilder."),
    (re.compile(r"new\s+(?:com\.google\.android\.material\.bottomsheet\.)?BottomSheetDialog\s*\("),
     "Use the shared card-sheet/dialog component instead of creating BottomSheetDialog directly."),
    (re.compile(r"new\s+(?:(?:android\.app|androidx\.appcompat\.app)\.)?Dialog\s*\("),
     "Use DshaDialogBuilder or document a narrowly scoped platform-dialog exception."),
    (re.compile(r"new\s+(?:(?:android\.widget\.)?PopupMenu)\s*\("),
     "PopupMenu needs a shared style or explicit review before adding."),
]

errors = []
for source in sorted(JAVA_ROOT.rglob("*.java")):
    rel = source.relative_to(JAVA_ROOT).as_posix()
    text = source.read_text(encoding="utf-8")
    for pattern, message in RULES:
        for match in pattern.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            errors.append(f"{rel}:{line}: {message}")
    match = re.search(r"new\s+PopupWindow\s*\(", text)
    if match and "DshaPopupTheme.apply(" not in text:
        line = text.count("\n", 0, match.start()) + 1
        errors.append(
            f"{rel}:{line}: PopupWindow must apply DshaPopupTheme or receive an explicit reviewed exception."
        )

if errors:
    print("Dialog entry-point check failed:", file=sys.stderr)
    print("\n".join(f" - {error}" for error in errors), file=sys.stderr)
    sys.exit(1)

print("Dialog entry-point check passed: modal dialogs use shared styling; anchored dropdowns are allowlisted.")
