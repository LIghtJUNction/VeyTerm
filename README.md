# VeyTerm

VeyTerm is an Android terminal application being developed from the open-source
[Termux app](https://github.com/termux/termux-app). The current tree keeps the
terminal emulator, Android integration, shell bootstrap flow, and shared
libraries from that upstream project so they can be extended with native AI
workflows.

## Current fork boundary

- The user-facing application name is **VeyTerm**.
- The Android application ID and `$PREFIX` are still `com.termux` while the
  bootstrap archives are unchanged. This keeps the downloaded upstream
  bootstrap binaries runnable; changing the package ID requires rebuilding them
  with the new path.
- The Java package names still use `com.termux` for the same compatibility
  reason. New VeyTerm features should be isolated behind project-specific
  modules and migrated deliberately.
- The original upstream documentation is kept in
  [`README_TERMUX.md`](README_TERMUX.md).

## Build locally

Use Android Studio or the Gradle wrapper. A debug APK can be built with:

```bash
./gradlew assembleDebug
```

The generated APK uses a `veyterm-app_...apk` filename. Unit tests can be run
locally with:

```bash
./gradlew test
```

## Upstream and license

The exact source baseline and license notes are recorded in
[`docs/UPSTREAM.md`](docs/UPSTREAM.md). This project preserves the upstream
GPLv3-only license and the component-specific license exceptions listed in
[`LICENSE.md`](LICENSE.md).
