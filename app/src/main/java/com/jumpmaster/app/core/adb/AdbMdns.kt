package com.jumpmaster.app.core.adb

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * mDNS discovery for Android wireless debugging services.
 *
 * Finds the device's own `_adb-tls-connect._tcp` and `_adb-tls-pairing._tcp`
 * services so the user does not need to type the IP / connect port / pairing port.
 * Logic ported from Shizuku (RikkaApps/Shizuku).
 */
class AdbMdns(
    context: Context,
    private val onServiceFound: (MdnsEndpoint) -> Unit,
    private val onServiceLost: (MdnsEndpoint) -> Unit
) {

    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val listener = DiscoveryListener()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var running = false

    /** Number of active discovery sessions (one per service type). */
    private var discoveryCount = 0

    /** Currently known endpoints, keyed by "name:serviceType". */
    private val discovered = mutableMapOf<String, MdnsEndpoint>()

    /** ServiceInfoCallbacks registered on API 33+, keyed by "name:serviceType". */
    private val registeredCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    fun start() {
        if (running) return
        running = true
        try {
            nsdManager.discoverServices(AdbMdns.TLS_CONNECT, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager.discoverServices(AdbMdns.TLS_PAIRING, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "start discovery failed", e)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (discoveryCount > 0) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "stop discovery failed", e)
            }
            discoveryCount = 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registeredCallbacks.values.forEach {
                try {
                    nsdManager.unregisterServiceInfoCallback(it)
                } catch (e: Exception) {
                    Log.w(TAG, "unregister callback failed", e)
                }
            }
            registeredCallbacks.clear()
        }
        discovered.clear()
        executor.shutdown()
    }

    private fun onDiscoveryStarted() {
        discoveryCount++
    }

    private fun onDiscoveryStopped() {
        discoveryCount = (discoveryCount - 1).coerceAtLeast(0)
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val callback = ServiceInfoCallbackImpl()
                nsdManager.registerServiceInfoCallback(info, executor, callback)
                registeredCallbacks[info.serviceName + ":" + info.serviceType] = callback
            } else {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(info, ResolveListener())
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed", e)
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        val key = info.serviceName + ":" + info.serviceType
        discovered.remove(key)?.let {
            onServiceLost(it)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registeredCallbacks.remove(key)?.let {
                try {
                    nsdManager.unregisterServiceInfoCallback(it)
                } catch (e: Exception) {
                    Log.w(TAG, "unregister callback failed", e)
                }
            }
        }
    }

    private fun onServiceResolved(resolved: NsdServiceInfo) {
        if (!running) return

        val isLocalHost = NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.any { networkInterface ->
                networkInterface.inetAddresses.asSequence()
                    .any { resolved.hostAddressOrNull() == it.hostAddress }
            } == true

        // Only accept services bound to this device's own interfaces
        // (mirrors Shizuku which connects to 127.0.0.1 / local adbd).
        if (!isLocalHost) return
        if (!isPortAvailable(resolved.port)) return

        val type = resolved.serviceType
        val key = resolved.serviceName + ":" + type
        val endpoint = MdnsEndpoint(
            name = resolved.serviceName,
            host = resolved.hostAddressOrNull() ?: "127.0.0.1",
            port = resolved.port,
            serviceType = type
        )
        if (discovered.put(key, endpoint) == null) {
            Log.i(TAG, "Discovered ${endpoint.serviceType} -> ${endpoint.host}:${endpoint.port}")
            onServiceFound(endpoint)
        }
    }

    /** Returns the service host address, using the modern API on Android 14+. */
    @SuppressLint("NewApi")
    private fun NsdServiceInfo.hostAddressOrNull(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            host?.hostAddress
        }
    }

    /** Returns true if the port is already bound by a local process (i.e. adbd). */
    private fun isPortAvailable(port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    private inner class DiscoveryListener : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            onDiscoveryStarted()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "onStartDiscoveryFailed: $serviceType, error=$errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            onDiscoveryStopped()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStopDiscoveryFailed: $serviceType, error=$errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
            onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            onServiceLost(serviceInfo)
        }
    }

    @Suppress("DEPRECATION")
    private inner class ResolveListener : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "onResolveFailed: ${serviceInfo.serviceName}, error=$errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            onServiceResolved(serviceInfo)
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private inner class ServiceInfoCallbackImpl : NsdManager.ServiceInfoCallback {
        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
            onServiceResolved(serviceInfo)
        }

        override fun onServiceLost() {
            // Service info is dropped; DiscoveryListener#onServiceLost(serviceInfo)
            // already handles removal from the known-endpoint map.
        }

        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
            Log.w(TAG, "onServiceInfoCallbackRegistrationFailed: error=$errorCode")
        }

        override fun onServiceInfoCallbackUnregistered() {
            Log.v(TAG, "onServiceInfoCallbackUnregistered")
        }
    }

    companion object {
        private const val TAG = "AdbMdns"

        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    }
}

/** A discovered wireless debugging endpoint. */
data class MdnsEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    val serviceType: String
) {
    val isPairing: Boolean get() = serviceType == AdbMdns.TLS_PAIRING
}
