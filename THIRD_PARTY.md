# Protocol sources and licenses

This Kotlin implementation adapts the public frame format, checksums, initialization frames, register identifiers and telemetry decoding from these MIT-licensed projects:

- [HJewkes/voltra-node-sdk](https://github.com/HJewkes/voltra-node-sdk), revision `1d47cb29a90912284c383fabb684981b508f5f54`. References include `src/voltra/protocol/telemetry-decoder.ts`, the protocol catalog and decoder tests. Copyright 2026 Henry Jewkes. [License](third_party/voltra-node-sdk-LICENSE).
- [albertzhang86/voltra-remote](https://github.com/albertzhang86/voltra-remote), revision `d7c700d67466344570ba9b177b51fd4cefbdf2bd`, derived from Omar Shahine's VOLTRA knob project. References include `firmware/VoltraKnob/src/protocol`, `src/link`, and `third_party/voltra-protocol.json`. Copyright 2026 Omar Shahine; local adaptations by Albert Zhang. [License](third_party/voltra-knob-LICENSE).

The newer SDK decoder corrects legacy battery offsets: 35/52-byte status messages are not battery readings. This connector reads battery register `0x4e2d` instead. Rep count and device set count are both 16-bit; only pull events are counted.

Beyond Power's [Cortex Twin documentation](https://cortex.beyond-power.com/docs/cortex/twin) describes official CLI pair/unpair operations. It does not establish a raw Android BLE implementation here. No Cortex binaries, private captures, credentials, trainer addresses or private vendor documentation are included.
