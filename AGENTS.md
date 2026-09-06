# Contributor guidance

Policy: implementation-review-loop-v1. This root guide applies throughout the repository; preserve stronger nested instructions and explicit task restrictions.

## Purpose and boundaries

This repository owns G-Earth Trade Assistant, a standalone Origins currency-furniture conversion extension. Player trading and market stalls are future work. Never modify another extension, a live G-Earth installation, or launcher configuration as an incidental build step.

## Architecture and invariants

- `domain/` owns typed identities, observed inventory, the serialized conversion state machine, and the unavailable purchase policy. No domain class may import native packet types.
- `protocol/` is the only native API and packet boundary. `runtime/` owns scheduling, the send cancellation fence and atomic local state. `ui/` owns Swing controls and immutable snapshots.
- Blocked incoming context notifications still invalidate the synchronous send fence and room context. Room observation overflow freezes recording until new context; never keep growing a halted collection.
- Listeners copy only relevant bounded packet bodies, enqueue facts, return packets unchanged and never wait for disk, timers or hotel responses. Swing reads/updates belong to its event-dispatch thread; persistence belongs to the worker.
- Register listeners once, serialize sends, reserve actual observed instances, journal intent before transport, and confirm only matching observations. Never infer an ID sequence, ownership, credited delta, price or pagination from a name or timing.
- Recovery archives contain only unresolved evidence and are capped at 32 files / 32 MB. Preserve unreadable originals before replacement; never silently prune evidence. Flush renamed journal entries and newly created directory ancestors on supported providers; document Windows directory-flush limitations.
- Pause/Stop/context changes invalidate permits. No uncertain non-idempotent operation is replayed; persisted jobs never auto-resume. Keep every collection and scheduler bounded. Future trade/stall work needs its own scope and evidence.
- The API source pin and exact reader patch are documented in `docs/PROTOCOL.md`; change them only with reproducible public source, byte tests and fake-host checks. No `systemPath`, local unpublished JAR, private implementation copy or installed-host patch is allowed.

## Validation and privacy

- Full JDK 21+, Python 3.12+, Maven Wrapper, JUnit 5. Bootstrap: `python3 scripts/bootstrap.py`. Full canonical gate: `./mvnw clean verify`; it includes format, unit and packaged fake-host/ZIP checks. Focused regressions: `./mvnw -Dtest=ConversionEngineTest,OriginsCodecTest test`.
- Privacy/history gate: `python3 scripts/check-public.py` plus manual staged/history review and `git diff --check`. Network-free artifact smoke: `java -jar target/g-earth-trade-assistant-0.1.0.jar --demo`. CI mirrors the same headless gate and publishes artifacts. Private evidence validation is local-only through the opt-in `privateEvidence` property; standard CI intentionally skips it.
- Tests use fixtures, fake transport and isolated temporary state. Never redeem, buy, trade, log in, or contact a hotel during tests.
- Keep private captures, credentials, authentication arguments, logs, journals, settings, generated binaries, Maven caches and upstream checkouts out of every published commit.
- Preserve unexplained working-tree changes; never reset, stash, clean, rebase, amend or force-push to fit the workflow.

## Delivery

Use feature branches from the verified target, scoped changes, regression tests and current docs. Run the full gate and `git diff --check` before pushing. Open a ready PR only with publication authority; confirm hosted head and diff. Observe configured automatic Codex review before requesting another cycle. Inspect reviews, threads, checks and bot reactions; eyes and silence are not clearance. Fix valid findings with normal commits and repeat validation and fresh review for every substantive head. Bound review waits to about 15 minutes per cycle, and report exact pending heads when unavailable. Leave PRs unmerged unless separately authorized. Installation and account-changing smoke tests require separate authority.

Guidance adapted from the reviewed public AI engineering playbook at revision `bd51a9360e3e46d3ad5b0f4f2fb25b644995bd2a`; no private project implementation is reused.

## Review rules

- Any new send path must prove identity provenance, bounded intent, cancellation and acknowledgement through fake-transport regressions. A successful socket write is not server success.
- Keep live purchasing unavailable until matching cost, quantity, delivery and balance semantics are evidenced and documented. Abstract test receipts are not Origins messages.
- Inspect every published commit for private source, raw captures or expanded credentials. Preserve licenses and keep current runtime evidence out of public documentation. Do not merge, release or install this extension without separate task authority.
