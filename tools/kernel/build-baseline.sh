#!/usr/bin/env bash
# Baseline only: no wondertap backport and no device deployment.
set -eo pipefail
ROOT=${OPAIRSHARE_BUILD_ROOT:-/mnt/opairshare-build}
[[ $EUID != 0 ]] || { echo 'Build as the normal user.' >&2; exit 1; }
[[ $(findmnt -n -o FSTYPE --mountpoint "$ROOT") == ext4 ]] || { echo 'Build image is not mounted.' >&2; exit 1; }
[[ -s $ROOT/logs/source-lock.xml ]] || { echo 'Source sync has not completed.' >&2; exit 1; }
[[ -s $ROOT/logs/checkout-ready ]] || { echo 'Actual checkout has not passed integrity checks.' >&2; exit 1; }
cd "$ROOT/opairshare"
TOOLS=${OPAIRSHARE_TOOLS_DIR:-$(CDPATH= cd -- "$(dirname "$0")" && pwd)}
mkdir -p "$TOOLS/bin"
if [[ ! -e $TOOLS/bin/python ]]; then
    ln -s "$ROOT/opairshare/kernel_platform/prebuilts/build-tools/path/linux-x86/python3" "$TOOLS/bin/python"
fi
export PATH="$TOOLS/bin:$PATH"
if [[ ! -e kernel_platform/.bazelrc ]]; then
    printf '%s\n' '# OPAirShare task: keep the build JVM within this host RAM budget.' \
        'startup --host_jvm_args=-Xmx3g' > kernel_platform/.bazelrc
fi
[[ $(git rev-parse HEAD) == d50b305f7da9e14715a25120a4ac7b1a4b8b97c3 ]] || { echo 'Unexpected modules baseline.' >&2; exit 1; }
[[ $(git -C kernel_platform/common rev-parse HEAD) == e1b346b6b4f4096eb342ae3684838a942fd6f6c4 ]] || { echo 'Unexpected common baseline.' >&2; exit 1; }
[[ $(git -C kernel_platform/msm-kernel rev-parse HEAD) == 6028f47faddaa27700f8dd3a1d83906ea8f27170 ]] || { echo 'Unexpected msm-kernel baseline.' >&2; exit 1; }

# oplus_setup overwrites EXTRA_KBUILD_ARGS, so append limits AFTER its init.
source kernel_platform/oplus/build/oplus_setup.sh sun perf
init_build_environment
mkdir -p "$ANDROID_PRODUCT_OUT"
export EXTRA_KBUILD_ARGS="$EXTRA_KBUILD_ARGS --jobs=2 --make_jobs=2 --local_ram_resources=6144"
export KLEAF_MAKE_JOBS=2
echo 'BASELINE_BEGIN (sun perf 4K only; jobs=2, make_jobs=2, Bazel local RAM=6144 MB, JVM heap=3 GiB)'
bash kernel_platform/build/android/prepare_vendor.sh "$variants_platform" "$variants_type"
echo 'BASELINE_BUILD_COMMAND_COMPLETE; inspect artifacts and CRC before declaring compatibility.'
