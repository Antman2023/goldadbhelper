#!/usr/bin/env python3
"""Build a development APK with native SDK/JDK tools, including on Windows."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

from version import read_version

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--build-dir', required=True, help='New output directory inside the repository build/ directory')
    parser.add_argument('--signing', choices=('debug', 'unsigned'), default='debug')
    args = parser.parse_args()
    if not args.sdk:
        parser.error('Set ANDROID_HOME or pass --sdk')
    build = (ROOT / args.build_dir).resolve()
    build_root = (ROOT / 'build').resolve()
    if build == build_root or build_root not in build.parents:
        parser.error('--build-dir must be a new child directory inside build/')
    if build.exists():
        parser.error('--build-dir already exists; choose a fresh directory to exclude stale outputs')
    sdk = Path(args.sdk).resolve()
    bt = sdk / 'build-tools/35.0.0'
    platform = sdk / 'platforms/android-35/android.jar'
    suffix = '.exe' if os.name == 'nt' else ''
    executables = {}
    for name in ('java', 'jar', 'keytool'):
        path = str(Path(args.java_home).resolve() / 'bin' / (name + suffix)) if args.java_home else shutil.which(name)
        if not path or not Path(path).is_file():
            parser.error('Missing JDK tool: ' + name)
        executables[name] = path
    for name in ('aapt2', 'aapt', 'zipalign'):
        executables[name] = str(bt / (name + suffix))
    for path in [platform, bt / 'lib/d8.jar', bt / 'lib/apksigner.jar', bt / 'core-lambda-stubs.jar',
                 *(Path(executables[name]) for name in ('aapt2', 'aapt', 'zipalign'))]:
        if not path.is_file():
            parser.error('Missing Android SDK input: ' + str(path))
    env = dict(os.environ, PYTHONUTF8='1')

    def run(*command, report=None):
        result = subprocess.run([str(x) for x in command], cwd=ROOT, env=env,
                                stdout=subprocess.PIPE if report else None, check=True)
        if report:
            report.write_bytes(result.stdout)
            print(result.stdout.decode('utf-8', errors='replace'), end='')

    def java(main_class, *arguments):
        run(executables['java'], '-cp', bt / 'lib/d8.jar', main_class, *arguments)

    version = read_version()
    for name in ('generated', 'classes', 'dex', 'desugar-dex', 'res', 'outputs'):
        (build / name).mkdir(parents=True)
    run(sys.executable, '-X', 'utf8', 'tools/snapshot-build.py', 'snapshot', build)
    run(executables['aapt2'], 'compile', '--dir', 'app/src/main/res', '-o', build / 'res/resources.zip')
    run(executables['aapt2'], 'link', '-o', build / 'base.apk', '-I', platform,
        '--manifest', 'app/src/main/AndroidManifest.xml', '--java', build / 'generated',
        '--custom-package', 'io.github.devicebridge.android', '--version-code', version['version_code'],
        '--version-name', version['version'], '-A', 'app/src/main/assets', build / 'res/resources.zip')
    sources = sorted(p for folder in (ROOT / 'core/src/main/java', ROOT / 'app/src/main/java', build / 'generated')
                     for p in folder.rglob('*.java'))
    # javac response files avoid the Windows command-line length limit.
    (build / 'app-sources.txt').write_text('\n'.join('"' + p.as_posix() + '"' for p in sources) + '\n', encoding='utf-8')
    libs = [ROOT / 'app/libs/conscrypt-android-2.5.3.jar', ROOT / 'app/libs/multidex-2.0.1.jar']
    run(executables['java'], '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-source', '8', '-target', '8',
        '-Xlint:-options', '-encoding', 'UTF-8', '-bootclasspath', platform,
        '-classpath', os.pathsep.join(str(p) for p in [*libs, bt / 'core-lambda-stubs.jar']),
        '-d', build / 'classes', '@' + str(build / 'app-sources.txt'))
    run(sys.executable, '-X', 'utf8', 'tools/snapshot-build.py', 'verify', build)
    run(executables['jar'], '--create', '--file', build / 'classes.jar', '-C', build / 'classes', '.')
    (build / 'main-dex.pro').write_text('-keep class io.github.devicebridge.android.BridgeRuntime { *; }\n'
                                     '-keep class androidx.multidex.** { *; }\n', encoding='utf-8')
    desugar = ROOT / 'third_party/desugar'
    common = ['--min-api', '19', '--lib', platform, '--desugared-lib', desugar / 'desugar.json']
    java('com.android.tools.r8.D8', *common, '--desugared-lib-pg-conf-output', build / 'desugar-keep.pro',
         '--main-dex-rules', build / 'main-dex.pro', '--output', build / 'dex', build / 'classes.jar', *libs)
    run(executables['java'], '-Xmx2g', '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.L8', '--release', *common,
        '--pg-conf', build / 'desugar-keep.pro', '--output', build / 'desugar-dex',
        desugar / 'desugar_jdk_libs_nio-2.1.5.jar', desugar / 'desugar_jdk_libs_configuration_nio-2.1.5.jar')
    shutil.copyfile(build / 'base.apk', build / 'unsigned.apk')
    with zipfile.ZipFile(build / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
        dex_files = sorted((build / 'dex').glob('*.dex')) + sorted((build / 'desugar-dex').glob('*.dex'))
        if not list((build / 'desugar-dex').glob('*.dex')):
            raise RuntimeError('Missing L8 backported API DEX')
        for index, path in enumerate(dex_files):
            archive.write(path, 'classes' + (str(index + 1) if index else '') + '.dex')
        native_root = ROOT / 'app/src/main/jniLibs'
        for path in sorted(native_root.rglob('*.so')):
            archive.write(path, 'lib/' + path.relative_to(native_root).as_posix())
    run(executables['zipalign'], '-f', '-p', '4', build / 'unsigned.apk', build / 'aligned.apk')
    # Legacy Windows aapt cannot open a Unicode APK filename. Validate an ASCII
    # staging name, then copy exactly those verified bytes to the display name.
    apk = build / 'candidate.apk'
    final_apk = build / 'outputs' / ('金甲虫ADB助手-' + version['version'] + ('-unsigned' if args.signing == 'unsigned' else '') + '.apk')
    if args.signing == 'debug':
        key = build_root / 'debug.keystore'
        if not key.exists():
            run(executables['keytool'], '-genkeypair', '-keystore', key, '-alias', 'goldenbeetle-debug',
                '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-storepass', 'android',
                '-keypass', 'android', '-dname', 'CN=GoldenBeetle Development,O=Independent Development,C=ZZ')
        signer = [executables['java'], '-jar', bt / 'lib/apksigner.jar']
        run(*signer, 'sign', '--ks', key, '--ks-key-alias', 'goldenbeetle-debug', '--ks-pass', 'pass:android',
            '--key-pass', 'pass:android', '--v4-signing-enabled', 'false', '--out', apk, build / 'aligned.apk')
        run(*signer, 'verify', '--verbose', '--print-certs', apk, report=build / 'outputs/signature.txt')
    else:
        shutil.copyfile(build / 'aligned.apk', apk)
    run(executables['zipalign'], '-c', '-p', '4', apk)
    run(executables['aapt'], 'dump', 'badging', apk, report=build / 'outputs/badging.txt')
    # Check again after packaging: the source archive must still identify this candidate.
    run(sys.executable, '-X', 'utf8', 'tools/snapshot-build.py', 'verify', build)
    shutil.copyfile(apk, final_apk)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    source = build / '金甲虫ADB助手-对应源码及许可.zip'
    with zipfile.ZipFile(apk) as archive:
        assets = {name: hashlib.sha256(archive.read(name)).hexdigest()
                  for name in ('assets/metadata-server.jar', 'assets/scrcpy-server.jar')}
    record = {'application': '金甲虫ADB助手', 'package': 'io.github.devicebridge.goldenbeetle',
              'version': version['version'], 'version_code': version['version_code'], 'signing': args.signing,
              'min_sdk': 19, 'target_sdk': 35, 'apk_sha256': sha(apk), 'corresponding_source': source.name,
              'source_archive_sha256': sha(source), 'source_input_manifest_sha256': sha(build / 'source-inputs.json'),
              'bundled_servers': assets, 'builder': 'tools/build-apk.py',
              'verification': 'Structure/alignment and debug signature only; no runtime device acceptance. '
                              'Bundled server assets are pinned inputs; this command does not rebuild them.'}
    (build / 'outputs/构建来源.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(str(final_apk) + '\nSHA-256: ' + record['apk_sha256'])


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print('Build failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
