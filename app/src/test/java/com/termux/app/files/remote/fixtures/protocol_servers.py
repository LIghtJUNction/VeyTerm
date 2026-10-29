#!/usr/bin/env python3
"""Loopback-only protocol fixtures. Needs pyftpdlib, paramiko, impacket (no system changes)."""
import base64
import hashlib
import io
import getpass
import subprocess
import json
import logging
import os
from pathlib import Path
import signal
import socket
import sys
import threading
import time

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler, TLS_FTPHandler
from pyftpdlib.servers import FTPServer
import paramiko
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from impacket.ntlm import compute_lmhash, compute_nthash

logging.disable(logging.CRITICAL)
workspace = Path(sys.argv[1]).resolve()
workspace.mkdir(parents=True, exist_ok=True)
root = workspace / 'root'
root.mkdir(exist_ok=True)

def available_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

# These credentials are deliberately public, local fixture-only values.
username, password = 'network-fixture', 'local-fixture-only'
auth = DummyAuthorizer()
auth.add_user(username, password, str(root), perm='elradfmwMT')
class Ftp(FTPHandler):
    pass
Ftp.authorizer = auth
ftp = FTPServer(('127.0.0.1', 0), Ftp)
ftp_port = ftp.socket.getsockname()[1]
# Both FTP listeners share one I/O loop; pyftpdlib is not thread-safe across loops.
class Ftps(TLS_FTPHandler):
    pass
Ftps.authorizer = auth
Ftps.certfile = str(workspace / 'cert.pem')
Ftps.keyfile = str(workspace / 'key.pem')
Ftps.tls_control_required = True
Ftps.tls_data_required = True
ftps = FTPServer(('127.0.0.1', 0), Ftps)
ftps_port = ftps.socket.getsockname()[1]
class NoMetadata(Ftp):
    def ftp_MFMT(self, path, timeval): self.respond('502 MFMT unavailable')
    def ftp_SITE_CHMOD(self, path, mode): self.respond('502 SITE CHMOD unavailable')
no_metadata = FTPServer(('127.0.0.1', 0), NoMetadata)
no_metadata_port = no_metadata.socket.getsockname()[1]
threading.Thread(target=ftp.serve_forever, kwargs={'timeout': .1}, daemon=True).start()

key = paramiko.RSAKey.generate(2048)
class SftpAuth(paramiko.ServerInterface):
    def check_auth_password(self, u, p):
        return paramiko.AUTH_SUCCESSFUL if (u, p) == (username, password) else paramiko.AUTH_FAILED
    def get_allowed_auths(self, u): return 'password'
    def check_channel_request(self, kind, channel):
        return paramiko.OPEN_SUCCEEDED if kind == 'session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
class Sftp(paramiko.SFTPServerInterface):
    def local(self, path, follow=True):
        raw = root / path.lstrip('/')
        target = raw.resolve() if follow or raw == root else raw.parent.resolve() / raw.name
        target.relative_to(root)
        return target
    def list_folder(self, path):
        try:
            result = []
            for f in self.local(path).iterdir():
                a = paramiko.SFTPAttributes.from_stat(f.lstat()); a.filename = f.name; result.append(a)
            return result
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def stat(self, path):
        try: return paramiko.SFTPAttributes.from_stat(self.local(path).stat())
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def lstat(self, path):
        try: return paramiko.SFTPAttributes.from_stat(self.local(path, follow=False).lstat())
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def readlink(self, path):
        try: return os.readlink(self.local(path, follow=False))
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def symlink(self, target_path, path):
        try: os.symlink(target_path, self.local(path, follow=False)); return paramiko.SFTP_OK
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def chattr(self, path, attr):
        try: paramiko.SFTPServer.set_file_attr(self.local(path), attr); return paramiko.SFTP_OK
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def open(self, path, flags, attr):
        try:
            fd = os.open(self.local(path), flags, 0o600)
            f = os.fdopen(fd, 'rb' if flags & os.O_WRONLY == 0 else 'wb')
            handle = paramiko.SFTPHandle(flags)
            if flags & os.O_WRONLY: handle.writefile = f
            else: handle.readfile = f
            return handle
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def mkdir(self, path, attr):
        try: self.local(path, follow=False).mkdir(); return paramiko.SFTP_OK
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def rmdir(self, path):
        try: self.local(path, follow=False).rmdir(); return paramiko.SFTP_OK
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def remove(self, path):
        try: self.local(path, follow=False).unlink(); return paramiko.SFTP_OK
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def rename(self, source, dest):
        try: self.local(source, follow=False).rename(self.local(dest, follow=False)); return paramiko.SFTP_OK
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
listener = socket.socket()
listener.bind(('127.0.0.1', 0)); listener.listen(5)
sftp_port = listener.getsockname()[1]
ed_key = paramiko.Ed25519Key(file_obj=io.StringIO(Ed25519PrivateKey.generate().private_bytes(
    serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH, serialization.NoEncryption()).decode()))
