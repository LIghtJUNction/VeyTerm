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
`build/verification/agents-tui` and `build/verification/agents-pi-hotkeys`.

Repeat the PTY checks on an idle debug emulator with:

```sh
python tools/emulator_agent_smoke.py --serial emulator-5570
```
