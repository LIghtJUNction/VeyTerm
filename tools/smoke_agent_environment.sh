#!/data/data/com.veyterm/files/usr/bin/bash
set -euo pipefail
work_directory="$(mktemp -d "$TMPDIR/veyterm-agent-project.XXXXXXXX")"
trap 'rm -rf "$work_directory"' EXIT
cd "$work_directory"
veyterm-agents /bin/bash -s <<'GUEST'
set -euo pipefail
test "$PWD" = /workspace
cat > hello.c <<'C'
#include <stdio.h>
int main(void) { puts("LINUX_COMPILER_EXECUTION_OK"); return 0; }
C
cc -std=c11 -Wall -Wextra -Werror hello.c -o hello
./hello
node -e 'require("fs").writeFileSync("project-write.txt", "SHARED_PROJECT_WRITE_OK\n")'
git init -q
git -c user.name=VeyTerm -c user.email=local@localhost commit --allow-empty -qm fixture
git rev-parse --verify HEAD
for agent in claude codex pi agy cursor-agent grok; do
    printf 'AGENT_VERSION[%s]=' "$agent"
    "$agent" --version
done
GUEST
grep -q SHARED_PROJECT_WRITE_OK project-write.txt
test -f hello.c
echo AGENT_ENVIRONMENT_AND_SHARED_PROJECT_OK
