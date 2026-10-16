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
never replayed. Classified native Twin telemetry is carried separately from the
physical members' readings; unclassified packets remain unavailable.
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
The mixed TV-button native Create/Exit cycle passed on October 16, and the user
reported a successful knob-button cycle. A loaded Weight Training capture verified
five native reps and both cable-force channels; the user confirmed live force and
reps on both the knob and TV after the update. Other training modes, direct-TV
native Twin telemetry and automatic workout transitions remain physically unverified.

The matching firmware pauses trainer discovery before advertising for the TV and
while the TV link remains active to avoid a reproduced ESP32 scheduler crash.
Saved candidates remain selectable after restart; they are verified through a
fresh trainer connection before any Twin command. A peer advertised as owned by
the connected TV is protected from local selection even while its readiness
offer expires or the TV is busy. Explicit mixed handoff uses authenticated identity
plus live GATT verification rather than re-enabling discovery. Disconnect the TV, then use
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
command 3, result 4, create Twin 5, exit Twin 6, peer offer 7, knob Twin request 8,
and request status 9. The Android reader also accepts v1
snapshots/results and writes v1 settings to older firmware; v1 has no Twin controls. Reject unsupported versions and malformed lengths.

Snapshot byte 3 flags are:

| Bit | Meaning |
| --- | --- |
| 0 | Selected trainer connected/ready |
| 1 | Fresh trainer status |
| 2 | Busy/pending operation |
| 3 | Verified endpoint topology known |
| 4 | Endpoint reports native Twin |
| 5 (`0x20`) | Exact-peer CREATE_TWIN identity guard supported |
| 6 (`0x40`) | Mixed ownership/offer/request extension supported |
| 7 (`0x80`) | This snapshot's candidate is an authorized TV handoff peer |

These capability/candidate flags are independent of weight flags at byte 133.
Snapshot uint32 connection
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
with bits 4, 6 and 7 clear, offset 22 is legacy measured cable force. These values
populate `runtimeWireWeightLb` or `measuredForceLb`, never active resistance.
Bit 6 identifies target resistance at offset 22. Bits 4, 6 and 7 are mutually
exclusive. Fresh fitness 4 gives active weight zero with source `UNLOADED`;
fresh fitness 5/21 permits a nonnegative bit-6 reading as `resistanceTargetLb`
and `activeWeightLb`, source `TARGET_FORCE`. Missing target, unknown state,
stale readings, or native Twin produce no active resistance. New firmware sets
bit 6 for explicit unloaded zero as well. Older firmware stays readable without
mislabeling its measured tension. No source is replaced by configured base weight.
The B4 optional target's protocol mapping is verified; its correspondence to the
trainer's displayed resistance under modifiers still needs physical validation.
Bit 7 identifies measured cable force during confirmed loaded movement at offset
22. It sets `measuredForceLb` and `cableMoving = true` only with fresh independent
identity, a known mode and loaded fitness 5/21; it never creates a target reading.
Without bit 7, motion is unknown, including older force-only snapshots. The knob
prioritizes this moving sample, otherwise publishes its existing target/unloaded
reading. Source values are never combined. The reader clears movement after
500 ms without a snapshot, independently of the 2-second bridge status timeout.
The version and packet length remain unchanged; older readers see bit-7 data as
measured force and do not mistake it for a resistance target.

For a fresh, verified native pair, bit 7 instead identifies a classified combined
force sample at offset 22. The new reader exposes this as
`TwinPair.measuredForceLb` and `cableMoving`, never as either physical member's
force. Firmware sums only two explicit force channels from the same recognized
runtime packet, with movement from either channel; it never doubles one cable.
Offset 26 carries the controller's logical raw count as `TwinPair.deviceReps`;
`TwinPair.reps` applies the app's set baseline. Unknown counts remain 65535 on the
wire. Topology/mode changes clear firmware counts until a new classified report;
ordinary unloaded display can retain the last count while device status is fresh.
Old v2 readers already suppress all paired telemetry and safely ignore these
values without a new packet type or changed length. The new reader continues to
accept older firmware's unavailable sentinels. Independent trainer telemetry and
native direct-TV decoding are unchanged.

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

Commands are 24 bytes: flags at 3 (zero except the extensions below); epoch at 4; trainer session at 8;
expiry in knob uptime at 12; uint16 strictly increasing request ID at 16;
register at 18 (zero for mode); int32 requested value at 20. Native Twin commands
use zero bytes 18..23 on legacy firmware; guarded creation puts the exact peer MAC
there. Non-offer IDs start at 1 per
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

## Mixed Twin offers and handoff

`KnobVoltraController` additionally implements optional `KnobTwinBridge`:

```kotlin
val twinRequests: Flow<KnobTwinRequest>
suspend fun offerTwinPeer(peer: VoltraDevice?, ready: Boolean = true)
suspend fun reportTwinRequest(request: KnobTwinRequest, status: KnobTwinRequestStatus)
```

`MixedVoltraController` announces a directly owned peer every 750 ms. Ownership
and readiness are separate: a peer with `ready = false` stays visible as connected
to the TV, while Create remains disabled. Expiry revokes authorization to start pairing,
not ownership. Only an explicit empty offer or a changed/disconnected authenticated
TV/trainer session clears ownership. While a handoff is executing, the released
peer's exact offer remains refreshed until the transaction completes.

