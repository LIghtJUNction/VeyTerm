TERMUX_PKG_HOMEPAGE=https://github.com/Morganamilo/paru
TERMUX_PKG_DESCRIPTION="AUR helper with native Android repository support"
TERMUX_PKG_LICENSE="GPL-3.0"
TERMUX_PKG_MAINTAINER="VeyTerm"
TERMUX_PKG_VERSION=2.1.0
TERMUX_PKG_REVISION=3
_COMMIT=7830fac148f8cd2143307386ead67d7ffd2efa33
TERMUX_PKG_SRCURL="https://github.com/Morganamilo/paru/archive/${_COMMIT}.tar.gz"
TERMUX_PKG_SHA256=94869ec2dac20d3d34b5aa6cb32ffa6e2f1f434e79201287729a6028266e248a
TERMUX_PKG_DEPENDS="pacman, git, openssl, libiconv"
TERMUX_PKG_BUILD_IN_SRC=true
TERMUX_PKG_CONFFILES="etc/paru.conf"

termux_step_pre_configure() {
	termux_setup_rust
	export OPENSSL_DIR="$TERMUX_PREFIX"
	export PKG_CONFIG_ALLOW_CROSS=1
	export LIBCLANG_PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib"
	export BINDGEN_EXTRA_CLANG_ARGS="$CPPFLAGS $CFLAGS --target=$TERMUX_HOST_PLATFORM$TERMUX_PKG_API_LEVEL --sysroot=$TERMUX_STANDALONE_TOOLCHAIN/sysroot"
	cargo fetch --locked
	mkdir -p vendor
	cp -a "$HOME"/.cargo/registry/src/*/alpm-5.0.2 vendor/alpm
	cp -a "$HOME"/.cargo/registry/src/*/openssl-probe-0.1.6 vendor/openssl-probe
	python3 - "$TERMUX_PREFIX" <<'PY'
from pathlib import Path
import re
import sys

prefix = sys.argv[1]
config = Path("src/config.rs")
text = config.read_text()
for old in ("/etc/paru.conf", "/var/lib/aurbuild/"):
    if old not in text:
        raise SystemExit(f"paru source changed: missing {old}")
    text = text.replace(old, prefix + old)
config.write_text(text)

# The Android pacman port deliberately installs as the app UID. Invoking sudo
# here would turn an ordinary native package transaction into a root request.
execution = Path("src/exec.rs")
text = execution.read_text()
old = "let mut cmd = if config.need_root {"
if text.count(old) != 1:
    raise SystemExit("paru source changed: pacman execution boundary moved")
execution.write_text(text.replace(old, 'let mut cmd = if config.need_root && !cfg!(target_os = "android") {'))

# alpm's build script tries to execute the target library on the build host.
# alpm-sys still checks the target's libalpm >=16 through cross pkg-config.
# Remove only the redundant host execution probe for this cross build.
manifest = Path("vendor/alpm/Cargo.toml")
text = manifest.read_text()
text, count = re.subn(r'\[build-dependencies\.alpm-sys\]\n[^\[]*', '', text)
if count != 1:
    raise SystemExit("alpm source changed: host version probe dependency moved")
manifest.write_text(text)
Path("vendor/alpm/build.rs").write_text("fn main() {}\n")

# C memcpy returns its destination pointer; recent Rust checks this ABI.
list_module = Path("vendor/alpm/src/list_mut.rs")
text = list_module.read_text()
old = "unsafe fn memcpy(dst: *mut c_void, src: *const c_void, n: usize);"
if text.count(old) != 1:
    raise SystemExit("alpm source changed: memcpy declaration moved")
list_module.write_text(text.replace(old, old[:-1] + " -> *mut c_void;"))

probe = Path("vendor/openssl-probe/src/lib.rs")
text = probe.read_text()
old = "/data/data/com.termux/files/usr/etc/tls"
if text.count(old) != 1:
    raise SystemExit("openssl-probe source changed: Android CA directory moved")
probe.write_text(text.replace(old, prefix + "/etc/tls"))
PY
	cat >> Cargo.toml <<-'EOF'
	alpm = { path = "vendor/alpm" }
	openssl-probe = { path = "vendor/openssl-probe" }
	EOF
	cargo update --offline -p alpm -p openssl-probe
	cargo fmt --all
}

termux_step_configure() {
	:
}

termux_step_make() {
	local target_name="${CARGO_TARGET_NAME//-/_}"
	local target_flags="CARGO_TARGET_${target_name^^}_RUSTFLAGS"
	export "$target_flags=${!target_flags} -l iconv"
	# alpm-sys ships x86_64 layout assertions. Generate bindings from the
	# Android target headers instead, including for 32-bit ARM and x86.
	cargo build --locked --release --features alpm/generate --target "$CARGO_TARGET_NAME" --jobs "$TERMUX_PKG_MAKE_PROCESSES"
}

termux_step_make_install() {
	install -Dm700 "target/$CARGO_TARGET_NAME/release/paru" "$TERMUX_PREFIX/bin/paru"
	mkdir -p "$TERMUX_PREFIX/etc"
	cat > "$TERMUX_PREFIX/etc/paru.conf" <<-EOF
	[options]
	RepoOnly
	PacmanConf = $TERMUX_PREFIX/etc/pacman.conf
	MakepkgConf = $TERMUX_PREFIX/etc/makepkg.conf
	EOF
	install -Dm600 LICENSE "$TERMUX_PREFIX/share/licenses/paru/LICENSE"
}
