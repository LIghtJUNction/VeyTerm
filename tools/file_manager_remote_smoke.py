#!/usr/bin/env python3
"""Real loopback fixtures + explicit dedicated-AVD orchestration. Never selects a device."""
import argparse
import atexit
import base64
import contextlib
import ftplib
import hashlib
import http.client
import http.server
import json
import os
from pathlib import Path
import re
import runpy
import shlex
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET


class SmokeError(Exception):
    """Only fixed, credential-free diagnostics cross the orchestration boundary."""


def command(argv, *, timeout=20, data=None, env=None, cwd=None):
    try:
        result = subprocess.run(list(map(str, argv)), input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, env=env, cwd=cwd)
    except subprocess.TimeoutExpired:
        raise SmokeError('command-timeout') from None
    if result.returncode:
        raise SmokeError('command-failed:' + Path(str(argv[0])).name) from None
    return result.stdout.decode('utf-8', errors='replace')


def dav_server(root, username, password):
    """The existing Python module has no DAV server (its JVM suite embeds one)."""
    auth = 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode()
    ns = '{DAV:}'

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass

        def response(self, code, data=b'', content_type='application/octet-stream'):
            self.send_response(code)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Content-Type', content_type)
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True

        def path_in_root(self, value):
            parsed = urllib.parse.urlsplit(value)
            if parsed.scheme or parsed.netloc or parsed.query or parsed.fragment:
                raise ValueError()
            relative = urllib.parse.unquote(parsed.path, errors='strict').lstrip('/')
            target = root / relative
            target.resolve().relative_to(root)
            if any(p == '..' for p in Path(relative).parts) or target.is_symlink():
                raise ValueError()
            return target

        def body(self):
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                total = bytearray()
                while True:
                    size = int(self.rfile.readline(128).split(b';')[0], 16)
                    if size == 0:
                        while self.rfile.readline(8192) not in (b'\r\n', b'\n', b''):
                            pass
                        return bytes(total)
                    if size < 0 or len(total) + size > 1048576:
                        raise ValueError()
                    block = self.rfile.read(size)
                    if len(block) != size or self.rfile.read(2) != b'\r\n':
                        raise ValueError()
                    total.extend(block)
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 <= length <= 1048576:
                raise ValueError()
            data = self.rfile.read(length)
            if len(data) != length:
                raise ValueError()
            return data

        def operation(self):
            self.connection.settimeout(10)
            if self.headers.get('Authorization') != auth:
                self.response(401)
                return
            try:
                path = self.path_in_root(self.path)
                body = self.body()
                if self.command == 'PROPFIND':
                    if not path.exists():
                        self.response(404)
                        return
                    paths = [path]
                    if self.headers.get('Depth') == '1' and path.is_dir():
                        paths += sorted(path.iterdir())
                    xml = ET.Element(ns + 'multistatus')
                    for item in paths:
                        response = ET.SubElement(xml, ns + 'response')
                        relative = item.relative_to(root).as_posix()
                        href = '/' if relative == '.' else '/' + urllib.parse.quote(relative, safe='/')
                        ET.SubElement(response, ns + 'href').text = href
                        propstat = ET.SubElement(response, ns + 'propstat')
                        prop = ET.SubElement(propstat, ns + 'prop')
                        kind = ET.SubElement(prop, ns + 'resourcetype')
                        if item.is_dir():
                            ET.SubElement(kind, ns + 'collection')
                        ET.SubElement(prop, ns + 'getcontentlength').text = str(item.stat().st_size)
                        ET.SubElement(propstat, ns + 'status').text = 'HTTP/1.1 200 OK'
                    self.response(207, ET.tostring(xml, encoding='utf-8', xml_declaration=True), 'application/xml; charset=utf-8')
                elif self.command == 'MKCOL':
                    path.mkdir()
                    self.response(201)
                elif self.command == 'PUT':
                    with path.open('xb' if self.headers.get('If-None-Match') == '*' else 'wb') as output:
                        output.write(body)
                    self.response(201)
                elif self.command == 'GET':
                    self.response(200, path.read_bytes())
                elif self.command == 'MOVE':
                    destination = urllib.parse.urlsplit(self.headers['Destination'])
                    if destination.scheme != 'http' or destination.netloc != self.headers.get('Host'):
                        raise ValueError()
                    target = self.path_in_root(destination.path)
                    if target.exists():
                        self.response(412)
                        return
                    path.rename(target)
                    self.response(201)
                elif self.command == 'DELETE':
                    path.rmdir() if path.is_dir() else path.unlink()
                    self.response(204)
                else:
                    self.response(405)
            except FileExistsError:
                self.response(412)
            except FileNotFoundError:
                self.response(404)
            except (OSError, ValueError, KeyError):
                self.response(400)

        do_PROPFIND = do_MKCOL = do_PUT = do_GET = do_MOVE = do_DELETE = operation

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def fixture_child(source, run, task):
    """Adapt the real fixture at its pause boundary; credentials stay module-owned."""
    from importlib.metadata import version
    from pyftpdlib.handlers import FTPHandler
    import paramiko
    if version('pyftpdlib') != '2.1.0' or version('paramiko') != '3.5.1':
        raise SmokeError('fixture-python-pin-mismatch')
    # Reserve passive ports until all control listeners have selected different ports.
    reservations = []
    for _ in range(8):
        sock = socket.socket()
        sock.bind(('127.0.0.1', 0))
        reservations.append(sock)
    passive = [s.getsockname()[1] for s in reservations]
    FTPHandler.passive_ports = passive
    FTPHandler.masquerade_address = '127.0.0.1'
    pause = signal.pause
    popen = subprocess.Popen

    def owned_popen(*positional, **keywords):
        process = popen(*positional, **keywords)
        if keywords.get('start_new_session'):
            def stop_owned_group():
                # A live/unreaped child retains its PID; never signal a reaped PID's new owner.
                if process.poll() is None:
                    with contextlib.suppress(ProcessLookupError):
                        os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=3)
            atexit.register(stop_owned_group)
        return process

    def ready():
        fixture = sys._getframe(1).f_globals
        for sock in reservations:
            sock.close()
        ports = json.loads((run / 'ports.json').read_text())
        username, password = fixture['username'], fixture['password']
        dav_root = run / 'dav'
        dav_root.mkdir()
        dav = dav_server(dav_root, username, password)
        atexit.register(dav.server_close)
        # Negotiation proves both advertised SSH listeners work before any ADB access.
        for key in ('sftp', 'sftpEd25519'):
            with paramiko.Transport(('127.0.0.1', ports[key])) as transport:
                transport.connect(username=username, password=password)
                with paramiko.SFTPClient.from_transport(transport) as client:
                    client.listdir('/')
        context = ssl.create_default_context(cafile=str(run / 'cert.pem'))
        metadata = {}
        for key in ('ftp', 'ftps'):
            client = ftplib.FTP_TLS(context=context) if key == 'ftps' else ftplib.FTP()
            with client:
                client.connect('127.0.0.1', ports[key], timeout=5)
                client.login(username, password)
                if key == 'ftps':
                    client.prot_p()
                client.nlst()
                metadata[key] = ('CHMOD' in client.sendcmd('SITE HELP'), 'MFMT' in client.sendcmd('FEAT'))
        from impacket.smbconnection import SMBConnection
        smb_client = SMBConnection('127.0.0.1', '127.0.0.1', sess_port=ports['smb'], timeout=5)
        try:
            smb_client.login(fixture['smb_user'], password)
            smb_client.listPath('fixture', '*')
            smb_client.logoff()
        finally:
            smb_client.close()
        auth = 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode()

        def dav_request(method, path, expected, body=None, headers=None, chunked=False):
            client = http.client.HTTPConnection('127.0.0.1', dav.server_port, timeout=5)
            try:
                client.request(method, path, body=body, headers={'Authorization': auth, **(headers or {})}, encode_chunked=chunked)
                result = client.getresponse()
                data = result.read()
                if result.status != expected:
                    raise SmokeError('host-dav-' + method.lower() + '-failed')
                return data
            finally:
                client.close()

        path = '/host-' + task
        original = path + '/' + urllib.parse.quote('你好 space #%.bin')
        moved = path + '/moved'
        dav_request('MKCOL', path, 201)
        payload = bytes(range(256)) * 626
        dav_request('PUT', original, 201, [payload[:70000], payload[70000:]], {'If-None-Match': '*'}, True)
        if dav_request('GET', original, 200) != payload:
            raise SmokeError('host-dav-bytes-failed')
        ET.fromstring(dav_request('PROPFIND', path, 207, b'', {'Depth': '1'}))
        dav_request('MOVE', original, 201, headers={'Destination': 'http://127.0.0.1:' + str(dav.server_port) + moved, 'Overwrite': 'F'})
        dav_request('PUT', path + '/zero', 201, b'')
        if dav_request('GET', path + '/zero', 200) != b'':
            raise SmokeError('host-dav-zero-failed')
        dav_request('DELETE', path + '/zero', 204)
        dav_request('DELETE', moved, 204)
        dav_request('DELETE', path, 204)
        der = ssl.PEM_cert_to_DER_cert((run / 'cert.pem').read_text())
        connections = []
        for name, protocol, key in [('SFTP_RSA', 'SFTP', 'sftp'), ('SFTP_ED25519', 'SFTP', 'sftpEd25519'),
                                     ('FTP', 'FTP', 'ftp'), ('FTPS', 'FTPS', 'ftps'),
                                     ('SMB', 'SMB', 'smb'), ('WEBDAV_HTTP', 'WEBDAV', 'dav')]:
            chmod, mtime = metadata.get(key, (protocol == 'SFTP', protocol in ('SFTP', 'SMB')))
            connections.append(dict(name=name, protocol=protocol, port=dav.server_port if key == 'dav' else ports[key],
                                    root='/fixture' if protocol == 'SMB' else '/',
                                    username=fixture['smb_user'] if protocol == 'SMB' else username,
                                    password=password, domain='', hostKey=ports['hostKey'] if key == 'sftp' else ports['ed25519HostKey'] if key == 'sftpEd25519' else '',
                                    insecure=protocol in ('FTP', 'WEBDAV'), chmod=chmod, mtime=mtime))
        config = dict(task=task, connections=connections, tlsCertificate=base64.b64encode(der).decode(),
                      tlsSha256=base64.b64encode(hashlib.sha256(der).digest()).decode())
        (run / 'config.json').write_text(json.dumps(config), encoding='utf-8')
        (run / 'config.json').chmod(0o600)
        # Include the unused negative-metadata fixture listener: it still belongs to this process,
        # but do not expose/reverse it since no device check uses it.
        (run / 'ready.json').write_text(json.dumps({'ports': sorted({e['port'] for e in connections} | set(passive))}))
        pause()

    signal.pause = ready
    subprocess.Popen = owned_popen
    sys.argv = [str(source), str(run)]
    try:
        runpy.run_path(str(source), run_name='__main__')
    finally:
        signal.pause = pause
        subprocess.Popen = popen
        for sock in reservations:
            sock.close()


