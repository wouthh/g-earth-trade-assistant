# G-Earth Trade Assistant

Standalone Java/Swing extension for **Habbo Origins through G-Earth**. It converts explicitly selected, observed bronze currency furniture using one serialized worker. It is independently authored and is not an official Habbo or G-Earth product.

## What works in v1

- **Auto-redeem my drops:** explicitly arm a finite count, then manually place bronze coins. The extension correlates each placement with its inventory removal and room addition before redeeming it.
- **Convert N inventory items:** load inventory pages manually, choose N observed bronze instances, and learn a destination by placing the first coin. That seed is included in N. Remaining distinct instances are placed and redeemed one at a time.
- Pause/Stop, conservative pacing, strict packet validation, duplicate handling, local recovery evidence, and headless fixture/fake-host tests.

**Live replacement purchasing is unavailable.** The gold-bar composer and bounded budget policy are tested offline, but catalogue price, quantity and delivery/balance contracts are unverified. The UI explains the gap. Other currency types, full inventory scanning, player trade acceptance and market stalls are not enabled features.

Conversion is irreversible. Automation can violate platform rules; there is no claim of approval, guaranteed account safety or undetectability. All development validation uses synthetic traffic or a localhost fake host, never real redemption or purchases.

## Build and verify

Requirements: a **full JDK 21+** supporting `--release 21`, Python 3.12+, network access to public dependency repositories for the initial bootstrap, and Git for the publication audit. A runtime-only Java installation is insufficient even when it contains some compiler modules. CI uses Temurin 21.

```sh
python3 -I -B scripts/bootstrap.py
./mvnw clean verify
python3 -I -B -m unittest discover -s scripts -p 'test_*.py'
python3 -I -B scripts/check-public.py
java -jar target/g-earth-trade-assistant-0.1.1.jar --demo
```

Bootstrap downloads an exact public G-Earth source revision, verifies its archive SHA-256 and builds the API in `.build/`. Maven dependencies are project-local. No installed G-Earth JAR, unpublished Maven cache, or other extension project is required. Bootstrap must run once in every fresh checkout; rerunning it always builds freshly extracted, verified source. The wrapper downloads Maven 3.9.16. Set `MAVEN_USER_HOME` to an isolated directory to isolate the wrapper's distribution cache too. See [protocol/build evidence](docs/PROTOCOL.md) and [third-party notices](THIRD-PARTY-NOTICES.md).

Artifacts:

- `target/g-earth-trade-assistant-0.1.1.jar` — executable shaded extension JAR.
- `target/G-Earth-Trade-Assistant-0.1.1-extension.zip` — loadable extension folder with launcher, JAR and notices.
- GitHub Actions artifact **g-earth-trade-assistant** — the same two build outputs, retained for 14 days.

`--demo` is network-free and writes no settings or journals. The optional private-evidence test accepts a local task/capture document through the `privateEvidence` Maven property; it emits only structural results. Raw evidence must never be checked in. Standard CI excludes that local-only test.

## Compatibility and loading

Targets Origins Shockwave/WEDGIE and the native G-Earth extension socket, with API revision `b993d5ba0b23ab5644633abb8074229d1cb53b71`. Discovery checked a beta-28 Java 17 G-Earth host with a separate Java 21 extension runtime. The packaged fake-host handshake and protocol tests prove offline compatibility; a real-host/account smoke test remains a user-controlled step.

1. Build/download and extract the complete ZIP. Its top-level folder contains `command.txt` and an `extension/` subdirectory containing `G-Earth-Trade-Assistant.jar`. Keep this layout: G-Earth uses `extension/` as the process working directory.
2. When you choose to load it, place that whole folder directly inside the intended G-Earth `Extensions` directory. G-Earth can automatically launch direct child folders; the extension itself always starts disarmed. Do not install while another account-sensitive session is active unless you deliberately choose that timing.
3. The supplied Bottles launcher explicitly selects `C:\G-Earth\jre\bin\java.exe` because the discovered host's ordinary `java` launcher selects Java 17. For another installation, edit only the executable path in `command.txt` to a verified Java 21+ runtime. Preserve `{port}`, `{filename}` and `{cookie}` exactly; never publish expanded authentication arguments.
4. Restart/reload G-Earth at a time you choose. Open **G-Earth Trade Assistant** through the extension's double-click/green button.
5. Connect Origins and re-enter your room so the extension observes its room context. It never infers context from an already-open room.

