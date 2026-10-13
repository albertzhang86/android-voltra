package io.github.albertzhang86.voltra.android

import android.content.Context
import io.github.albertzhang86.voltra.VoltraTransport
import io.github.albertzhang86.voltra.knob.KnobProtocol
import java.util.UUID

/** Discovers only the TV bridge service; ordinary trainer advertisements never match. */
class AndroidKnobTransport(context: Context) :
    VoltraTransport by AndroidVoltraTransport(
        context,
        GattProfile(
            service = UUID.fromString(KnobProtocol.SERVICE),
            write = UUID.fromString(KnobProtocol.COMMAND),
            notify = UUID.fromString(KnobProtocol.UPDATES),
            namePrefix = null,
            minimumMtu = KnobProtocol.MIN_MTU,
            connectTimeoutMs = 90_000,
        ),
    )