ed_listener = socket.socket()
ed_listener.bind(('127.0.0.1', 0)); ed_listener.listen(5)
sftp_ed_port = ed_listener.getsockname()[1]
transports = []
def sftp_loop(accepting, host_key, modern_only=False):
    while True:
        client, _ = accepting.accept()
        def serve(sock):
            transport = paramiko.Transport(sock); transports.append(transport)
            transport.add_server_key(host_key)
            if modern_only:
                options = transport.get_security_options()
                options.kex = ('curve25519-sha256@libssh.org',)
                options.key_types = ('ssh-ed25519',)
                options.ciphers = ('aes128-ctr',)
                options.digests = ('hmac-sha2-256',)
            transport.set_subsystem_handler('sftp', paramiko.SFTPServer, Sftp)
            try:
                transport.start_server(server=SftpAuth())
                while transport.is_active(): time.sleep(.1)
            except (paramiko.SSHException, EOFError): pass
            finally: transport.close()
        threading.Thread(target=serve, args=(client,), daemon=True).start()
threading.Thread(target=sftp_loop, args=(listener, key), daemon=True).start()
threading.Thread(target=sftp_loop, args=(ed_listener, ed_key, True), daemon=True).start()

# Real Samba enforces signing; impacket's server is not used as acceptance evidence.
smb_port = available_port()
samba_dir = workspace / 'samba'
samba_dir.mkdir()
for name in ('private', 'state', 'lock', 'cache', 'pid', 'rpc'):
    (samba_dir / name).mkdir()
smb_user = getpass.getuser()
(samba_dir / 'smbpasswd').write_text(f'{smb_user}:{os.getuid()}:{compute_lmhash(password).hex().upper()}:{compute_nthash(password).hex().upper()}:[U          ]:LCT-{int(time.time()):08X}:\n')
(samba_dir / 'smbpasswd').chmod(0o600)
(samba_dir / 'smb.conf').write_text(f"""[global]
workgroup = WORKGROUP
server role = standalone server
interfaces = 127.0.0.1
bind interfaces only = yes
smb ports = {smb_port}
server min protocol = SMB2
server max protocol = SMB3
server signing = mandatory
passdb backend = smbpasswd:./smbpasswd
private dir = ./private
state directory = ./state
lock directory = ./lock
cache directory = ./cache
pid directory = ./pid
ncalrpc dir = ./rpc
log file = ./server.log
load printers = no
printing = bsd
printcap name = /dev/null
map to guest = Never
[fixture]
path = {root}
read only = no
valid users = {smb_user}
""")
smb_process = subprocess.Popen(['smbd', '-F', '--no-process-group', '--configfile=' + str(samba_dir / 'smb.conf'), '--debug-stdout', '--debuglevel=0'], cwd=samba_dir, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
def stop(signum, frame):
    smb_process.terminate()
    try: smb_process.wait(timeout=3)
    except subprocess.TimeoutExpired: smb_process.kill(); smb_process.wait()
    sys.exit(0)
signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
for _ in range(50):
    try:
        with socket.create_connection(('127.0.0.1', smb_port), timeout=.1): break
    except OSError:
        if smb_process.poll() is not None: raise RuntimeError('Local Samba fixture failed to start')
        time.sleep(.05)
(workspace / 'ports.json').write_text(json.dumps({'ftp': ftp_port, 'ftpNoMetadata': no_metadata_port, 'ftps': ftps_port, 'sftp': sftp_port, 'smb': smb_port, 'sftpEd25519': sftp_ed_port,
    'ed25519HostKey': 'SHA256:' + base64.b64encode(hashlib.sha256(ed_key.asbytes()).digest()).decode().rstrip('='),
    'hostKey': 'SHA256:' + base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')}))
print('Local FTP/FTPS/SFTP/Samba fixtures ready', flush=True)
signal.pause()
