The `termux/termux-app` repository is released under [GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html) license.

### Exceptions

- [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) code is used which is released under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) license. Check [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator) libraries.
- Check [`termux-shared/LICENSE.md`](termux-shared/LICENSE.md) for `termux-shared` library related exceptions.
- File workspace dependencies retain their own notices in
  [`archive_third_party_notices.txt`](app/src/main/res/raw/archive_third_party_notices.txt)
  and [`apk_third_party_notices.txt`](app/src/main/res/raw/apk_third_party_notices.txt).
- The separately executed UnRAR command retains the
  [UnRAR license](app/src/main/cpp/veyterm-unrar/LICENSE.UnRAR) and
  [acknowledgements](app/src/main/cpp/veyterm-unrar/ACKNOWLEDGEMENTS.UnRAR),
  including its restriction on developing RAR compression. It is not linked
  into the application's GPL JNI reader. The command, its build guard, and
  the NDK runtime/sysroot have separate notices under
  `assets/licenses/veyterm-unrar/` in the APK; see
  [the component notes](docs/file-manager-rar-complete.md).
