# Packaging and mapped local delivery

Delivery has separate source, package, installed and loaded states. A passed fake
host test proves offline behavior; it does not prove that a live host loaded the
new JAR. Record these states separately in private receipts.

## Source and artifacts

The Maven project version is the product version. Keep the extension annotation,
assembly name and documented launch commands consistent when changing it. The
pinned G-Earth API revision and required Java 21 runtime are separate identities;
see [PROTOCOL.md](PROTOCOL.md). Guidance or installer changes that do not change
the extension runtime do not require a new product version.

Start with a clean, committed, reviewed source revision. Run `python3
scripts/bootstrap.py`, `./mvnw clean verify`, `python3 -m unittest discover -s
scripts -p 'test_*.py'`, `python3 scripts/check-public.py` and `git diff --check`.
The bootstrap verifies the pinned public API source; it is not an installation
step. Use an isolated cache and synthetic state. Run the packaged offline smoke
with `java -jar target/g-earth-trade-assistant-<version>.jar --demo`.

Keep both the JAR and `target/G-Earth-Trade-Assistant-<version>-extension.zip`.
The ZIP owns exactly `command.txt`, `extension/G-Earth-Trade-Assistant.jar`,
`README.md`, `LICENSE` and `THIRD-PARTY-NOTICES.md` beneath its versioned folder.
Record the source commit, dependency/API pin, toolchain, commands, test results,
artifact SHA-256 values and member hashes. The verifier checks structure, hashes,
version and entry point; its `--source` argument is an attested build identity,
not proof of compilation ancestry. Bind it to the fresh build receipt above.

## Existing mapped installation

Use `scripts/deliver.py` only with explicit installation authority and after its
source changes pass the repository review requirements. It supports Linux hosts
with same-filesystem `renameat2(RENAME_EXCHANGE)`, including a mapped extension
directory used by a stopped Wine host. Unsupported platforms remain package-only.
It does not discover installations, install a first copy, create host locks,
change profiles, launch Java or stop a process.

Create an owner-only JSON descriptor outside the host's `Extensions` directory.
Its schema is `1`, component is `g-earth-trade-assistant`, and its fields are:

- `target`: the exact existing versioned extension directory.
- `receipt_directory`: an owner-only directory outside `Extensions`, on the same
  filesystem as the target. Preserve it for retries and rollback.
- `host_executable` and `host_sha256`: the verified host file in the parent of
  `Extensions` and its exact SHA-256.
- `locks`: the host launcher's existing one to four owner-only lock file paths.
- `expected_files`: the exact current SHA-256 map for the five managed files.
- `expected_version`: the verified installed product version, consistent with the
  inspected JAR and file map. Never derive it from a stale directory name alone.
- `java_executable`: the explicit Java 21 executable used by this mapped host.

All paths are absolute and free of symlinks. The descriptor, package and host locks
must be outside the replaced target. An existing receipt directory must have owner
read/write/traverse access, and its existing ancestor must be writable.
Keep machine paths, host descriptors
and receipts private. No profile databases, journals or expanded authentication
arguments belong in a receipt or repository.

Invoke `python3 scripts/deliver.py verify --package <absolute-zip> --sha256
<sha256> --source <40-character-commit>`. For installation, replace `verify` with
`install` and add `--descriptor <absolute-json>`; first add `--dry-run`. Preview
uses the same identity, writer-lock, inventory and recovery guards as execution.
The helper refuses active same-user Java processes, changed inputs, unknown files,
symlinks, older candidate versions and incomplete or tampered recovery evidence.

Installation stages the complete file set and atomically exchanges it with the
mapped directory. Only launcher argument zero may be adapted to explicit Java;
the literal `{port}`, `{filename}` and `{cookie}` placeholders remain unchanged.
The receipt binds the package, before/after maps and retained original. File and
directory flushes precede activation; retries also flush existing state ancestors.
A retry recognizes an exchange completed before the receipt update. An identical
installation is a verified no-op and does not rewrite the target or create state.

Use `rollback` with the same package, source and descriptor, initially with
`--dry-run`, to exchange the verified original back. Keep both sides and receipts;
do not remove retained files as incidental cleanup. Rollback is retryable after an
interruption. Reconcile a completed rollback before preparing a new installation.

## Activation and handoff

Verify installed hashes and launcher syntax under the host locks. Record the
artifact and installed command hashes separately when Java argument zero differs.
Preserve every unrelated extension, mutable setting, journal and rollback copy.
The helper always reports `loaded: false` and never connects to a game.

Normal extension startup is disarmed and persisted jobs never auto-resume. Loading
or restarting a busy host still requires a separately safe activation boundary;
never buy, redeem, trade or resume automation as a smoke test. When activation is
deferred, retain a verified installed receipt and name the missing runtime proof.