def validate_pins(source_repo, deps):
    pins = json.loads((source_repo / 'app/src/test/java/com/termux/app/files/remote/dependency-pins.json').read_text())
    jars = []
    for pin in pins:
        jar = deps / pin['file']
        if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() != pin['sha256']:
            raise SmokeError('missing-or-mismatched-jar:' + pin['coordinate'])
        jars.append(jar)
    return jars


def compile_sdk(args, run, jars):
    if not args.android_jar or not args.lambda_stubs:
        raise SmokeError('compile-requires-android-jar-and-lambda-stubs')
    production = args.source_repo / 'app/src/main/java/com/termux/app/files/remote'
    sources = sorted(production.glob('*.java'))
    fs = args.fs_source
    sources += [fs / (name + '.java') for name in ('FileAccess', 'FileLocation', 'FileEntry', 'RemoteFileAccess')]
    sources += [Path(__file__).resolve().parents[1] / 'app/src/androidTest/java/com/termux/app/files/RemoteWorkspaceSmoke.java']
    if not sources or any(not p.is_file() for p in sources):
        raise SmokeError('missing-production-or-parent-fs-source')
    command(['javac', '-J-Xmx256m', '-J-XX:+UseSerialGC', '-J-Djava.io.tmpdir=' + str(run),
             '-source', '8', '-target', '8', '-Xlint:-options', '-encoding', 'UTF-8',
             '-bootclasspath', os.pathsep.join(map(str, [args.android_jar, args.lambda_stubs])),
             '-cp', os.pathsep.join(map(str, jars)), '-d', run / 'classes', *sources], timeout=120)
    print('PASS Android SDK compilation: production remote + parent fs adapter + instrumentation', flush=True)


