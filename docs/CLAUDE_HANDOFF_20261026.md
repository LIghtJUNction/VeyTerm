# Claude handoff

Work directory: `/home/lightjunction/Documents/GITHUB/VeyTerm`.
The UI and software compatibility release is complete. The prerelease was
published on 2026-10-26 at 11:05:02 UTC; all 40 assets and the public download,
default HTTPS repository and Python/pip repair paths passed acceptance.
The original handoff checkpoint is retained below as historical context.

## Instructions

- Read `/home/lightjunction/AGENTS.md` and `/home/lightjunction/.codex/RTK.md`.
- Speak Chinese directly. Use local tests; do not trigger or wait for routine CI.
- Preserve unrelated work. Do not reset/clean the external
  `/home/lightjunction/Documents/GITHUB/termux-packages-veyterm` checkout.
- Finish the pending release before expanding the task. Existing artifacts are
  frozen and tested; do not rebuild or regenerate their signatures unnecessarily.
- Do not copy host provider credentials into the emulator or upload private
  signing material, emulator userdata, or Agent authentication traces.

## Source and implemented work

Runtime commit `8616dbc24f319da136bbaa2ed868600ba31266bb` is pushed to `origin/main`.
Later commits may contain handoff documentation only.

- Removed hard borders from terminal chrome, drawer and input; improved spacing,
  typography, 48 dp controls and status-bar contrast. Updated the HTML preview.
- Fixed converted Python post-install hooks selecting Debian rather than pacman:
  they had deleted pacman-owned pip. Corrected package release: `3.14.8-0.1`.
  The converter adapts ownership guards, retains payload bytes and increments the
  release. A focused test fails before the fix and passes after it.
- Existing default repository configuration advances to the new release,
  retaining the original signed repository as a fallback. Custom servers and
  symlinked configs are preserved. Android's `/data/data` alias was handled and
  actual upgrade migration passed.
- Added software installation/execution probes and safer emulator fixture
  preflight checks. Manifests now describe the actual Bionic packages/keyring.

## Verified

- 34 app unit tests and 8 package/runtime checks pass locally.
- Four native archives and all five APKs pass archive/ELF/signature checks.
- Exact final x86_64 APK SHA256:
  `a461ceb0e2fb5e818d5daf86a95095439fe3394cf3b69d9f24c318312da900de`.
- Fresh prefix, restart, signed packages, tampered-signature rejection,
  unprivileged makepkg and Python reinstall passed. All 607 pip files remain.
  `.zshrc` is unchanged and the six-Agent Linux container was restored.
- Nine software checks: eight pass; Android Ruff has no matching native wheel.
  Native requests/httpx/Rich and Linux Ruff/uv, TypeScript/esbuild/tsx,
  GCC/CMake/Ninja, ripgrep/fd/SQLite were installed and exercised.
- Six Agent PTYs launched on the new UI. Codex's 14-second cold probe had no
  output; a 45-second retry passed with 18 synchronized frames. Do not count the
  failed short probe as successful or weaken the output/liveness assertions.
- Actual UI checks passed new-session creation, settings, 48 dp toolbar controls
  and portrait/landscape control bounds. Day/night screenshots inspected.
  Desktop and 390 px mobile HTML preview checked with Playwright.
- ARM/32-bit execution and authenticated model tasks remain untested.
- The old 0/240 flicker video result belongs to the prior native release;
  it was not remeasured for this cosmetic update.

## Completion record: public release acceptance

Published prerelease:
https://github.com/LIghtJUNction/VeyTerm/releases/tag/veyterm-native-20261026-ui2

- Release ID `424111981`: `draft=false`, `prerelease=true`. The published tag
  points to runtime commit `8616dbc24f319da136bbaa2ed868600ba31266bb`.
- Uploaded only the five missing files listed in the historical checkpoint.
  All 40 remote assets have state `uploaded`, matching local sizes and SHA256
  digests. No APK/bootstrap rebuild or signature regeneration was needed.
- Anonymous downloads of the x86_64 APK, bootstrap, checksum, checksum signature
  and public key match local bytes. The downloaded checksum signature validates
  as `EDD66C5F3D1227BAECF69D834AB314C725D22B75`. Bootstrap validation reports
  zero errors; the APK embeds the same bootstrap and passes APK v1/v2 signature
  verification. Apksigner also emits warnings about JAR metadata entries outside
  v1 signature coverage; v2 verification and the full-file checksum pass.
- `tools/smoke_public_repository.sh` passed through the installed app's PTY on
  `emulator-5570`, using default public HTTPS URLs and required package/database
  signatures. Database/file-index refresh, package search, cold-cache nano
  download and signed installation passed. Unchanged payloads use the prior
  release fallback after an expected 404 on the new release.
- Added `tools/smoke_public_python.sh` and ran it through the same PTY. Empty-cache
  installation of signed Python `3.14.8-0.1` preserves pip. Temporarily moving the
  package-owned `pip`/`pip3` launchers, then running `pacman -Syu python-pip`,
  restores both. All 607 pip files are present, both entry points and `pip check`
  pass, and `.zshrc` retains SHA256
  `f8082a457e846d3e63b99a2857b26f3807916ae650ab9565e48c65baf9851bd6`.
  The fixture restores missing launchers on failure and removes temporary caches.
- Final checks preserve the Linux Agent container and all six executable paths.
  Earlier Agent UI tests remain the evidence for execution; this final check
  confirms availability only. ARM/32-bit execution and authenticated model calls
  remain unverified. No routine CI was triggered or awaited.
