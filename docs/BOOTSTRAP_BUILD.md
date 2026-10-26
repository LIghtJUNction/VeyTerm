# VeyTerm bootstrap build

VeyTerm needs a bootstrap archive for each Android ABI, with every executable
compiled for `/data/data/com.veyterm/files/usr`. Extracting an upstream package
under that directory does not change its ELF runpaths or script interpreters.

The `v0.118.0-veyterm-pacman` archives listed below are **rejected**: runtime
inspection found upstream paths and a `paru` placeholder. Their checksums prove
artifact identity, not that the packages work. Do not regenerate a release with
the old download-and-relocate generator.

## Build native packages from source

Use `termux/termux-packages` revision
`582ebd8b2829f017127c65d512493328604dc431` and apply
[`termux-packages-native-veyterm.patch`](termux-packages-native-veyterm.patch).
This includes the fork identity, existing cross-build fixes, exported
`termux-tools` configure variables, and zsh login selection. Copy
[`packages/paru`](../packages/paru) into that checkout's `packages` directory.
The paru recipe pins its upstream source commit and checksum, builds the real
Android executable, and configures repository-only operations by default.

Start the upstream package-builder container and copy the tools into it:

```sh
# From the patched termux-packages checkout; VeyTerm is a sibling checkout.
cp -a ../VeyTerm/packages/paru packages/
./scripts/run-docker.sh true
docker exec termux-package-builder mkdir -p /home/builder/veyterm-tools
docker cp -L ../VeyTerm/tools/. termux-package-builder:/home/builder/veyterm-tools/
docker exec termux-package-builder bash -lc \
  'cd /home/builder/termux-packages && bash /home/builder/veyterm-tools/build_native_packages.sh'
```

`build_native_packages.sh` defaults to `x86_64 aarch64 arm i686`, uses two
compiler jobs, and checks the fork prefix before building. It rebuilds the
scripts whose generated identity or package-manager mode changed.
Other locally compiled VeyTerm Debian packages can be reused: the assembler
converts package metadata and maintainer-script arguments, preserving payload
bytes, file permissions, and symlinks. It rejects missing runtime dependencies
and any old prefix in the payload.

Compile-time package-format comparisons in Debian maintainer hooks are also
converted to pacman. Affected packages receive a higher release number. This
prevents Python's cleanup hook from deleting pip that pacman owns; replacing
only the maintainer-script arguments is insufficient.

## Assemble and sign

On a Linux host with Python 3.11+, `ar`, `vercmp`, `repo-add`, and GnuPG, use a
package signing key and the URL where the versioned native repository will be
published. Keep the secret key outside version control. Pass the full signing
fingerprint, not a short key ID.

```sh
for arch in x86_64 aarch64 arm i686; do
  python tools/assemble_pacman_bootstrap.py ../termux-packages-veyterm/output/veyterm-snapshots/"$arch" \
    --packages-source ../termux-packages-veyterm \
    --arch "$arch" --output build/native-bootstrap \
    --signing-key "$VEYTERM_SIGNING_KEY" \
    --repository-url "$VEYTERM_REPOSITORY_URL"
done
python tools/verify_bootstrap.py build/native-bootstrap/bootstrap-*.zip
```

The output contains four archives, per-ABI signed package repositories, and
input and output package hashes in `bootstrap-<arch>.packages.json`. Publish the repository
databases under their ABI-qualified names and retain their detached signatures.
Use `{arch}` in `VEYTERM_REPOSITORY_URL` when each ABI has a separate repository
directory or release tag. Build snapshots preserve each ABI's inputs while
later builds may replace architecture-independent package files.
For a flat GitHub release, use one common release download URL. Package asset
names include the target ABI, with URL-sensitive characters normalized; package
names and versions inside the archives retain their original meaning. Upload
only filenames listed in the provenance manifests, together with their
signatures and the ABI-qualified databases. Stale files in a local repository
directory are not release inputs.
The native repository has its own VeyTerm keyring; an Arch/glibc guest must use
the Arch keyring inside that separate rootfs. Ship the public keyring files;
let the first-start hook create the GnuPG directory and its device-local key.
Precreating an empty GnuPG directory prevents that initialization.

Before changing the hashes in `app/build.gradle`, verify all four archives.
Before shipping an APK, install it on a fresh emulator and exercise shell
startup, package sync/install/remove, `paru`, script execution, and restart.
Also upgrade an existing broken prefix and verify that user shell configuration
survives and the previous prefix is retained. Local unit tests cover failed and
interrupted directory switches, but do not replace those device checks.

Gradle checks both the archive content and the actual `blob`/`blob_size` exported
from every packaged `libtermux-bootstrap.so`. The NDK assembly object and Gradle
native tasks explicitly depend on the included zip. This prevents an incremental
build from shipping an older cached bootstrap despite a successful checksum check.
Native input changes also force APK repackaging; the APK gate rejects excessive
unused ZIP space from older embedded blobs. The package gate rejects build-host
PATH assignments in executable scripts. `makepkg` uses private aliases for GNU
binutils and includes `bsdtar` without changing the caller's remaining PATH.

Repository databases and packages require signatures. Explicit local package
installation accepts unsigned packages built by the user and verifies any
provided signature. The smoke test first installs an intact signed download,
then rejects a valid package archive with modified contents and its original
signature. It also builds, installs and removes an unsigned local fixture as
the ordinary app UID.

The current four-ABI data release is `veyterm-native-20261026-ui2`. The optional
official coding-agent environment is described in
[`AGENT_COMPATIBILITY.md`](AGENT_COMPATIBILITY.md).

The current release reuses 448 unchanged signed package payloads from
`veyterm-native-20261026`. When assembling it, pass that release's download URL
as `--fallback-repository-url`, with the new release as `--repository-url`.
The new release contains all repository databases, corrected Python packages,
bootstraps and APKs. Compare every reused payload hash with the published
baseline before omitting it from the upload list. Both mirrors retain mandatory
package and database signature verification.

After publishing the release, verify its default HTTPS repository from the
installed app, without a test mirror or credentials:

```sh
python tools/emulator_command.py --serial emulator-5570 \
  --name public-repository --script tools/smoke_public_repository.sh --timeout 300
```

This checks signed database synchronization, file ownership lookup, Paru,
an empty-cache package download and installation with its public detached
signature. It reinstalls nano and removes only its temporary download directory.
The [acceptance checkpoint](CONTINUE_20261026.md) records the published artifacts.

## Rejected download-and-relocate build

The older apt build changes are recorded in
[`termux-packages-veyterm.patch`](termux-packages-veyterm.patch). The current
pacman bootstrap generator changes are recorded separately in
[`termux-packages-pacman-veyterm.patch`](termux-packages-pacman-veyterm.patch).
Those patches describe the rejected relocation approach. Use the native source
pipeline above for new builds. The old release tag was
`v0.118.0-veyterm-pacman`; its values below are historical failure evidence.

The rejected release has these hashes:

| ABI | SHA-256 |
| --- | --- |
| aarch64 | `3d18bb2cddd1fabb7e75b2ef92470134009bf0de28ac25966863f8ebf6c2b959` |
| arm | `0a13f9bfcf01894f22f6c8a4d75cbbf11f23622baa6298bfc7007fe966a57ab6` |
| i686 | `73c908d0fcc2018b3a72d37c437ac040d7c3c09153169d18a0dd9554efd9c83f` |
| x86_64 | `80b84b339face2ef8822059e46558139f283ee0ae5db4b07d2d36aae40c080c6` |
