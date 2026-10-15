# android-voltra

UI-free Kotlin/JVM protocol and Android BLE connector for Beyond Power VOLTRA trainers.

**Experimental.** The implementation is covered by protocol and simulated-transport tests; native discovery, two connections, Twin creation/exit and peer reconnection have been verified on a Google TV Streamer with two unloaded trainers. Live workout telemetry and loaded controls still need hardware validation. This is an independent community project, not an official Beyond Power SDK.

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
route, close Android's direct controller first; Android connects to the knob's
bridge while the knob owns the trainer connection. Release the connection in the
previous client explicitly—closing its window may leave Bluetooth connected.
An occupied connection can cause discovery/connection failures; a timeout alone
does not prove this cause. Native Twin is reached through its verified controller,
with the peer represented logically rather than connected independently.

### Bluetooth knob bridge

`KnobVoltraController` and `AndroidKnobTransport` connect through the matching
Guition VOLTRA Remote firmware. The knob owns one selected trainer; the TV receives
readings and sends validated, expiring setting commands. Bridge v2 adds native
Twin creation/exit and verified controller/peer identities. Motor load/unload stays
on the knob. See [protocol and pairing](docs/knob-bridge.md).
The JVM/demo checks and firmware build do not replace physical pairing/coexistence tests.

### Twin status

The direct connector implements native Twin creation/exit using endpoint status
(`0xa7`) and correlated commands (`0xa8`). The future peer receives the future
controller's address. Success requires both the acknowledgment and matching fresh
endpoint identities. A paired controller exposes both trainers through one BLE
connection. Exit confirms independent state, waits for the peer to advertise, then
reconnects it; a missing peer is reported without replaying unpair.

A Google TV Streamer pair/exit cycle passed with both trainers unloaded and in
idle mode. Shared mode/settings commands are implemented but not physically
validated under load. **Native Twin live force and reps remain unavailable**:
no unclassified single-trainer reading is doubled or substituted into pair data.
Knob v2 supports the same topology and commands. Physical TV/knob PIN pairing and
authenticated bridge telemetry pass; native Twin over the bridge remains unverified.
Knob pair/exit currently requires idle mode on both trainers.

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
}
controller.beginSet() // Local rep boundary; no motor command.
// On permanent disposal, on the owner dispatcher:
controller.close()
scope.cancel()
```

Use a long-lived owner scope when the app displays a workout overlay. A dead app process closes its connections; applications must reconnect explicitly and read back settings. This connector does not replay commands. The explicit Exit Twin flow is the only
automatic reconnect path: it restores the now-independent peer.

Call `controller.forget(deviceId)` to reset an app connection: it disconnects,
cancels pending settings/topology work and removes cached identity/readings. For a
native Twin member, both local trainer views disconnect; the trainers remain in
their existing hardware Twin mode. Forget never sends a motor or Twin-exit
command. Forget during peer rediscovery also cancels the delayed reconnect.
Android Bluetooth bonds remain managed by system Settings. The knob route can
forget its knob connection; relayed trainers are managed on the knob itself.

## Units and availability

- Parameter weights are integer **pounds**; eccentric is an added signed weight, not a percentage. Base plus eccentric must be 5–200 lb.
- `activeWeightLb` is commanded resistance, independent of `setWeightLb` and measured cable tension. Fresh independent fitness state 4 produces zero with source `UNLOADED`. In loaded states 5/21, only a fresh device-reported target produces a value with source `TARGET_FORCE`; unknown state or missing/expired target produces null. Base weight is never substituted.
- `resistanceTargetLb` preserves the fresh optional `0xb4` target. `measuredForceLb` separately carries the first B4 word or legacy `0xaa/0x81/0x2b` measured tension. `runtimeWireWeightLb` is the coarse `0x3e83` signed whole-pound measurement, read every 250 ms plus Bluetooth processing time. Residual measured tension can be nonzero while unloaded; neither measured source is active resistance. Direct readings expire after 1.5 seconds and clear on mode, load-state, or endpoint changes.
- B4 independent sources 0/1/2 have verified protocol fields for measured force and optional target in tenths of pounds. Their mapping is known from the Cortex decoder and captured frames; correspondence of target resistance to the trainer's display during chains/eccentric motion still requires physical validation. No target is inferred for layouts without it. Native Twin readings remain withheld.
- `cableMoving` classifies fresh loaded movement separately from resistance target. Direct connections use signed B4 velocity from the same sample as measured force: magnitude at least 10 mm/s is moving, less is stationary, and unknown/expired motion is null. The 10 mm/s deadband is display policy, not a vendor constant. Motion expires after 500 ms, resets with the force sample on mode/load/topology changes, and cannot be carried over to a legacy force-only sample. Bridge v2 bit 7 carries the knob's verified moving-force classification; older snapshots never imply movement. A display can show `measuredForceLb` only while `cableMoving == true`, otherwise show the mode's configured setting. Damper level and Isokinetic target speed are not weight values.
- Mode/status and parameter freshness is checked separately. Stale state disables controls; after 10 seconds without status the connection fails. Unknown modes remain read-only.
- Battery is decoded from its register or validated endpoint status, never guessed from packet length.
- Speeds use mm/s. Isokinetic return speed `0` means Auto. Damper levels are zero-based; `Parameter.damperFactors` provides factors.
- Only pull-phase rep events count. Return events, duplicates and delayed older device sets are ignored. `beginSet` establishes a local baseline; if no prior device count exists, the first observed pull counts as one. A new device set resets that baseline. Apps should call `beginSet` after their countdown.
- Unknown, disconnected and stale readings must be shown as unavailable. Zero active resistance is reserved for a fresh explicit unloaded state or a device-reported zero target.
- Mode/modifier changes require unloaded state. The connector permits base weight changes in the verified loaded states. Load/unload motor commands are deliberately not exposed in this version.

## Build and test

```sh
./gradlew :voltra-core:test :voltra-testing:test \
  :voltra-android:assembleRelease :voltra-android:lintDebug
```

Tests cover malformed/fragmented frames, CRCs, units, battery decoding, independent command routing, readback timeouts, stale readings, rep deduplication and demo Twin behavior. Tests do not validate radio behavior or compatibility with every firmware. CI runs the same checks. For cloud-synced workspaces, `voltra.buildRoot` in local.properties can redirect generated files outside the checkout.

Protocol attribution and pinned references: [THIRD_PARTY.md](THIRD_PARTY.md). License: [MIT](LICENSE).
