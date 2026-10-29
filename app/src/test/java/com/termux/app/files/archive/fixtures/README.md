# RAR corpus provenance

The 43 `.rar.uu` files are unmodified text fixtures from the hash-pinned
libarchive 3.8.10 release described in `docs/file-manager-rar-dependencies.md`.
Their exact bytes are checked against SHA256SUMS by the JVM runner before decoding.
No downloaded native binary is committed. The optional full-helper test mode
builds a hash-pinned, independently licensed UnRAR command from official source;
the default BSD/JNI tests do not use it.

Upstream: https://github.com/libarchive/libarchive/tree/v3.8.10/libarchive/test
The release tarball SHA-256 is
`56cad765459d5ee25a3b761f90c47d99379d28297fbfb72d908880d6b875fb96`.
Their associated test sources carry the BSD notices reproduced below; the release
COPYING is also retained at `app/src/main/cpp/veyterm-archive/LICENSE.libarchive`.

RAR4 normal/best contain symlinks: tests deliberately reject the complete archive.
For successful public-API extraction tests, the first compressed member is retained
byte-for-byte with its original marker/main header and original ENDARC record.
Its independently checked plaintext SHA-256 is
`ee16390e87152d7dec632ae2b37566da6135966e46b5166e6f40a1c827fa61ad`.
RAR5 polynomial payloads are checked byte-for-byte against the upstream mathematical
recipe, not against a second decode by this adapter. Generated hostile STORE
archives use actual header/payload CRCs and no external compressor.

The full-helper suite adds 20 unmodified upstream fixtures: both encrypted
solid-header RAR4/RAR5 variants, the remaining seven RAR5 volumes, three RAR4
single-file volumes, six RAR4 multiple-file volumes, and the two older data/header
encryption fixtures. It tests complete archives with their original bytes and
hashes, not re-compressed substitutes. The native runner verifies all six kernel
resource limits while the real command awaits its ordinary stdin password.

Password and plaintext provenance:

* [test_read_format_rar_encryption.c](https://github.com/libarchive/libarchive/blob/v3.8.10/libarchive/test/test_read_format_rar_encryption.c)
  specifies `password` for solid or encrypted-header archives; the mixed clear
  header archives use no encryption on a/c, `password` on b and `password2` on d.
  Every file is exactly the 18 bytes `This is from X.txt`, without a newline.
  Full mixed-password archives must fail a one-password whole-archive pass.
  Successful clear-header single-password tests additionally preserve the first
  two original headers/ciphertexts verbatim and append a standard ENDARC record.
* [test_read_format_rar_encryption_data.c](https://github.com/libarchive/libarchive/blob/v3.8.10/libarchive/test/test_read_format_rar_encryption_data.c)
  and [test_read_format_rar_encryption_header.c](https://github.com/libarchive/libarchive/blob/v3.8.10/libarchive/test/test_read_format_rar_encryption_header.c)
  specify `12345678` for the older AES fixtures.
* [test_read_format_rar5.c](https://github.com/libarchive/libarchive/blob/v3.8.10/libarchive/test/test_read_format_rar5.c)
  gives CRC32 `35277473` / `E59665F8` and sizes 144608 / 365672 for the two
  complete RAR5 multivolume executable payloads. RAR4's complete three-volume
  STORE payload has the independently pinned HTML SHA-256 above.

The extra encryption test-source notice is reproduced unchanged below.

From test_read_format_rar_encryption.c (the data/header variants carry the same notice):

```c
/*
 * Copyright (c) 2003-2018
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer
 *    in this position and unchanged.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
```

From test_read_format_rar.c:

```c
/*-
 * Copyright (c) 2003-2007 Tim Kientzle
 * Copyright (c) 2011 Andres Mejia
 * Copyright (c) 2011-2012 Michihiro NAKAJIMA
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
```

From test_read_format_rar5.c:

```c
/*-
 * Copyright (c) 2018 Grzegorz Antoniak
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
```
