#!/data/data/com.veyterm/files/usr/bin/bash
# Run through the installed app's PTY after publishing the native release.
set -Eeuo pipefail

work_directory="$(mktemp -d "$TMPDIR/veyterm-public-repository.XXXXXXXX")"
trap 'result=$?; rm -rf "$work_directory"; echo "PUBLIC_REPOSITORY_RESULT=$result"; exit "$result"' EXIT

test "$(id -u)" != 0
echo "APP_UID=$(id -u)"
cat "$PREFIX/etc/pacman.conf"
repository_url="$(sed -n 's/^Server = //p' "$PREFIX/etc/pacman.conf")"
[[ "$repository_url" == https://github.com/LIghtJUNction/VeyTerm/releases/download/veyterm-native-* ]]
grep -qx 'SigLevel = Required DatabaseRequired' "$PREFIX/etc/pacman.conf"

pacman -Syy --noconfirm
pacman -Fyy --noconfirm
pacman -F "${PREFIX#/}/bin/nano"
pacman -Fq "${PREFIX#/}/bin/nano" > "$work_directory/owners"
cat "$work_directory/owners"
grep -q '/nano$' "$work_directory/owners"
paru -Si nano

# A new empty cache proves that the package is fetched over public HTTPS.
mkdir "$work_directory/cache"
pacman -Sw --noconfirm --cachedir "$work_directory/cache" nano
mapfile -t packages < <(find "$work_directory/cache" -name '*.pkg.tar.xz')
printf 'COLD_CACHE_PACKAGE_COUNT=%s\n' "${#packages[@]}"
test "${#packages[@]}" = 1
# %l can point at a previously cached file; %f retains the repository filename.
package_url="$repository_url/$(pacman -Sp --print-format '%f' nano)"
printf 'PACKAGE_URL=%s\n' "$package_url"
curl --fail --location --retry 2 "$package_url.sig" -o "${packages[0]}.sig"
pacman -U --noconfirm "${packages[0]}"
nano --version
pacman -Qi nano | tee "$work_directory/installed"
grep -Eq '^Validated By *:.*Signature' "$work_directory/installed"
pacman -Qk nano
echo PUBLIC_HTTPS_DATABASE_FILES_PACKAGE_AND_SIGNATURE_OK
