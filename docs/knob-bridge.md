# Bluetooth knob bridge v2

`KnobVoltraController` implements `VoltraController`. `AndroidKnobTransport` connects
to the Guition VOLTRA Remote firmware's GATT server; the knob remains the trainer's
BLE client. The TV never opens a competing direct trainer connection in this route.
The implementation requires the matching bridge firmware, not the stock knob app.

The knob exposes its selected trainer, or a verified native Twin with both batteries
and explicit controller/peer identities. A discovered second trainer is a pairing
candidate, not a connected snapshot. Pairing opens a temporary second GATT session,
verifies both trainers are idle/unloaded, sends the controller address to the peer,
and waits for matching endpoint status. Exit stays on the controller. Commands are
never replayed. Native Twin force/reps are withheld pending verification.
Load, unload and trainer selection remain local to the knob.

## Pairing

For a new TV, open **TV link → Pair TV** on the knob, then discover it from the TV.
New pairing is available for two minutes, accepts one TV, and requires authenticated, encrypted
Bluetooth pairing. Enter the six-digit code shown on the knob in Android's system
pairing prompt. Bond storage is handled by each Bluetooth stack. A trusted TV can
reconnect without reopening pairing, including after a knob restart. Firmware
advertises for trusted reconnection and verifies the authenticated Secure Connections
identity; outside explicit pairing, new peers and replacement bonds are rejected.
Reconnection does not replay setting commands. Android remembers the knob after a
valid snapshot and restores that target only if its Bluetooth bond still exists.
Telemetry and commands are withheld before authentication. Disconnect TV is
available on either device. PIN pairing, authenticated encryption and v2 bridge
telemetry have been verified on a physical Google TV Streamer, including receiving
the selected trainer's battery, mode and set weight while both links are active.
Native Twin and TV-issued setting changes through the bridge still need hardware validation.

The matching firmware pauses trainer discovery before advertising for the TV and
while the TV link remains active to avoid a reproduced ESP32 scheduler crash.
Saved candidates remain selectable after restart; they are verified through a
fresh trainer connection before any Twin command. Disconnect the TV, then use
**TV link → Find trainers** on the knob for a 30-second discovery window.

After failure, `connect()` can retry without a separate disconnect call. Old
trainer/Twin state is cleared, a fresh bridge epoch is required, and commands are
never replayed. Transport timeouts become a recoverable failed state; cancellation
removes the pending connection. Android transport errors identify the knob rather
than reporting a direct VOLTRA failure.

`forget(knobId)` disconnects the bridge, cancels pending/queued commands, clears all
relayed readings and drops the saved reconnect target. Pass
`onForgotten = transport::forgetDevice` alongside
`onConnected = transport::rememberDevice` when constructing the controller with
`AndroidKnobTransport`. Late snapshots from that connection cannot restore the
forgotten target. Trainers relayed by the knob must be managed on the knob;
`forget(trainerId)` rejects that route rather than silently forgetting the knob.

Forgetting in the TV app does not remove Android's Bluetooth bond or the knob's
trusted-TV entry. Use Android Bluetooth settings to remove the system pairing,
and the knob's own trusted-TV controls when resetting trust on both sides.

## GATT and wire format

All multibyte integers are little-endian. These are project-owned UUIDs, not vendor
trainer UUIDs. Minimum negotiated MTU is 247; one notification carries one packet.

| Endpoint | UUID |
| --- | --- |
| Service | `aed90001-4174-4ab6-a910-72457c592900` |
| Command (write with response) | `aed90002-4174-4ab6-a910-72457c592900` |
| Updates (notify) | `aed90003-4174-4ab6-a910-72457c592900` |

Every packet begins `4b 02 type`. Snapshot type is 1, mode command 2, parameter
command 3, result 4, create Twin 5, exit Twin 6. The Android reader also accepts v1
snapshots/results and writes v1 settings to older firmware; v1 has no Twin controls. Reject unsupported versions and malformed lengths.

