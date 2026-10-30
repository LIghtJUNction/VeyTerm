# Complete RAR decoding through a replaceable command

`ArchiveTools` keeps every existing public method, `Options`, `Job`, `Entry` and
read-only RAR behavior. No Android imports were added. By default it still uses
the BSD libarchive JNI reader; there is no PATH search, automatic subprocess
fallback, bundled Junrar, or UnRAR linkage into Java/JNI.

The optional public configuration is:

```java
com.termux.app.files.archive.RarExecutable.setExecutable(absoluteExecutableFile); // validates file + executable permission
RarExecutable.setExecutable(null);                   // BSD default
```

Configuration is snapshotted into each operation's private options. Do not use
a system property to configure production; the JVM test runner's property is
read only by `FullRarTest` and passed explicitly to this setter.

## Decoder and license decision

Primary sources checked on 2026-10-30:

* [libarchive RAR source](https://github.com/libarchive/libarchive/blob/v3.8.10/libarchive/archive_read_support_format_rar.c)
  and [its encryption tests](https://github.com/libarchive/libarchive/blob/v3.8.10/libarchive/test/test_read_format_rar_encryption.c)
  explicitly lack encrypted payload/header decoding and RAR4 solid decoding.
  Keep this BSD reader for installations without the optional command.
* [unarr's own limitations](https://github.com/selmf/unarr#limitations) exclude
  passwords, split archives and RAR5. It cannot satisfy this task.
* [7-Zip's license](https://www.7-zip.org/license.txt) identifies the UnRAR
  restriction on its RAR engine. Linking that engine does not remove the GPL
  incompatibility. Do not link it into the existing GPL application/JNI either.
* [RARLAB's official source download](https://www.rarlab.com/rar_add.htm) supplies
  the full compatible UnRAR command. Its license paragraph 2 restricts creating
  a compatible RAR compressor; paragraph 3 permits redistribution inside other
  software packages. The complete text is retained as `LICENSE.UnRAR`.
* [GNU GPL FAQ: mere aggregation](https://www.gnu.org/licenses/gpl-faq.html#MereAggregation)
  distinguishes independent programs communicating through ordinary arguments
  and pipes from one linked program or an intimate exchange of internal data
  structures. Its final classification is a legal question, not a filename or
  process-boundary guarantee.

The design follows the FAQ's independent-program case: a separately licensed,
independently runnable, ordinary UnRAR CLI, speaking only public `lt`, `t`, `p`
commands, UTF-8 stdin/stdout and local pathnames. There is no DLL interface,
function-pointer linkage, custom private IPC, shared objects/data structures or
private wire format. The `.so` extension is an Android packaging convention;
the artifact is a standalone executable PIE with PT_INTERP and its own `main`.
Never call `System.loadLibrary("veyterm_unrar")` or add it to JNI link libraries.
The aggregate must preserve both programs' separate licenses and must not
impose UnRAR's compressor restriction on VeyTerm's GPL code.

This is an implementation/license interpretation grounded in those primary
texts, not a legal ruling. A distributor must preserve this separation and all
notices. The native command directory contains independently licensed UnRAR
and BSD guard/build support only; it is not GPL JNI/Java source.

## Pinned source and notices

* URL: `https://www.rarlab.com/rar/unrarsrc-7.3.2.tar.gz`
* SHA-256: `f61a1160d6e2ebbda2ad353c8e82f62ab4312f97fde23c6c2c982d25e0014f99`
* Banner: UnRAR 7.30 beta 2, official source available on the research date.
* `prepare.py` validates the hash and the exact vendored license and
  acknowledgements before preparing anything. A failed hash removes the old
  success stamp. Downloads are explicit; CMake/ndk-build never download code.
* The complete original source, including all original notices, remains in
  prepared `source/original/`; the working copy retains all upstream files.
  `sources.txt` is the ordinary command's 44 OBJECTS + 5 UNRAR_OBJ translation
  units, excluding the DLL. No algorithm source is committed to GPL JNI.
* `LICENSE.UnRAR` and `ACKNOWLEDGEMENTS.UnRAR` are copied verbatim from the
  official archive, including Intel's BSD notice. `LICENSE.guard` covers the
  separate resource guard. `NOTICE.NDK-r30-runtime` preserves the NDK r30 LLVM,
  libc++, libc++abi and compiler runtime notices for the static runtime.
  `NOTICE.NDK-r30-sysroot` additionally preserves the Bionic/startup-object
  and sysroot notices. The runtime-notice SHA-256 is `f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1`.
* The preparation script and patched source comments include the full required
  UnRAR paragraph 2. Downstream source distributions must keep it.

Source preparation makes policy changes to `arcread.cpp`, never the algorithms:
reject oversize/truncated raw names, embedded NULs, malformed RAR5 UTF-8,
control/bidi formatting characters, link/redirection/special types and unknown
host types before name normalization. That covers decrypted headers as well.
RAR4's specified backslash path separator is converted by the ordinary command
to `/`; RAR5 literal backslashes and colons are rejected. The extra checks are
necessary because upstream `lt` has no lossless quoting format and upstream
normalizes/truncates certain names and displays Unix special modes as files.

## Process and archive contract

* The command must be a trusted, explicit absolute executable. It is genuinely
  replaceable: an independently built CLI implementing these public switches
  and the same lossless-input/special-file rejection contract can be selected
  with `setExecutable`. An arbitrary upstream binary that silently normalizes
  hostile names is **not** an equivalent safe replacement. Replacement tools
  must pass the full feature and hostile-input suites. No private handshake
  binds the Java code to this build or source hash.
* Commands use `ProcessBuilder(List<String>)`; no shell. `-cfg-` disables
  configuration; RAR/RARINISWITCHES environment settings are removed, and the
  child receives `LC_ALL=C.UTF-8`, `LANG=C.UTF-8`, `TZ=UTC`. Archive paths
  containing `*?[]` are explicitly rejected, since the CLI could expand them.
* Passwords use the documented bare `-p` prompt and a UTF-8 stdin line, then EOF.
  They are never in argv, logs or retained stderr. Missing passwords use `-p-`.
  Reject line/control characters, ill-formed UTF-16 and >127 UTF-16 units
  rather than silently truncating/altering a password. Each API still accepts
  just one password: mixed per-entry passwords fail the whole verification or
  extraction pass, even if a selected cleartext entry could be read alone.
* `lt -v -c- -idcp -scf` is parsed as a strict technical listing. Names are not
  trimmed. Require UTF-8, known fields, unique fields, numeric bounds, valid
  attributes/types/dates/compression descriptions and complete split chains.
  Only QO/RR/CMT service metadata is ignored; streams, unknown service types,
  file versions/owner extensions, missing payload checksums and unknown/localized
  output are rejected.
  All names/tree conflicts/duplicates are preflighted, including unselected
  members. Partial/missing volumes and non-first continuations fail explicitly.
* `t -inul` verifies all payloads. `p -inul` emits the **whole archive**, never
  a user-supplied wildcard member mask. Validated declared sizes delimit each
  entry; unwanted data is discarded with a 32 KiB buffer. Both unexpected extra
  bytes and short stdout fail. Await successful process exit (including final
  CRC32/BLAKE2/MAC checks) before `Data.copy` returns, syncs the staged target,
  and permits the existing same-directory atomic rename. Late checksum errors
  cannot publish even the first selected file. Never invoke `x`/`e`, nor point
  any CLI extraction at the caller's destination.
* Each selected regular file currently requires one complete `p` pass. This is
  intentionally bounded in RAM/disk, but can be expensive for many files in a
  solid archive. List and test do not buffer payloads. There is no speed claim.
* Limits apply to all declared entries/data, including entries not selected.
  Technical stdout is capped at `min(8 MiB, maxMemoryKiB / 4 in bytes)`, a line
  at 8192 chars, stderr at 64 KiB (discarded), and volumes at 1024. Whole-archive
  stdout is bounded to the validated sum of sizes. The native child is single
  threaded (no RAR_SMP). `-mdx` is a power-of-two dictionary budget at most one
  half of `maxMemoryKiB`; `-limm` and `-limt120` are ordinary CLI limits.
* `command_main.cpp` additionally sets kernel limits before upstream main:
  RLIMIT_DATA to twice that dictionary (at most the caller's allocation
  budget, capped at 256 MiB), RLIMIT_AS to this plus 128 MiB mapping allowance,
  RLIMIT_CPU 120 s, NOFILE 32, CORE 0, and FSIZE 0. The latter prohibits regular
  file payload writes; Java invokes only p/t/lt and never file extraction.
  Resource/limit setup fails closed. These bound
  the independent child, not the parent Java heap or the sum of simultaneous
  operations. The parent UI must limit concurrent archive jobs.
* Java drains both pipes on daemon workers, polls cancellation/interruption and
  failures every 20 ms, applies a 120-second per-process wall deadline, destroys
  the child and closes/joins pipe workers on every exit. It uses Android-21
  `Process.destroy/exitValue` APIs, not ProcessHandle, timed waitFor, isAlive or
  destroyForcibly. Android 21's ProcessManager.destroy sends SIGKILL
  ([primary source](https://android.googlesource.com/platform/libcore/+/android-5.0.0_r1/luni/src/main/java/java/lang/ProcessManager.java)).
  Helper byte-progress callbacks run on the stdout pump; UI consumers must
  marshal updates to their UI thread. On the host the standard command honors
  termination signals. Replacements
  must terminate on destroy; the CLI is trusted executable code, not a sandbox
  for arbitrary hostile programs.

The source/destination filesystem must not be concurrently mutated, matching
the existing extraction contract. All discovered source volumes are protected
against extraction overwrite. Volumes must be regular, non-symlink siblings of
the first local file. Remote/SAF sibling staging and first-volume selection are
parent/UI follow-ups; this layer receives local files, not content URIs.

## Build the four Android standalone commands

From repository root, with Python >=3.9, CMake >=3.22 and NDK
`30.0.16248370` (r30):

```sh
python3 app/src/main/cpp/veyterm-unrar/prepare.py
python3 app/src/main/cpp/veyterm-unrar/build-android.py \
  --ndk /opt/android-sdk/ndk/30.0.16248370
```

The script configures Android API 21, static libc++, PIE, RELRO/NOW, stack guards
and 16 KiB load alignment, then builds **one job and one ABI at a time**. Outputs:

```text
app/src/main/cpp/veyterm-unrar/build/android/arm64-v8a/libveyterm_unrar.so
app/src/main/cpp/veyterm-unrar/build/android/armeabi-v7a/libveyterm_unrar.so
app/src/main/cpp/veyterm-unrar/build/android/x86/libveyterm_unrar.so
app/src/main/cpp/veyterm-unrar/build/android/x86_64/libveyterm_unrar.so
```

`android_compat.hpp` selects upstream's POSIX nanosecond/utimensat path at API 21,
and a small BSD termios shim supplies Bionic's missing getpass for terminal use.
Redirected stdin passwords still use the unchanged upstream code path.

No bootstrap, app/JNI build, Gradle, APK, device or CI is involved. CMake checks
for a PIE interpreter and absence of a shared libc++ dependency. `Android.mk`
also provides a BUILD_EXECUTABLE module for ndk-build users. If the parent
chooses this route, include it explicitly from the root Android.mk, build
`APP_MODULES=veyterm_unrar APP_STL=c++_static APP_PLATFORM=android-21` with
`-j1`, and stage the **executable** output below; merely adding an executable
module does not make Gradle package it as a JNI library. Do not add it as a
dependency of another native module. The dedicated CMake path avoids a global
APP_STL change affecting existing modules and is the recommended integration.

## Exact parent-owned packaging and initialization

The child change does not edit shared Gradle, root Android.mk, Manifest or
FileStores. The parent can add this standalone path to `app/build.gradle`:

```groovy
def unrarDir = file("src/main/cpp/veyterm-unrar")
def unrarNative = layout.buildDirectory.dir("generated/unrar/jniLibs")
def unrarNotices = layout.buildDirectory.dir("generated/unrar/assets")

// Source preparation is an EXPLICIT pre-build provisioning step above;
// neither this task nor native compilation performs network access.
def buildUnrarCommand = tasks.register("buildUnrarCommand", Exec) {
    workingDir rootProject.projectDir
    commandLine "python3", new File(unrarDir, "build-android.py").absolutePath,
        "--ndk", new File(android.sdkDirectory, "ndk/${android.ndkVersion}").absolutePath
    inputs.files(fileTree(unrarDir) { exclude "build/android/**" })
    outputs.files(["arm64-v8a", "armeabi-v7a", "x86", "x86_64"].collect {
        new File(unrarDir, "build/android/${it}/libveyterm_unrar.so")
    })
}
def stageUnrarCommand = tasks.register("stageUnrarCommand", Sync) {
    dependsOn buildUnrarCommand
    ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"].each { abi ->
        from(new File(unrarDir, "build/android/${abi}")) {
            include "libveyterm_unrar.so"
            into abi
        }
    }
    into unrarNative
}
def stageUnrarNotices = tasks.register("stageUnrarNotices", Sync) {
    from(unrarDir) {
        include "LICENSE.UnRAR", "ACKNOWLEDGEMENTS.UnRAR", "LICENSE.guard", "NOTICE.NDK-r30-*"
    }
    into unrarNotices.map { it.dir("licenses/veyterm-unrar") }
}
android.sourceSets.main.jniLibs.srcDir(unrarNative)
android.sourceSets.main.assets.srcDir(unrarNotices)
tasks.named("preBuild").configure { dependsOn stageUnrarCommand, stageUnrarNotices }
tasks.configureEach {
    if (name.matches("merge.*NativeLibs")) dependsOn stageUnrarCommand
    if (name.matches("merge.*Assets")) dependsOn stageUnrarNotices
}
// Existing packagingOptions.jniLibs.useLegacyPackaging true must remain:
// PackageManager must extract the PIE to the executable nativeLibraryDir.
```

Ensure Gradle's `ndkVersion` is the r30 version above. Do not rely on
`System.loadLibrary` or extract/chmod an executable in app-writable data:
Android 10+ prohibits execution there for modern target SDKs. Preserve the
installed native-library extraction policy (`useLegacyPackaging true`; parent
must inspect the merged manifest/install result). Keep the four ABI names
aligned with the parent's ABI filters. Supply notices in the APK assets and
the release/source distribution; do not advertise the aggregate as entirely
GPL/BSD.

Android integration additionally measures the command's existing virtual address
reservations before setting its address-space headroom: Bionic/Scudo reserves
large inaccessible regions before `main`. Applying the host's absolute address
limit on Android caused an observed abort. The writable-data, CPU, descriptor,
file-output and dictionary limits remain enforced. Android's password reader
also accepts the process's redirected stdin, without requiring a TTY. These
paths are covered by the packaged Android encrypted-header and solid RAR cases.

At FileStores/application initialization, on a background setup path:

```java
File command = new File(context.getApplicationInfo().nativeLibraryDir,
                        "libveyterm_unrar.so");
if (command.isFile() && command.canExecute()) {
    RarExecutable.setExecutable(command);
} else {
    RarExecutable.setExecutable(null); // BSD reader still works as before
}
```

This parent-owned call is required. With no call the complete command remains
disabled. Device acceptance must check permissions, SELinux/exec, APK ABI and
notice contents, and use list/test/extract with encrypted headers and local
volumes through the actual UI. An ELF build does not establish device execution.

## Reproducible host verification and remaining acceptance

```sh
python3 app/src/test/java/com/termux/app/files/archive/run-jvm-tests.py --full-helper
```

The runner builds native dependencies serially and uses separate JVMs for the
40 original archive/native tests, the missing-JNI test, and `FullRarTest` with
the actual standalone helper. The full-helper JVM deliberately has no JNI
library. Its test-only property is translated into the explicit setter.
The synthetic C command is used only for deterministic timeout/output-bound,
malformed metadata and late exit-code tests, never feature success evidence.

Public fixtures remain unmodified uuencoded libarchive 3.8.10 data. SHA256SUMS
is checked before decoding. Upstream test sources describe passwords and exact
plaintext; see the fixture README. Small single-password subsets retain original
file/header/ciphertext bytes and add the standard ENDARC record. They do not
substitute invented encrypted data for full public archives: all six complete
solid/header-encrypted RAR4/RAR5 variants are also exercised. Tests verify
source preservation, wrong/missing/mixed passwords, last-member selection,
whole-pass CRC failures, Unicode and wildcard names, limits, cancellation,
thread cleanup, local volumes and hostile input.

Validation results are recorded below after running. Full Android/Gradle builds,
APK packaging, device execution/UI, remote/SAF volume staging, CI and push are
outside this change. RAR remains read-only. Mixed passwords and unsupported
metadata are explicit failures; many-file extraction currently repeats decoding.

## Local validation result

Completed locally on 2026-10-30, without Gradle/app/device/CI work:

* Original BSD/JNI archive suite: **40 passed**; missing-JNI separate JVM:
  **1 passed**. Full command suite in a separate JVM without JNI: **12 passed**.
  Main application's public API signatures remain unchanged. Compilation uses
  `javac --release 8`; JVM heap limit is 192 MiB.
* Complete original encrypted-header and/or solid RAR4/RAR5 fixture variants
  extract all four exact 18-byte plaintext files with `password`. Older complete
  data/header fixtures use `12345678`. Clear-header single-password subsets also
  pass; complete archives with both `password` and `password2` explicitly fail
  whole-archive verification/extraction without replacing an existing target.
* Complete RAR4 three- and six-volume sets and RAR5 eight-volume sets list,
  test and extract; the RAR4 HTML SHA-256 and both RAR5 upstream CRC32s match.
  Missing middle volumes fail; generated genuine split STORE volumes cannot
  overwrite their own later volume.
* Cancellation during a 20,131,111-byte actual full-helper stream and during
  encrypted RAR4 solid / RAR5 encrypted-header extraction preserves source hashes
  and existing targets with no staging files or pump threads left. Progress
  chunks remain at most 32768 bytes. Late CRC exit and stdout excess fail before
  atomic publication. Traversal, duplicate/conflicting names, control/NUL names,
  links/specials and malformed technical listing tests pass.
* Observed six hard kernel limits on the actual host child: DATA 64 MiB,
  AS 192 MiB, CPU 120 s, NOFILE 32, CORE/FSIZE 0. A direct 1 MiB invocation
  fails closed. These are limit checks, not a peak-RSS benchmark or device proof.
* All **159** original upstream source files are preserved byte-for-byte.
  All **43** public fixture hashes verify. A deliberately bad source hash is
  rejected and removes a stale success stamp. Fresh preparation reproduces the
  exact patched source used for compilation and checks the command-source list
  against upstream's makefile.
* NDK **30.0.16248370**, Android API 21, serial four-ABI CMake builds succeeded.
  Each artifact is ET_DYN/PIE, has PT_INTERP and GNU_RELRO, uses 0x4000 LOAD
  alignment, has no JNI exports, and needs only `libm.so`, `libdl.so`, `libc.so`.
  Static libc++ is included; no `libc++_shared.so` dependency exists.

| ABI | Unstripped PIE bytes |
| --- | ---: |
| arm64-v8a | 4,966,720 |
| armeabi-v7a | 4,154,964 |
| x86 | 4,223,404 |
| x86_64 | 4,972,208 |

Temporary host commands, jars/classes, research downloads and Android build
outputs are removed after validation. Only the small ignored reproducible source
bootstrap/cache is retained; the parent rebuilds from the commands above.
APK packaging, installed executable permissions/SELinux, emulator/device/UI and
remote/SAF sibling staging remain unverified here and belong to the parent.
