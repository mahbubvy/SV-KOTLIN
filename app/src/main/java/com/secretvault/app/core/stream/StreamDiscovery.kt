package com.secretvault.app.core.stream

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.net.Inet4Address

data class StreamDiscoveryState(val cameras: List<StreamEndpoint> = emptyList(), val message: String = "Searching for cameras…")

class StreamDiscovery(context: Context) : Closeable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val mutableState = MutableStateFlow(StreamDiscoveryState())
    val state = mutableState.asStateFlow()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = linkedMapOf<String, StreamEndpoint>()
    private val names = mutableSetOf<String>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized fun advertise(endpoint: StreamEndpoint) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = endpoint.name; serviceType = TYPE; port = endpoint.port
            setAttribute("v", endpoint.version.toString()); setAttribute("s", endpoint.sessionId); setAttribute("f", endpoint.fingerprint)
            setAttribute("n", endpoint.name); setAttribute("a", if (endpoint.requiresPin) "1" else "0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                synchronized(this@StreamDiscovery) {
                    if (registration === this) mutableState.value = StreamDiscoveryState(message = "Could not advertise camera on Wi-Fi. Stop and try again.")
                }
            }
            override fun onServiceRegistered(info: NsdServiceInfo) {
                synchronized(this@StreamDiscovery) { if (registration !== this) runCatching { nsd.unregisterService(this) } }
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        }
        registration = listener
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: Exception) { registration = null; mutableState.value = StreamDiscoveryState(message = "Wi-Fi camera discovery is unavailable") }
    }

    @Synchronized fun stopAdvertising() {
        val listener = registration; registration = null
        listener?.let { runCatching { nsd.unregisterService(it) } }
    }

    @Synchronized fun search() {
        stopSearching(); mutableState.value = StreamDiscoveryState()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                synchronized(this@StreamDiscovery) {
                    if (discovery === this) mutableState.value = StreamDiscoveryState(message = "Could not search Wi-Fi. Check the connection and refresh.")
                }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(this@StreamDiscovery) {
                    if (discovery !== this || info.serviceType != TYPE || info.serviceName.length !in 1..64 || names.size >= 16) return
                    if (names.add(info.serviceName)) { pending.addLast(info); resolveNext(this) }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(this@StreamDiscovery) {
                    if (discovery !== this) return
                    names.remove(info.serviceName); found.remove(info.serviceName)
                    pending.removeAll { it.serviceName == info.serviceName }; publish()
                }
            }
        }
        discovery = listener
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: Exception) { discovery = null; mutableState.value = StreamDiscoveryState(message = "Wi-Fi camera discovery is unavailable") }
    }

    private fun resolveNext(owner: NsdManager.DiscoveryListener) {
        if (resolving || discovery !== owner) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = finish(null)
            override fun onServiceResolved(info: NsdServiceInfo) {
                val endpoint = runCatching {
                    fun attr(name: String): String {
                        val bytes = requireNotNull(info.attributes[name]); require(bytes.size <= 128)
                        return bytes.toString(Charsets.UTF_8)
                    }
                    val version = attr("v").toInt()
                    val requiresPin = version % 2 == 0
                    require(version in 2..9 && attr("a") == if (requiresPin) "1" else "0")
                    require(info.host is Inet4Address)
                    StreamEndpoint(requireNotNull(info.host.hostAddress), info.port, attr("f"), attr("s"), info.serviceName, requiresPin, version >= 4, version >= 6, version >= 8)
                }.getOrNull()
                finish(endpoint)
            }
            private fun finish(endpoint: StreamEndpoint?) {
                synchronized(this@StreamDiscovery) {
                    resolving = false
                    if (discovery !== owner) { discovery?.let(::resolveNext); return }
                    if (endpoint != null && info.serviceName in names) found[info.serviceName] = endpoint
                    publish(); resolveNext(owner)
                }
            }
        }
        try { nsd.resolveService(info, listener) }
        catch (_: Exception) { resolving = false; resolveNext(owner) }
    }

    private fun publish() {
        val cameras = found.values.distinctBy { it.sessionId }.take(16)
        mutableState.value = StreamDiscoveryState(cameras, if (cameras.isEmpty()) "No cameras found. Start Stream on the other device and use the same Wi-Fi." else "Choose a camera")
    }
    @Synchronized fun stopSearching() {
        val listener = discovery; discovery = null
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        names.clear(); found.clear(); pending.clear()
        mutableState.value = StreamDiscoveryState(message = "Refresh to search for cameras on Wi-Fi")
    }
    override fun close() { stopAdvertising(); stopSearching() }
    companion object { private const val TYPE = "_svcamera._tcp." }
}
