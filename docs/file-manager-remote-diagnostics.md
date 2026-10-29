# Safe remote runtime diagnostics

`RemoteFiles.safeDiagnostic(Throwable)` exposes exception classes and up to four
class/method/line frames per cause (eight causes, 3500 characters maximum).
It never reads messages, source filenames, URLs, server replies, or credentials.
Code symbols outside the accepted alphabet are redacted; nested-class and
constructor symbols are normalized for the existing host instrumentation filter.
Cycles stop by identity. Suppressed exceptions are not traversed.

Production failures keep only this diagnostic string and a generic UI message;
the original third-party throwable is not attached or retained. Connect,
operations, unsupported operations, and stream read/write/flush/completion use
this boundary. Cancellation still takes precedence. The runner reports the
safe chain and finer engine stages without changing the host report contract.

API33 r2 evidence: encrypted-store checks contribute 11 assertions per endpoint.
FTPS adds two trust-rejection checks, root stat, and mkdir/list, reaching 15;
its next operation is the first upload (including create-only lookup, stream
creation, transfer and completion). WebDAV HTTP adds one opt-in rejection,
reaching 12 before connect. Its connect performs root PROPFIND internally.
The generic IOException in that log cannot identify either root cause.
No engine behavior, TLS/trust/pins, plaintext consent or auth policy is changed.
The parent must rerun Android with matching production and runner APKs and
return the safe FTPS/WEBDAV status chains before choosing a protocol fix.

Local validation: pinned offline `run_local.py` passed 1195 assertions in each
of host-JDK and Java-8-base-JAR modes (2390 total), including real FTPS and DAV
round trips, untrusted FTPS rejection, and diagnostic redaction/stream/cycle
checks. SDK35 Java8 bootclasspath compilation passed for production remote,
read-only parent fs adapter, and instrumentation. No ADB, emulator, Gradle,
CI or push was used. Host checks are not Android runtime acceptance.
