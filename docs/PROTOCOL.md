# Origins profile and evidence

G-Earth API pin: [b993d5ba0b23ab5644633abb8074229d1cb53b71](https://github.com/G-Realm/G-Earth/tree/b993d5ba0b23ab5644633abb8074229d1cb53b71/G-Earth-Api). `scripts/bootstrap.py` verifies the source archive SHA-256 before extraction. The independent API build needs no upstream app build or submodule code. OpenJFX is compile-only. Two exact-source transformations bound the extension socket frame length and replace its partial-EOF spin with `readFully`; no host installation is modified.

## Source-confirmed encoding and host behavior

`HClient.SHOCKWAVE` uses `WEDGIE_INCOMING` and `WEDGIE_OUTGOING`. Shockwave packets supplied to the API consist of a two-character Base64 header followed by the body; the host owns hotel transport framing and encryption. The localhost extension-control connection separately uses modern length/header framing. Never prepend that framing to hotel commands.

The API representation reader converts bracket-byte notation once. Signed integers use VL64. Incoming strings end with byte 2. Outgoing `appendString` uses a two-character length prefix; the captured GETSTRIP and catalogue commands instead use raw bodies. Our strict cursor also rejects truncated, overflowed and noncanonical integers and missing terminators, which the upstream convenience readers alone do not reliably reject.

The host's native `ExtensionHandler` sends extension injections directly through `hConnection.sendToServer`; they do not pass through the normal client intercept path in this pin. Controlled placements therefore register their intent before injection. If a placement is reflected by another host path, its existing handle/target is deduplicated. A distinct outgoing redemption observed from the client during a run is competing activity and stops the run. No packet body is ever blocked or modified by this extension.

## Capture-confirmed conversion profile

| Direction | Header | Binding | Body used |
|---|---:|---|---|
| Out | 65 | GETSTRIP | Raw new/next/update; observation only |
| In | 140 | STRIPINFO_2 | Counted groups and actual member handles |
| Out | 90 | PLACESTUFF | Signed handle, x, y, rotation as VL64 |
| In | 99 | REMOVESTRIPITEM | Signed inventory handle |
| In | 93 | ACTIVEOBJECT_ADD | Decimal ID string, additional integer, class string, geometry and furniture data |
| Out | 1245 | CONVERT_FURNI_TO_HABLOONS | Positive room-object ID as VL64 |
| In | 94 | ACTIVEOBJECT_REMOVE | Unterminated decimal room-object ID |
| In | 1249 | HABLOON_BALANCE | Nonnegative absolute VL64 balance |
| Out | 100 | PURCHASE_FROM_CATALOG | Raw CR-delimited gold-bar command; live execution unavailable |

For the demonstrated bronze profile, a negative floor handle maps to the positive decimal room-object ID with the same magnitude. This is a profile-specific correlation, not permission to invent other instances. Each candidate requires an observed outgoing placement, exact signed inventory removal, matching room-object addition, bronze class and matching destination. Earlier/foreign additions do not qualify. Other currency classes and arbitrary `CF_` names are not eligible.

Inventory grammar: group count; for each group, representative handle, additional-member count, exactly that many handles, slot, floor/wall string, representative object ID, two zero-valued fields, class string, floor dimensions when applicable, and furniture data string; then a trailing integer. Representative plus additional members are real observed handles, each with group/slot/revision provenance. Unknown fields are preserved and constrained to the observed profile, never interpreted as permissions. Wall furniture uses positive handles and is never converted. Pages can replace slot representatives; used/removed handles remain tombstoned until reconnection. Overlapping contradictory slot data excludes the ambiguous handles.

The private supplied inventory refresh removes exactly its placed bronze instance; the 17-item manual trace confirms identity correlation under interleaving and numeric gaps. Its bulk excerpt has no redemption acknowledgements. Public tests substitute independently encoded IDs and simulate subsequent acknowledgements explicitly.

## Limits of result confirmation

Redemption completion means an exact pending object's matching removal was observed after submission, with no observed competing manipulation. The capture supplies that response association, but no transaction ID or stronger exactly-once protocol. A transport return, unrelated removal or balance update never completes the operation. Unknown outcomes stop without replay. A matching removal does not prove a credited amount; reported run proceeds remain unverified. Unobserved external activity can prevent stronger causal attribution.

The trailing inventory integer changes in the supplied refresh, but neither it nor one page proves full inventory coverage. Automatic pagination, termination and request/response correlation are not implemented. Load pages manually; the UI always reports partial coverage.

## Room context and unsupported packets

Connection-specific symbolic metadata is preferred. Numeric fallbacks are confined to the documented profile and rejected when metadata contradicts them; generic modern message maps are unsuitable.

ROOM_READY (69) raw `model roomId` decoding is source-confirmed in [xabbo/core RoomManager at 062537ee](https://github.com/xabbo/core/blob/062537ee04de5f54a20db82bea1a5c4712b7f220/src/Xabbo.Core/Game/Room/RoomManager.Handlers.cs), not present in the supplied appendix. QUIT (53), GOTOFLAT (59), and ROOM_RIGHTS variants (42/43/47) are named by the inspected Origins metadata. Navigation clears room context. Any rights notification disarms, invalidates the learned target and requires a fresh decision; no rights value is treated as ownership proof. ADDSTRIPITEM (67), MOVESTUFF (73), and catalogue purchases are competing client mutations. Unexpected layouts fail closed. Re-enter a room after attaching the extension; no room ID is guessed from an already-open session.

## Purchase evidence still needed

The composer exactly preserves header 100, eight CR delimiters, the `production`, `origins_habloons`, `en`, `a0 CF_50_goldbar`, `-`, and `0` fields, and the three trailing CR bytes. The meaning of `a0` and `0`, the unit quantity, unit cost and fees are not proven. Product face value is not a price.

For a future user-directed capture, record the current client revision, visible catalogue offer and exact cost/quantity, balance immediately before, one deliberately initiated purchase, its incoming success/error messages, exact delivered inventory changes, and balance afterward. Exclude unrelated traffic and credentials. Repeat only when the previous result is reconciled. Do not test this against a real account in CI or during development.

`PurchasePhase` tests a bounded integer-budget policy through an abstract verified contract. Production has no such contract and never invokes a purchase send. Receipt types in those tests are simulated domain observations, not invented Origins wire messages. Purchasing cannot feed back into conversion.

Worker overflow or an unexpected worker exception permanently cancels that process and requires restarting the extension. Dropped observations cannot be used as room context for a new run. Inventory storage is bounded across all observed pages; a rejected page leaves previous provenance intact.

Recovery storage archives only unresolved evidence, with a 32-file/32-MB ceiling and no automatic evidence deletion. Corrupt originals are copied byte for byte before replacement; unarchivable originals block writes. Successful saves flush file contents and, on supported Java filesystem providers, the renamed directory entry before transport submission. Windows directory-channel limitations prevent claiming equivalent power-loss durability there.
