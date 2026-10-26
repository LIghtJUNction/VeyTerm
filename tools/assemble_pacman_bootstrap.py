#!/usr/bin/env python3
"""Assemble a signed pacman bootstrap from packages compiled for com.veyterm.

Locally built Debian payloads can be reused without recompiling their binaries.
Only package metadata and maintainer-script calling conventions are converted;
payload paths and executable bytes are never relocated or rewritten.
"""

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

from verify_bootstrap import OLD_PREFIX, safe_relative, verify


PREFIX = "data/data/com.veyterm/files/usr"
DEFAULT_PACKAGES = (
    "pacman,zsh,paru,sudo,git,fakeroot,proot,proot-distro,autoconf,automake,"
    "bison,flex,make,file,gettext,pkg-config,binutils,patch,nano,ed,dos2unix,inetutils,"
    "lsof,net-tools,unzip,termux-tools,termux-keyring,bash,tar,bsdtar"
)


def run(*args, **kwargs):
    return subprocess.check_output(args, **kwargs)


def fields(text):
    result = {}
    key = None
    for line in text.splitlines():
        if line.startswith(" ") and key:
            result[key] += "\n" + line[1:]
        elif ": " in line:
            key, value = line.split(": ", 1)
            result[key] = value
    return result


def pacman_fields(text):
    result = {}
    for line in text.splitlines():
        if " = " in line:
            key, value = line.split(" = ", 1)
            result.setdefault(key, []).append(value)
    return result


def pacman_version(debian_version):
    upstream, separator, revision = debian_version.rpartition("-")
    if not separator:
        upstream, revision = debian_version, "0"
    return upstream.replace("-", ".") + "-" + revision


def dependency(value, from_debian=False):
    value = re.sub(r"\s*\(([^)]+)\)", lambda m: m[1].replace(" ", ""), value.strip())
    value = value.replace(":any", "").replace("<<", "<").replace(">>", ">")
    if from_debian:
        match = re.fullmatch(r"([^<>=]+)([<>=]+)(.+)", value)
        if match:
            value = match[1] + match[2] + pacman_version(match[3])
    return value


@dataclass
class Package:
    path: Path
    info: dict
    control: dict

    @property
    def name(self):
        return self.info["pkgname"][0]

    @property
    def version(self):
        return self.info["pkgver"][0]


def read_package(path):
    control = {}
    if path.suffix == ".deb":
        with tarfile.open(fileobj=io.BytesIO(run("ar", "p", str(path), "control.tar.xz"))) as archive:
            control = {Path(m.name).name: archive.extractfile(m).read() for m in archive if m.isfile()}
        manager_changed = False
        for name in ("preinst", "postinst", "prerm", "postrm"):
            if name not in control:
                continue
            # These compile-time guards refer to the installed package format.
            # Leaving Debian selected makes Python delete pacman-owned pip.
            converted = re.sub(rb'"debian"(\s*=\s*"(?:debian|pacman)")',
                               rb'"pacman"\1', control[name])
            manager_changed |= converted != control[name]
            control[name] = converted
        meta = fields(control["control"].decode())
        version = pacman_version(meta["Version"])
        if manager_changed:
            version += ".1"
        info = {key: [value] for key, value in {
            "pkgname": meta["Package"], "pkgver": version,
            "pkgdesc": meta["Description"].splitlines()[0],
            "arch": "any" if meta["Architecture"] == "all" else meta["Architecture"],
            "url": meta.get("Homepage", ""), "packager": meta.get("Maintainer", "VeyTerm"),
            "size": str(int(meta["Installed-Size"]) * 1024),
        }.items()}
        for source, target in (("Depends", "depend"), ("Pre-Depends", "depend"),
                               ("Provides", "provides"), ("Conflicts", "conflict"),
                               ("Replaces", "replaces")):
            for value in meta.get(source, "").split(","):
                if value.strip():
                    info.setdefault(target, []).append(dependency(value, from_debian=True))
        info["backup"] = [x.lstrip("/") for x in control.get("conffiles", b"").decode().splitlines()]
    else:
        with tarfile.open(path) as archive:
            info = pacman_fields(archive.extractfile(".PKGINFO").read().decode())
    if not re.fullmatch(r"[^\s-]+-[0-9]+(?:\.[0-9]+)?", info["pkgver"][0]):
        raise ValueError(f"Invalid pacman version in {path.name}: {info['pkgver'][0]}")
    return Package(path, info, control)


