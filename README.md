# android-voltra

UI-free Kotlin/JVM protocol and Android BLE connector for Beyond Power VOLTRA trainers.

**Experimental.** Protocol, controller and simulated-transport tests cover the implementation. Physical tests on October 16 verified mixed Twin creation/exit from the TV and knob, plus Weight Training live force and five reps through the knob on both displays. Direct-TV native Twin telemetry, other training modes, automatic workout transitions and recovery across real device failures still require hardware acceptance. This is an independent community project, not an official Beyond Power SDK.

## Modules

| Module | Responsibility |
| --- | --- |
| `voltra-core` | Models, controller/transport interfaces, frame reassembly, CRC validation, command confirmation and rep tracking. No Android dependencies. |
| `voltra-android` | Android discovery, permissions checks and a separate GATT connection per trainer. No views or permission prompts. |
| `voltra-testing` | Explicit two-device demo controller for app development without Bluetooth hardware. Never an automatic fallback. |

The direct connector supports up to two independent connections, battery, live cable force, per-device reps, and four modes: Weight Training, Resistance Band, Damper and Isokinetic. Settings remain device-confirmed: a successful Bluetooth write alone does not update the displayed value. Connecting, starting an app set and reconnecting never change resistance or load the motor.

### Connection ownership

Treat each trainer as accepting one controlling client at a time. Disconnect
Cortex, Beyond+ or the knob before connecting directly from Android. For the knob
route, disconnect that specific trainer from Android first; Android connects to
the knob's bridge while the knob owns the trainer connection. A different trainer
may stay connected directly through `MixedVoltraController`. Release the connection in the
previous client explicitly—closing its window may leave Bluetooth connected.
An occupied connection can cause discovery/connection failures; a timeout alone
does not prove this cause. Native Twin is reached through its verified controller,
with the peer represented logically rather than connected independently.

### Bluetooth knob bridge

`KnobVoltraController` and `AndroidKnobTransport` connect through the matching
Guition VOLTRA Remote firmware. The knob owns one selected trainer; the TV receives
readings and sends validated, expiring setting commands. Bridge v2 adds native
Twin creation/exit and verified controller/peer identities. An optional capability
adds explicit, confirmed motor load/unload; older firmware leaves it unavailable. See [protocol and pairing](docs/knob-bridge.md).
The JVM/demo checks and firmware build do not replace physical pairing/coexistence tests.

### Twin status

The direct connector implements native Twin creation/exit using endpoint status
(`0xa7`) and correlated commands (`0xa8`). The future peer receives the future
controller's address. Success requires both the acknowledgment and matching fresh
endpoint identities. A paired controller exposes both trainers through one BLE
connection. Exit confirms independent state, waits for the peer to advertise, then
reconnects it; a missing peer is reported without replaying unpair.

Shared mode/settings commands are implemented; successful unloaded topology checks
do not validate every mode under load. Native Twin force and reps through the knob
were physically verified in Weight Training on October 16: five reps and moving
weight appeared on both the knob and TV. The firmware sums two explicit measured
force channels from the same recognized packet; it never doubles one cable.
Direct-TV native Twin telemetry remains unavailable pending classification and
hardware validation. Mixed native creation/exit passed from both the TV and knob.
Knob pairing accepts a fresh unloaded home or supported training screen, verifies
both live endpoint identities and unloaded states, and enters idle before native
pairing. It restores the controller's previous mode only after the pair is confirmed.

The direct and knob demos implement Twin UI semantics only: shared settings in all
four modes, two batteries, per-side force readings and a logical pair reading with
one controller's rep count. `TwinPair` carries the logical telemetry so consumers
never have to guess whether a native force reading is per-side or combined.
`TrainerState.twinVerified` and `twinRole` must describe verified live topology,
never a remembered role. Losing either member keeps a `DEGRADED` pair, clears its
readings and blocks shared controls. Two fresh, distinct, unloaded trainers are
required for demo creation; matching verified roles are required for exit.

Simulated telemetry does not establish physical telemetry semantics. See
[CONTRIBUTING.md](CONTRIBUTING.md) for transport extension points.

## Use from an Android app

Requires JDK 17+, Android SDK 35 and Android 8/API 26+. Maven Central publication is not configured yet. Include the source as a Git submodule/composite build:

