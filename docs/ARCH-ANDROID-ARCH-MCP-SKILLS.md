# VeyTerm Android architecture boundary

This document records the runtime contract. `pacman-android-7` is now the
default build variant and the four local bootstrap archives have been generated
and checked for package contents and SHA-256 identity. The older
`apt-android-7` APK remains available as a compatibility fallback while the
bootstrap publication and independent signing provenance are finalized.

## Shell startup

`UnixShellEnvironment.LOGIN_SHELL_BINARIES` now probes `zsh`, then `bash`, then
`fish`, and finally `sh`. This keeps shell selection in the existing background
session runner while making zsh the product default and bash the first fallback.
The pure Kotlin `ShellLaunchPolicy` mirrors that order for tests and future
settings UI.

The first bootstrap install writes `~/.zshrc` and `~/.bashrc` only if those
files do not exist. These assets are intentionally small and offline: prompt,
`autocd`, comments, editor defaults, and common aliases. They do not download
oh-my-zsh, run `pacman`, or make network calls. Existing user configuration is
never overwritten.

## Arch/pacman bootstrap

`TermuxBootstrap` now understands the metadata identity
`PACMAN_ANDROID_7`/`pacman-android-7`, and the Kotlin manifest in
`VeyTermPackageManifests` is the first executable contract. It requires
`pacman`, `paru`, `sudo`, `base-devel`, `git`, `ca-certificates`,
`archlinux-keyring`, `openssl`, `curl`, `zstd`, and `fakeroot`. The bootstrap
must contain `pacman.conf`, `makepkg.conf`, a populated keyring, sync database,
and the `pacman`/`paru`/`sudo` commands under the VeyTerm prefix.

`paru` is preinstalled but AUR use is disabled by default. It must build as the
ordinary Android app user, with no root assumption. `sudo` is present for the
Arch user experience, and the installer installs a bounded shim when the prefix
has no package-provided `sudo`. `SudoCapabilityDetector` only marks
the `su` path usable after an elevation probe; otherwise `SudoPolicy` returns
an explicit denial. This boundary does not grant Android permissions or fake
root state.

The manifest remains `sourceReady=false` until the generated archives are
published with independent signing/keyring provenance. `app/build.gradle`
already verifies all four SHA-256 values and can build the variant from the
local archives; a clean checkout uses the versioned bootstrap release URL. The
current installer deletes/recreates a prefix, so the pacman installer must
first stage the new prefix, verify every archive and signature, then atomically
rename it and retain the previous prefix for rollback. A package-manager
migration must not reuse an existing apt database.

The prefix remains `/data/data/com.veyterm/files/usr` for compatibility with
the existing terminal and bootstrap JNI. The Linux user view presents the
prefix root as `/usr`, maps its `bin`, `lib`, `share`, and `include` children
under `/usr`, and maps `etc`, `var`, and `tmp` to their conventional root
locations. The private `/data/data/com.veyterm/files` path never appears in a
view; the home directory is shown as `~` (with `/home/...` accepted when a
Linux-style absolute path is needed).

## Linux file view

`LinuxPathMapper` exposes `/`, `/etc`, `/usr`, `/var`, `/tmp`, and `~` style
paths while hiding the Android app-data prefix. `LinuxFileEntry` carries the
display fields required by the file view: owner, group, byte size, modification
time, permission string, type, and optional symbolic-link target.
`LinuxFileView.standardOptions` defaults to hidden entries, long metadata, and
directories first, matching the familiar `ls -la` view. Real filesystem
enumeration and Android SAF permissions remain outside this pure model.

## MCP and Skills

`McpServerManifest` declares an id, semantic version, `stdio` or HTTP
transport, command/endpoint, permissions, tools, and enabled state.
`SkillManifest` declares its version, permissions, allowed MCP servers/tools,
and enabled state. `AiSessionContext` keeps the terminal session id, virtual
working directory, selected skills, and a bounded context size.

`McpSkillPolicy` is an authorization boundary, not a network client. It denies
disabled servers/skills, undeclared tools, server mismatches, unbounded
timeouts, and HTTP servers without the explicit `NETWORK` permission. Actual
stdio process I/O and HTTP requests must run on a worker dispatcher, carry a
request id, enforce the 1–120 second timeout, and observe cancellation. No MCP
or Skills operation may run on the Android UI thread. Terminal execution and
file write permissions remain explicit capability grants; opening a shell does
not grant them.

The next implementation slice should add persisted, user-reviewable
enable/disable state and manifest parsing. It must not silently enable remote
servers or AUR tools. `McpToolDispatcher` is the first worker-boundary slice:
callers inject an executor and timer, receive a request handle that can be
cancelled, and get exactly one completed/denied/timed-out/cancelled/failed
result. Its callback is deliberately delivered off the Android UI thread; UI
code must post the result itself.

## Acceptance gates

1. A fresh package with zsh starts the offline prompt; a package without zsh
   starts bash; without both, it reaches the existing safe `sh` fallback.
2. Existing `.zshrc`/`.bashrc` files survive bootstrap installation unchanged.
3. A pacman release contains independently signed four-ABI archives, `paru`,
   `sudo`, keyring, build dependencies, ordinary-user `makepkg` behavior, and
   an exercised staged-prefix rollback. The current local archives pass
   content, path-leak, zip-integrity, and checksum checks; signing provenance
   and device-level first-launch acceptance are still pending.
4. Non-root devices receive a clear non-elevation result for sudo requests;
   root-device tests execute the real sudo path.
5. File view snapshots contain Linux paths and metadata, with no
   `/data/data/com.veyterm/files` leakage.
6. MCP/Skills tests cover stdio authorization, HTTP network permission,
   disabled capability, timeout, cancellation, and UI-thread exclusion.
