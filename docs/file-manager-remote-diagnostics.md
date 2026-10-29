# Safe remote runtime diagnostics

`RemoteFiles.safeDiagnostic(Throwable)` exposes exception classes and up to four
class/method/line frames per cause, plus two frames each from the app, Apache
FTP, and Conscrypt layers (eight causes, 3500 characters maximum).
It never reads messages, source filenames, URLs, server replies, or credentials.
Code symbols outside the accepted alphabet are redacted; nested-class and
constructor symbols are normalized for the existing host instrumentation filter.
Frames use ` at ` separators to avoid being mistaken for email addresses.
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

## Android diagnostic r2 follow-up

Android confirmed WebDAV `ParserConfigurationException` at Harmony DOM
`setFeature`. `WebDavBackend` now strictly decodes response bytes as UTF-8,
rejects malformed input, NUL, DOCTYPE and ENTITY declarations before DOM, and
parses a character Reader so XML encoding declarations cannot change that
boundary. It retains non-expanding DOM and a rejecting external-entity resolver.
Unsupported provider feature switches are no longer needed. This deliberately
supports UTF-8 DAV XML; alternate encodings fail closed. The two-MiB response
limit remains in place. Host checks cover normal UTF-8/BOM, external/internal
and parameter entities, expansion, malformed UTF-8, UTF-16/32 and EBCDIC attacks.

FTPS r2 exposes only socket-read frames during first upload; it does not show
whether timeout occurs during the create-only listing, data TLS handshake,
transfer or completion reply. Existing passive port reservation/reversal covers
all eight advertised fixture ports, and host verified FTPS listings and complete
round trips pass. That is insufficient evidence for a production TLS change.
Additional diagnostic slots now preserve Apache/app callers beyond deep socket
or TLS stacks; no FTPS behavioral workaround or policy change is included.
The parent must return the next Android FTPS safe chain and verify WebDAV CRUD.

Follow-up host suite: 1236 assertions per host-JDK/base-JAR mode, 2472 total.
SDK35 compilation passed with production remote, parent adapter and runner.
A subsequent focused recompile checks the final per-layer frame selection.