```kotlin
// settings.gradle.kts
includeBuild("libraries/android-voltra")

// app/build.gradle.kts
implementation("io.github.albertzhang86.voltra:voltra-android:0.1.0-SNAPSHOT")
debugImplementation("io.github.albertzhang86.voltra:voltra-testing:0.1.0-SNAPSHOT")
```

Create one controller on a single owner dispatcher, normally `Dispatchers.Main.immediate`. Request the permissions returned by `VoltraBluetooth.permissions()` from your own activity. On Android 8–11, scanning also needs system location enabled. The library merges the necessary manifest declarations but does not show prompts or enable Bluetooth.

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
val controller: VoltraController = DirectVoltraController(
    transport = AndroidVoltraTransport(applicationContext),
    parentScope = scope,
    clientName = "My TV App",
)

scope.launch {
    controller.scan().collect { discovered -> /* render selection */ }
}
// After the user chooses a device, stop scanning and:
scope.launch { controller.connect(chosenDevice) }
// connect starts the handshake. Observe state until READY and fresh.
scope.launch { controller.state.collect { state -> /* render each trainer */ } }

scope.launch {
    // Explicit user action; throws if unavailable, stale, or not confirmed.
    controller.setParameter(chosenDevice.id, Parameter.WEIGHT, 40)
    controller.setLoaded(chosenDevice.id, true) // Explicit, confirmed motor action.
    controller.beginSet() // Local rep boundary; no motor command.
}
// On permanent disposal, on the owner dispatcher:
controller.close()
scope.cancel()
```

Use a long-lived owner scope when the app displays a workout overlay. A dead app process closes its connections; applications must reconnect explicitly and read back settings. This connector does not replay commands. Twin exit restores the now-independent
peer. Mixed handoff can also restore the former direct peer after a confirmed
failed creation or a separately observed local-knob exit; uncertain topology
blocks restoration until fresh state proves that reconnecting is safe.

An uncertain mixed Twin result exposes `recoverableKnobId` for the original
trusted knob. That exact knob may reconnect despite `connectionError`; a fresh
snapshot must confirm the original pair or independent controller before control
is restored. Cancelling that reconnect preserves the uncertainty block. Recovery
does not replay a Twin request, change resistance, or silently reconnect the peer.

The Android direct transport retries an early GATT establishment failure once,
after one second, within the original connection deadline. Only connection-status
133 or 0x3e before service discovery qualify. Authentication, subscription and
established-link failures are not retried; the knob transport retains its separate
session policy. Optional `VoltraGatt` debug logging records connection phase and
close reason without device addresses or packet contents.

Workouts should bind continuity to `state.telemetryEpochFor(controllerId, peerId)`;
omit `peerId` for a single-trainer set. The per-trainer revisions preserve source
changes across conflated snapshots, including disconnect/reconnect and mode or
topology changes, without invalidating a set when another independent trainer
finishes connecting. Removed identities retain revisions so an old connection
cannot be mistaken for a new one. The aggregate `telemetryEpoch` remains available;
custom connectors without per-trainer revisions use it as the compatibility fallback.

Call `controller.forget(deviceId)` to reset an app connection: it disconnects,
cancels pending settings/topology work and removes cached identity/readings. For a
native Twin member, both local trainer views disconnect; the trainers remain in
their existing hardware Twin mode. Forget never sends a motor or Twin-exit
command. Forget during peer rediscovery also cancels the delayed reconnect.
Android Bluetooth bonds remain managed by system Settings. The knob route can
forget its knob connection; relayed trainers are managed on the knob itself.

## Units and availability

- Parameter weights are integer **pounds**; eccentric is an added signed weight, not a percentage. `ResistanceLimits` uses the trainer-reported allowed-force ceiling for Weight and Band, retaining 200 lb when that report is unavailable. Base plus eccentric must remain between 5 lb and that ceiling. Isokinetic retains its separate limits. `weightUnit` and `lengthUnit` carry the trainer's display preferences for both direct and knob connections.
- `activeWeightLb` is commanded resistance, independent of `setWeightLb` and measured cable tension. Fresh independent unloaded reports (Firmware 2.0 state 0 or legacy state 4) produce zero with source `UNLOADED`. Loaded reports (1 or legacy 5/21) require a fresh device-reported target to produce `TARGET_FORCE`; unknown state or missing/expired target produces null. Base weight is never substituted.
- `resistanceTargetLb` preserves the fresh optional `0xb4` target. `measuredForceLb` separately carries the first B4 word or legacy `0xaa/0x81/0x2b` measured tension. `runtimeWireWeightLb` is the coarse `0x3e83` signed whole-pound measurement, read every 250 ms plus Bluetooth processing time. Residual measured tension can be nonzero while unloaded; neither measured source is active resistance. Direct readings expire after 1.5 seconds and clear on mode, load-state, or endpoint changes.
- B4 independent sources 0/1/2 have verified protocol fields for measured force and optional target in tenths of pounds. Their mapping is known from the Cortex decoder and captured frames; correspondence of target resistance to the trainer's display during chains/eccentric motion still requires physical validation. No target is inferred for layouts without it. Direct-TV native Twin readings remain withheld; classified knob-bridge pair readings are exposed separately on `TwinPair`.
- `cableMoving` classifies fresh loaded movement separately from resistance target. Direct connections use signed B4 velocity from the same sample as measured force: magnitude at least 10 mm/s is moving, less is stationary, and unknown/expired motion is null. The 10 mm/s deadband is display policy, not a vendor constant. Motion expires after 500 ms, resets with the force sample on mode/load/topology changes, and cannot be carried over to a legacy force-only sample. Bridge v2 bit 7 carries the knob's verified moving-force classification; older snapshots never imply movement. A display can show `measuredForceLb` only while `cableMoving == true`, otherwise show the mode's configured setting. Damper level and Isokinetic target speed are not weight values.
- Native Twin through the knob exposes combined `TwinPair.measuredForceLb` only with confirmed `cableMoving`, raw logical counts as `deviceReps`, and app-set-relative counts as `reps`. Weight Training force and five reps were verified on both displays; other modes and automated workout transitions remain pending physical acceptance.
- Mode/status and parameter freshness is checked separately. Stale state disables controls; after 10 seconds without status the connection fails. Unknown modes remain read-only.
- Battery is decoded from its register or validated endpoint status, never guessed from packet length.
- Speeds use mm/s. Isokinetic return speed `0` means Auto. Damper levels are zero-based; `Parameter.damperFactors` provides factors.
- Only pull-phase rep events count. Return events, duplicates and delayed older device sets are ignored. `beginSet` establishes a local baseline; if no prior device count exists, the first observed pull counts as one. A new device set resets that baseline. Apps should call `beginSet` after their countdown.
- Unknown, disconnected and stale readings must be shown as unavailable. Zero active resistance is reserved for a fresh explicit unloaded state or a device-reported zero target.
- Mode/modifier changes require unloaded state. The connector permits base weight changes in the verified loaded states. Explicit `setLoaded(deviceId, loaded)` requests require fresh controller identity, supported mode and advertised admission. Loading requires verified cable-zero readbacks; no offset bypass or motor retry is performed.

## Build and test

```sh
./gradlew :voltra-core:test :voltra-testing:test \
  :voltra-android:assembleRelease :voltra-android:lintDebug
