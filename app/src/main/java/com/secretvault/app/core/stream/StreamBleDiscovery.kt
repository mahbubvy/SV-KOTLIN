package com.secretvault.app.core.stream

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.UUID

@SuppressLint("MissingPermission") // Every entry checks the role's permissions; revocation falls back to Wi-Fi.
class StreamBleDiscovery(private val context: Context) : Closeable {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(StreamDiscoveryState(message = ""))
    val state = mutableState.asStateFlow()
    @Volatile private var server: BluetoothGattServer? = null
    private var advertisement: AdvertiseCallback? = null
    private var scan: ScanCallback? = null
    private var client: BluetoothGatt? = null
    private val seen = mutableSetOf<String>()
    private val pending = ArrayDeque<BluetoothDevice>()
    private val found = linkedMapOf<String, StreamEndpoint>()
    private var readTimeout: Runnable? = null
    private val scanTimeout = Runnable { stopSearching(clear = false) }

    fun advertise(endpoint: StreamEndpoint) {
        stopAdvertising()
        if (!allowed(context, true)) { unavailable("Allow Nearby devices for Bluetooth discovery"); return }
        try {
            val adapter = manager?.adapter
            val advertiser = adapter?.bluetoothLeAdvertiser
            if (adapter?.isEnabled != true || advertiser == null) { unavailable("Bluetooth advertising unavailable"); return }
            val payload = endpoint.publicBytes()
            val callback = object : AdvertiseCallback() {
                override fun onStartFailure(code: Int) { main.post { if (advertisement === this) { stopAdvertising(); unavailable("Bluetooth advertising failed") } } }
            }
            advertisement = callback
            var owner: BluetoothGattServer? = null
            val peers = mutableMapOf<BluetoothDevice, Int>()
            owner = manager.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, service: BluetoothGattService) { main.post {
                    if (server !== owner || advertisement !== callback) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) { stopAdvertising(); unavailable("Bluetooth service unavailable"); return@post }
                    try {
                        advertiser.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
                            AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build(), callback)
                        mutableState.value = StreamDiscoveryState(message = "Bluetooth discovery on")
                    } catch (_: Exception) { stopAdvertising(); unavailable("Bluetooth advertising unavailable") }
                } }
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    synchronized(peers) {
                        if (newState == BluetoothProfile.STATE_CONNECTED && server === owner) {
                            if (peers.size >= 2 && device !in peers) runCatching { owner?.cancelConnection(device) }
                            else peers[device] = 23
                        } else peers.remove(device)
                    }
                }
                override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { synchronized(peers) { if (device in peers) peers[device] = mtu.coerceIn(23, 517) } }
                override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                    val mtu = synchronized(peers) { peers[device] ?: 23 }
                    val valid = server === owner && characteristic.uuid == DETAILS && offset in 0..payload.size
                    runCatching { owner?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET,
                        offset, if (valid) payload.copyOfRange(offset, minOf(payload.size, offset + mtu - 1)) else null) }
                }
            })
            server = owner ?: throw IllegalStateException("GATT unavailable")
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(BluetoothGattCharacteristic(DETAILS, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            }
            if (!requireNotNull(owner).addService(service)) throw IllegalStateException("GATT service rejected")
        } catch (_: Exception) { stopAdvertising(); unavailable("Bluetooth advertising unavailable") }
    }

    fun stopAdvertising() {
        val callback = advertisement; advertisement = null
        runCatching { callback?.let { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        val old = server; server = null; runCatching { old?.close() }
    }

    fun search() {
        stopSearching()
        if (!allowed(context, false)) { unavailable("Allow Nearby devices for Bluetooth discovery"); return }
        try {
            val adapter = manager?.adapter
            if (adapter?.isEnabled != true) { unavailable("Bluetooth is off"); return }
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) { main.post {
                    if (scan !== this || seen.size >= 8) return@post
                    try { if (seen.add(result.device.address)) { pending.addLast(result.device); readNext(this) } }
                    catch (_: Exception) { stopSearching(); unavailable("Bluetooth permission unavailable") }
                } }
                override fun onScanFailed(code: Int) { main.post { if (scan === this) { stopSearching(); unavailable("Bluetooth scan failed") } } }
            }
            scan = callback
            requireNotNull(adapter.bluetoothLeScanner).startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            mutableState.value = StreamDiscoveryState(message = "Searching Bluetooth too…")
            main.postDelayed(scanTimeout, 12_000)
        } catch (_: Exception) { stopSearching(); unavailable("Bluetooth discovery unavailable") }
    }

    private fun readNext(owner: ScanCallback) {
        if (client != null || scan !== owner) return
        val device = pending.removeFirstOrNull() ?: return
        fun finish(gatt: BluetoothGatt, value: ByteArray? = null) {
            if (client !== gatt) { runCatching { gatt.close() }; return }
            readTimeout?.let(main::removeCallbacks); readTimeout = null
            client = null; runCatching { gatt.disconnect(); gatt.close() }
            if (scan !== owner) return
            value?.let { bytes -> runCatching { StreamEndpoint.fromPublicBytes(bytes) }.getOrNull()?.let { found[it.sessionId] = it } }
            mutableState.value = StreamDiscoveryState(found.values.take(16), if (found.isEmpty()) "No Bluetooth cameras found yet" else "Nearby cameras found")
            readNext(owner)
        }
        try {
            client = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { main.post {
                    if (client !== gatt || scan !== owner) { runCatching { gatt.close() }; return@post }
                    if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) finish(gatt)
                    else if (newState == BluetoothProfile.STATE_CONNECTED) try {
                        if (!gatt.requestMtu(517) && !gatt.discoverServices()) finish(gatt)
                    } catch (_: Exception) { finish(gatt) }
                } }
                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { main.post {
                    if (client === gatt) try { if (!gatt.discoverServices()) finish(gatt) } catch (_: Exception) { finish(gatt) }
                } }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { main.post {
                    if (client !== gatt) return@post
                    val characteristic = gatt.getService(SERVICE)?.getCharacteristic(DETAILS)
                    try { if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || !gatt.readCharacteristic(characteristic)) finish(gatt) }
                    catch (_: Exception) { finish(gatt) }
                } }
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    main.post { finish(gatt, value.takeIf { status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == DETAILS }) }
                }
                @Deprecated("Used on Android 12 and earlier")
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (Build.VERSION.SDK_INT < 33) onCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
                }
            }, BluetoothDevice.TRANSPORT_LE)
            val gatt = client ?: throw IllegalStateException("GATT connection rejected")
            readTimeout = Runnable { finish(gatt) }.also { main.postDelayed(it, 10_000) }
        } catch (_: Exception) { client = null; readNext(owner) }
    }

    fun stopSearching(clear: Boolean = true) {
        main.removeCallbacks(scanTimeout); readTimeout?.let(main::removeCallbacks); readTimeout = null
        val callback = scan; scan = null
        runCatching { callback?.let { manager?.adapter?.bluetoothLeScanner?.stopScan(it) } }
        val old = client; client = null; runCatching { old?.disconnect(); old?.close() }
        pending.clear(); seen.clear()
        if (clear) { found.clear(); mutableState.value = StreamDiscoveryState(message = "") }
        else mutableState.value = StreamDiscoveryState(found.values.toList(), "Bluetooth search finished. Refresh to scan again.")
    }
    private fun unavailable(reason: String) { mutableState.value = StreamDiscoveryState(message = "$reason. Wi-Fi discovery still works.") }
    override fun close() { stopAdvertising(); stopSearching() }

    companion object {
        private val SERVICE = UUID.fromString("6fd09a70-895b-4a01-b030-f0c0879c97e1")
        private val DETAILS = UUID.fromString("6fd09a71-895b-4a01-b030-f0c0879c97e1")
        fun permissions(sender: Boolean): Array<String> = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(if (sender) Manifest.permission.BLUETOOTH_ADVERTISE else Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else if (sender) emptyArray() else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        fun allowed(context: Context, sender: Boolean): Boolean = permissions(sender).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
