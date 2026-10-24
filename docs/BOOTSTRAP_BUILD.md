# VeyTerm bootstrap build

VeyTerm ships a bootstrap archive for each Android ABI. The archives are built
from the `termux-packages` checkout with the VeyTerm package identity before
they are published as GitHub release assets.

## Reproduce the build

The older apt build changes are recorded in
[`termux-packages-veyterm.patch`](termux-packages-veyterm.patch). The current
pacman bootstrap generator changes are recorded separately in
[`termux-packages-pacman-veyterm.patch`](termux-packages-pacman-veyterm.patch).
Apply the pacman patch to the upstream `termux/termux-packages` revision
recorded below, then generate the four archives:

```bash
git clone https://github.com/termux/termux-packages.git termux-packages-veyterm
cd termux-packages-veyterm
git checkout 582ebd8b2829f017127c65d512493328604dc431
git apply ../VeyTerm/docs/termux-packages-pacman-veyterm.patch
TMPDIR="$HOME/.cache/veyterm-pacman-build" \
  ./scripts/generate-bootstraps.sh --pm pacman \
  --architectures aarch64,arm,i686,x86_64 \
  --add zsh,sudo,git,fakeroot,ca-certificates,curl,openssl,zstd,\
autoconf,automake,bison,flex,m4,make,file,gettext,pkg-config,patch,\
proot,proot-distro
```

The package configuration uses the Android package ID `com.veyterm` and keeps
the internal Java/JNI namespace `com.termux` for compatibility. The bootstrap
script follows runtime dependencies while assembling the archive, so build
only dependencies such as documentation and X11 toolchains are not shipped.

The four resulting files are:

```text
bootstrap-aarch64.zip
bootstrap-arm.zip
bootstrap-i686.zip
bootstrap-x86_64.zip
```

Their SHA-256 values are pinned in `app/build.gradle`. The release tag used by
the current Gradle configuration is `v0.118.0-veyterm-pacman`.

The current build produced these hashes:

| ABI | SHA-256 |
| --- | --- |
| aarch64 | `3d18bb2cddd1fabb7e75b2ef92470134009bf0de28ac25966863f8ebf6c2b959` |
| arm | `0a13f9bfcf01894f22f6c8a4d75cbbf11f23622baa6298bfc7007fe966a57ab6` |
| i686 | `73c908d0fcc2018b3a72d37c437ac040d7c3c09153169d18a0dd9554efd9c83f` |
| x86_64 | `80b84b339face2ef8822059e46558139f283ee0ae5db4b07d2d36aae40c080c6` |