def run_host(args):
    # Task output has one UUID root, including subprocess temporary files and generated keys.
    task = uuid.uuid4().hex
    root = args.source_repo / 'build/verification'
    root.mkdir(parents=True, exist_ok=True)
    run = root / ('remote-smoke-' + task)
    run.mkdir(mode=0o700)
    fixture = None
    owned_reverses = []
    staged = False
    cache = None
    device_task = 'cache/remote-smoke-' + task
    staging = '/data/local/tmp/remote-smoke-' + task + '.json'
    prefix = 'rsm_' + task + '_'
    adb = [args.adb, '-s', args.serial]
    try:
        jars = validate_pins(args.source_repo, args.deps)
        if args.compile_only:
            compile_sdk(args, run, jars)
            return
        if not args.dry_run:
            # All ADB calls carry the explicit serial; no devices/current/default selection.
            if command([*adb, 'get-state']).strip() != 'device':
                raise SmokeError('dedicated-avd-not-online')
            avd_name = command([*adb, 'emu', 'avd', 'name']).splitlines()
            if not avd_name or avd_name[0].strip() != args.expected_avd:
                raise SmokeError('wrong-avd-name')
            if command([*adb, 'shell', 'getprop', 'ro.kernel.qemu']).strip() != '1':
                raise SmokeError('target-is-not-emulator')
            if command([*adb, 'shell', 'getprop', 'ro.build.version.sdk']).strip() != '33':
                raise SmokeError('wrong-avd-api')
            cache = command([*adb, 'shell', 'run-as', args.package, 'pwd']).strip()
            if not re.fullmatch(r'/data/(?:user/0|data)/' + re.escape(args.package), cache):
                raise SmokeError('unexpected-run-as-private-root')
        env = os.environ.copy()
        env.update(TMPDIR=str(run), TMP=str(run), TEMP=str(run), PYTHONDONTWRITEBYTECODE='1', PYTHONUNBUFFERED='1')
        env['PYTHONPATH'] = str(args.deps / 'python') + os.pathsep + env.get('PYTHONPATH', '')
        command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', run / 'key.pem',
                 '-out', run / 'cert.pem', '-days', '1', '-subj', '/CN=127.0.0.1',
                 '-addext', 'subjectAltName=IP:127.0.0.1'], env=env, cwd=run)
        source = args.source_repo / 'app/src/test/java/com/termux/app/files/remote/fixtures/protocol_servers.py'
        fixture = subprocess.Popen([str(args.python), str(Path(__file__).resolve()), '--fixture-child', str(source), str(run), task],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env, cwd=run, start_new_session=True)
        deadline = time.monotonic() + 35
        while not (run / 'ready.json').exists():
            if fixture.poll() is not None:
                diagnostic = run / 'child-error.json'
                kind = json.loads(diagnostic.read_text())['type'] if diagnostic.exists() else 'unknown'
                if not re.fullmatch(r'[A-Za-z0-9_:.-]+', kind):
                    kind = 'unknown'
                raise SmokeError('fixture-startup-or-negotiation-failed:' + kind)
            if time.monotonic() > deadline:
                raise SmokeError('fixture-startup-timeout')
            time.sleep(.1)
        ports = json.loads((run / 'ready.json').read_text())['ports']
        config = json.loads((run / 'config.json').read_text())
        print('PASS real fixture startup: SFTP_RSA SFTP_ED25519 FTP FTPS SMB WEBDAV_HTTP', flush=True)
        if args.dry_run:
            print('DRY-RUN: no ADB calls; 6 private connection records; %d owned loopback reverses planned' % len(ports), flush=True)
            print('DRY-RUN: config_path=<target-private-cache>/remote-smoke-<UUID>/config.json timeout_ms=' + str(args.timeout_ms), flush=True)
            return
        existing = command([*adb, 'reverse', '--list'])
        existing_ports = {int(p) for p in re.findall(r'\btcp:(\d+)\b', existing)}
        if existing_ports.intersection(ports):
            raise SmokeError('fixture-port-collides-with-existing-reverse')
        for port in ports:
            command([*adb, 'reverse', '--no-rebind', 'tcp:' + str(port), 'tcp:' + str(port)])
            owned_reverses.append(port)
        # Staging is shell-owned 0600; shell reads it into a run-as pipe, never chmods it world-readable.
        staged = True
        command([*adb, 'push', run / 'config.json', staging])
        command([*adb, 'shell', 'chmod', '600', staging])
        command([*adb, 'shell', 'run-as', args.package, 'mkdir', '-p', device_task])
        script = 'cat ' + shlex.quote(staging) + ' | run-as ' + shlex.quote(args.package) + ' sh -c ' + shlex.quote('umask 077; cat > ' + device_task + '/config.json')
        command([*adb, 'shell', script])
        command([*adb, 'shell', 'rm', '-f', staging])
        config_path = cache + '/' + device_task + '/config.json'
        output = command([*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'config_path', config_path,
                          '-e', 'timeout_ms', str(args.timeout_ms), args.instrumentation], timeout=args.timeout_ms / 1000 + 30)
        # Never echo raw am/ADB output: third-party exceptions could include credentials.
        lines = []
        for line in output.splitlines():
            if re.fullmatch(r'INSTRUMENTATION_(?:STATUS|RESULT): (?:name|assertions|error|errors|shortMsg)=[A-Za-z0-9_./:@,\[\] -]*', line) or re.fullmatch(r'INSTRUMENTATION_CODE: -?[0-9]+', line):
                lines.append(line)
        print('\n'.join(lines), flush=True)
        if not re.search(r'^INSTRUMENTATION_CODE: -1\s*$', output, re.M) or 'INSTRUMENTATION_RESULT: errors=[]' not in output:
            raise SmokeError('instrumentation-failed-or-missing-final-result')
        names = re.findall(r'^INSTRUMENTATION_STATUS: name=(\S+)\s*$', output, re.M)
        if names != [e['name'] for e in config['connections']] or output.count('INSTRUMENTATION_STATUS: error=PASS') != 6:
            raise SmokeError('instrumentation-missing-protocol-result')
        print('PASS Android runtime remote workspace smoke', flush=True)
    finally:
        cleanup_errors = []
        if cache is not None:
            # Bound a hung worker before deleting only this UUID's records/config. No pm clear/key removal.
            cleanup = [[*adb, 'shell', 'am', 'force-stop', args.package],
                       [*adb, 'shell', 'run-as', args.package, 'rm', '-rf', device_task]]
            ids = [prefix + str(i) + suffix for i in range(6) for suffix in ('', '_aad')]
            files = ['no_backup/remote-connections/' + i + ext for i in ids for ext in ('.gcm', '.gcm.bak', '.gcm.new')]
            cleanup.append([*adb, 'shell', 'run-as', args.package, 'rm', '-f', *files])
            for cmd in cleanup:
                try: command(cmd)
                except SmokeError: cleanup_errors.append('private-files-or-worker')
        if staged:
            try: command([*adb, 'shell', 'rm', '-f', staging])
            except SmokeError: cleanup_errors.append('staging-file')
        for port in reversed(owned_reverses):
            try: command([*adb, 'reverse', '--remove', 'tcp:' + str(port)])
            except SmokeError: cleanup_errors.append('owned-reverse')
        if fixture is not None:
            if fixture.poll() is None:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(fixture.pid, signal.SIGTERM)
                try: fixture.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    os.killpg(fixture.pid, signal.SIGKILL)
                    fixture.wait(timeout=3)
        shutil.rmtree(run)
        if cleanup_errors:
            raise SmokeError('cleanup-failed:' + ','.join(sorted(set(cleanup_errors))))


