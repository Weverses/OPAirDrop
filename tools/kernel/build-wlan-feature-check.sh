#!/usr/bin/env bash
# Build-only dependency probe. Restore the original feature-off defconfig.
set -euo pipefail
ROOT=${OPAIRSHARE_BUILD_ROOT:-/mnt/opairshare-build}
CFG="$ROOT/opairshare/vendor/qcom/opensource/wlan/qcacld-3.0/configs/sun_gki_peach-v2_defconfig"
BACKUP=$(mktemp "$ROOT/stage3-defconfig.XXXXXX")
cp "$CFG" "$BACKUP"
cleanup() { cp "$BACKUP" "$CFG"; rm -f "$BACKUP"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 - "$CFG" <<'PY'
import sys
from pathlib import Path
p = Path(sys.argv[1])
lines = [l for l in p.read_text().splitlines()
         if not any(k in l for k in ('CONFIG_DRIVER_PASSTHRU_MODE', 'CONFIG_WONDER_SUPPORT'))]
p.write_text('\n'.join(lines) + '\nCONFIG_DRIVER_PASSTHRU_MODE=y\nCONFIG_WONDER_SUPPORT=y\n')
PY
cd "$ROOT/opairshare/kernel_platform"
export KLEAF_MAKE_JOBS=2
./tools/bazel --output_user_root="$ROOT/opairshare/bazel-cache" build \
  --jobs=2 --make_jobs=2 --local_ram_resources=6144 \
  --//msm-kernel:skip_abi=true --//msm-kernel:skip_abl=true \
  --config=stamp --user_kmi_symbol_lists=//msm-kernel:android/abi_gki_aarch64_qcom \
  --incompatible_sandbox_hermetic_tmp=false --nozstd_dwarf_compression \
  //vendor/qcom/opensource/wlan/qcacld-3.0:sun_perf_qca_cld_peach-v2 "$@"
