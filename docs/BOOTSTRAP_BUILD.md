# VeyTerm bootstrap build

VeyTerm ships a bootstrap archive for each Android ABI. The archives are built
from the `termux-packages` checkout with the VeyTerm package identity before
they are published as GitHub release assets.

## Reproduce the build

The package build changes are recorded in
[`termux-packages-veyterm.patch`](termux-packages-veyterm.patch). Apply that
patch to the upstream `termux/termux-packages` revision recorded below, then
run the bootstrap script in the package-builder container:

```bash
git clone https://github.com/termux/termux-packages.git termux-packages-veyterm
cd termux-packages-veyterm
git checkout 582ebd8b2829f017127c65d512493328604dc431
git apply ../VeyTerm/docs/termux-packages-veyterm.patch

docker pull ghcr.io/termux/package-builder:latest
docker run -d --name termux-package-builder \
  -v "$PWD:/home/builder/termux-packages" \
  ghcr.io/termux/package-builder:latest sleep infinity
docker exec --workdir /home/builder/termux-packages \
  termux-package-builder ./scripts/build-bootstraps.sh
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
the current Gradle configuration is `bootstrap-veyterm-0.1.0`.

The current build produced these hashes:

| ABI | SHA-256 |
| --- | --- |
| aarch64 | `9951eabae8b5acf65c4fc3e418f69207171ab97b1cb54c7b576214782d5678d6` |
| arm | `0ac40cda79ece308944c4f0fca848c64cb17d2146978662b8c1ae8a74e585791` |
| i686 | `f4102e4a892bd0962e5d6b6e50e23027b017dc14cf4f0b1a33350c0956215807` |
| x86_64 | `163ecccc6b18d384d32a81a334774dc313004883ee027bf5e7c14d7345494c8c` |
