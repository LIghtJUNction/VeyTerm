import io
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from assemble_pacman_bootstrap import add_bytes, pacman_version, read_package, write_package


class PackageConversionTests(unittest.TestCase):
    def test_debian_upstream_hyphen_does_not_become_part_of_the_package_name(self):
        for source, expected in (("9.3", "9.3-0"), ("1.3-20260721-0", "1.3.20260721-0"),
                                 ("1:3.6.5", "1:3.6.5-0"), ("2.47-2", "2.47-2")):
            with self.subTest(source=source):
                self.assertEqual(expected, pacman_version(source))

    def test_real_pacman_sees_the_program_in_a_converted_debian_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = self.fixture(root)
            output = root / "fixture-1.3.20260721-0-x86_64.pkg.tar.xz"
            write_package(package, output)
            files = subprocess.check_output(["pacman", "-Qlp", "--quiet", str(output)], text=True).splitlines()
            self.assertIn("/data/data/com.veyterm/files/usr/bin/fixture", files)
            with tarfile.open(output) as archive:
                member = archive.getmember("data/data/com.veyterm/files/usr/bin/fixture")
                self.assertEqual(0o700, member.mode)
                self.assertEqual(b"#!/system/bin/sh\necho fixture\n", archive.extractfile(member).read())

    def test_maintainer_hooks_receive_debian_configure_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "fixture.pkg.tar.xz"
            write_package(self.fixture(root), output)
            with tarfile.open(output) as archive:
                hook = archive.extractfile(".INSTALL").read()
            (root / "install").write_bytes(hook)
            env = dict(os.environ, CALL_LOG=str(root / "calls"))
            subprocess.run(["bash", "-c", 'source "$1"; post_install 1.3.20260721-0', "bash", str(root / "install")], env=env, check=True)
            self.assertEqual("configure\n", (root / "calls").read_text())
            subprocess.run(["bash", "-c", 'source "$1"; post_upgrade 2.0-0 1.3.20260721-0', "bash", str(root / "install")], env=env, check=True)
            self.assertEqual("configure\n1.3.20260721-0\n", (root / "calls").read_text())

    @staticmethod
    def fixture(root):
        control = ("Package: fixture\nVersion: 1.3-20260721-0\nArchitecture: x86_64\n"
                   "Installed-Size: 1\nDescription: Package conversion fixture\n")
        with tarfile.open(root / "control.tar.xz", "w:xz") as archive:
            add_bytes(archive, "control", control.encode())
            add_bytes(archive, "postinst", b'#!/bin/sh\nprintf "%s\\n" "$@" > "$CALL_LOG"\n')
        data = b"#!/system/bin/sh\necho fixture\n"
        with tarfile.open(root / "data.tar.xz", "w:xz") as archive:
            member = tarfile.TarInfo("./data/data/com.veyterm/files/usr/bin/fixture")
            member.mode, member.size = 0o700, len(data)
            archive.addfile(member, io.BytesIO(data))
        (root / "debian-binary").write_text("2.0\n")
        path = root / "fixture.deb"
        subprocess.run(["ar", "rc", str(path), "debian-binary", "control.tar.xz", "data.tar.xz"], cwd=root, check=True)
        return read_package(path)


if __name__ == "__main__":
    unittest.main()
