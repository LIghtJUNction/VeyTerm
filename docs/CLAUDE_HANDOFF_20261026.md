# Claude handoff

Continue work in `/home/lightjunction/Documents/GITHUB/VeyTerm`.
The user asked to improve the appearance and test software installation
compatibility, then explicitly requested this handoff. No further publication
was performed after that request.

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

## Pending release: the immediate next work

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

## Files and live state

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
