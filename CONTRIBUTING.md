# Contributing

Keep UI, account storage and workout planning out of this repository. The app owns those concerns.

- Implement `VoltraTransport`/`VoltraLink` for another byte transport, or `VoltraController` for a connector whose capabilities or telemetry differ. Keep implementation details behind these interfaces.
- Expose immutable snapshots via StateFlow. Lifecycle/state operations use one owner dispatcher. Never turn a real connection failure into demo readings.
- Add a sanitized captured-frame fixture and a provenance link when adding a register or packet family. Do not include Bluetooth addresses, serials, account identifiers, tokens or personal training logs.
- Commands must validate state/units and await device readback. Check rejection, disconnection, timeout, cancellation and stale state. Never retry a motor setting automatically after reconnect.
- For native Twin, contributions must verify controller/peer identity, roles, pair and unpair completion, loss of either connection, settings routing and the Twin telemetry schema. Summing two independent streams or writing the same value twice is not native Twin. Do not expose working Twin capability until physical-device tests pass.
- Record trainer model, firmware, Android/TV model and test outcome in the PR. State clearly when validation used only the simulator.

Run the checks in README before submitting a PR. Keep protocol logic in `voltra-core`, Android APIs in `voltra-android`, and synthetic behavior in `voltra-testing`.
