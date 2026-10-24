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

The current Android application ID remains `com.termux`, and the bootstrap
binaries still target `/data/data/com.termux/files/usr`. A future independent
VeyTerm package must change these together:

1. Rebuild Termux bootstrap archives and packages with the new prefix.
2. Update `TermuxConstants`, manifest placeholders, shortcuts, and any plugin
   intents that still contain the old package ID.
3. Build and install a clean APK, then verify bootstrap installation and a
   real shell session on Android.