Snapshot: byte 3 flags (ready/fresh/busy/Twin-known/Twin-reported); uint32 connection
epoch at 4, trainer session at 8, knob uptime at 12; slot at 16 (255 if none); knob
battery at 17 and trainer battery at 18 (255 unknown); mode at 19; uint16 fitness
state at 20 (65535 unknown); int32 classified weight reading in tenths lb at 22 (-1 unknown);
uint16 cumulative reps at 26 (65535 unknown); editable-parameter bitmask at 28;
mode-edit flag at 30; parameter count at 31; zero-terminated UTF-8 name in 32..63;
ASCII trainer address in 64..81. Peer/candidate name occupies 82..113 and address
114..131; battery at 132 (255 unknown); flags at 133 (can-create bit 0, can-exit bit 1).
Bits 2..3 are an optional display-unit extension: 0 unknown/older firmware, 1 lb,
2 kg, 3 reserved/unknown. The knob reports its displayed unit (Weight Training
uses kg; other modes follow its reported unit setting). These flags do not change
the raw parameter or force units. Older readers ignore them and packet lengths
remain unchanged. `TrainerState.weightUnit` exposes the preference to consumers.
Bit 4 of byte 133 identifies legacy runtime wire-weight telemetry (`0x3e83`);
with bits 4 and 6 clear, offset 22 is legacy measured cable force. These values
populate `runtimeWireWeightLb` or `measuredForceLb`, never active resistance.
Bit 6 identifies target resistance at offset 22. Bits 4 and 6 are mutually
exclusive. Fresh fitness 4 gives active weight zero with source `UNLOADED`;
fresh fitness 5/21 permits a nonnegative bit-6 reading as `resistanceTargetLb`
and `activeWeightLb`, source `TARGET_FORCE`. Missing target, unknown state,
stale readings, or native Twin produce no active resistance. New firmware sets
bit 6 for explicit unloaded zero as well. Older firmware stays readable without
mislabeling its measured tension. No source is replaced by configured base weight.
The B4 optional target's protocol mapping is verified; its correspondence to the
trainer's displayed resistance under modifiers still needs physical validation.
Bit 5 indicates detected USB-host power for the knob, independently of trainer
selection or battery percentage. `KnobState.usbPowerConnected` exposes it. A
lightning battery icon can replace an unavailable percentage, but this flag does
not prove active charging or a full battery. It is absent on older firmware and
cleared when bridge readings expire. The Guition board has no exposed charging
status input: its current detection uses USB SOF traffic, so a charge-only wall
adapter or wireless charger cannot be identified reliably.
Peer identity in paired status is from the controller endpoint report. Each of up
to 11 entries at 134 has uint16 register
and int32 value. Parameter bit order is Weight, Chains, Inverse Chains, Eccentric,
Band Maximum, Damper Level, Speed, Return Type, Return Speed, Return Weight, Limit.
Parameter values use the library's protocol units. Snapshots publish at 4 Hz.

Commands are 24 bytes: reserved zero at 3; epoch at 4; trainer session at 8;
expiry in knob uptime at 12; uint16 strictly increasing request ID at 16;
register at 18 (zero for mode/Twin); int32 requested value at 20 (zero for Twin). IDs start at 1 per
connection. The knob accepts only the current authenticated connection/session,
at most two seconds remaining, and valid state/mode/range. It rechecks expiry and
connection ownership before trainer writes. Twin requests must start within that
expiry; their bounded handshake/confirmation can take 40 seconds, rechecking the
authenticated epoch and selected trainer session throughout. Local knob edits retain their gates.

Results are 16 bytes: code at 3 (0 queued, 1 device-confirmed, 2 rejected, 3 timeout),
epoch/session at 4/8, request ID at 12, reserved zero at 14. Queued is not success.
The TV waits for confirmation plus matching fresh snapshot readback. A five-second
TV timeout (45 seconds for Twin) reports uncertainty; there is no automatic retry. Unknown/stale target resistance
is never replaced by measured force or configured weight in the connector. Consumers
can display the configured setpoint separately with its own label.
A two-second notification gap disables
TV commands and clears live force/reps. Rep baselines are local to app sets.

## Validation boundary

JVM tests cover decoding, malformed inputs, confirmation vs readback, staleness,
capability restrictions, trainer-session changes, disconnect and no replay. The
firmware has host tests for command framing, expiry including uptime wraparound,
session/ID rejection, and a rendered pairing-page test. Android assembly and
firmware compilation do not establish radio or physical trainer compatibility.

Physical Google TV/knob validation on October 13 confirmed trusted reconnect after
a knob reboot, a cold app restart and repeated manual disconnect/reconnect without
a new PIN or pairing window. Trainer identity and battery readings resumed. The
Android transport gates MTU, discovery and subscription callbacks once per GATT
connection; regression tests cover duplicate setup callbacks and callbacks after
closure. Extended stability and native Twin through the knob remain separate
physical validation tasks.
