#!/usr/bin/env bash
# Run inside the Termux package-builder container, in its patched checkout.
set -euo pipefail

tool_directory="$(cd "$(dirname "$0")" && pwd)"
read -r -a architectures <<< "${1:-x86_64 aarch64 arm i686}"
build_jobs="${VEYTERM_BUILD_JOBS:-2}"
export RUSTUP_PROFILE=minimal

if [[ ! -f scripts/properties.sh || ! -f packages/paru/build.sh ]]; then
    echo "Run from the patched packages checkout with VeyTerm's paru recipe installed." >&2
    exit 1
fi
build_prefix="$(bash -c 'source scripts/properties.sh; printf "%s" "$TERMUX__PREFIX"')"
if [[ "$build_prefix" != /data/data/com.veyterm/files/usr ]]; then
    echo "Refusing to build packages for another app: $build_prefix" >&2
    exit 1
fi
package_list="$(python3 - "$tool_directory" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
from assemble_pacman_bootstrap import DEFAULT_PACKAGES
print(DEFAULT_PACKAGES)
PY
)"
IFS=, read -r -a bootstrap_packages <<< "$package_list"
build_targets=()
shopt -s nullglob
for package in "${bootstrap_packages[@]}"; do
    target="$package"
    if [[ ! -d "packages/$target" ]]; then
        recipes=(packages/*/"$package.subpackage.sh")
        if [[ "${#recipes[@]}" != 1 ]]; then
            echo "Cannot resolve the build recipe for $package" >&2
            exit 1
        fi
        target="$(basename "$(dirname "${recipes[0]}")")"
    fi
    if [[ " ${build_targets[*]} " != *" $target "* ]]; then
        build_targets+=("$target")
    fi
done

for architecture in "${architectures[@]}"; do
    case "$architecture" in
        x86_64|aarch64|arm|i686) ;;
        *) echo "Unsupported architecture: $architecture" >&2; exit 1 ;;
    esac
    # These scripts changed their generated identity or package-manager mode;
    # a cached Debian build must not bypass rebuilding them for pacman.
    ./build-package.sh -a "$architecture" -j "$build_jobs" --format pacman -f \
        termux-tools termux-exec termux-keyring proot proot-distro sudo pacman
    ./build-package.sh -a "$architecture" -j "$build_jobs" --format pacman \
        "${build_targets[@]}"
    snapshot_directory="output/veyterm-snapshots/$architecture"
    mkdir -p "$snapshot_directory"
    inputs=(output/*_"$architecture".deb output/*_all.deb \
            output/*-"$architecture".pkg.tar.xz output/*-any.pkg.tar.xz)
    cp --reflink=auto "${inputs[@]}" "$snapshot_directory/"
done
