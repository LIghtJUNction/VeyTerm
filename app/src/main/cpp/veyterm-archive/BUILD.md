# RAR native module

Run from the repository root with Python >= 3.9 and NDK 30:

```sh
python3 app/src/main/cpp/veyterm-archive/prepare.py
```

This downloads the 6.42 MiB libarchive 3.8.10 source tarball, verifies SHA-256
`56cad765459d5ee25a3b761f90c47d99379d28297fbfb72d908880d6b875fb96`, extracts
only 21 C files plus headers/COPYING (about 1.1 MiB), applies the documented
policy/checksum fixes, and deletes the tarball. No shell, binary or generated
build script from the download is executed. To work offline use
`--tarball /absolute/path/libarchive-3.8.10.tar.xz` (same hash verification).
A failed prepare removes `.prepared`, preventing ndk-build with partial sources.
All generated sources and compiled outputs are beneath ignored `build/` directories.

The parent adds the following at the END of `app/src/main/cpp/Android.mk`:

```make
veyterm_app_cpp_path := $(LOCAL_PATH)
include $(veyterm_app_cpp_path)/veyterm-archive/Android.mk
LOCAL_PATH := $(veyterm_app_cpp_path)
```

This defines private static module `veyterm_rar_reader` and JNI shared module
`veyterm_archive`, producing `libveyterm_archive.so`. No static libarchive/system
libarchive package, iconv, C++ STL, external decoder, native executable or Java RAR
dependency is required. Only Android's system zlib/libc/libm/libdl and NDK compiler support
are linked. Both native modules append `-fstack-protector-strong` after inherited app flags,
including the parent's `-fno-stack-protector`; no vendor warning suppression is needed.
The export map keeps libarchive and allocator symbols private.
If the parent specifies native build targets explicitly, include
`veyterm_archive` along with existing targets. Preserve all four ABI filters.
The normal Gradle ndk-build packaging then discovers the shared module.
No Manifest permission is needed.

Independent native compilation, without Gradle/bootstrap:

```sh
mkdir -p build/rar-native-check
cat > build/rar-native-check/Application.mk <<'MAKE'
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_PLATFORM := android-21
APP_STL := none
APP_MODULES := veyterm_archive
MAKE
/opt/android-ndk/ndk-build NDK_PROJECT_PATH=null \
  APP_BUILD_SCRIPT="$PWD/app/src/main/cpp/veyterm-archive/Android.mk" \
  NDK_APPLICATION_MK="$PWD/build/rar-native-check/Application.mk" \
  NDK_OUT="$PWD/build/rar-native-check/obj" \
  NDK_LIBS_OUT="$PWD/build/rar-native-check/libs" \
  APP_CFLAGS='-Wall -Wextra -Werror -Os -fno-stack-protector' -j4
```

For host JVM verification, use the standalone runner documented in
`docs/file-manager-rar-dependencies.md`. It builds these same sources with the same
allocator and zlib, avoiding a system-libarchive version/configuration mismatch.
CMake accepts `-DARCHIVE_SOURCE_DIR=/absolute/prepared/source` for temporary builds.
The Android configuration avoids `nl_langinfo` (only available from API 26), using
libarchive's existing Bionic UTF-8 fallback. API 21 is the native compile baseline.

R8/ProGuard: the native entry names and callback/constructor must be retained:

```proguard
-keep class com.termux.app.files.archive.NativeRar { *; }
-keep class com.termux.app.files.archive.NativeRar$Member { *; }
```

Keep these with the app's existing native-method keep rules. The parent owns those
shared build/configuration files; this worker did not edit them. Package
`LICENSE.libarchive`, `LICENSE.CC0` and the source copyright notices as third-party
notices with the combined application. BLAKE2 uses the CC0 option; PPMd7's notices
state public domain. The adapter itself is GPL-3.0-only.