All offer/status commands still require the current encrypted, authenticated TV
connection. Their framing is:

| Type | Size | Specific fields |
| --- | --- | --- |
| 7, peer offer | 24 | Byte 3 bit 0 = ready; epoch/session/expiry at 4/8/12; ID at 16 **must be zero**; MAC bytes at 18..23, all zero to clear ownership |
| 8, knob request notification | 20 | Byte 3 zero; epoch/session at 4/8; uint16 request ID at 12; exact peer MAC at 14..19 |
| 9, TV request status | 24 | Byte 3 zero; epoch/session/expiry at 4/8/12; normal increasing command ID at 16; uint16 status at 18 (1 accepted, 2 succeeded, 3 failed); uint32 referenced knob request ID at 20 |

MAC bytes are in textual address order, not reversed or treated as an integer.
An offer defaults to 1.5 seconds of lifetime; firmware accepts only a positive
remaining lifetime of at most two seconds. Each accepted deadline must be strictly
newer than the previous offer deadline in the same epoch/session, using signed
uint32 differences so the uptime wrap works. Duplicates, stale offers and distant
future deadlines cannot refresh authorization. ID zero offers do not consume or
advance the finite motor-command sequence; a long-lived 750 ms heartbeat therefore
does not exhaust its 65,535 IDs. Offers serialize BLE writes, but do not wait for a
native Twin operation to finish before refreshing its authorization.

Pressing Create on the knob produces a session-bound request, not a trainer
command. Unacknowledged requests repeat every 750 ms for up to eight seconds;
accepted requests have a bounded 90-second UI wait. The TV validates current
capability, exact offered peer, ownership and readiness, then handles each
(epoch, session, request ID) once. Duplicates and old notifications do not retry
pairing; a new button action is needed after failure. Reconnection/session changes
clear in-flight UI state. Success requires the exact fresh native pair, not an
accepted request status or a successful BLE write.

For type 5, snapshot capability bit 5 enables the expected peer MAC in bytes
18..23. Snapshot bit 7 additionally requires command byte 3 bit 0 = 1, meaning
**explicit trusted handoff**, plus capability bit 6. A handoff without an exact
MAC is rejected. Type 6 exit keeps flags and bytes 18..23 zero. Standalone native
creation leaves byte 3 zero and retains its unambiguous, recently advertised peer
gate. Old all-zero type-5 requests keep legacy behavior; mixed orchestration must
not be used with firmware lacking the guarded capabilities.

The handoff worker checks the queued exact identity, fresh authorization and
revision again before execution. Once admitted, the attempt retains that revision
for its fixed 40-second transaction deadline; delayed heartbeat delivery cannot
cancel its read-only handshake. Explicitly changing ownership/readiness, TV epoch,
selected trainer/session or the expected peer still cancels it. A heartbeat cannot
extend the transaction deadline or revive a revoked revision. Cached saved/advertised metadata
may provide the BLE address type, but never substitutes for nearby evidence. For
an unknown address type, the read-only handshake may try public then random for
that **same MAC**. The live endpoint identity, independent topology and unloaded
state of both trainers must be confirmed before any mode or native pairing write.
No scanning is resumed, no nearby timestamp is fabricated, and the standalone
arbitrary-peer discovery rule is unchanged. A new peer is saved only after its
native pair is verified.

After handoff, both logical trainer routes are `KNOB`; all shared settings go to
the native knob controller. A confirmed failure may restore the released direct
peer; uncertain or still-pending topology cannot. TV exit or a later separately
verified local-knob exit restores the former direct peer once, after fresh
independent state, under the same topology lock. A changed controller, stale or
loaded state cannot trigger that restoration.

## Validation boundary

JVM tests cover decoding, malformed inputs, confirmation vs readback, staleness,
capability restrictions, trainer-session changes, disconnect and no replay. The
firmware has host tests for command framing, expiry including uptime wraparound,
session/ID rejection, exact-peer handoff gates while scanning is paused, offer
replay/expiry/uptime wrap, and rendered ownership/readiness states. Android assembly and
firmware compilation do not establish radio or physical trainer compatibility.

Physical Google TV/knob validation on October 13 confirmed trusted reconnect after
a knob reboot, a cold app restart and repeated manual disconnect/reconnect without
a new PIN or pairing window. Trainer identity and battery readings resumed. The
Android transport gates MTU, discovery and subscription callbacks once per GATT
connection; regression tests cover duplicate setup callbacks and callbacks after
closure. October 16 testing also verified mixed native Twin creation/exit from
both buttons and Weight Training live force plus five reps through the knob on
both displays. These checks do not establish extended stability or every training
mode.

Mixed handoff has automated coverage for direct release, exact candidate identity,
ownership updates, request deduplication, readiness revocation, offer refresh during
native commands, safe failure restoration and one-time recovery after local knob
exit. A simulated end-to-end workout also exercises topology transitions.
Automatic workout transitions, failure recovery across real device interruptions,
other training modes and direct-TV native Twin telemetry still require separate
hardware acceptance; automated checks and successful builds do not establish it.