def main():
    if len(sys.argv) == 5 and sys.argv[1] == '--fixture-child':
        os.umask(0o077)
        source, run, task = Path(sys.argv[2]), Path(sys.argv[3]), sys.argv[4]
        try:
            fixture_child(source, run, task)
        except Exception as error:
            trace = error.__traceback__
            while trace.tb_next:
                trace = trace.tb_next
            symbol = re.sub(r'[^A-Za-z0-9_]', '_', trace.tb_frame.f_code.co_name)
            (run / 'child-error.json').write_text(json.dumps({'type': type(error).__name__ + ':' + symbol + ':' + str(trace.tb_lineno)}))
            raise
        return
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit dedicated AVD serial; never a phone')
    parser.add_argument('--expected-avd', default='VeyTerm_Files_API33_20261030', help='Exact AVD name to verify before mutation (default: fresh task AVD)')
    parser.add_argument('--source-repo', type=Path, default=repo, help='Parent checkout with current fixtures and production source (read-only)')
    parser.add_argument('--fs-source', type=Path, help='Absolute parent untracked files/fs directory for --compile-only')
    parser.add_argument('--deps', type=Path, required=True, help='Existing SHA-256 pinned JAR directory; optional python/ fixture packages')
    parser.add_argument('--python', type=Path, default=Path(sys.executable), help='Existing fixture venv interpreter; no installs/downloads')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--package', default='com.veyterm')
    parser.add_argument('--instrumentation', default='com.veyterm.test/com.termux.app.files.RemoteWorkspaceSmoke')
    parser.add_argument('--timeout-ms', type=int, default=240000)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--dry-run', action='store_true', help='Start/validate real host fixtures, prepare config, clean; zero ADB calls')
    mode.add_argument('--compile-only', action='store_true', help='SDK javac only, -Xmx256m; zero fixtures/ADB/Gradle')
    parser.add_argument('--android-jar', type=Path)
    parser.add_argument('--lambda-stubs', type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.:-]+', args.serial) or not re.fullmatch(r'[A-Za-z0-9_.]+', args.package):
        parser.error('invalid serial or package')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', args.expected_avd):
        parser.error('invalid expected AVD name')
    if not re.fullmatch(r'[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+', args.instrumentation):
        parser.error('invalid instrumentation component')
    if not 30000 <= args.timeout_ms <= 600000:
        parser.error('timeout-ms must be 30000..600000')
    for name in ('source_repo', 'deps', 'python', 'fs_source', 'android_jar', 'lambda_stubs'):
        value = getattr(args, name)
        if value is not None:
            setattr(args, name, value.absolute())
    if args.fs_source is None:
        args.fs_source = args.source_repo / 'app/src/main/java/com/termux/app/files/fs'
    os.umask(0o077)
    # SIGINT/SIGTERM run the same finally cleanup as ordinary failures.
    def interrupted(signum, frame):
        raise SmokeError('interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    run_host(args)


if __name__ == '__main__':
    try:
        main()
    except (SmokeError, OSError, ValueError, KeyError) as error:
        # Only our fixed errors are printable. Never print dependency exception text.
        print('FAIL ' + (str(error) if isinstance(error, SmokeError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)
