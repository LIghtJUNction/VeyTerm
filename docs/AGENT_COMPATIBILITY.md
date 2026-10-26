# Coding Agent compatibility

`veyterm-agents` runs official Linux programs in an optional Debian environment,
with the current project directory available as `/workspace`. Android root is
not required. Its Linux packages use Debian's repositories; the native Android
environment uses VeyTerm's signed pacman repository.

```sh
# Install Debian, Node.js, a compiler, and the six official Agents.
veyterm-agents install

# Run an Agent in the current project.
cd my-project
veyterm-agents claude
veyterm-agents codex
veyterm-agents pi
veyterm-agents agy
veyterm-agents cursor-agent
veyterm-agents grok
```

For a development environment without the Agent downloads, run
`veyterm-agents setup`. `veyterm-agents` opens a Linux shell, and
`veyterm-agents COMMAND ...` runs other Linux programs. Files written in
`/workspace` are written directly into the current Android project directory.
Initial setup needs a network connection.

For Python environments and other development tools, see the
[software compatibility checks](SOFTWARE_COMPATIBILITY.md).

## Helper upgrades and installation storage

App startup installs missing bundled helpers and updates recognized bundled
versions. SHA256 fingerprints identify the preceding installed version; known
legacy fingerprints allow the first upgrade without existing state. Unrecognized
contents are preserved. Symlinks, directories and other non-regular targets are
skipped. Updates use a temporary file in the target directory and an atomic
rename. Fingerprints live in `$PREFIX/var/lib/veyterm/bundled-helpers`.
Shell configuration remains create-only.

Each Agent installation now owns a temporary directory for its npm cache and
downloaded installer scripts. Setup similarly puts apt package archives and Node
downloads in its temporary directory. An EXIT trap removes that directory on
success, failure or an early return. Existing user caches, apt package indexes,
Agent binaries and the Debian root filesystem are retained. This prevents new
installer residue; it does not claim to shrink the existing Linux environment.

Local shell tests exercise six success/failure/early-return paths with command
stubs, including preservation of an existing user npm cache. A normal debug APK
upgrade on `emulator-5570` updated both changed helpers, preserved the
package-provided `sudo` and `.zshrc`, and remained stable across another startup.
The new helpers were not used to repeat the six network installations. The
version and PTY results below belong to the earlier acceptance runs.

## Verified versions

The following official releases were installed on the API 33 x86_64 emulator
on 2026-10-26, using Node.js 24.22.1 and PRoot-Distro 5.10.2.

| Agent | Version | Official source |
| --- | --- | --- |
| Claude Code | 2.1.310 | [Anthropic setup](https://code.claude.com/docs/en/setup) |
| Codex | 0.164.0 | [OpenAI installation command](https://developers.openai.com/cookbook/examples/codex/using_goals_in_codex) |
| Pi | 1.4.0 | [earendil-works/pi](https://github.com/earendil-works/pi) |
| Antigravity CLI (`agy`) | 1.4.7 | [Google installation](https://antigravity.google/docs/install?tab=cli) |
| Cursor CLI (`cursor-agent`) | 2026.10.22-27dcf5f | [Cursor installation](https://cursor.com/docs/cli/installation) |
| Grok Build (`grok`) | 1.0.70 | [xai-org/grok-build](https://github.com/xai-org/grok-build) |

All six passed installation, version queries and PTY startup with the actual
VeyTerm renderer. Claude's theme selection and Pi's `/hotkeys` exercised input
and repainting. Codex, Pi, agy and Grok emitted synchronized output frames.
The [synchronized rendering checks](TERMINAL_RENDERING.md) test incomplete
frames and recovery separately.

Account sign-in and authenticated model tasks were not tested. The official
Linux binary releases used here target x86_64 or ARM64; only x86_64 was run in
this acceptance test. Screenshots, terminal traces and logs remain local under
`build/verification/agents-final`; the final shared-project/compiler check is
`build/verification/veyterm-agent-project-final.log`. Public repository and
download acceptance is recorded in
[`CONTINUE_20261026.md`](CONTINUE_20261026.md).

The UI update was also exercised with all six Agents under
`build/verification/agents-ui2-final`. Codex's cold startup did not produce a
UI within the old 14-second probe window; a separate 45-second check passed
under `build/verification/agents-ui2-codex-cold`. The probe now exposes
`--seconds` and keeps its output and live-process requirements.

Repeat the PTY checks on an idle debug emulator with:

```sh
python tools/emulator_agent_smoke.py --serial emulator-5570
```
