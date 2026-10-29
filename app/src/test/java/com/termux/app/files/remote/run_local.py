#!/usr/bin/env python3
"""Focused local tests; dependencies supplied explicitly. Does not run Gradle or download anything."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--deps', type=Path, required=True, help='Directory of pinned JARs; optional python/ for fixture packages')
parser.add_argument('--android-jar', type=Path, help='Also compile Android UI/store/root against this SDK jar')
args = parser.parse_args()
here = Path(__file__).resolve().parent
repo = next(p for p in here.parents if (p / 'settings.gradle').exists())
source = repo / 'app/src/main/java/com/termux/app/files/remote'
deps = args.deps.resolve()
# Keep all temporary test artifacts in the explicitly owned test directory.
with tempfile.TemporaryDirectory(prefix='.remote-run-', dir=here) as working:
    run = Path(working)
    cp = str(deps / '*')
    classes = run / 'classes'
    all_java = list(source.glob('*.java')) if args.android_jar else [f for f in source.glob('*.java') if f.stem not in ('AndroidTransferRoot', 'ConnectionStore', 'RemoteConnectionsActivity')]
    subprocess.run(['javac', '-source', '8', '-target', '8', '-Xlint:-options', '-cp', cp + (os.pathsep + str(args.android_jar) if args.android_jar else ''), '-d', str(classes), *map(str, all_java)], check=True)
    subprocess.run(['javac', '-cp', cp + os.pathsep + str(classes), '-d', str(classes), str(here / 'RemoteRoundTripChecks.java')], check=True)
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(run / 'key.pem'), '-out', str(run / 'cert.pem'), '-days', '1', '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', 'fixture', '-file', str(run / 'cert.pem'), '-keystore', str(run / 'trust.p12'), '-storepass', 'fixture-store-only'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    env = os.environ.copy()
    env['PYTHONPATH'] = str(deps / 'python') + os.pathsep + env.get('PYTHONPATH', '')
    fixture = subprocess.Popen(['python3', str(here / 'fixtures/protocol_servers.py'), str(run)], env=env)
    try:
        for _ in range(100):
            if (run / 'ports.json').exists(): break
            if fixture.poll() is not None: raise RuntimeError('Protocol fixtures failed to start')
            time.sleep(.1)
        ports = json.loads((run / 'ports.json').read_text())
        runtime_cp = str(classes) + os.pathsep + cp
        main = 'com.termux.app.files.remote.RemoteRoundTripChecks'
        subprocess.run(['java', '-cp', runtime_cp, main, 'reject-ftps', str(ports['ftps'])], check=True, timeout=20)
        subprocess.run(['java', '-Djavax.net.ssl.trustStore=' + str(run / 'trust.p12'), '-Djavax.net.ssl.trustStorePassword=fixture-store-only', '-cp', runtime_cp, main, str(run), str(ports['ftp']), str(ports['sftp']), str(ports['smb']), ports['hostKey'], str(ports['ftps'])], check=True, timeout=90)
    finally:
        fixture.terminate()
        try: fixture.wait(timeout=5)
        except subprocess.TimeoutExpired: fixture.kill(); fixture.wait()
