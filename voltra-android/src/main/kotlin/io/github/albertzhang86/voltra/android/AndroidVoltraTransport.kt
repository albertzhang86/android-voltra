package io.github.albertzhang86.voltra.android

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.albertzhang86.voltra.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object VoltraGatt {
    private val connectionIds = AtomicInteger()

    fun nextConnectionId(): Int = connectionIds.incrementAndGet()

    fun trace(message: String) {
        if (Log.isLoggable("VoltraGatt", Log.DEBUG)) Log.d("VoltraGatt", message)
    }

    val service: UUID = UUID.fromString("e4dada34-0867-8783-9f70-2ca29216c7e4")
    val write: UUID = UUID.fromString("a010891d-f50f-44f0-901f-9a2421a9e050")
    val notify: UUID = UUID.fromString("55ca1e52-7354-25de-6afc-b7df1e8816ac")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

internal data class GattProfile(
    val service: UUID = VoltraGatt.service,
    val write: UUID = VoltraGatt.write,
    val notify: UUID = VoltraGatt.notify,
    val namePrefix: String? = "VTR-",
    val minimumMtu: Int = 64,
    val connectTimeoutMs: Long = 15_000,
    val label: String = "VOLTRA",
    /** The knob creates a new command session on an authenticated CCCD disable/enable cycle. */
    val renewNotificationSubscription: Boolean = false,
    /** Retry only an early direct-trainer radio failure, before service discovery begins. */
    val retryEstablishment: Boolean = true,
)

/** One native GATT instance per trainer; the controller enforces the two-device limit. */
@SuppressLint("MissingPermission")
class AndroidVoltraTransport
internal constructor(context: Context, private val profile: GattProfile) : VoltraTransport {
    constructor(context: Context) : this(context, GattProfile())

    private val context = context.applicationContext
    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun scan(): Flow<List<VoltraDevice>> =
        callbackFlow {
                VoltraBluetooth.problem(context)?.let { error(it) }
                val scanner =
                    adapter?.bluetoothLeScanner ?: error("Bluetooth scanner is unavailable.")
                val found = linkedMapOf<String, VoltraDevice>()
                val callback =
                    object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            try {
                                val record = result.scanRecord
                                val name = record?.deviceName ?: result.device.name.orEmpty()
                                if (
                                    !result.isConnectable ||
                                        (record?.serviceUuids?.any { it.uuid == profile.service } !=
                                            true &&
                                            (profile.namePrefix == null ||
                                                !name.startsWith(profile.namePrefix)))
                                )
                                    return
                                val device =
                                    VoltraDevice(
                                        result.device.address,
                                        name.ifBlank { profile.label },
                                    )
                                synchronized(found) {
                                    found[device.id] = device
                                    trySend(found.values.toList())
                                }
                            } catch (_: SecurityException) {
                                close(IllegalStateException("Bluetooth permission was revoked."))
                            }
                        }

                        override fun onScanFailed(errorCode: Int) {
                            close(
                                IllegalStateException(
                                    "Bluetooth scan failed ($errorCode). Try again."
                                )
                            )
                        }
                    }
                try {
                    scanner.startScan(
                        null,
                        ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build(),
                        callback,
                    )
                } catch (failure: Exception) {
                    runCatching { scanner.stopScan(callback) }
                    throw IllegalStateException("Unable to start Bluetooth discovery.", failure)
                }
                trySend(emptyList())
                val timeout = launch {
                    delay(10_000)
                    close()
                }
                awaitClose {
                    timeout.cancel()
                    runCatching { scanner.stopScan(callback) }
                }
            }
            .conflate()

    override suspend fun connect(device: VoltraDevice): VoltraLink =
        withGattConnectionOwnership<AndroidVoltraLink> { retain ->
            withContext(Dispatchers.Main.immediate) {
                VoltraBluetooth.problem(context, scanning = false)?.let { error(it) }
                require(BluetoothAdapter.checkBluetoothAddress(device.id)) {
                    "Select a discovered ${profile.label}."
                }
                withTimeout(profile.connectTimeoutMs) {
                    establishGattConnection(
                        retryEstablishment = profile.retryEstablishment,
                        createAttempt = { attempt ->
                            VoltraBluetooth.problem(context, scanning = false)?.let { error(it) }
                            AndroidVoltraLink(
                                context,
                                requireNotNull(adapter).getRemoteDevice(device.id),
                                profile,
                                attempt,
                            ).also(retain)
                        },
                        onRetry = {
                            VoltraGatt.trace(
                                "${profile.label} establishment retry in 1000ms status=${it.status} phase=${it.phase}"
                            )
                        },
                    )
                }
            }
        }
}

