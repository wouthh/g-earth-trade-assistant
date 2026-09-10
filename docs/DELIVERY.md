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

Start with a clean, committed, reviewed source revision. Run `python3 -I -B
scripts/bootstrap.py`, `python3 -I -B scripts/build.py`, `python3 -I -B -m unittest discover -s
scripts -p 'test_*.py'`, `python3 -I -B scripts/check-public.py` and `git diff --check`.
The bootstrap verifies the pinned public API source; it is not an installation
step. Use an isolated cache and synthetic state. Run the packaged offline smoke
with `java -jar target/g-earth-trade-assistant-<version>.jar --demo`.

Keep both the JAR and `target/G-Earth-Trade-Assistant-<version>-extension.zip`.
The ZIP owns exactly `command.txt`, `extension/G-Earth-Trade-Assistant.jar`,
`README.md`, `LICENSE` and `THIRD-PARTY-NOTICES.md` beneath its versioned folder.
Record the source commit, dependency/API pin, toolchain, commands, test results,
artifact SHA-256 values and member hashes. The verifier checks structure, hashes,
version and entry point; the embedded source revision is produced by the clean-commit build wrapper.
Bind the package hash and source revision to the fresh build receipt above.

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
- `locks`: the host launcher's existing one to four empty, user-owned lock files,
  with no group/world write permission. Readable empty locks need no chmod.
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

Invoke `python3 -I -B scripts/deliver.py verify --package <absolute-zip> --sha256
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

The mapped Extensions parent must pass the same write/execute preflight during
preview and execution. The verified original JAR must match `expected_version`;
recovery selects that original from the retained slot after an exchange. An
already-exchanged retry flushes both parents again before finalizing its receipt,
and a staged JAR's parent is flushed again even when the member already exists.

A dedicated receipt directory retains at most eight operation generations, each
with at most one 64 MiB image and a 32 KiB receipt. A private, durable scope record
binds the directory to one target, host path and lock set. A ninth new generation
is refused without pruning; existing-generation retry/rollback still works.
Unknown files and incomplete scope records are preserved and refused. Reconcile
old generations explicitly before consuming another slot.

Managed delivery binds the host, Extensions parent, target and all managed entries
to the same real/effective user used for lock and stopped-Java inspection. Run as
the host owner. Recovery uses only deterministic, bounded pending-write names:
one scope bootstrap, one receipt update or one staged member at a time. Pending
bytes must be exact prefixes of the operation's trusted payload; unrelated or
altered files are preserved and refused. Member temporaries stay outside images.
Scope publication cannot replace another binding. Immutable generation identities
include both inventories; retry and rollback never infer authority from a temporary
file. Test abrupt-write leftovers and retained-receipt tampering with synthetic data.

The mapped Java command must be an absolute POSIX `java` ELF executable or a
Windows `C:\...\java.exe` PE executable mapped beneath the host's `drive_c`. The
private descriptor also binds `java_sha256` and `java_release_sha256` from the
owner's verified Java 21 installation. The adjacent JDK/JRE `release` file must
identify Java 21. Verify these inputs from the established runtime recipe; a hash
only binds identity and is not a substitute for that initial provenance check.
The installer never runs a descriptor-supplied executable to discover its version.
Archive verification rejects differing local/central compression methods or flags.

The original and staged managed launcher argv must match the descriptor-attested
Java command and literal host placeholders before any install or rollback. Keep
retained bytes exact; an unverified previous command is refused, never rewritten
as rollback evidence. Wine path lookup uses the unique actual case-insensitive
entry and still refuses ambiguous siblings.


## Passive loaded identity

Version 0.1.1 writes `runtime-identity.json` in the existing app-owned state
folder after the host initializes the extension. It uses the same state lock and
atomic persistence as other local state, outside the managed extension package.
The receipt contains only product/source/artifact identities, the Java executable
hash, PID, process start instant and `loaded` or `stopped` lifecycle state. It
contains no paths, command arguments, credentials, account or game data. Normal
close writes `stopped`; abrupt termination may leave the old receipt intact.
Neither startup nor verification arms, resumes or sends anything to a hotel.

Run the verified package's Java 21 entry point as:

```
java -jar <verified-jar> --verify-loaded <runtime-identity.json> <expected-jar> <source-commit>
```

Use the same operating-system process namespace/runtime as the extension (the
same Wine prefix for a Windows JVM). The bounded read-only verifier correlates
receipt schema, expected JAR hash and embedded provenance, live PID/start instant,
and executable hash, then rechecks process liveness and receipt stability. It
never reads process arguments. Exit 0 means this process identity was alive at
verification, not that the extension is connected, healthy or armed. A missing,
stopped, malformed, stale, PID-reused or mismatched receipt exits 2. Unsupported
process diagnostics leave loaded identity unverified; never infer success from
the startup file alone. Native Windows/Wine validation remains a separate target.
State creation keeps inherited Windows ACLs and skips POSIX-only volume queries
on providers without that attribute view. POSIX state permissions remain private.

`python3 -I -B scripts/build.py` refuses dirty/untracked source, embeds the exact clean
commit through Maven resource filtering and checks the source again afterward.
It also refuses ignored files under Maven's source/resource, wrapper, API recipe
and assembly input trees, plus Python recipe files and bytecode under `scripts/`;
Git's ordinary clean status does not cover those files.
Tracked `skip-worktree` or `assume-unchanged` entries are refused as well: sparse
or hidden index state can conceal missing resources or tests from ordinary status.
The preflight preserves those files and index flags; use a complete separate checkout.
Guarded Git reads disable fsmonitor for that command and optional index refreshes:
a stale monitor cannot hide changed source, no monitor hook is invoked, and the
caller's monitor configuration and index are preserved.
Refused inputs are preserved. Root `.build/` caches and `target/` output are separate
from these source trees and remain subject to the pinned bootstrap/build checks.
Each Python CLI re-executes itself with `-I -B` using only built-in modules before
standard-library imports. Ordinary `python3 scripts/<tool>.py` calls still work;
recipe-local modules and legacy bytecode never enter those import paths. Library
imports retain their caller's semantics; run tests only after source preflight,
with `-I -B` to avoid generating recipe bytecode. Existing script caches are
preserved and refused; use a fresh isolated checkout instead of deleting them.
This protects against local recipe shadowing, not an untrusted interpreter,
site configuration or build toolchain.
It does not edit tracked source or create a self-referential commit field. Plain
`./mvnw clean verify` remains a development gate; its default `unverified` build
provenance deliberately cannot emit a verified runtime receipt. Retain source,
recipe, toolchain and artifact fingerprints with delivery builds. Stop other
writers before packaging; observed clean-tree checks are not atomic isolation.
