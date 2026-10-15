# Protocol sources and licenses

This Kotlin implementation adapts the public frame format, checksums, initialization frames, register identifiers and telemetry decoding from these MIT-licensed projects:

- [HJewkes/voltra-node-sdk](https://github.com/HJewkes/voltra-node-sdk), revision `1d47cb29a90912284c383fabb684981b508f5f54`. References include `src/voltra/protocol/telemetry-decoder.ts`, the protocol catalog and decoder tests. Copyright 2026 Henry Jewkes. [License](third_party/voltra-node-sdk-LICENSE).
- [albertzhang86/voltra-remote](https://github.com/albertzhang86/voltra-remote), revision `d7c700d67466344570ba9b177b51fd4cefbdf2bd`, derived from Omar Shahine's VOLTRA knob project. References include `firmware/VoltraKnob/src/protocol`, `src/link`, and `third_party/voltra-protocol.json`. Copyright 2026 Omar Shahine; local adaptations by Albert Zhang. [License](third_party/voltra-knob-LICENSE).

The newer SDK decoder corrects legacy battery offsets: 35/52-byte status messages are not battery readings. The connector reads battery register `0x4e2d` or a validated `0xa7` endpoint message. Rep count and device set count are both 16-bit; only pull events are counted.

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
telemetry: payload word 0 is `forceTenthLbs`, word 1 is cable length, word 2 is
signed velocity. Source 0 carries 6 or 8 payload bytes; sources 1 and 2 carry
10 or 12. The optional trailing word is `targetForceTenthLbs`, separate from measured
force. It is exposed as resistance target; its correspondence to the trainer
display under load and modifiers remains pending physical validation. The decoder accepts only those independent layouts and does not interpret
native Twin source values `0x10`/`0x11`/`0x12`. Tests include four telemetry-only
fixture frames without trainer identities. No Cortex implementation or binary
is redistributed. Runtime register readback was also verified unloaded.
