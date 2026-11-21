package com.lagradost.cloudstream3.actions.temp.fcast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.util.ArrayDeque

/**
 * Orbit-owned sender discovery for CloudStream's pinned FCast action contract.
 *
 * Local-network permission policy: while Orbit targets SDK 36, Android grants local-network
 * access implicitly through INTERNET, so this target must not declare or request
 * ACCESS_LOCAL_NETWORK. Before targeting SDK 37, either migrate discovery to the system-run
 * NSD picker (`DiscoveryRequest.FLAG_SHOW_PICKER` plus `registerServiceInfoCallback`) and use
 * the selected service's `getHostAddresses()` on its `getNetwork()`, or declare and
 * runtime-request ACCESS_LOCAL_NETWORK before this app-managed discovery and raw TCP sender
 * path. Handle permission denial and revocation before attempting discovery or connection. See
 * https://developer.android.com/privacy-and-security/local-network-permission.
 */
class FcastManager {
    private val main = Handler(Looper.getMainLooper())
    private var manager: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var generation = 0L
    private val knownNames = LinkedHashSet<String>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun init(context: Context, registerReceiver: Boolean) {
        // Orbit sends to receivers; it never advertises a receiver without a playback server.
        require(!registerReceiver) { "Orbit supports FCast sender discovery only" }
        val app = context.applicationContext
        main.post {
            if (listener != null) return@post
            if (owner !== this) owner?.stopOnMain()
            owner = this
            val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@post
            manager = nsd
            val token = ++generation
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) = Unit
                override fun onDiscoveryStopped(serviceType: String?) = Unit
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    main.post { if (token == generation) stopOnMain() }
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) return
                    main.post {
                        if (token != generation || listener == null || knownNames.size >= MAX_DEVICES) return@post
                        if (serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE) return@post
                        if (knownNames.add(serviceInfo.serviceName)) {
                            pending.addLast(serviceInfo)
                            resolveNext(token)
                        }
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) return
                    main.post {
                        if (token != generation) return@post
                        knownNames.remove(serviceInfo.serviceName)
                        synchronized(deviceLock) { devices.remove(serviceInfo.serviceName) }
                    }
                }
            }
            listener = discovery
            try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            catch (_: RuntimeException) { stopOnMain() }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(token: Long) {
        if (resolving || token != generation || listener == null) return
        val info = pending.pollFirst() ?: return
        if (info.serviceName !in knownNames) { resolveNext(token); return }
        val nsd = manager ?: return
        resolving = true
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                main.post {
                    if (token != generation) return@post
                    resolving = false
                    resolveNext(token)
                }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                main.post {
                    if (token != generation) return@post
                    resolving = false
                    if (serviceInfo != null && serviceInfo.serviceName in knownNames) {
                        val address = serviceInfo.host
                        if (address != null && isLocalReceiver(address) && serviceInfo.port in 1..65535) {
                            val device = PublicDeviceInfo(serviceInfo)
                            synchronized(deviceLock) { devices[device.rawName] = device }
                        }
                    }
                    resolveNext(token)
                }
            }
        }
        try { nsd.resolveService(info, callback) }
        catch (_: RuntimeException) { resolving = false; resolveNext(token) }
    }

    fun stop() { main.post { stopOnMain() } }

    private fun stopOnMain() {
        generation++
        val previous = listener
        listener = null
        if (previous != null) try { manager?.stopServiceDiscovery(previous) } catch (_: RuntimeException) { }
        manager = null
        pending.clear()
        knownNames.clear()
        resolving = false
        if (owner === this) {
            owner = null
            synchronized(deviceLock) { devices.clear() }
        }
    }

    companion object {
        const val APP_PREFIX = "Orbit"
        const val TCP_PORT = 46899
        private const val SERVICE_TYPE = "_fcast._tcp"
        private const val MAX_DEVICES = 64
        private val deviceLock = Any()
        private val devices = LinkedHashMap<String, PublicDeviceInfo>()
        private var owner: FcastManager? = null
        val currentDevices: List<PublicDeviceInfo>
            get() = synchronized(deviceLock) { devices.values.toList() }

        internal fun isLocalReceiver(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return false
            val bytes = address.address
            return address.isSiteLocalAddress || address.isLinkLocalAddress ||
                (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc)
        }
    }
}

class PublicDeviceInfo(serviceInfo: NsdServiceInfo) {
    val rawName: String = serviceInfo.serviceName
    @Suppress("DEPRECATION")
    val host: String? = serviceInfo.host?.hostAddress
    val port: Int = serviceInfo.port
    val name: String = rawName.replace('-', ' ').take(120)
}
