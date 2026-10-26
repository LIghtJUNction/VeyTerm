#!/data/data/com.veyterm/files/usr/bin/bash
# Run from the actual VeyTerm terminal, after its native repository is reachable.
set -Eeuo pipefail

log_file="$HOME/veyterm-smoke.log"
work_directory="$(mktemp -d "$TMPDIR/veyterm-smoke.XXXXXXXX")"
exec > >(tee "$log_file") 2>&1
trap 'result=$?; rm -rf "$work_directory"; echo "VEYTERM_SMOKE_RESULT=$result"; exit "$result"' EXIT

echo "APP_UID=$(id -u)"
test "$(id -u)" != 0
test "$PREFIX" = /data/data/com.veyterm/files/usr
test "$TERMUX_APP_PACKAGE_MANAGER" = pacman
test -n "${LD_PRELOAD:-}"
parent_command="$(tr '\0' ' ' < "/proc/$PPID/cmdline")"
echo "PARENT_SHELL=$parent_command"
[[ "$parent_command" == *zsh* ]]

for program in zsh bash pacman paru git curl python3 pip proot proot-distro \
               sudo make makepkg autoconf automake bison flex file pkg-config; do
    command -v "$program"
done
pacman --version
paru --version
git --version
python3 -c 'import ssl; print("PYTHON_TLS=" + ssl.OPENSSL_VERSION)'
python3 -m pip --version
pip --version
proot --version
proot-distro --help

cat > "$work_directory/env-script" <<'PY'
#!/usr/bin/env python3
print("PORTABLE_SHEBANG_OK")
PY
chmod 700 "$work_directory/env-script"
"$work_directory/env-script"

git -C "$work_directory" init -q
git -C "$work_directory" -c user.name=VeyTerm -c user.email=local@localhost \
    commit --allow-empty -qm 'terminal smoke test'
git -C "$work_directory" rev-parse --verify HEAD

set +e
timeout 10 sudo true > "$work_directory/sudo.log" 2>&1
sudo_status=$?
set -e
cat "$work_directory/sudo.log"
if [[ "$sudo_status" == 0 || "$sudo_status" == 124 || "$sudo_status" == 137 ]]; then
    echo "Unexpected elevation on the non-root test image" >&2
    exit 1
fi
grep -Eqi 'su|root|sandbox|permission' "$work_directory/sudo.log"
grep -q '^RepoOnly$' "$PREFIX/etc/paru.conf"

if [[ -n "${VEYTERM_TEST_REPOSITORY:-}" ]]; then
    test_configuration="$work_directory/pacman.conf"
    python3 - "$PREFIX/etc/pacman.conf" "$test_configuration" "$VEYTERM_TEST_REPOSITORY" <<'PY'
from pathlib import Path
import re
import sys
source, target, url = sys.argv[1:]
Path(target).write_text(re.sub(r'^Server = .*$', lambda match: 'Server = ' + url,
                             Path(source).read_text(), flags=re.M))
PY
    echo "TEST_REPOSITORY=$VEYTERM_TEST_REPOSITORY"
    pacman() { command pacman --config "$test_configuration" "$@"; }
    paru() { command paru --config "$test_configuration" "$@"; }
fi

pacman -Sy --noconfirm
pacman -R --noconfirm nano
if command -v nano; then
    echo "Package removal left nano installed" >&2
    exit 1
fi
pacman -S --noconfirm nano
nano --version
paru -Si nano
pacman -R --noconfirm nano
paru -S --noconfirm nano
pacman -Q nano
pacman -S --noconfirm libc++ ca-certificates
pacman -S --noconfirm python
python3 -m pip --version
echo PYTHON_REINSTALL_PRESERVES_PIP_OK

mkdir "$work_directory/download"
pacman -Sw --noconfirm --cachedir "$work_directory/download" nano
mapfile -t downloads < <(find "$work_directory/download" -name '*.pkg.tar.xz')
test "${#downloads[@]}" = 1
package_url="$(pacman -Sp --print-format '%l' nano)"
curl -fL "$package_url.sig" -o "${downloads[0]}.sig"
pacman -U --noconfirm "${downloads[0]}"
python3 - "${downloads[0]}" <<'PY'
import io
from pathlib import Path
import sys
import tarfile
path = Path(sys.argv[1])
temporary = path.with_suffix('.tampered')
changed = False
with tarfile.open(path) as source, tarfile.open(temporary, 'w:xz') as target:
    for member in source:
        data = source.extractfile(member).read() if member.isfile() else None
        if data is not None and not changed and not member.name.startswith('.'):
            data += b'\nsignature rejection fixture\n'
            member.size = len(data)
            changed = True
        target.addfile(member, io.BytesIO(data) if data is not None else None)
assert changed
temporary.replace(path)
PY
if pacman -U --noconfirm "${downloads[0]}" > "$work_directory/rejected.log" 2>&1; then
    echo "A modified signed package was accepted" >&2
    exit 1
fi
cat "$work_directory/rejected.log"
grep -Eqi 'signature|PGP' "$work_directory/rejected.log"
echo SIGNATURE_REJECTION_OK

mkdir "$work_directory/makepkg"
cd "$work_directory/makepkg"
cat > PKGBUILD <<'PKGBUILD'
pkgname=veyterm-smoke-fixture
pkgver=1
pkgrel=1
pkgdesc='Unprivileged makepkg fixture'
arch=(any)
license=(MIT)
package() {
    install -d "$pkgdir$PREFIX/share/veyterm-smoke"
    printf 'ordinary user package\n' > "$pkgdir$PREFIX/share/veyterm-smoke/result"
}
PKGBUILD
makepkg --nodeps --nocheck --noconfirm
fixture="$(find . -name 'veyterm-smoke-fixture-*.pkg.tar.*' -print -quit)"
test -n "$fixture"
pacman -U --noconfirm "$fixture"
grep -q 'ordinary user package' "$PREFIX/share/veyterm-smoke/result"
pacman -R --noconfirm veyterm-smoke-fixture
echo UNPRIVILEGED_MAKEPKG_OK
