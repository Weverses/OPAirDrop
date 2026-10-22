#!/usr/bin/env python3
"""Validate an OPAirDrop ZIP and generate KernelSU update assets without publishing."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import re
from urllib.parse import quote
import zipfile

REPOSITORY = 'Weverses/OPAirDrop'


def read_properties(text):
    result = {}
    for line in text.splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        if '=' not in line:
            raise ValueError('Malformed module.prop')
        key, value = line.split('=', 1)
        if key in result:
            raise ValueError(f'Duplicate module property: {key}')
        result[key] = value
    return result


def update_url(repository):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Expected a GitHub owner/repository')
    return f'https://github.com/{repository}/releases/latest/download/update.json'


def release_notes(text, version):
    lines = text.splitlines()
    marker = f'## {version}'
    try:
        start = lines.index(marker) + 1
    except ValueError as error:
        raise ValueError(f'Missing changelog section: {marker}') from error
    end = next((i for i in range(start, len(lines)) if lines[i].startswith('## ')), len(lines))
    body = '\n'.join(lines[start:end]).strip()
    if not body:
        raise ValueError('Current version has no release notes')
    return f'# OPAirDrop {version}\n\n{body}\n'


def inspect_archive(archive, repository=REPOSITORY, tag=None):
    with zipfile.ZipFile(archive) as z:
        names = [i.filename for i in z.infolist() if not i.is_dir()]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP entries')
        for name in names:
            path = PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts or str(path) != name or '\\' in name:
                raise ValueError(f'Invalid ZIP path: {name}')
        if z.testzip() is not None:
            raise ValueError('ZIP CRC check failed')
        expected = {}
        for line in z.read('SHA256SUMS').decode().splitlines():
            checksum, name = line.split(None, 1)
            if name in expected or not re.fullmatch(r'[0-9a-f]{64}', checksum):
                raise ValueError('Invalid SHA256 manifest')
            expected[name] = checksum
        if set(expected) != set(names) - {'SHA256SUMS'}:
            raise ValueError('SHA256 manifest does not cover exactly the ZIP payload')
        for name, checksum in expected.items():
            if hashlib.sha256(z.read(name)).hexdigest() != checksum:
                raise ValueError(f'ZIP payload checksum mismatch: {name}')
        props = read_properties(z.read('module.prop').decode())
        if props.get('id') != 'opairshare' or props.get('name') != 'OPAirDrop':
            raise ValueError('Unexpected module identity')
        version = props.get('version', '')
        if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
            raise ValueError('A stable major.minor.patch version is required')
        code = props.get('versionCode', '')
        if not re.fullmatch(r'[1-9][0-9]*', code) or int(code) > 2147483647:
            raise ValueError('versionCode must be a positive signed 32-bit integer')
        if props.get('updateJson') != update_url(repository):
            raise ValueError('module.prop updateJson does not match the release repository')
        if tag is not None and tag != f'v{version}':
            raise ValueError('Release tag must equal v + the ZIP module version')
        notes = release_notes(z.read('CHANGELOG.md').decode(), version)
        return props, notes


def emit_metadata(archive, output, repository=REPOSITORY, tag=None):
    archive, output = Path(archive), Path(output)
    props, notes = inspect_archive(archive, repository, tag)
    tag = f'v{props["version"]}'
    base = f'https://github.com/{repository}/releases/download/{quote(tag, safe="")}'
    metadata = {'version': props['version'], 'versionCode': int(props['versionCode']),
                'zipUrl': f'{base}/{quote(archive.name, safe="")}', 'changelog': f'{base}/changelog.md'}
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    # Validate everything before writing release assets.
    output.mkdir(parents=True, exist_ok=True)
    (output / 'update.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    (output / 'changelog.md').write_text(notes)
    (output / (archive.name + '.sha256')).write_text(f'{checksum}  {archive.name}\n')
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--zip', type=Path)
    source.add_argument('--archive-dir', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--repo', default=REPOSITORY)
    parser.add_argument('--tag')
    args = parser.parse_args()
    archive = args.zip
    if args.archive_dir:
        matches = sorted(path for path in args.archive_dir.glob('*.zip') if path.is_file())
        if len(matches) != 1:
            parser.error(f'Expected exactly one module ZIP in --archive-dir; found {len(matches)}: '
                         + ', '.join(path.name for path in matches))
        archive = matches[0]
    try:
        metadata = emit_metadata(archive, args.out, args.repo, args.tag)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Release validation failed: {error}\n')
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
