# Protocol sources and licenses

This Kotlin implementation adapts the public frame format, checksums, initialization frames, register identifiers and telemetry decoding from these MIT-licensed projects:

- [HJewkes/voltra-node-sdk](https://github.com/HJewkes/voltra-node-sdk), revision `1d47cb29a90912284c383fabb684981b508f5f54`. References include `src/voltra/protocol/telemetry-decoder.ts`, the protocol catalog and decoder tests. Copyright 2026 Henry Jewkes. [License](third_party/voltra-node-sdk-LICENSE).
- [albertzhang86/voltra-remote](https://github.com/albertzhang86/voltra-remote), revision `d7c700d67466344570ba9b177b51fd4cefbdf2bd`, derived from Omar Shahine's VOLTRA knob project. References include `firmware/VoltraKnob/src/protocol`, `src/link`, and `third_party/voltra-protocol.json`. Copyright 2026 Omar Shahine; local adaptations by Albert Zhang. [License](third_party/voltra-knob-LICENSE).

The newer SDK decoder corrects legacy battery offsets: 35/52-byte status messages are not battery readings. The connector reads battery register `0x4e2d` or a validated `0xa7` endpoint message. Device set and per-rep fields are both 16-bit. For `0xaa/0x82/0x3b`, a physical eight-rep trace established phase-specific normalization: pull reports 0, return reports 1 for the first rep; the eighth pull reports 7 and its return and final summary report 8. Only the pull index receives +1. Return counts and summary totals remain unchanged, and matching pull/return counts are deduplicated. The SDK's captured first-pull packet and matching one-rep set summary independently support the pull-index/summary distinction. Cortex names the per-rep field `repIndex`; the hardware trace establishes the phase-specific behavior. Tests use synthetic frames for that sequence; private trainer identities are not included.

Beyond Power's [Cortex Twin documentation](https://cortex.beyond-power.com/docs/cortex/twin) describes official CLI pair/unpair operations. It does not establish a raw Android BLE implementation here. No Cortex binaries, private captures, credentials, trainer addresses or private vendor documentation are included.

Native Twin `0xa7` endpoint layouts and `0xa8` pair/exit direction, identities,
acknowledgments and state transitions were verified with two unloaded trainers.
Tests use synthetic addresses; full private bench logs are not distributed. No
third-party Twin implementation is copied into this connector.

Additional public protocol facts were cross-checked against
[dylanmaniatakes/Beyond-Power-HomeAssistant](https://github.com/dylanmaniatakes/Beyond-Power-HomeAssistant/blob/93b0819745e665ab4d35f4cc501d48c6296c81f7/custom_components/voltra/protocol.py),
revision `93b0819745e665ab4d35f4cc501d48c6296c81f7`: runtime wire weight is signed
16-bit whole pounds at register `0x3e83`, distinct from configured base weight
`0x3e86`; the 58-byte live-force family is command `0xaa`, subtype `0x81/0x2b`,
with force at frame byte 22 in tenths of pounds. No implementation code from that
repository is copied here. The runtime read path and its physical behavior still
require validation on each supported firmware.

Independent `0xb4` terse-runtime protocol facts were verified against Cortex
0.2.26's frame decoder and diagnostic field serializer, then against trainer
telemetry: payload word 0 is `forceTenthLbs`, word 1 is `cableLengthMm` (uint16),
word 2 is `velocityMmPerSecond` (int16). The same-sample force/velocity pair is
used to classify loaded movement; the 10 mm/s deadband and 500 ms expiry are our
display policy, not vendor-provided constants. Source 0 carries 6 or 8 payload bytes; sources 1 and 2 carry
10 or 12. The optional trailing word is `targetForceTenthLbs`, separate from measured
force. It is exposed as resistance target; its correspondence to the trainer
display under load and modifiers remains pending physical validation. The decoder accepts only those independent layouts and does not interpret
native Twin source values `0x10`/`0x11`/`0x12`. Tests include four telemetry-only
fixture frames without trainer identities. No Cortex implementation or binary
is redistributed. Runtime register readback was also verified unloaded.

Firmware 2.0 compatibility capture (VTR-161230, 2026-10-28): its settings cascade
reports mode `1` and motor state `0` while the device displays Weight Training
controls. The exact CRC-valid packet is a `ProtocolTest` fixture. One-byte
widths for read-only fields `0x5102`, `0x4ee1`, and `0x5124` were cross-checked
against the protocol catalogue distributed in `@voltras/node-sdk` 0.15.0
([upstream](https://github.com/HJewkes/voltra-node-sdk)). Their semantics are
unknown; no commands are added for them. Mode confirmation is independent of
motor readiness in both direct and knob transports.

A subsequent native load/unload test on the same trainer captured register
`0x3e89 = 1` while loaded and `0` while unloaded. CRC-valid fixtures are in
`ProtocolTest`; raw words are preserved. The explicit motor-word policy is shared
by direct control, bridge control, and telemetry. It recognizes only observed
values; it does not infer arbitrary flag combinations or identify firmware by
seeing a zero. Selected mode, topology, cable zero, settings and confirmation
are checked separately. Commands use the observed `0/1` pair or the existing
legacy `4/5` family. Physical acceptance of the new writes remains pending;
simulated readbacks are not hardware verification. 230 lb support and new
modifier semantics are not inferred from these captures.

2026-10-28 hardware acceptance: user confirmed knob taps load and unload
VTR-161230 in Weight Training after both updates. USB capture confirms
`0 → 1 → 0`, successful one-shot load, touch STOP writes, and live moving-force
rendering with motor `1`. TV reconnected and reports both knob and trainer ready.
Direct-TV motor control, TV-initiated motor commands, Twin and other modes
remain covered by regression tests but await Firmware 2.0 hardware acceptance.


### Device units and current allowed force — 2026-10-28

Read-only discovery used the public protocol identifiers in
https://github.com/dylanmaniatakes/Beyond-Power-Voltra-Android (VoltraControlFrames,
VoltraOfficialReadOnlyBootstrap and PROTOCOL_NOTES; no implementation copied).
VTR-161230 Firmware 2.0 then returned these CRC-valid isolated reports:

- Weight preference `0x506e = 1` (kg): `5516085f10aa002020000f0001006e50010000002c0c`.
- Length preference `0x506f = 0` (cm): `5516085f10aa002020000f0001006f5000000000bc14`.
- Current allowed force `0x5314 = 229` lb: `551408ce10aa002020000f0001001453e5007daa`.

229 raw lb displays as 104 kg using the existing verified setpoint grid. This is
current device enforcement, not a factory capacity or a firmware-version guess.
A separate Overdrive maximum read `0x541e` returned zero records; availability
`0x5421` returned 0. Neither is used to grant a higher range. No setting was
changed during discovery. Unsupported/invalid ceilings retain the conservative
legacy 200 lb envelope, and raw values beyond the supported 250 lb envelope do
not authorize commands. New preference and limit reports are polled and also
accepted asynchronously, reset with a new connection. Hardware switching to lb/in
and testing the other modes remains a separate acceptance step.
