#!/usr/bin/env python3
"""Check shared dialog entry points and scrollable, shrinkable XML dialog bodies."""
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "app" / "src" / "main" / "java"

BUILDER_RE = re.compile(r"new\s+(?:(?:com\.deepseekharness\.app\.ui\.)?DshaDialogBuilder)\s*\(")
LEGACY_BUILDER_RE = re.compile(
    r"new\s+(?:(?:android\.app|androidx\.appcompat\.app)\.)?AlertDialog\.Builder\s*\("
)
RULES = [
    (LEGACY_BUILDER_RE,
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

ANDROID = "{http://schemas.android.com/apk/res/android}"
SCROLL_TAGS = {"ScrollView", "NestedScrollView", "MaxHeightNestedScrollView"}


def view_type(view):
    return view.tag.rsplit("}", 1)[-1].rsplit(".", 1)[-1]


def check_dialog_layout(root, rel):
    """Check body scroll bounds without inspecting weights of horizontal form fields."""
    layout_errors = []
    parents = {child: parent for parent in root.iter() for child in parent}

    def report(view, message):
        view_id = view.get(ANDROID + "id", view_type(view))
        layout_errors.append(f"{rel}:{view_id}: {message}")

    for view in root.iter():
        kind = view_type(view)
        if kind.endswith("EditText"):
            ancestor = parents.get(view)
            while ancestor is not None and view_type(ancestor) not in SCROLL_TAGS:
                ancestor = parents.get(ancestor)
            if ancestor is None:
                report(view, "Dialog input must be inside a ScrollView or NestedScrollView body.")

        if kind not in SCROLL_TAGS:
            continue
        if len(view) > 1:
            report(view, "A scroll container must have at most one direct child.")

        parent = parents.get(view)
        if (parent is None or view_type(parent) != "ModernCardView"
                or parent.get(ANDROID + "orientation") != "vertical"):
            continue
        siblings = list(parent)
        following = siblings[siblings.index(view) + 1:]
        has_fixed_actions = any(
            view_type(descendant).endswith("Button")
            for sibling in following for descendant in sibling.iter()
        )
        if not has_fixed_actions:
            continue
        try:
            weight = float(view.get(ANDROID + "layout_weight", "0"))
        except ValueError:
            weight = 0
        if not math.isfinite(weight) or weight <= 0:
            report(view, "A card body above fixed actions needs a positive layout_weight to shrink.")
        if view.get(ANDROID + "layout_height") not in {"wrap_content", "0dp", "0dip"}:
            report(view, "A weighted card body must use wrap_content or 0dp, not a fixed height.")
    return layout_errors


errors = []
model_setup = JAVA_ROOT / "com" / "deepseekharness" / "app" / "ui" / "ModelSetupActivity.java"
model_text = model_setup.read_text(encoding="utf-8")
confirm_model = re.search(
    r"private void confirmDeleteModel\([^)]*\)\s*\{([\s\S]*?)\n    \}", model_text
)
if not confirm_model or "DshaCardDialogs.confirm(" not in confirm_model.group(1):
    errors.append(
        "com/deepseekharness/app/ui/ModelSetupActivity.java:confirmDeleteModel: "
        "model removal confirmation must use the shared Skia-card confirmation component."
    )

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

RES_ROOT = ROOT / "app" / "src" / "main" / "res"
for layout in sorted(RES_ROOT.glob("layout*/dialog_*.xml")):
    rel = layout.relative_to(ROOT).as_posix()
    try:
        root = ET.parse(layout).getroot()
    except ET.ParseError as error:
        errors.append(f"{rel}: Invalid dialog XML: {error}")
        continue
    errors.extend(check_dialog_layout(root, rel))

if errors:
    print("Dialog entry-point check failed:", file=sys.stderr)
    print("\n".join(f" - {error}" for error in errors), file=sys.stderr)
    sys.exit(1)

print("Dialog entry-point check passed: shared dialog styling and scrollable XML bodies verified.")
