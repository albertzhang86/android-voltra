# android-voltra

UI-free Kotlin/JVM protocol and Android BLE connector for Beyond Power VOLTRA trainers.

**Experimental.** The implementation is covered by protocol and simulated-transport tests; physical Google TV/VOLTRA validation is still pending. This is an independent community project, not an official Beyond Power SDK.

## Modules

| Module | Responsibility |
| --- | --- |
| `voltra-core` | Models, controller/transport interfaces, frame reassembly, CRC validation, command confirmation and rep tracking. No Android dependencies. |
| `voltra-android` | Android discovery, permissions checks and a separate GATT connection per trainer. No views or permission prompts. |
| `voltra-testing` | Explicit two-device demo controller for app development without Bluetooth hardware. Never an automatic fallback. |

The direct connector supports up to two independent connections, battery, live cable force, per-device reps, and four modes: Weight Training, Resistance Band, Damper and Isokinetic. Settings remain device-confirmed: a successful Bluetooth write alone does not update the displayed value. Connecting, starting an app set and reconnecting never change resistance or load the motor.

### Bluetooth knob bridge

`KnobVoltraController` and `AndroidKnobTransport` connect through the matching
Guition VOLTRA Remote firmware. The knob owns one selected trainer; the TV receives
readings and sends validated, expiring setting commands. Native Twin and motor
load/unload are not exposed through this bridge. See [protocol and pairing](docs/knob-bridge.md).
The JVM/demo checks and firmware build do not replace physical pairing/coexistence tests.

### Twin status

**Native Twin creation/exit and Twin telemetry are not implemented.** The verified public protocol sources used here do not provide the native pair/unpair exchange or validated role/peer verification. The direct connector reports `twinSupported = false`; its Twin methods fail without sending control commands. A trainer already reporting Twin mode is read-only, and independent force/rep decoding is withheld for that device. Two connections alone never imply a Twin pair.

The demo implements Twin UI semantics only: shared settings, two batteries, per-side force readings, and one selected controller's rep count. It is not evidence that physical Twin pairing works. A future connector can implement the full `VoltraController` interface after native pairing and telemetry are verified. See [CONTRIBUTING.md](CONTRIBUTING.md).

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

Use a long-lived owner scope when the app displays a workout overlay. A dead app process closes its connections; applications must reconnect explicitly and read back settings. This connector does not replay commands or automatically reconnect.

## Units and availability

- Parameter weights are integer **pounds**; eccentric is an added signed weight, not a percentage. Base plus eccentric must be 5–200 lb.
- Live `activeWeightLb` is cable force decoded from tenths of a pound. It is independent from `setWeightLb` and becomes `null` after 1.5 seconds without a force sample.
- Mode/status and parameter freshness is checked separately. Stale state disables controls; after 10 seconds without status the connection fails. Unknown modes remain read-only.
- Battery is decoded only from its register, never guessed from packet length.
- Speeds use mm/s. Isokinetic return speed `0` means Auto. Damper levels are zero-based; `Parameter.damperFactors` provides factors.
- Only pull-phase rep events count. Return events, duplicates and delayed older device sets are ignored. `beginSet` establishes a local baseline; if no prior device count exists, the first observed pull counts as one. A new device set resets that baseline. Apps should call `beginSet` after their countdown.
- Unknown, disconnected and stale readings must be shown as unavailable, not as zero or as the target weight.
- Mode/modifier changes require unloaded state. The connector permits base weight changes in the verified loaded states. Load/unload motor commands are deliberately not exposed in this version.

## Build and test

```sh
./gradlew :voltra-core:test :voltra-testing:test \
  :voltra-android:assembleRelease :voltra-android:lintDebug
```

Tests cover malformed/fragmented frames, CRCs, units, battery decoding, independent command routing, readback timeouts, stale readings, rep deduplication and demo Twin behavior. Tests do not validate radio behavior or compatibility with every firmware. CI runs the same checks. For cloud-synced workspaces, `voltra.buildRoot` in local.properties can redirect generated files outside the checkout.

Protocol attribution and pinned references: [THIRD_PARTY.md](THIRD_PARTY.md). License: [MIT](LICENSE).