- Stopped the two task HTTP services after checking their PIDs, arguments and
  working directories; removed `emulator-5570`'s 18765 reverse mapping and
  confirmed no listeners/mappings remain. The final APK, Agents and AVD userdata
  were retained; `emulator-5580` was not operated. The AVD data filesystem has
  848 MiB free (86% used); the host has about 201 GiB free (78% used).

Evidence under `build/verification/ui-polish/`: `final-server-release.json`,
`public-downloads-result.json`, `public-downloads.log`, `public-apk-signature.log`.
PTY logs under `build/verification/`: `veyterm-public-ui2.log`,
`veyterm-public-python-ui2.log`, `veyterm-public-final-state.log`.

Repeat the release-specific x86_64 Python acceptance on the preserved emulator:

```sh
python3 tools/emulator_command.py --serial emulator-5570 \
  --name public-python-ui2 --script tools/smoke_public_python.sh --timeout 600
```

## Historical checkpoint: release pending at handoff

Repository `LIghtJUNction/VeyTerm`.
Tag `veyterm-native-20261026-ui2`, REST release ID **424111981**.
Target commit is the runtime commit above. It is **still a draft**.
Draft URL: https://github.com/LIghtJUNction/VeyTerm/releases/tag/untagged-11b618d24f61fa370bbe

The uploader has **terminated**, not merely become unobservable. Its former
PID `389586` and upload child are gone. It exited after GitHub EOF errors.
35 assets are uploaded; every current server digest and size matches the local
file. Exactly these five were missing at handoff:

```text
build/ui-compat-bootstrap/SHA256SUMS.sig
build/ui-compat-bootstrap/bootstrap-x86_64.zip
build/ui-compat-bootstrap/aarch64/veyterm-aarch64.db.sig
build/ui-compat-bootstrap/i686/veyterm-i686.db.sig
build/ui-compat-bootstrap/veyterm-native-keyring.gpg
```

Recheck current remote state before retrying. Upload only missing assets; most
large APKs/archives are already present. Avoid blindly rerunning the old uploader:
its API-error handling can stop progress reporting while queued uploads finish.

Per-command settings that succeeded for the GitHub API after the EOF errors:

```sh
HTTPS_PROXY=http://127.0.0.1:7897 GODEBUG=http2client=0 gh api \
  'repos/LIghtJUNction/VeyTerm/releases/424111981/assets?per_page=100'
```

These are temporary environment settings, not changes to host proxy configuration.
`gh` is authenticated. Its wrapper invokes mise; `mise which gh` locates the
same actual binary if repeated wrapper setup is slow. Git push may print a
D-Bus credential-store warning despite succeeding; do not change the keyring.

After all 40 remote asset digests match the whitelist and signed checksum:

1. Publish this draft (`draft=false`) using its REST ID, retaining prerelease.
2. Download the public x86_64 bootstrap/APK/checksum files without authorization
   and compare hashes/signature. Metadata alone is insufficient.
3. Run the default HTTPS repository acceptance from the emulator:

   ```sh
   python3 tools/emulator_command.py --serial emulator-5570 \
     --name public-ui2 --script tools/smoke_public_repository.sh --timeout 300
   ```

4. Also exercise a cold public download/reinstall of corrected Python and the
   documented `pacman -Syu python-pip` repair path. Earlier fresh-prefix checks
   used a temporary localhost mirror, explicitly recorded in the result JSON.
5. Record publication/public acceptance, commit/push documentation changes,
   and stop after completing the scoped work.

All 448 reused package payload hashes were compared with the prior release.
Do not reupload them: the new repo uses the old signed release as a fallback.
Both package and database signatures remain required.

## Files and live state at original handoff

- Exact 40-file whitelist: `build/verification/ui-polish/release-assets.json`.
- Current server snapshot: `build/verification/ui-polish/handoff-server-assets.json`.
- Draft metadata, notes, uploader and log: `build/verification/ui-polish/`.
- Artifacts: `build/ui-compat-bootstrap/`, `app/build/outputs/apk/debug/`.
- Signed checksums/public key: `build/ui-compat-bootstrap/SHA256SUMS*` and
  `veyterm-native-keyring.gpg`. Secret signing material stays in ignored
  `.signing/gnupg`; it must never be printed, committed or uploaded.
- Fresh-prefix evidence: `build/verification/emulator-ui2-fresh/result.json`.
- Software results: `build/verification/software-compatibility-ui2/`.
- Agent UI results: `build/verification/agents-ui2-final/` and
  `build/verification/agents-ui2-codex-cold/`.
- UI screenshots/results: `build/verification/ui-polish/after/`.
- Preview screenshots: `output/playwright/`; dedicated Playwright browser closed.

Own emulator **VeyTerm_API_33 / emulator-5570 is running** and contains the final
APK, native pip, all six Agents and installed Debian tools. Do not operate
unrelated `emulator-5580`. The temporary native fixture from this run was removed
after restoring the Agent container; older 36 KB recovery fixtures were kept.

Local test HTTP servers were started on 127.0.0.1:18765 (native repo) and 18766
(preview); check actual listeners/PIDs before managing them. An ADB reverse for
18765 may remain. The installed default pacman.conf uses public HTTPS URLs,
not either localhost server. Remove only these task-owned servers/reverse when
finished; retain AVD userdata and installed software.

If the own AVD has stopped, start it once with:

```sh
/opt/android-sdk/emulator/emulator -avd VeyTerm_API_33 -port 5570 -no-window \
  -no-snapshot -no-audio -no-boot-anim -feature -Vulkan -gpu software \
  -memory 3072 -cores 4
```
