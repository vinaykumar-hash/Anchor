package com.example.contextmemory.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Handles mDNS/NSD service registration and discovery for finding
 * other ContextMemory devices on the local WiFi network.
 */
class DeviceDiscovery(private val context: Context) {
    private val TAG = "DeviceDiscovery"
    private val SERVICE_TYPE = "_contextmemory._tcp."
    private val SERVICE_NAME = "ContextMemory"

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpSocket: DatagramSocket? = null
    @Volatile private var isDiscovering = false

    data class DiscoveredDevice(
        val name: String,
        val ip: String,
        val port: Int,
        val deviceType: String = "unknown"
    )

    /**
     * Get this device's local IP address.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }

    /**
     * Register this device as a ContextMemory service on the network.
     */
    fun registerService(port: Int) {
        if (registrationListener != null) return

        // Acquire multicast lock so NSD works reliably
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("contextmemory_nsd").apply {
            setReferenceCounted(true)
            acquire()
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service registration failed: error $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: error $errorCode")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    /**
     * Start discovering other ContextMemory devices on the network.
     */
    fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true

        // 1. mDNS Discovery
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName.contains(SERVICE_NAME)) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                _discoveredDevices.value = _discoveredDevices.value.filter {
                    it.name != serviceInfo.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        // 2. UDP Heartbeat Listener (Secondary Discovery)
        Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(8474)
                udpSocket = socket
                socket.broadcast = true
                val buffer = ByteArray(1024)
                while (isDiscovering && !socket.isClosed) {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    try {
                        val json = org.json.JSONObject(data)
                        if (json.getString("type") == "context_memory_discovery") {
                            val name = json.getString("name")
                            val ip = json.getString("ip")
                            val port = json.getInt("port")

                            if (ip != getLocalIpAddress()) {
                                val device = DiscoveredDevice(name, ip, port, "pc")
                                val current = _discoveredDevices.value.toMutableList()
                                if (current.none { it.ip == ip }) {
                                    current.add(device)
                                    _discoveredDevices.value = current
                                    Log.d(TAG, "Found device via UDP: $name at $ip")
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                if (isDiscovering) {
                    Log.e(TAG, "UDP Listener failed", e)
                } else {
                    Log.d(TAG, "UDP Listener stopped")
                }
            } finally {
                if (udpSocket === socket) {
                    udpSocket?.close()
                    udpSocket = null
                }
            }
        }.start()
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: error $errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val port = info.port
                val localIp = getLocalIpAddress()

                // Skip our own device
                if (host == localIp) return

                Log.d(TAG, "Resolved: ${info.serviceName} at $host:$port")
                val device = DiscoveredDevice(
                    name = info.serviceName,
                    ip = host,
                    port = port,
                    deviceType = "pc" // We'll update this when we query the status endpoint
                )

                val current = _discoveredDevices.value.toMutableList()
                current.removeAll { it.ip == host }
                current.add(device)
                _discoveredDevices.value = current
            }
        })
    }

    fun stopDiscovery() {
        isDiscovering = false
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
        discoveryListener = null
        udpSocket?.close()
        udpSocket = null
        _discoveredDevices.value = emptyList()
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service", e)
        }
        registrationListener = null
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        multicastLock = null
    }

    fun cleanup() {
        stopDiscovery()
        unregisterService()
    }
}
