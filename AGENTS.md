# Contributor guidance

Policy: implementation-review-loop-v1. This root guide applies throughout the repository; preserve stronger nested instructions and explicit task restrictions.

## Purpose and boundaries

This repository owns G-Earth Trade Assistant, a standalone Origins currency-furniture conversion extension. Player trading and market stalls are future work. Never modify another extension, a live G-Earth installation, or launcher configuration as an incidental build step.

## Validation and privacy

- Java 21+, Maven Wrapper, JUnit 5. The bootstrap gate is `./mvnw verify`.
- Tests use fixtures, fake transport and isolated temporary state. Never redeem, buy, trade, log in, or contact a hotel during tests.
- Keep private captures, credentials, authentication arguments, logs, journals, settings, generated binaries, Maven caches and upstream checkouts out of every published commit.
- Preserve unexplained working-tree changes; never reset, stash, clean, rebase, amend or force-push to fit the workflow.

## Delivery

Use feature branches from the verified target, scoped changes, regression tests and current docs. Run the full gate and `git diff --check` before pushing. Open a ready PR only with publication authority; confirm hosted head and diff. Observe configured automatic Codex review before requesting another cycle. Inspect reviews, threads, checks and bot reactions; eyes and silence are not clearance. Fix valid findings with normal commits and repeat validation and fresh review for every substantive head. Bound review waits to about 15 minutes per cycle, and report exact pending heads when unavailable. Leave PRs unmerged unless separately authorized. Installation and account-changing smoke tests require separate authority.

Guidance adapted from the reviewed public AI engineering playbook at revision `bd51a9360e3e46d3ad5b0f4f2fb25b644995bd2a`; no private project implementation is reused.
