# Software installation compatibility

These checks ran in VeyTerm on the API 33 x86_64 emulator on 2026-10-26.
They installed software and exercised its work. ARM and 32-bit execution
need separate verification.

| Environment | Software | Verified work |
| --- | --- | --- |
| Native Android | Python 3.14.8, pip 26.2.2 | Signed installation; Python upgrade and reinstall retain all 607 pip files |
| Native Android | requests 2.34.2, httpx 0.28.1, Rich 15.0.0 | Virtual environment installation, dependency check, HTTP request over TLS, terminal output |
| Native Android | Ruff | No compatible binary wheel was available; use the Linux version below |
| Debian through `veyterm-agents` | Python 3.13.5, Ruff 0.17.2, uv 0.13.5 | Create virtual environments, install binary wheels, lint valid Python, reject an undefined name, install and import a dependency with uv |
| Debian through `veyterm-agents` | TypeScript 7.0.2, esbuild 0.28.2, tsx 4.23.15 | npm installation; compile, bundle and execute a TypeScript program through all three tools |
| Debian through `veyterm-agents` | GCC 14.2.0, CMake 3.31.6, Ninja 1.12.1 | Configure, compile and execute a C++17 project |
| Debian through `veyterm-agents` | ripgrep 14.1.1, fd 10.2.0, SQLite 3.46.1 | Find project files and execute a SQL aggregate |
| Debian through `veyterm-agents` | Claude Code, Codex, Pi, agy, Cursor CLI, Grok Build | All six installed versions still execute; [Agent UI checks](AGENT_COMPATIBILITY.md) are recorded separately |

Nine checks completed: eight passed, and the native Ruff wheel probe recorded
the compatibility limit above. The native environment uses Android's Bionic;
the Debian environment supplies glibc and has its own package manager.

## Native Python

The corrected Python package is `3.14.8-0.1`. Earlier converted Python hooks
used Debian's package-ownership check and deleted pacman-owned pip on first
startup or Python reinstallation. The converter now selects pacman and
increments the package release so existing installations can upgrade.
Executable payloads are unchanged. A regression test reproduces the deletion
before the fix and verifies that the corrected hook retains package-owned pip.

An updated APK advances the original bundled repository address and retains
the previous signed release as a fallback for unchanged packages. Custom
repository addresses and symlinked configuration files are preserved. To repair
pip files already deleted by an older installation, run once after updating:

```sh
pacman -Syu python-pip
```

For project dependencies, use the package-managed pip with an isolated project:

```sh
python3 -m venv --without-pip --system-site-packages .venv
.venv/bin/python -m pip install requests httpx rich
```

New dependencies stay inside the virtual environment. Avoid replacing the
system pip with `pip install --upgrade pip`.

## Linux development tools

```sh
veyterm-agents setup
veyterm-agents bash
# The following commands run inside the Linux shell, in the shared project.
apt-get update
apt-get install -y python3-venv cmake ninja-build ripgrep fd-find sqlite3
python3 -m venv .venv
.venv/bin/python -m pip install ruff uv
npm install --save-dev typescript esbuild tsx
```

Debian names the fd executable `fdfind`. New environments include
`python3-venv` through the setup helper; the explicit installation above also
works with an existing environment.

Installation methods follow the official [Python packaging guide](https://packaging.python.org/en/latest/tutorials/installing-packages/),
[esbuild guide](https://esbuild.github.io/getting-started/) and
[Ruff installation guide](https://docs.astral.sh/ruff/installation/).

## Repeat the checks

On an idle debug emulator with the Linux Agent environment prepared:

```sh
python3 tools/software_compatibility_probe.py --serial emulator-5570
```

Results and per-case logs are copied to `build/verification/software-compatibility`.
The checks retain installed Debian tools and the existing Agents, and remove
their own temporary projects, virtual environments and npm downloads. This
run's evidence is under `build/verification/software-compatibility-ui2`.
Provider authentication and real model calls were not tested.
