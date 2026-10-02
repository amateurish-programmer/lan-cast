#!/usr/bin/env python3
"""Create bounded public update metadata for already signed APKs (no credentials)."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commit', required=True, help='Immutable Git commit containing the APKs')
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--notes-file', type=pathlib.Path, required=True)
    parser.add_argument('--phone', type=pathlib.Path, required=True)
    parser.add_argument('--tv', type=pathlib.Path, required=True)
    parser.add_argument('--build-tools', type=pathlib.Path, required=True)
    parser.add_argument('--expected-certificate-sha256', required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{40}', args.commit):
        parser.error('commit must be a lowercase 40-character Git SHA')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', args.version_name) or args.version_code < 1:
        parser.error('version must be numeric x.y.z and a positive code')
    if not re.fullmatch(r'[0-9a-f]{64}', args.expected_certificate_sha256):
        parser.error('expected certificate must be a lowercase SHA-256 digest')
    notes = args.notes_file.read_text(encoding='utf-8')
    if len(notes) > 16000:
        parser.error('release notes too long')
    artifacts = {}
    for app in ('phone', 'tv'):
        apk = getattr(args, app)
        size = apk.stat().st_size
        if not 1 <= size <= 100 * 1024 * 1024:
            parser.error(f'{app}: APK outside permitted size limit')
        output = subprocess.check_output([str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
        certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', output)
        if certificates != [args.expected_certificate_sha256]:
            parser.error(f'{app}: signing certificate does not match expected existing signer')
        badging = subprocess.check_output([str(args.build_tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
        package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
        if not package or package.groups() != (f'dev.lancast.{app}', str(args.version_code), args.version_name):
            parser.error(f'{app}: APK package/version does not match declared manifest')
        artifacts[app] = {
            'packageName': f'dev.lancast.{app}',
            'url': f'https://raw.githubusercontent.com/amateurish-programmer/lan-cast/{args.commit}/apks/v{args.version_name}/{app}.apk',
            'sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
            'size': size,
        }
    manifest = dict(schemaVersion=1, versionName=args.version_name, versionCode=args.version_code, notes=notes, artifacts=artifacts)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Wrote verified manifest: {args.output}')


if __name__ == '__main__':
    main()
