# VeyTerm core migration boundary

## Kotlin first

The first Kotlin slice is `SessionPresentationResolver` and the
`KeyboardShortcut` value type. They are Android-free or UI-adjacent policy and
can be tested without touching the terminal emulator, service, bootstrap, or
JNI process lifecycle. Java callers use the generated Kotlin getters/fields, so
migration can proceed file by file.

The terminal emulator, `TerminalView`, `TermuxService`, installer, and
permission/intent code remain Java until parity tests cover their lifecycle and
threading behavior. Kotlin does not change the `com.termux` Java namespace.

## Rust boundary

`rust-core` is a dependency-free Rust `cdylib` with a versioned JNI ABI probe.
It intentionally does not replace the existing PTY JNI (`libtermux`), bootstrap
JNI (`libtermux-bootstrap`), or local socket JNI. Those paths are terminal
critical and must first have Android-target build, ABI, signal, fork/exec, and
crash parity evidence.

The Kotlin adapter loads `veyterm_core` only when `VeyTermNativeCore.abiVersion()`
is called. This keeps the current APK behavior unchanged while the Android Rust
targets are made reproducible. The crate has no third-party dependencies and is
licensed GPL-3.0-only like the new VeyTerm application code.

## Android integration gate

The app keeps Rust opt-in while release builders provision `cargo-ndk` and all
four Android standard-library targets: `arm64-v8a`, `armeabi-v7a`, `x86`, and
`x86_64`. The current APK still uses ndk-build for existing JNI and the default
release artifact is `veyterm-app_apt-android-7-release_universal.apk` plus ABI
splits. A Rust-enabled build is explicit:

```sh
VEYTERM_BUILD_RUST_CORE=1 ./gradlew :app:assembleDebug
```

That task builds `rust-core` into generated `jniLibs`; release verification must
check that the APK contains one `libveyterm_core.so` per ABI before enabling
`VeyTermNativeCore.abiVersion()` in application code. The adapter is not called
by the current app, so the default APK cannot fail at startup because the
optional library is absent.
