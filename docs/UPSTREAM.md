# Upstream baseline

VeyTerm was initialized from the `master` branch of
[`termux/termux-app`](https://github.com/termux/termux-app).

- Source commit: `8629e632fcb95da272221be327db653fb24befe9`
- Source snapshot date: 2026-09-26
- Upstream remote: `https://github.com/termux/termux-app.git`
- Upstream license: GPLv3-only, with the exceptions recorded in
  [`../LICENSE.md`](../LICENSE.md) and the component license files.

The Git remote is named `upstream` locally so a future VeyTerm remote cannot be
confused with the source repository.

## Package identity boundary

VeyTerm uses Android application ID `com.veyterm`, and the rebuilt bootstrap
archives target `/data/data/com.veyterm/files/usr`. The Java namespace remains
`com.termux` for existing classes and JNI symbols; `TermuxConstants` keeps that
namespace separate from the installed application ID.

The bootstrap build currently covers the core apt bootstrap package set for
all four Android architectures. A complete VeyTerm package repository still
needs to be published before arbitrary `pkg install` operations can use
VeyTerm-specific binaries.
