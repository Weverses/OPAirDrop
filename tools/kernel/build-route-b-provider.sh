#!/usr/bin/env bash
set -euo pipefail
ROOT=${OPAIRSHARE_BUILD_ROOT:-/mnt/opairshare-build}
cd "$ROOT/opairshare/kernel_platform"
export KLEAF_MAKE_JOBS=2
./tools/bazel --output_user_root="$ROOT/opairshare/bazel-cache" build \
  --jobs=2 --make_jobs=2 --local_ram_resources=6144 \
  --//msm-kernel:skip_abi=true --//msm-kernel:skip_abl=true \
  --config=stamp --user_kmi_symbol_lists=//msm-kernel:android/abi_gki_aarch64_qcom \
  --incompatible_sandbox_hermetic_tmp=false --nozstd_dwarf_compression \
  //opairshare-route-b-provider:opairshare_wonder_provider \
  //opairshare-route-b-provider:opairshare_route_b_probe
