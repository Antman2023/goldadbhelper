#!/usr/bin/env python3
"""Read the single version source of truth and validate release tags."""
import argparse
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEMVER = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?')


def read_version(path=ROOT / 'app/build.gradle'):
    text = path.read_text(encoding='utf-8')
    names = re.findall(r"^\s*versionName\s+['\"]([^'\"]+)['\"]\s*$", text, re.M)
    codes = re.findall(r'^\s*versionCode\s+([0-9]+)\s*$', text, re.M)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError('app/build.gradle must contain one literal versionName and versionCode')
    name, code = names[0], int(codes[0])
    match = SEMVER.fullmatch(name)
    if not match or not 1 <= code <= 2100000000:
        raise ValueError('Use a SemVer versionName (without +metadata) and versionCode 1..2100000000')
    if match[4] and any(p.isdigit() and len(p) > 1 and p[0] == '0' for p in match[4].split('.')):
        raise ValueError('Numeric prerelease identifiers cannot have leading zeroes')
    return {'version': name, 'version_code': code, 'prerelease': '-' in name}


def validate_tag(tag, version):
    expected = 'v' + version['version']
    if tag != expected:
        raise ValueError(f'Tag must exactly match app/build.gradle: {expected}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag')
    parser.add_argument('--format', choices=['json', 'shell', 'github'], default='json')
    args = parser.parse_args()
    try:
        version = read_version()
        if args.tag is not None:
            validate_tag(args.tag, version)
    except ValueError as error:
        parser.error(str(error))
    if args.format == 'shell':
        print(version['version_code'], version['version'])
    elif args.format == 'github':
        print('version=' + version['version'])
        print('version_code=' + str(version['version_code']))
        print('prerelease=' + str(version['prerelease']).lower())
    else:
        print(json.dumps(version))


if __name__ == '__main__':
    main()
