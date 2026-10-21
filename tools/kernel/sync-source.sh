#!/usr/bin/env bash
# Run as the normal build user on an ext4 Linux build volume.
set -euo pipefail
ROOT=${OPAIRSHARE_BUILD_ROOT:-/mnt/opairshare-build}
export OPAIRSHARE_BUILD_ROOT="$ROOT"
TOOLS=${OPAIRSHARE_TOOLS_DIR:-$(CDPATH= cd -- "$(dirname "$0")" && pwd)}
[[ $EUID != 0 ]] || { echo 'Do not sync/build as root.' >&2; exit 1; }
[[ $(findmnt -n -o FSTYPE --mountpoint "$ROOT") == ext4 ]] || { echo 'Build image is not mounted.' >&2; exit 1; }
mkdir -p "$ROOT/opairshare" "$ROOT/logs" "$TOOLS/bin"
export PATH="$TOOLS/bin:$PATH"
# Keep HTTP/2 transport failures scoped to this job rather than changing global Git config.
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=http.version
export GIT_CONFIG_VALUE_0=HTTP/1.1
if [[ ! -s $TOOLS/bin/repo ]]; then
    curl --fail --location --retry 3 https://storage.googleapis.com/git-repo-downloads/repo -o "$TOOLS/bin/repo.download"
    mv "$TOOLS/bin/repo.download" "$TOOLS/bin/repo"
    chmod +x "$TOOLS/bin/repo"
fi
cd "$ROOT/opairshare"
repo init -u https://github.com/OnePlusOSS/kernel_manifest \
    -b oneplus/sm8750 -m oneplus_13_b.xml --depth=1 \
    --partial-clone --clone-filter=blob:limit=1m
mkdir -p .repo/local_manifests
cat > .repo/local_manifests/opairshare-baseline.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <extend-project name="android_kernel_common_oneplus_sm8750" revision="e1b346b6b4f4096eb342ae3684838a942fd6f6c4" upstream="refs/heads/oneplus/sm8750_b_16.0.0_oneplus_13" />
  <extend-project name="android_kernel_oneplus_sm8750" revision="6028f47faddaa27700f8dd3a1d83906ea8f27170" upstream="refs/heads/oneplus/sm8750_b_16.0.0_oneplus_13" />
  <extend-project name="android_kernel_modules_and_devicetree_oneplus_sm8750" revision="d50b305f7da9e14715a25120a4ac7b1a4b8b97c3" upstream="refs/heads/oneplus/sm8750_b_16.0.0_oneplus_13" />
</manifest>
XML
repo sync -n -c --no-tags -j4 --fail-fast
# A pre-existing project may contain .promisor packs from a manual recovery fetch
# without the persistent remote flag. Repair only those marked partial repositories.
python3 - <<'PY'
from pathlib import Path
import subprocess
for config in Path('.repo/projects').rglob('config'):
    gitdir = config.parent
    if not gitdir.name.endswith('.git'):
        continue
    if not any((gitdir / 'objects/pack').glob('*.promisor')):
        continue
    command = ['git', '--git-dir=' + str(gitdir)]
    remotes = subprocess.check_output(command + ['remote'], text=True).splitlines()
    for remote in remotes:
        subprocess.run(command + ['config', f'remote.{remote}.promisor', 'true'], check=True)
        subprocess.run(command + ['config', f'remote.{remote}.partialclonefilter', 'blob:none'], check=True)
PY
printf 'NETWORK_METADATA_COMPLETE\n'
repo sync -l -j4 --fail-fast
repo manifest -r -o "$ROOT/logs/source-lock.xml"
python3 - <<'PY'
from pathlib import Path
import subprocess, os
import xml.etree.ElementTree as ET
root = Path.cwd()
for project in ET.parse(Path(os.environ['OPAIRSHARE_BUILD_ROOT'])/'logs/source-lock.xml').getroot().findall('project'):
    path = root / project.get('path', project.get('name'))
    command = ['git', '-C', str(path)]
    indexed = subprocess.check_output(command + ['ls-files', '-z'])
    if not indexed:
        print(f'Restoring empty fresh index: {project.get("name")}', flush=True)
        subprocess.run(command + ['config', 'http.version', 'HTTP/1.1'], check=True)
        remotes = subprocess.check_output(command + ['remote'], text=True).splitlines()
        if len(remotes) != 1:
            raise SystemExit(f'Expected exactly one source remote: {path}')
        subprocess.run(command + ['fetch', '--refetch', '--depth=1', '--filter=blob:limit=1m',
                                 '--no-auto-gc', remotes[0], project.get('revision')], check=True)
        subprocess.run(command + ['restore', '--source=HEAD', '--staged', '--worktree', '--', '.'], check=True)
    if subprocess.run(command + ['diff', '--quiet', 'HEAD', '--']).returncode:
        raise SystemExit(f'Checkout differs from locked baseline: {path}')
required = [root / 'kernel_platform/common/Makefile',
            root / 'kernel_platform/prebuilts/build-tools/path/linux-x86/python3']
for path in required:
    if not path.exists():
        raise SystemExit(f'Missing required entry file: {path}')
(Path(os.environ['OPAIRSHARE_BUILD_ROOT'])/'logs/checkout-ready').write_text('validated\n')
PY
printf 'SYNC_COMPLETE\n'
