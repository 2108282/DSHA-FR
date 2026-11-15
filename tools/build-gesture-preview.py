#!/usr/bin/env python3
"""Build a Java-only gesture preview on ARM64 without aapt2; never install it.

Requires a base APK whose resources match the cached application classes. Signing
requires DSHA_KEYSTORE_PASSWORD, DSHA_KEY_PASSWORD and a matching signing key.
Passwords are passed via environment references, never command-line values.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

DEX_HELPER = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

public class GestureDexFilter {
  public static void main(String[] a) throws Exception {
    Opcodes ops = Opcodes.forApi(31);
    Set<String> exact = new HashSet<>(Files.readAllLines(Path.of(a[2])));
    List<String> families = Files.readAllLines(Path.of(a[3]));
    Set<String> removed = new TreeSet<>();
    for (File file : Objects.requireNonNull(new File(a[0]).listFiles())) {
      if (!file.getName().endsWith(".dex")) continue;
      DexFile dex = DexFileFactory.loadDexFile(file, ops);
      Set<ClassDef> keep = new LinkedHashSet<>();
      for (ClassDef cls : dex.getClasses()) {
        String type = cls.getType();
        boolean drop = exact.contains(type);
        for (String family : families) {
          if (type.equals(family + ";") || type.startsWith(family + "$")) drop = true;
        }
        if (drop) removed.add(type); else keep.add(cls);
      }
      if (keep.size() == dex.getClasses().size()) {
        Files.copy(file.toPath(), Path.of(a[1], file.getName()));
      } else {
        DexPool.writeTo(Path.of(a[1], file.getName()).toString(), new ImmutableDexFile(ops, keep));
      }
    }
    // Check that no duplicate types remain across retained base DEX + patch DEX.
    Set<String> seen = new HashSet<>();
    for (String dir : new String[]{a[1], a[4]}) {
      for (File file : Objects.requireNonNull(new File(dir).listFiles())) {
        if (!file.getName().endsWith(".dex")) continue;
        for (ClassDef cls : DexFileFactory.loadDexFile(file, ops).getClasses()) {
          if (dir.equals(a[4]) && !cls.getType().startsWith("Lcom/deepseekharness/app/"))
            throw new IllegalStateException("External or compile-only patch class " + cls.getType());
          if (!seen.add(cls.getType())) throw new IllegalStateException("Duplicate DEX class " + cls.getType());
        }
      }
    }
    Files.write(Path.of(a[5]), removed);
    System.out.println("Removed old classes: " + removed.size() + "; unique output types: " + seen.size());
  }
}
'''


def run(argv, *, capture=False, env=None):
    # Arguments contain environment-variable names for passwords, never values.
    display = []
    hide_next = False
    for arg in map(str, argv):
        if hide_next:
            display.append('<classpath>')
            hide_next = False
        else:
            display.append(arg)
            hide_next = arg in ('-cp', '-classpath', '--classpath')
    print("+ " + " ".join(display), flush=True)
    return subprocess.run(list(map(str, argv)), check=True, text=True,
                          capture_output=capture, env=env)


def certificate_digests(apksigner, apk):
    result = run(['java', '-jar', apksigner, 'verify', '--print-certs', apk], capture=True)
    digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', result.stdout)
    if not digests:
        raise RuntimeError('No signer certificate returned for ' + str(apk))
    return sorted(x.lower() for x in digests)


def read_resource_ids(apk):
    """Hash the preserved resource table; cached R.jar must match the base APK."""
    with zipfile.ZipFile(apk) as z:
        if 'resources.arsc' not in z.namelist():
            raise RuntimeError('Base APK has no resources.arsc')
        return hashlib.sha256(z.read('resources.arsc')).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-apk', type=Path, required=True)
    parser.add_argument('--cache-repo', type=Path,
                        default=Path('/root/workspaces/DSH项目维护/DSHA_repo'))
    parser.add_argument('--sdk', type=Path, default=Path('/usr/lib/android-sdk'))
    parser.add_argument('--gradle-cache', type=Path, default=Path.home() / '.gradle/caches')
    parser.add_argument('--jadx-jar', type=Path, default=Path('/opt/jadx/lib/jadx-1.5.6-all.jar'))
    parser.add_argument('--stubs-source', type=Path, action='append', default=[],
                        help='Directory containing compile-only hidden API Java stubs (repeatable)')
    parser.add_argument('--source', type=Path, action='append', default=[],
                        help='Extra Java source to replace, in addition to gesture/* and SheetSettingsFragment')
    parser.add_argument('--classpath', type=Path, action='append', default=[])
    parser.add_argument('--keystore', type=Path, default=os.environ.get('DSHA_KEYSTORE'))
    parser.add_argument('--key-alias', default=os.environ.get('DSHA_KEY_ALIAS'))
    parser.add_argument('--output', type=Path, default=Path('build/gesture-preview/gesture-preview.apk'))
    parser.add_argument('--unsigned', action='store_true', help='Produce unsigned APK only; cannot cover-install')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    base = args.base_apk.resolve(strict=True)
    output = args.output.resolve()
    if output == base or output.exists():
        raise RuntimeError('Output must be a new path distinct from the base APK')
    sdk = args.sdk.resolve()
    android = sdk / 'platforms/android-37.0/android.jar'
    bt = sdk / 'build-tools/36.0.0'
    d8 = bt / 'lib/d8.jar'
    signer = bt / 'lib/apksigner.jar'
    for path in [android, d8, signer, args.jadx_jar]:
        if not path.is_file():
            raise RuntimeError('Missing tool: ' + str(path))
    base_certs = certificate_digests(signer, base)
    if not args.unsigned:
        if not args.keystore or not args.key_alias:
            raise RuntimeError('Set --keystore/DSHA_KEYSTORE and --key-alias/DSHA_KEY_ALIAS, or use --unsigned')
        if not args.keystore.is_file():
            raise RuntimeError('Signing keystore does not exist')
        for name in ['DSHA_KEYSTORE_PASSWORD', 'DSHA_KEY_PASSWORD']:
            if name not in os.environ:
                raise RuntimeError('Missing signing environment variable ' + name)
        # keytool -exportcert reads the public certificate only. Compare before compilation.
        cert = subprocess.run(['keytool', '-exportcert', '-keystore', str(args.keystore),
                               '-alias', args.key_alias, '-storepass:env', 'DSHA_KEYSTORE_PASSWORD'],
                              check=True, capture_output=True).stdout
        if [hashlib.sha256(cert).hexdigest()] != base_certs:
            raise RuntimeError('Signing certificate differs from base APK; refusing a non-cover-installable build')
    src_root = repo / 'app/src/main/java'
    gesture = src_root / 'com/deepseekharness/app/gesture'
    sources = sorted(gesture.rglob('*.java')) if gesture.is_dir() else []
    if not sources:
        raise RuntimeError('No gesture Java sources yet')
    sources += [src_root / 'com/deepseekharness/app/ui/SheetSettingsFragment.java'] + args.source
    for p in sources:
        if not p.is_file():
            raise RuntimeError('Missing source: ' + str(p))
    cache = args.cache_repo / 'app/build'
    app_classes = cache / 'intermediates/javac/standardDebug/compileStandardDebugJavaWithJavac/classes'
    kotlin = cache / 'intermediates/built_in_kotlinc/standardDebug/compileStandardDebugKotlin/classes'
    rjar = cache / 'intermediates/compile_r_class_jar/standardDebug/generateStandardDebugRFile/R.jar'
    if not app_classes.is_dir() or not rjar.is_file():
        raise RuntimeError('Cached application classes and R.jar are required; use --cache-repo')
    cp = [android, app_classes, rjar]
    if kotlin.is_dir():
        cp.append(kotlin)
    # AAR extraction caches contain Java bytecode, usable without architecture-specific aapt2.
    cp += sorted(args.gradle_cache.glob('*/transforms/*/transformed/*/jars/classes.jar'))
    modules = args.gradle_cache / 'modules-2/files-2.1'
    for group, artifacts in [('androidx.annotation', ['*']), ('androidx.lifecycle', ['lifecycle-common*']),
                             ('androidx.collection', ['collection*']),
                             ('org.jetbrains.kotlin', ['kotlin-stdlib*', 'kotlin-reflect']),
                             ('org.jetbrains', ['annotations']), ('com.google.code.gson', ['gson']),
                             ('org.jetbrains.kotlinx', ['kotlinx-coroutines*'])]:
        group_dir = modules / group
        if group_dir.is_dir():
            for artifact in artifacts:
                cp += sorted(group_dir.glob(artifact + '/*/*/*.jar'))
    cp += args.classpath
    cp = list(dict.fromkeys(cp))
    output.parent.mkdir(parents=True, exist_ok=True)
    resource_hash = read_resource_ids(base)
    with tempfile.TemporaryDirectory(prefix='dsha-gesture-', dir=output.parent) as tmp:
        work = Path(tmp)
        classes = work / 'classes'
        stubs = work / 'stubs'
        patch_dex = work / 'patch-dex'
        old_dex = work / 'base-dex'
        kept_dex = work / 'kept-dex'
        for directory in [classes, stubs, patch_dex, old_dex, kept_dex]:
            directory.mkdir()
        stub_sources = []
        for directory in args.stubs_source:
            stub_sources.extend(sorted(directory.rglob('*.java')))
        # Detect the conventional source tree, while never putting stubs in runtime DEX.
        conventional = repo / 'app/src/hiddenApi/java'
        if not args.stubs_source and conventional.is_dir():
            stub_sources = sorted(conventional.rglob('*.java'))
        if stub_sources:
            run(['javac', '-source', '17', '-target', '17', '-proc:none', '-classpath', os.pathsep.join(map(str, cp)),
                 '-d', stubs, *stub_sources])
            cp.insert(0, stubs)
        classpath = os.pathsep.join(map(str, cp))
        run(['javac', '-source', '17', '-target', '17', '-proc:none', '-implicit:none',
             '-sourcepath', '', '-classpath', classpath, '-d', classes, *sources])
        patch_classes = sorted(classes.rglob('*.class'))
        if not patch_classes:
            raise RuntimeError('Compilation produced no classes')
        forbidden = [p for p in patch_classes if not p.relative_to(classes).as_posix().startswith('com/deepseekharness/app/')]
        if forbidden:
            raise RuntimeError('Compile-only or external classes would enter runtime DEX')
        class_jar = work / 'patch.jar'
        with zipfile.ZipFile(class_jar, 'w', zipfile.ZIP_DEFLATED) as z:
            for p in patch_classes:
                z.write(p, p.relative_to(classes).as_posix())
        d8_args = ['java', '-cp', d8, 'com.android.tools.r8.D8', '--min-api', '31', '--lib', android,
                   '--output', patch_dex]
        for path in cp:
            if path != android:
                d8_args.extend(['--classpath', path])
        run([*d8_args, class_jar])
        exact_types = work / 'exact.txt'
        exact_types.write_text(''.join('L' + p.relative_to(classes).as_posix()[:-6] + ';\n' for p in patch_classes))
        family_types = set()
        for source in sources:
            text = source.read_text()
            match = re.search(r'^\s*package\s+([\w.]+)\s*;', text, re.MULTILINE)
            if not match:
                raise RuntimeError('Source lacks package: ' + str(source))
            family_types.add('L' + match.group(1).replace('.', '/') + '/' + source.stem)
        families = work / 'families.txt'
        families.write_text('\n'.join(sorted(family_types)))
        dex_re = re.compile(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex')
        with zipfile.ZipFile(base) as z:
            for name in z.namelist():
                if dex_re.fullmatch(name):
                    (old_dex / name).write_bytes(z.read(name))
        if not list(old_dex.glob('*.dex')):
            raise RuntimeError('Base APK has no DEX files')
        helper = work / 'GestureDexFilter.java'
        helper.write_text(DEX_HELPER)
        run(['javac', '-classpath', args.jadx_jar, '-d', work, helper])
        removed = work / 'removed.txt'
        run(['java', '-cp', str(work) + os.pathsep + str(args.jadx_jar), 'GestureDexFilter',
             old_dex, kept_dex, exact_types, families, patch_dex, removed])
        unsigned = work / 'unsigned.apk'
        with zipfile.ZipFile(base) as original, zipfile.ZipFile(unsigned, 'w') as dest:
            for entry in original.infolist():
                name = entry.filename
                upper = name.upper()
                is_signature = upper.startswith('META-INF/') and (
                    upper.endswith(('.SF', '.RSA', '.DSA', '.EC')) or upper == 'META-INF/MANIFEST.MF')
                if dex_re.fullmatch(name) or is_signature:
                    continue
                # Keep ZipInfo attributes, compression and alignment extras for existing entries.
                dest.writestr(entry, original.read(name))
            max_index = 1
            for p in sorted(kept_dex.glob('*.dex')):
                match = re.fullmatch(r'classes(\d*)\.dex', p.name)
                max_index = max(max_index, int(match.group(1) or '1'))
                dest.write(p, p.name, compress_type=zipfile.ZIP_DEFLATED)
            for p in sorted(patch_dex.glob('*.dex')):
                max_index += 1
                dest.write(p, f'classes{max_index}.dex', compress_type=zipfile.ZIP_DEFLATED)
        # Standard DSHA APK compresses its native library. Refuse unaligned uncompressed natives.
        with zipfile.ZipFile(unsigned) as z, open(unsigned, 'rb') as stream:
            for entry in z.infolist():
                if entry.compress_type == zipfile.ZIP_STORED and entry.filename.startswith('lib/') and entry.filename.endswith('.so'):
                    stream.seek(entry.header_offset + 26)
                    name_len, extra_len = struct.unpack('<HH', stream.read(4))
                    if (entry.header_offset + 30 + name_len + extra_len) % 16384:
                        raise RuntimeError('Uncompressed native library needs ARM64 zipalign before signing')
        if args.unsigned:
            output.write_bytes(unsigned.read_bytes())
        else:
            run(['java', '-jar', signer, 'sign', '--ks', args.keystore, '--ks-key-alias', args.key_alias,
                 '--ks-pass', 'env:DSHA_KEYSTORE_PASSWORD', '--key-pass', 'env:DSHA_KEY_PASSWORD',
                 '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
                 '--out', output, unsigned])
            if certificate_digests(signer, output) != base_certs:
                raise RuntimeError('Output certificate does not match base APK')
        if read_resource_ids(output) != resource_hash:
            raise RuntimeError('Resources changed unexpectedly')
        report = {'base_apk': str(base), 'output_apk': str(output), 'signed': not args.unsigned,
                  'base_certificate_sha256': base_certs, 'resources_sha256': resource_hash,
                  'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
                  'java_sources': [str(p) for p in sources],
                  'removed_types': removed.read_text().splitlines(),
                  'patch_classes': [p.relative_to(classes).as_posix() for p in patch_classes],
                  'classpath_entries': [str(p) for p in cp]}
        output.with_suffix('.build.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print('Built ' + str(output) + (' (unsigned; cannot install over existing app)' if args.unsigned else ' (certificate matches base APK)'))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, zipfile.BadZipFile) as exc:
        print('ERROR: ' + str(exc), file=sys.stderr)
        sys.exit(1)
