import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_bootstrap import resolves_to_member, runtime_environment_errors


class RuntimeEnvironmentTests(unittest.TestCase):
    def test_python_entrypoint_may_resolve_through_packaged_symlinks(self):
        links = {"bin/python3": "python3.14", "bin/python": "python3"}
        self.assertTrue(resolves_to_member("bin/python", {"bin/python3.14"}, links))
        self.assertFalse(resolves_to_member("bin/python", set(), links))
        self.assertFalse(resolves_to_member("bin/python", set(), {"bin/python": "python"}))

    def test_reject_build_host_path_in_packaged_shell_script(self):
        data = b'#!/bin/bash\nexport PATH="/data/data/com.veyterm/files/usr/libexec/makepkg-binutils:/home/builder/.termux-build/bin:/usr/bin"\n'
        errors = runtime_environment_errors("bin/makepkg", data)
        self.assertEqual(2, len(errors))

    def test_keep_the_calling_environment_path(self):
        data = b'#!/bin/bash\nexport PATH="/data/data/com.veyterm/files/usr/libexec/makepkg-binutils:$PATH"\n'
        self.assertEqual([], runtime_environment_errors("bin/makepkg", data))

    def test_debug_source_locations_in_native_programs_are_not_runtime_paths(self):
        self.assertEqual([], runtime_environment_errors("bin/paru", b'\x7fELF/home/builder/.cargo/source.rs'))


if __name__ == "__main__":
    unittest.main()