def index_packages(directory, arch):
    index = {}
    for path in sorted(directory.iterdir()):
        if not (path.name.endswith(f"_{arch}.deb") or path.name.endswith("_all.deb") or
                path.name.endswith(f"-{arch}.pkg.tar.xz") or path.name.endswith("-any.pkg.tar.xz")):
            continue
        package = read_package(path)
        if package.info["arch"][0] not in (arch, "any"):
            continue
        previous = index.get(package.name)
        comparison = int(run("vercmp", package.version, previous.version)) if previous else 1
        if comparison > 0 or (comparison == 0 and path.suffix != ".deb"):
            index[package.name] = package
    return index


def closure(index, seeds):
    selected = {}
    pending = list(seeds)
    while pending:
        name = pending.pop(0)
        if name in selected:
            continue
        if name not in index:
            raise ValueError(f"Missing locally compiled package: {name}")
        package = selected[name] = index[name]
        dependencies = []
        for expression in package.info.get("depend", []):
            candidates = [dependency(x) for x in expression.split("|")]
            chosen = None
            for candidate in candidates:
                match = re.fullmatch(r"([^<>=]+)([<>=]+)?(.*)", candidate)
                if not match or match[1] not in index:
                    continue
                if match[2]:
                    comparison = int(run("vercmp", index[match[1]].version, match[3]))
                    if not {"=": comparison == 0, ">": comparison > 0, "<": comparison < 0,
                            ">=": comparison >= 0, "<=": comparison <= 0}[match[2]]:
                        continue
                chosen = candidate
                break
            if chosen is None:
                raise ValueError(f"Missing or incompatible dependency of {name}: {expression}")
            dependencies.append(chosen)
            pending.append(re.split(r"[<>=]", chosen)[0])
        package.info["depend"] = dependencies
    return selected


def inspect_member(member, data):
    name = member.name.removeprefix("./")
    if not safe_relative(name):
        raise ValueError(f"Unsafe package path: {name}")
    if name not in (".", "data", "data/data", "data/data/com.veyterm", "data/data/com.veyterm/files"):
        if name != PREFIX and not name.startswith(PREFIX + "/"):
            raise ValueError(f"Package payload outside VeyTerm: {name}")
    if OLD_PREFIX in data or OLD_PREFIX.decode() in member.linkname:
        raise ValueError(f"Package still contains upstream runtime paths: {name}")


def write_package(package, destination):
    if package.path.suffix != ".deb":
        shutil.copyfile(package.path, destination)
        return
    with tarfile.open(destination, "w:xz", preset=1) as output:
        text = "".join(f"{key} = {value}\n" for key, values in package.info.items() for value in values)
        add_bytes(output, ".PKGINFO", text.encode())
        hooks = ""
        for script, function, arguments in (("preinst", "pre_install", "install"),
                                            ("preinst", "pre_upgrade", 'upgrade "$2"'),
                                            ("postinst", "post_install", "configure"),
                                            ("postinst", "post_upgrade", 'configure "$2"'),
                                            ("prerm", "pre_remove", "remove"),
                                            ("postrm", "post_remove", "remove")):
            if script in package.control:
                body = package.control[script].decode()
                hooks += f'{function}() {{ (\nset -- {arguments}\n{body}\n); }}\n'
        if hooks:
            add_bytes(output, ".INSTALL", hooks.encode())
        with tarfile.open(fileobj=io.BytesIO(run("ar", "p", str(package.path), "data.tar.xz"))) as payload:
            for member in payload:
                data = payload.extractfile(member).read() if member.isfile() else b""
                inspect_member(member, data)
                member.name = member.name.removeprefix("./")
                if member.name in ("", "."):
                    continue
                if member.islnk():
                    member.linkname = member.linkname.removeprefix("./")
                output.addfile(member, io.BytesIO(data) if member.isfile() else None)


def add_bytes(archive, name, data):
    member = tarfile.TarInfo(name)
    member.size = len(data)
    member.mode = 0o644
    archive.addfile(member, io.BytesIO(data))


