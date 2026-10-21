#!/usr/bin/env bash
# Build just the stock peach-v2 WLAN module and its native dependencies.
set -eo pipefail
ROOT=${OPAIRSHARE_BUILD_ROOT:-/mnt/opairshare-build}
[[ $EUID != 0 ]] || { echo 'Build as the normal user.' >&2; exit 1; }
[[ -s $ROOT/logs/checkout-ready ]] || { echo 'Source checkout is not ready.' >&2; exit 1; }
cd "$ROOT/opairshare/kernel_platform"
export KLEAF_MAKE_JOBS=2
mkdir -p "$ROOT/baseline-wlan"
./tools/bazel --output_user_root="$ROOT/opairshare/bazel-cache" run \
    --jobs=2 --make_jobs=2 --local_ram_resources=6144 \
    --//msm-kernel:skip_abi=true --//msm-kernel:skip_abl=true \
    --config=stamp --user_kmi_symbol_lists=//msm-kernel:android/abi_gki_aarch64_qcom \
    --incompatible_sandbox_hermetic_tmp=false --nozstd_dwarf_compression \
    //vendor/qcom/opensource/wlan/qcacld-3.0:sun_perf_peach-v2_modules_dist \
    -- --dist_dir="$ROOT/baseline-wlan"
echo 'WLAN_BASELINE_BUILD_COMPLETE; audit imports and provider CRCs before loading.'