```

Tests cover malformed/fragmented frames, CRCs, units, battery decoding, independent command routing, readback timeouts, stale readings, rep deduplication and demo Twin behavior. Tests do not validate radio behavior or compatibility with every firmware. CI runs the same checks. For cloud-synced workspaces, `voltra.buildRoot` in local.properties can redirect generated files outside the checkout.

Protocol attribution and pinned references: [THIRD_PARTY.md](THIRD_PARTY.md). License: [MIT](LICENSE).

### Mixed direct and knob connections

`MixedVoltraController(direct, knob, parentScope)` combines independent controllers
into one `VoltraController`. `selectRoute(VoltraConnectionRoute.DIRECT/KNOB)` selects
discovery and new connections without closing either owner. `state.routeFor(id)`
identifies the transport for each trainer; mode, parameter, disconnect and forget
operations follow that owner regardless of the discovery route. `currentState`
and `state.value` reflect synchronous child changes before collectors resume.

Up to two trainer identities are supported. Duplicate ownership (case-insensitive
identity) and unexpected over-capacity from knob changes surface `connectionError`
and block settings/Twin operations until resolved. Both inventories remain visible
so the caller can offer disconnect actions. Switching discovery never changes
physical ownership or creates a pair. An explicit mixed Create Twin action does
transfer the direct peer to the knob through the guarded native handoff below.
The UI should use per-trainer `routeFor`, not the aggregate `viaKnob` discovery
flag, for unit fallback, control descriptions and disconnect actions.

For mixed Twin, the knob-side trainer is always the native controller. The library
releases the direct peer, advertises its exact identity through the trusted bridge,
and requests native pairing only when the knob reports that exact authorized
candidate. The knob can perform the peer's read-only handshake while TV radio
coexistence keeps discovery paused; it never invents a recent advertisement.
Fresh identity and unloaded readbacks from both trainers are required before mode
or pairing writes. A successful pair is owned entirely by the knob, not emulated
by sending settings to two independent connections.

The optional `KnobTwinBridge` interface carries peer ownership/readiness offers,
explicit knob-button requests and their completion status. Offers alone never
pair. Busy or expired readiness disables Create while retaining the TV-owned peer
in the knob's device list. Epoch/session-bound request IDs suppress duplicate
notifications and reconnect replay. Pairing, exit and restoration are serialized;
settings and lifecycle changes are blocked while topology is changing.

A known failed handoff restores the former direct peer only after independent
state is confirmed. After native pairing, either TV Exit or a later verified local
knob Exit restores that same peer to the TV once. Changed controller identity,
loaded/stale/unknown state and ambiguous pairing outcomes block this recovery.
The app may choose a bounded retry policy after a safely restored failure; this
library does not blindly resend an uncertain native command. See
[the complete bridge extension](docs/knob-bridge.md#mixed-twin-offers-and-handoff).

### Explicit motor control

`setLoaded(deviceId, loaded)` is separate from set telemetry. Applications must obtain user authorization for automatic loading, confirm the selected exercise settings, and call it at the intended start boundary. A successful return requires fresh device confirmation. A fresh already-loaded or already-unloaded controller is a no-op. `beginSet`, `endSet`, reconnect and discovery never issue motor commands to real hardware. Native Twin accepts only its confirmed controller ID; mixed connections route to the unique owner without controlling the other trainer.

Direct loading sends the existing setup query (`0x506a` saved zero and `0x3e82` cable position), requires both new readbacks, rechecks the mode/endpoint, then sends GO once. Cable readiness uses the verified Cortex predicate `signed16(position - savedOffset) < 2`, with missing, stale and out-of-range readings rejected. It never writes a calibration offset. Unload sends STOP once. Only read-only polls follow either attempt; a timeout or cancellation never replays the motor write. The supported ready values are 4 and captured `0x0104`; loaded confirmation remains verified 5/21, and known armed `0x26`/`0x27` may be explicitly unloaded. Other fitness values are rejected.

The knob bridge must advertise `loadControlSupported` and current `loadAllowed` / `unloadAllowed` flags. Newer firmware can separately advertise `loadPreparationAllowed`: `canPrepareLoad` permits a request to refresh cable evidence before the worker checks whether GO is safe. It does not make `canLoad` true. The worker requires new cable/zero and unchanged configuration readbacks, then reports a confirmed result followed by fresh loaded status. Older firmware defaults to unsupported preparation. Automated tests cover confirmation, wrong/stale cable readings, changed identity/mode/session, native Twin controller routing and one-shot failure. **Physical automatic load/unload acceptance is still pending**, including the four modes and workout transitions.

### Explicit manual Twin preparation

`createTwinAfterUnloading(controllerId, peerId, stillRelevant)` and
`exitTwinAfterUnloading(stillRelevant)` are explicit user actions that confirm the
required unloads before calling the strict native Twin operation. The planning
predicates `canCreateTwinAfterUnloading` / `canExitTwinAfterUnloading` may enable
the action for fresh, safely unloadable trainers. The existing `canCreateTwin`,
`canExitTwin`, `createTwin` and `exitTwin` retain their unloaded-only guards.

Preparation validates every connected participant before the first STOP, checks
identity, route, epoch and mode between commands, and never sends GO. Independent
trainers are unloaded separately; an existing native pair is unloaded through its
verified controller. Missing confirmation or cancellation stops the operation
without retrying a motor command. The caller's `stillRelevant` predicate also
cancels waits if the screen or workflow changes.

`MixedVoltraController` reserves the whole preparation through native confirmation.
Observers see `twinPending` throughout, and unrelated mode, setting, motor, set-start
and Twin commands cannot interleave between STOPs. The operation's scoped owner
can run the required internal commands without exposing an unlocked public state.
The same path handles explicit mixed Create Twin requests from the knob; it never
automatically loads a trainer afterward. Hardware acceptance of these automatic
unload paths remains pending.
