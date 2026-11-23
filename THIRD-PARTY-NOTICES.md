# Third-Party Notices

Kaze's repository contains its own source code and generated test fixtures. Build dependencies are resolved through their package managers rather than vendored in this repository.

## Direct build dependencies

| Project | License | Use |
| --- | --- | --- |
| Kotlin 2.1.21, kotlinx-coroutines | Apache-2.0 | Language and asynchronous runtime |
| JetBrains Compose Multiplatform 1.8.2 | Apache-2.0 | Desktop UI |
| Protocol Buffers (`protobuf-java`, `protoc`) | BSD-3-Clause | IPC serialization |
| Gson | Apache-2.0 | JSON processing |
| Xerial SQLite JDBC | Apache-2.0 | Translation memory storage |
| JNA | LGPL-2.1 or Apache-2.0 (Kaze uses Apache-2.0) | Windows API interop |
| Bouncy Castle | MIT-style Bouncy Castle License | Cryptography |
| JUnit Jupiter | EPL-2.0 | Tests only |

## Native and packaged runtime components

The C++ relay links against Protocol Buffers and its Abseil dependency. Portable Windows packages contain a Java runtime and runtime libraries whose license notices remain with their respective distributions.

- Protocol Buffers: BSD-3-Clause
- Abseil: Apache-2.0
- OpenJDK / Zulu OpenJDK: GPL-2.0 with Classpath Exception

## Format implementation

`adapters/mvmz/lzstring.py` is an independent implementation of the LZ-String compression format used by RPG Maker save files. The reference LZ-String library by pieroxy is distributed under the MIT License; the reference library itself is not included in Kaze.

## Development-only fixture generation

The optional `tools/make_rgss_fixtures.py` script uses `rubymarshal` (MIT) to generate synthetic RGSS test fixtures. This package is not required by the application and is not included in release packages.

For authoritative license texts and source offers, consult the corresponding upstream projects and the license metadata included in their distributed artifacts.
