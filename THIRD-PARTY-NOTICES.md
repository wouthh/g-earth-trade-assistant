# Third-party notices

New application code is MIT licensed (see LICENSE).

- G-Earth API: G-Realm/G-Earth at b993d5ba0b23ab5644633abb8074229d1cb53b71, MIT, Copyright 2018 sirjonasxx. The complete license is included at `META-INF/licenses/G-Earth-MIT.txt`. Its VL64 implementation retains the source attribution to Kepler, Copyright 2018 Quackster. No private extension source is used.
- The source bootstrap changes only the bundled API's localhost frame reader: `readFully` replaces a loop that spins on partial-frame EOF, and frame allocations are bounded to four million bytes. Both replacements require exact pinned source preconditions. The installed G-Earth host is unchanged.
- SLF4J API 2.0.17: MIT, QOS.ch. Its published JAR license is retained under `META-INF/licenses/`.
- JSON-java 20251224: public-domain dedication, retained under `META-INF/licenses/` from its public release source.
- Apache Maven Wrapper 3.3.4: Apache License 2.0. Its source headers and complete Apache license are retained. Maven and its plugins are build tools, not part of the extension JAR.
- OpenJFX 21.0.7 is used only to compile the public API's unused JavaFX helpers. Its binaries are not included in the Swing extension. JUnit 5 is test-only.

Reference implementations inspected for protocol research (not linked or copied into the application): xabbo/core, xabbo/goearth and SimonFlapse/G-Java-Parser. Their older inventory layouts do not implement the supplied grouped-instance profile; this repository's decoder is independently authored from structural evidence.
