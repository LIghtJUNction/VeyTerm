#!/data/data/com.veyterm/files/usr/bin/bash
# Run through the installed app's PTY after publishing the native release.
set -Eeuo pipefail

work_directory="$(mktemp -d "$TMPDIR/veyterm-public-python.XXXXXXXX")"
cleanup() {
    result=$?
    for launcher in pip pip3; do
        if [[ -f "$work_directory/$launcher" && ! -e "$PREFIX/bin/$launcher" ]]; then
            cp -p "$work_directory/$launcher" "$PREFIX/bin/$launcher"
        fi
    done
    rm -rf "$work_directory"
    echo "PUBLIC_PYTHON_RESULT=$result"
    exit "$result"
}
trap cleanup EXIT

test "$(id -u)" != 0
test "$(uname -m)" = x86_64
grep -qx 'SigLevel = Required DatabaseRequired' "$PREFIX/etc/pacman.conf"
grep -qx 'Server = https://github.com/LIghtJUNction/VeyTerm/releases/download/veyterm-native-20261026-ui2' "$PREFIX/etc/pacman.conf"
if grep -Eq '^Server = (http:|.*localhost|.*127\.0\.0\.1)' "$PREFIX/etc/pacman.conf"; then
    exit 1
fi
sha256sum "$HOME/.zshrc" > "$work_directory/zshrc.sha256"

# The corrected Python package must come from the new public release.
mkdir "$work_directory/python-cache" "$work_directory/pip-cache"
pacman -S --noconfirm --cachedir "$work_directory/python-cache" python
mapfile -t python_packages < <(find "$work_directory/python-cache" -name '*.pkg.tar.xz')
test "${#python_packages[@]}" = 1
test "$(basename "${python_packages[0]}")" = x86_64-python-3.14.8-0.1-x86_64.pkg.tar.xz
printf 'e500cd26b0eb79e3c8ed0e90fd190ac8694c81b9ff1d4f51b599df3dbabc33b1  %s\n' "${python_packages[0]}" | sha256sum -c -
test "$(pacman -Q python)" = 'python 3.14.8-0.1'
pacman -Qi python | tee "$work_directory/python-installed"
grep -Eq '^Validated By *:.*Signature' "$work_directory/python-installed"
pacman -Qk python-pip
python3 -m pip --version
echo PUBLIC_COLD_PYTHON_REINSTALL_PRESERVES_PIP_OK

# Reproduce missing entry points while retaining originals until repair succeeds.
for launcher in pip pip3; do
    test -f "$PREFIX/bin/$launcher"
    pacman -Qo "$PREFIX/bin/$launcher" | grep -q 'python-pip'
    mv "$PREFIX/bin/$launcher" "$work_directory/$launcher"
done
test ! -e "$PREFIX/bin/pip"
test ! -e "$PREFIX/bin/pip3"
pacman -Syu --noconfirm --cachedir "$work_directory/pip-cache" python-pip
mapfile -t pip_packages < <(find "$work_directory/pip-cache" -name '*-python-pip-*.pkg.tar.xz')
test "${#pip_packages[@]}" = 1
pacman -Qi python-pip | tee "$work_directory/pip-installed"
grep -Eq '^Validated By *:.*Signature' "$work_directory/pip-installed"
pacman -Qk python-pip
"$PREFIX/bin/pip" --version
"$PREFIX/bin/pip3" --version
python3 -m pip check
sha256sum -c "$work_directory/zshrc.sha256"
echo PUBLIC_COLD_PACMAN_SYU_PYTHON_PIP_REPAIR_OK