Installation, updates, restarts and live account actions are not performed by the build scripts.

## Everyday use

For manual drops, choose **Auto-redeem my drops**, set a maximum count, and press **Arm / Start**. Place your bronze coins normally. The tracker accepts rapid interleaving but redeems serially. Only drops recorded after arming qualify. An unsupported type, changed destination, competing pickup/move/purchase, or ambiguous packet stops the run; room-wide cleanup is never performed.

For inventory conversion, manually load pages containing the bronze stack. The displayed count is **observed coverage**, always partial. Choose **Convert N inventory items**, keep destination learning selected, choose N no greater than the observed count, and press **Arm / Start**. Manually place one bronze coin from that observed group at the desired tile. It counts as item 1. The extension learns x/y/rotation from the successful placement and never places that seed again. Later runs in the same room may reuse the learned target by clearing the learning checkbox. Room/permission changes clear it.

Defaults are quantity 1, at least 1,500 ms between automation submissions, and a 15-second outcome timeout. Counts are bounded to 1–1,000 and pending correlations to 128. These are engineering defaults, not platform-approved limits. Insufficient observed inventory causes an honest partial stop.

**Pause** and **Stop** serialize cancellation with the local transport write. Cancellation may wait for a write already in progress; after it returns, no stale submission can begin. Sent operations remain subject to reconciliation. A definitively unsent placement releases its reservation, and Resume can use that observed instance; an unknown submission is never replayed. Pause needs explicit Resume. Stop never sends cleanup or rollback packets. Closing the window also stops automation. Disconnect, room changes and uncertain outcomes never automatically resume.

“Redeemed/removal confirmed” means the exact pending object's removal was observed. Balance is displayed separately as an absolute observation. Credited proceeds remain unverified, even after a matching removal; unrelated balances cannot complete a redemption. Exactly-once delivery is not guaranteed by this protocol.

## Recovery, privacy and troubleshooting

- Journal and harmless preferences live under `LOCALAPPDATA/G-Earth Trade Assistant` on Windows, or `XDG_STATE_HOME/g-earth-trade-assistant` (default `~/.local/state/g-earth-trade-assistant`) on Linux. An explicit `tradeassistant.stateDir` Java property can select an isolated test directory. No armed state or selected inventory IDs are restored into an executable job.
- Each process holds a directory lock. Only recovery evidence is archived before replacement; ordinary disarmed/completed restarts create no archives. Archives are capped at 32 files and 32 MB. Capacity exhaustion stops further writes/sends until you export and reconcile archives manually; evidence is never automatically deleted. Corrupt or unsupported journals are preserved byte for byte before replacement; oversized or unarchivable originals remain untouched and block writes. Review recorded pending handles and known unredeemed room IDs after a crash/timeout. Resolve the hotel state yourself before acknowledging the journal. Acknowledgement sends nothing and does not undo anything.
- Unknown room: re-enter the room. Unsupported protocol or metadata: leave automation disarmed and inspect the documented profile; never guess new headers. Unexpected rights notifications disarm and invalidate destination learning.
- No observed instances: manually load the relevant inventory pages. Stale refreshes cannot resurrect used IDs. Newly bought furniture is never automatically selected for a currently running job.
- Durability: file contents are flushed before atomic rename, and the containing directory is flushed where the Java filesystem provider supports it. The Windows provider may not support directory flushes, so abrupt power-loss durability remains weaker there; no exactly-once guarantee is claimed.
- Journal/lock failure: close another Trade Assistant using that same state directory, or repair local filesystem permissions. The extension stops before further sends if journal writes fail.
- No raw traffic logger is enabled. Application status contains only bounded conversion facts and local identifiers; chat, friends, credentials and arbitrary hotel traffic are not retained. Keep local journals private. Dependency-generated errors should likewise be inspected locally, not pasted unredacted.

See the [manual smoke checklist](docs/SMOKE-TEST.md). Rollback consists of stopping the extension, removing only its folder at a chosen safe time, and retaining the journal for reconciliation. Removing software does not reverse currency conversion.


Loaded-code checks are passive and separate from game status. Delivery builds can
write a privacy-safe lifecycle receipt in the existing application state folder;
use the bounded verifier documented in [delivery guidance](docs/DELIVERY.md#passive-loaded-identity).
A startup receipt alone does not prove a process is still running. Build deliverable
artifacts from a clean commit with `python3 -I -B scripts/build.py` after bootstrap.
