# Bluetooth knob bridge v1

`KnobVoltraController` implements `VoltraController`. `AndroidKnobTransport` connects
to the Guition VOLTRA Remote firmware's GATT server; the knob remains the trainer's
BLE client. The TV never opens a competing direct trainer connection in this route.
The implementation requires the matching bridge firmware, not the stock knob app.

One knob exposes its one selected trainer. Changing selection on the knob replaces
that trainer snapshot and invalidates in-flight TV commands. Native Twin remains
read-only because controller/peer roles and Twin telemetry are not verified here.
There are no load, unload, trainer-selection or native Twin commands in this protocol.

## Pairing

Open **TV link → Pair TV** on the knob, then discover it from the TV. The firmware
advertises for two minutes, accepts one TV, and requires authenticated, encrypted
Bluetooth pairing. Enter the six-digit code shown on the knob in Android's system
pairing prompt. Bond storage is handled by each Bluetooth stack. Reconnection
requires reopening TV pairing on the knob; it does not replay setting commands.
Telemetry and commands are withheld before authentication. Disconnect TV is
available on either device. Physical TV/knob pairing remains unverified.

## GATT and wire format

All multibyte integers are little-endian. These are project-owned UUIDs, not vendor
trainer UUIDs. Minimum negotiated MTU is 185; one notification carries one packet.

| Endpoint | UUID |
| --- | --- |
| Service | `aed90001-4174-4ab6-a910-72457c592900` |
| Command (write with response) | `aed90002-4174-4ab6-a910-72457c592900` |
| Updates (notify) | `aed90003-4174-4ab6-a910-72457c592900` |

Every packet begins `4b 01 type`. Snapshot type is 1, mode command 2, parameter
command 3, result 4. Reject unsupported versions and malformed lengths.

Snapshot: byte 3 flags (ready/fresh/busy/Twin-known/Twin-reported); uint32 connection
epoch at 4, trainer session at 8, knob uptime at 12; slot at 16 (255 if none); knob
battery at 17 and trainer battery at 18 (255 unknown); mode at 19; uint16 fitness
state at 20 (65535 unknown); int32 live force in tenths lb at 22 (-1 unknown);
uint16 cumulative reps at 26 (65535 unknown); editable-parameter bitmask at 28;
mode-edit flag at 30; parameter count at 31; zero-terminated UTF-8 name in 32..63;
ASCII trainer address in 64..81. Each of up to 11 entries at 82 has uint16 register
and int32 value. Parameter bit order is Weight, Chains, Inverse Chains, Eccentric,
Band Maximum, Damper Level, Speed, Return Type, Return Speed, Return Weight, Limit.
Parameter values use the library's protocol units. Snapshots publish at 4 Hz.

Commands are 24 bytes: reserved zero at 3; epoch at 4; trainer session at 8;
expiry in knob uptime at 12; uint16 strictly increasing request ID at 16;
register at 18 (zero for mode); int32 requested value at 20. IDs start at 1 per
connection. The knob accepts only the current authenticated connection/session,
at most two seconds remaining, and valid state/mode/range. It rechecks expiry and
connection ownership before trainer writes. Local knob edits retain their gates.

Results are 16 bytes: code at 3 (0 queued, 1 device-confirmed, 2 rejected, 3 timeout),
epoch/session at 4/8, request ID at 12, reserved zero at 14. Queued is not success.
The TV waits for confirmation plus matching fresh snapshot readback. A five-second
TV timeout reports uncertainty; there is no automatic retry. Unknown/stale force
is never replaced by configured weight. A two-second notification gap disables
TV commands and clears live force/reps. Rep baselines are local to app sets.

## Validation boundary

JVM tests cover decoding, malformed inputs, confirmation vs readback, staleness,
capability restrictions, trainer-session changes, disconnect and no replay. The
firmware has host tests for command framing, expiry including uptime wraparound,
session/ID rejection, and a rendered pairing-page test. Android assembly and
firmware compilation do not establish radio or physical trainer compatibility.
