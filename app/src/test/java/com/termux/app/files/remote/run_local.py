#!/usr/bin/env python3
"""Focused local tests; dependencies supplied explicitly. Does not run Gradle or download anything."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--deps', type=Path, required=True, help='Directory of pinned JARs; optional python/ for fixture packages')
parser.add_argument('--android-jar', type=Path, help='Also compile all production sources with this SDK bootclasspath')
parser.add_argument('--lambda-stubs', type=Path, help='SDK build-tools/core-lambda-stubs.jar; required with --android-jar')
args = parser.parse_args()
here = Path(__file__).resolve().parent
repo = next(p for p in here.parents if (p / 'settings.gradle').exists())
source = repo / 'app/src/main/java/com/termux/app/files/remote'
deps = args.deps.resolve()
pins = json.loads((here / 'dependency-pins.json').read_text())
for pin in pins:
    jar = deps / pin['file']
    if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() != pin['sha256']:
        parser.error('Missing or mismatched pinned artifact: ' + pin['coordinate'])
if args.android_jar and not args.lambda_stubs:
    parser.error('--android-jar requires --lambda-stubs from Android SDK build-tools')
# Explicit classpath excludes old/extra jars, even if they remain in --deps.
cp = os.pathsep.join(str(deps / pin['file']) for pin in pins)
work_parent = repo / 'build/network-refresh'
work_parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='roundtrip-', dir=work_parent) as working:
    run = Path(working)
    classes = run / 'classes'
    all_java = sorted(source.glob('*.java'))
    engine = [f for f in all_java if f.stem not in ('AndroidTransferRoot', 'ConnectionStore', 'RemoteConnectionsActivity')]
    subprocess.run(['javac', '--release', '8', '-cp', cp, '-d', str(classes), *map(str, engine)], check=True)
    print('PASS Java 8 API/bytecode compilation (engine)', flush=True)
    if args.android_jar:
        boot = os.pathsep.join(map(str, (args.android_jar.resolve(), args.lambda_stubs.resolve())))
        subprocess.run(['javac', '-source', '8', '-target', '8', '-Xlint:-options', '-bootclasspath', boot,
                        '-cp', cp, '-d', str(run / 'android-classes'), *map(str, all_java)], check=True)
        print('PASS Android SDK bootclasspath/Java 8 compilation (all production sources)', flush=True)
    subprocess.run(['javac', '-cp', cp + os.pathsep + str(classes), '-d', str(classes), str(here / 'RemoteRoundTripChecks.java')], check=True)
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(run / 'key.pem'), '-out', str(run / 'cert.pem'), '-days', '1', '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', 'fixture', '-file', str(run / 'cert.pem'), '-keystore', str(run / 'trust.p12'), '-storepass', 'fixture-store-only'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    env = os.environ.copy()
    env['PYTHONPATH'] = str(deps / 'python') + os.pathsep + env.get('PYTHONPATH', '')
    env['PYTHONDONTWRITEBYTECODE'] = '1'
    env['TMPDIR'] = str(run)
    fixture = subprocess.Popen([os.sys.executable, str(here / 'fixtures/protocol_servers.py'), str(run)], env=env)
    try:
        for _ in range(100):
            if (run / 'ports.json').exists(): break
            if fixture.poll() is not None: raise RuntimeError('Protocol fixtures failed to start')
            time.sleep(.1)
        ports = json.loads((run / 'ports.json').read_text())
        runtime_cp = str(classes) + os.pathsep + cp
        main = 'com.termux.app.files.remote.RemoteRoundTripChecks'
        subprocess.run(['java', '-cp', runtime_cp, main, 'reject-ftps', str(ports['ftps'])], check=True, timeout=20)
        for label, options in [('host-jdk', []), ('java8-base-jar', ['-Djdk.util.jar.enableMultiRelease=false'])]:
            workspace = run / label
            workspace.mkdir()
            print('Running real protocol suite: ' + label, flush=True)
            subprocess.run(['java', *options, '-Djavax.net.ssl.trustStore=' + str(run / 'trust.p12'),
                            '-Djavax.net.ssl.trustStorePassword=fixture-store-only', '-cp', runtime_cp, main,
                            str(workspace), str(ports['ftp']), str(ports['sftp']), str(ports['smb']),
                            ports['hostKey'], str(ports['ftps']), str(ports['sftpEd25519']), ports['ed25519HostKey']],
                           check=True, timeout=90)
    finally:
        fixture.terminate()
        try: fixture.wait(timeout=5)
        except subprocess.TimeoutExpired: fixture.kill(); fixture.wait()