@SuppressLint("MissingPermission")
private class AndroidVoltraLink(
    private val context: Context,
    device: BluetoothDevice,
    private val profile: GattProfile,
    attempt: Int,
) : VoltraLink, GattConnectionAttempt {
    private val ready = CompletableDeferred<Unit>()
    private val connectionId = VoltraGatt.nextConnectionId()
    private var phase = GattConnectionPhase.CONNECTING
    private val incoming = Channel<ByteArray>(256)
    override val packets = incoming.receiveAsFlow()
    private val closed = AtomicBoolean()
    private val setup = GattSetupGate(profile.renewNotificationSubscription)
    private val writeMutex = Mutex()
    private var writer: BluetoothGattCharacteristic? = null
    private var descriptor: BluetoothGattDescriptor? = null
    private var writeResult: CompletableDeferred<Unit>? = null
    private var revocationResult: CompletableDeferred<Unit>? = null
    private var revocationFinished: CompletableDeferred<Unit>? = null
    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (closed.get()) return
                trace("connection callback status=$status state=$newState")
                if (
                    status != BluetoothGatt.GATT_SUCCESS ||
                        newState == BluetoothProfile.STATE_DISCONNECTED
                ) {
                    val message =
                        if (phase in setOf(GattConnectionPhase.CONNECTING, GattConnectionPhase.MTU))
                            "${profile.label} connection could not be established ($status). Try again."
                        else "${profile.label} disconnected ($status). Reconnect to continue."
                    fail(GattConnectionFailure(status, phase, message))
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (phase != GattConnectionPhase.CONNECTING) return
                    phase = GattConnectionPhase.MTU
                    operation {
                        check(gatt.requestMtu(517)) { "Unable to negotiate Bluetooth packet size." }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                trace("MTU callback status=$status mtu=$mtu")
                if (closed.get() || !setup.beginDiscovery()) return
                operation {
                    check(status == BluetoothGatt.GATT_SUCCESS && mtu >= profile.minimumMtu) {
                        "${profile.label} requires a larger Bluetooth packet size."
                    }
                    phase = GattConnectionPhase.SERVICES
                    check(gatt.discoverServices()) {
                        "Unable to discover ${profile.label} services."
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                trace("services callback status=$status")
                if (closed.get() || !setup.beginSubscription()) return
                operation {
                    check(status == BluetoothGatt.GATT_SUCCESS) {
                        "${profile.label} service discovery failed."
                    }
                    val service =
                        gatt.getService(profile.service)
                            ?: error("${profile.label} service is unavailable.")
                    writer =
                        service.getCharacteristic(profile.write)
                            ?: error("${profile.label} controls are unavailable.")
                    val updates =
                        service.getCharacteristic(profile.notify)
                            ?: error("${profile.label} notifications are unavailable.")
                    check(updates.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    check(gatt.setCharacteristicNotification(updates, true))
                    descriptor =
                        updates.getDescriptor(VoltraGatt.cccd)
                            ?: error("${profile.label} subscription is unavailable.")
                    phase = GattConnectionPhase.SUBSCRIPTION
                    subscribe(
                        gatt,
                        requireNotNull(descriptor),
                        enabled = !profile.renewNotificationSubscription,
                    )
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                written: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (closed.get() || written !== descriptor) return
                trace("subscription callback status=$status")
                if (setup.isRevoking) {
                    if (status == BluetoothGatt.GATT_SUCCESS) revocationResult?.complete(Unit)
                    else
                        revocationResult?.completeExceptionally(
                            IllegalStateException("${profile.label} session revocation failed.")
                        )
                    return
                }
                operation {
                    check(status == BluetoothGatt.GATT_SUCCESS) {
                        "${profile.label} subscription failed. Reconnect to continue."
                    }
                    if (setup.finishSubscriptionReset()) {
                        subscribe(gatt, written, enabled = true)
                    } else if (setup.complete()) {
                        phase = GattConnectionPhase.READY
                        trace("ready")
                        ready.complete(Unit)
                    }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (closed.get() || characteristic.uuid != profile.write) return
                if (status == BluetoothGatt.GATT_SUCCESS) writeResult?.complete(Unit)
                else fail("${profile.label} Bluetooth write failed ($status).")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) = receive(characteristic, value)

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT < 33)
                    characteristic.value?.let { receive(characteristic, it) }
            }

            private fun receive(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (
                    !closed.get() &&
                        (!profile.renewNotificationSubscription || setup.isReady) &&
                        characteristic.uuid == profile.notify &&
                        characteristic.service?.uuid == profile.service
                ) {
                    if (!incoming.trySend(value.copyOf()).isSuccess)
                        fail("${profile.label} data buffer overflow. Reconnect to continue.")
                }
            }
        }
    private val gatt =
        run {
            trace("connect attempt=$attempt")
            device.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                Handler(Looper.getMainLooper()),
            ) ?: error("Could not connect to ${profile.label}.")
        }

    override suspend fun awaitReady() = ready.await()

    @Suppress("DEPRECATION")
    private fun subscribe(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        enabled: Boolean,
    ) {
        val value =
            if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        val accepted =
            if (Build.VERSION.SDK_INT >= 33)
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        check(accepted) {
            if (enabled) "Unable to subscribe to ${profile.label}."
            else "Unable to reset the ${profile.label} subscription."
        }
    }

    override suspend fun write(frame: ByteArray) =
        withContext(Dispatchers.Main.immediate) {
            check(!setup.isRevoking) { "${profile.label} session is closing." }
            writeMutex.withLock {
                check(!closed.get() && !setup.isRevoking) { "${profile.label} disconnected." }
                VoltraBluetooth.problem(context, scanning = false)?.let {
                    fail(it)
                    error(it)
                }
                val characteristic = requireNotNull(writer)
                val response =
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                check(
                    response ||
                        characteristic.properties and
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                )
                val type =
                    if (response) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                val completion = CompletableDeferred<Unit>()
                writeResult = completion
                try {
                    val accepted = writeNative(characteristic, frame.copyOf(), type)
                    check(accepted) { "${profile.label} could not accept the Bluetooth write." }
                    if (response) withTimeout(3_000) { completion.await() } else delay(40)
                } catch (failure: Exception) {
                    fail("${profile.label} write was interrupted. Reconnect before retrying.")
                    throw failure
                } finally {
                    writeResult = null
                }
            }
        }

    /**
     * Android may keep a bonded physical link alive after GATT close. The knob revokes its worker
     * epoch synchronously on CCCD disable, so await that acknowledgement before closing when
     * possible.
     */
    override suspend fun revoke() =
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            revocationFinished?.let {
                withTimeoutOrNull(500) { it.await() }
                return@withContext
            }
            val finished = CompletableDeferred<Unit>().also { revocationFinished = it }
            try {
                if (closed.get() || !setup.beginRevocation()) return@withContext
                phase = GattConnectionPhase.REVOKING
                trace("revocation requested")
                // No old snapshot may authorize another command while the epoch is being revoked.
                while (incoming.tryReceive().isSuccess) Unit
                withTimeout(500) {
                    writeMutex.withLock {
                        if (closed.get()) return@withLock
                        val completion = CompletableDeferred<Unit>()
                        revocationResult = completion
                        subscribe(gatt, requireNotNull(descriptor), enabled = false)
                        completion.await()
                    }
                }
            } catch (_: Exception) {
                // An in-flight operation, rejected CCCD, or missing ACK falls back to link closure.
            } finally {
                close()
                finished.complete(Unit)
            }
        }

    @Suppress("DEPRECATION")
    private fun writeNative(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        type: Int,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            gatt.writeCharacteristic(characteristic, value, type) == BluetoothStatusCodes.SUCCESS
        else {
            characteristic.writeType = type
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }

    private fun operation(block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
            fail("Bluetooth permission was revoked.")
        } catch (failure: Exception) {
            fail(failure.message ?: "${profile.label} Bluetooth operation failed.")
        }
    }

    private fun fail(message: String) = fail(IllegalStateException(message))

    private fun fail(error: Exception) {
        trace("failure ${error.javaClass.simpleName}: ${error.message}")
        ready.completeExceptionally(error)
        writeResult?.completeExceptionally(error)
        revocationResult?.completeExceptionally(error)
        incoming.close(error)
        close("failure")
    }

    override fun close() = close("caller")

    private fun close(reason: String) {
        if (closed.getAndSet(true)) return
        trace("close reason=$reason")
        phase = GattConnectionPhase.CLOSED
        setup.close()
        ready.cancel()
        writeResult?.cancel()
        revocationResult?.cancel()
        incoming.close()
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun trace(event: String) =
        VoltraGatt.trace("link=$connectionId ${profile.label} phase=$phase $event")
}
