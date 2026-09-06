# Deliberate manual smoke test

Offline development gate: bootstrap, `./mvnw clean verify`, public-source audit, and the packaged `--demo`. Tests must never connect to a real hotel or discover a live extension port. The fake-host test binds a new ephemeral loopback port and uses synthetic credentials and isolated state.

The following real-account steps are **for the user to choose**, not an automated development procedure:

1. At a safe time, load the whole extension folder with a verified Java 21 launcher. Confirm the window opens disarmed. Connect/reconnect and confirm it remains disarmed.
2. Re-enter a room you control. Confirm the observed room status. Leave automation off while inspecting the interface. Purchasing must visibly remain unavailable.
3. If you deliberately accept the irreversible conversion, select manual-drop mode, limit 1, and arm. Place one bronze coin. Verify exact placement/removal progress and the final matching object-removal count. Compare balance manually; do not infer a credited delta without a baseline.
4. For a separate deliberate inventory test, load the bronze inventory page, select N=1 with destination learning, arm, and place one bronze seed. Confirm it counts once and is not placed again. Only choose N=2 afterward if you want an additional irreversible test.
5. Pause or Stop before a pending next send. Confirm no next automation submission begins, and any already-placed object remains reported for reconciliation. Change rooms/reconnect and confirm re-arming is required.
6. Close the extension. Retain local journals if anything is uncertain. Never retry an ambiguous operation automatically.

Do not purchase, trade, or run market-stall actions as part of this smoke test. Future purchase evidence collection is described in [PROTOCOL.md](PROTOCOL.md) and needs a separate deliberate user action.