def install_package(package, archive_path, root):
    files = []
    hook = None
    with tarfile.open(archive_path) as archive:
        for member in archive:
            if member.name == ".INSTALL":
                hook = archive.extractfile(member).read()
                continue
            if member.name.startswith(".") and not member.name.startswith("./data"):
                continue
            data = archive.extractfile(member).read() if member.isfile() else b""
            inspect_member(member, data)
            name = member.name.removeprefix("./")
            if name == PREFIX or name.startswith(PREFIX + "/"):
                files.append(name + ("/" if member.isdir() else ""))
                destination = root / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                if not destination.parent.resolve().is_relative_to(root):
                    raise ValueError(f"Package path follows a symlink outside staging: {name}")
                if member.isdir():
                    destination.mkdir(exist_ok=True)
                else:
                    if destination.is_symlink() or destination.exists():
                        destination.unlink()
                    if member.issym():
                        destination.symlink_to(member.linkname)
                    elif member.islnk():
                        target = root / member.linkname.removeprefix("./")
                        if not target.resolve().is_relative_to(root):
                            raise ValueError(f"Invalid package hardlink: {name}")
                        os.link(target, destination)
                    elif member.isfile():
                        destination.write_bytes(data)
                        destination.chmod(member.mode & 0o777)
                    else:
                        raise ValueError(f"Unsupported package entry: {name}")
    database = root / PREFIX / "var/lib/pacman/local" / f"{package.name}-{package.version}"
    database.mkdir(parents=True, exist_ok=True)
    desc = {"NAME": [package.name], "VERSION": [package.version],
            "DESC": package.info.get("pkgdesc", []), "ARCH": package.info["arch"],
            "DEPENDS": package.info.get("depend", []), "PROVIDES": package.info.get("provides", []),
            "GROUPS": package.info.get("group", []), "LICENSE": package.info.get("license", []),
            "CONFLICTS": package.info.get("conflict", []), "REPLACES": package.info.get("replaces", []),
            "SIZE": package.info.get("size", ["0"]), "REASON": ["0"], "VALIDATION": ["pgp"]}
    (database / "desc").write_text("".join(f"%{key}%\n" + "\n".join(values) + "\n\n" for key, values in desc.items() if values))
    backups = []
    for name in package.info.get("backup", []):
        path = root / name
        if path.is_file():
            backups.append(name + "\t" + hashlib.md5(path.read_bytes(), usedforsecurity=False).hexdigest())
    backup_section = "\n%BACKUP%\n" + "\n".join(backups) + "\n" if backups else ""
    (database / "files").write_text("%FILES%\n" + "\n".join(sorted(files)) + "\n" + backup_section)
    if hook:
        (database / "install").write_bytes(hook)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("packages_directory", type=Path)
    parser.add_argument("--arch", choices=("aarch64", "arm", "i686", "x86_64"), required=True)
    parser.add_argument("--packages", default=DEFAULT_PACKAGES)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--fallback-repository-url", action="append", default=[],
                        help="Additional signed-package mirrors, for reusing unchanged release assets")
    parser.add_argument("--signing-key", required=True)
    parser.add_argument("--packages-source", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    packages = closure(index_packages(args.packages_directory, args.arch), args.packages.split(","))
    repository = args.output / args.arch
    repository_name = f"veyterm-{args.arch}"
    repository.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="veyterm-bootstrap-") as temporary:
        root = Path(temporary)
        built_archives = []
        for package in packages.values():
            # Release assets share one flat directory. Even architecture-neutral
            # source packages can have different build metadata in each snapshot.
            filename = f"{args.arch}-{package.name}-{package.version}-{package.info['arch'][0]}.pkg.tar.xz"
            archive = repository / re.sub(r"[^A-Za-z0-9_.-]", "_", filename)
            if archive in built_archives:
                raise ValueError(f"Release asset name collision: {archive.name}")
            write_package(package, archive)
            run("gpg", "--batch", "--yes", "--local-user", args.signing_key, "--detach-sign", str(archive))
            install_package(package, archive, root)
            built_archives.append(archive)
        prefix = root / PREFIX
        # termux-keyring's first-install hook creates the GnuPG directory and
        # generates its local trust key. An empty precreated directory skips it.
        for name in ("tmp", "var/lib/pacman/sync", "var/cache/pacman/pkg", "var/log"):
            (prefix / name).mkdir(parents=True, exist_ok=True)
        (prefix / "var/lib/pacman/local/ALPM_DB_VERSION").write_text("9\n")
        servers = "".join(f"Server = {url.replace('{arch}', args.arch)}\n"
                          for url in [args.repository_url, *args.fallback_repository_url])
        (prefix / "etc/pacman.conf").write_text(
            f"[options]\nArchitecture = {args.arch}\nColor\nSigLevel = Required DatabaseRequired\n"
            f"LocalFileSigLevel = Optional\n\n[{repository_name}]\n{servers}")
        keyring = prefix / "share/pacman/keyrings"
        keyring.mkdir(parents=True, exist_ok=True)
        (keyring / "veyterm.gpg").write_bytes(run("gpg", "--batch", "--export", args.signing_key))
        (keyring / "veyterm-trusted").write_text(f"{args.signing_key}:4:\n")
        database_directory = root / "repository-database"
        database_directory.mkdir()
        subprocess.run(["repo-add", "--sign", "--key", args.signing_key, str(database_directory / f"{repository_name}.db.tar.gz"),
                        *map(str, sorted(built_archives))], check=True, stdout=subprocess.DEVNULL)
        for path in database_directory.iterdir():
            target = repository / path.name
            if target.is_symlink():
                target.unlink()
            shutil.copyfile(path, target)
        for suffix in ("db", "db.sig"):
            name = f"{repository_name}.{suffix}"
            shutil.copyfile(repository / name, prefix / f"var/lib/pacman/sync/{name}")
        source = args.packages_source / "scripts/bootstrap"
        properties = run("bash", "-c", 'source "$1/scripts/properties.sh"; printf "%s\\n%s\\n%s\\n" "$TERMUX_BOOTSTRAP__BOOTSTRAP_SECOND_STAGE_DIR" "$TERMUX_BOOTSTRAP__BOOTSTRAP_SECOND_STAGE_ENTRY_POINT_SUBFILE" "$TERMUX_ENV__S_TERMUX"', "bash", str(args.packages_source)).decode().splitlines()
        stage_directory, entrypoint, environment_scope = properties[-3:]
        replacements = {
            "TERMUX_PREFIX": "/" + PREFIX, "TERMUX_PACKAGE_MANAGER": "pacman",
            "TERMUX_PACKAGE_ARCH": args.arch, "TERMUX_APP__NAME": "VeyTerm", "TERMUX_ENV__S_TERMUX": environment_scope,
            "TERMUX__PREFIX__PROFILE_D_DIR": "/" + PREFIX + "/etc/profile.d",
            "TERMUX_BOOTSTRAP__BOOTSTRAP_SECOND_STAGE_DIR": stage_directory,
            "TERMUX_BOOTSTRAP__BOOTSTRAP_SECOND_STAGE_ENTRY_POINT_SUBFILE": entrypoint,
        }
        for filename, target in ((entrypoint, root / stage_directory.lstrip("/") / entrypoint),
                                 ("01-termux-bootstrap-second-stage-fallback.sh", prefix / "etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh")):
            text = (source / filename).read_text()
            for key, value in replacements.items():
                text = text.replace(f"@{key}@", value)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
            target.chmod(0o700)
        archive_path = args.output / f"bootstrap-{args.arch}.zip"
        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            links = []
            for path in sorted(prefix.rglob("*")):
                relative = str(path.relative_to(prefix))
                if path.is_symlink():
                    links.append(f"{path.readlink()}←./{relative}")
                elif path.is_file() or path.is_dir():
                    archive.write(path, relative)
            archive.writestr("SYMLINKS.txt", "\n".join(links) + "\n")
        if not verify(archive_path):
            raise ValueError("Bootstrap failed runtime-prefix validation")
        provenance = {}
        for package, archive in zip(packages.values(), built_archives, strict=True):
            filename = archive.name
            provenance[package.name] = {
                "source": package.path.name, "sha256": hashlib.sha256(package.path.read_bytes()).hexdigest(),
                "package": filename, "package_sha256": hashlib.sha256((repository / filename).read_bytes()).hexdigest(),
            }
        (args.output / f"bootstrap-{args.arch}.packages.json").write_text(json.dumps(provenance, indent=2) + "\n")


if __name__ == "__main__":
    main()
