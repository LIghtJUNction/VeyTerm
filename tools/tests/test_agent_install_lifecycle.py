"""Exercise installer cleanup without downloading or replacing real software."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets/shell"


class AgentInstallLifecycleTest(unittest.TestCase):
    def run_helper(self, helper, fail_command="", node_ready=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            commands = root / "bin"
            commands.mkdir()
            home = root / "home"
            home.mkdir()
            temporary = root / "tmp"
            temporary.mkdir()
            cache = home / ".npm"
            cache.mkdir()
            (cache / "keep").write_text("existing user cache")
            (commands / "proot-distro").write_text('#!/bin/bash\nexec /bin/bash -s\n')
            stub = '''#!/bin/bash
set -eu
command="${0##*/}"
if [[ "$command" = "$FAIL_COMMAND" ]]; then exit 37; fi
case "$command" in
  apt-get)
    for arg in "$@"; do
      if [[ "$arg" = Dir::Cache::archives=* ]]; then
        touch "${arg#*=}/download.deb"
      fi
    done ;;
  npm)
    if [[ "${1:-}" = install ]]; then
      mkdir -p "$npm_config_cache"
      touch "$npm_config_cache/download"
    fi ;;
  node)
    if [[ "${1:-}" = -e && "$NODE_READY" = no ]]; then exit 1; fi ;;
  sha256sum)
    if [[ "${1:-}" = -c ]]; then cat > /dev/null; fi ;;
  uname) printf 'x86_64\\n' ;;
  curl)
    while [[ $# -gt 0 ]]; do
      if [[ "$1" = -o ]]; then
        if [[ "$2" = SHASUMS256.txt ]]; then
          printf 'stub  node-v24.22.1-linux-x64.tar.xz\\n' > "$2"
        else
          printf '#!/bin/bash\\nexit 0\\n' > "$2"
        fi
        break
      fi
      shift
    done ;;
esac
'''
            for name in ("apt-get", "npm", "node", "uname", "curl", "sha256sum", "tar",
                         "claude", "codex", "pi", "agy", "grok", "cursor-agent", "git"):
                (commands / name).write_text(stub)
            for command in commands.iterdir():
                command.chmod(0o755)
            environment = dict(os.environ, PATH=f"{commands}:/usr/bin:/bin", HOME=str(home),
                               TMPDIR=str(temporary), FAIL_COMMAND=fail_command,
                               NODE_READY="yes" if node_ready else "no")
            # The guest PATH is intentionally reset by the installer. Inject stubs
            # immediately after that reset, keeping the real installer body intact.
            script = (ASSETS / helper).read_text().replace(
                'export PATH="$HOME/.local/bin:$HOME/.grok/bin:/usr/local/bin:$PATH"',
                'export PATH="$TEST_COMMANDS:$PATH"')
            environment["TEST_COMMANDS"] = str(commands)
            result = subprocess.run(["/bin/bash"], input=script, text=True, env=environment,
                                    cwd=root, capture_output=True)
            self.assertEqual([], list(temporary.iterdir()), result.stderr)
            self.assertEqual("existing user cache", (cache / "keep").read_text())
            self.assertFalse((home / "veyterm-agent-installers").exists())
            return result

    def test_agent_success_and_failure_remove_only_owned_cache(self):
        self.assertEqual(0, self.run_helper("veyterm-agent-install").returncode)
        self.assertEqual(37, self.run_helper("veyterm-agent-install", "claude").returncode)
        self.assertEqual(37, self.run_helper("veyterm-agent-install", "curl").returncode)

    def test_setup_early_return_download_and_failure_clean_up(self):
        self.assertEqual(0, self.run_helper("veyterm-agent-setup").returncode)
        self.assertEqual(0, self.run_helper("veyterm-agent-setup", node_ready=False).returncode)
        self.assertEqual(37, self.run_helper("veyterm-agent-setup", "apt-get").returncode)


if __name__ == "__main__":
    unittest.main()
