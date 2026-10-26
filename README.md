# VeyTerm

VeyTerm is an Android terminal application being developed from the open-source
[Termux app](https://github.com/termux/termux-app). The current tree keeps the
terminal emulator, Android integration, shell bootstrap flow, and shared
libraries from that upstream project so they can be extended with native AI
workflows.

The current tested APKs are available in the
[native bootstrap release](https://github.com/LIghtJUNction/VeyTerm/releases/tag/veyterm-native-20261026-ui2).

## Current fork boundary

- The user-facing application name is **VeyTerm**.
- The Android application ID and `$PREFIX` use `com.veyterm`. Bootstrap
  archives are rebuilt for `/data/data/com.veyterm/files/usr`.
- The Java package names still use `com.termux` as an internal namespace for
  existing classes and JNI symbols. New VeyTerm features should be isolated
  behind project-specific modules and migrated deliberately.
- The original upstream documentation is kept in
  [`README_TERMUX.md`](README_TERMUX.md).

## Coding Agents

Run `veyterm-agents install` to install Claude Code, Codex, Pi, Antigravity CLI,
Cursor CLI and Grok Build in an optional Linux environment. Start one in your
project with, for example, `veyterm-agents codex`. Setup and the tested versions
are documented in [`docs/AGENT_COMPATIBILITY.md`](docs/AGENT_COMPATIBILITY.md).
Installation and execution checks for Python packages, Node build tools,
CMake/Ninja and other utilities are in
[`docs/SOFTWARE_COMPATIBILITY.md`](docs/SOFTWARE_COMPATIBILITY.md).

## Build locally

Use Android Studio or the Gradle wrapper, with Python 3.11+ available for the
bootstrap runtime-path check. A debug APK can be built with:

```bash
./gradlew assembleDebug
```

For a local x86_64 emulator build, use
`./gradlew :app:assembleDebug -PveytermDebugAbi=x86_64`. This verifies the
included bootstrap and produces an x86_64 split APK. Release builds always
require all four ABIs. Bootstrap source and signing instructions are in
[`docs/BOOTSTRAP_BUILD.md`](docs/BOOTSTRAP_BUILD.md).

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
