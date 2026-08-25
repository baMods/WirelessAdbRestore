package com.bamods.adbrestore.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

class AdbMdnsDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null

    private var isDiscoveringPairing = false
    private var isDiscoveringConnect = false

    var onPairingDiscovered: ((port: Int, host: String) -> Unit)? = null
    var onConnectDiscovered: ((port: Int, host: String) -> Unit)? = null

    fun startDiscovery() {
        startPairingDiscovery()
        startConnectDiscovery()
    }

    fun stopDiscovery() {
        stopPairingDiscovery()
        stopConnectDiscovery()
    }

    private fun startPairingDiscovery() {
        if (isDiscoveringPairing || nsdManager == null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscoveringPairing = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("_adb-tls-pairing") || serviceInfo.serviceType.contains("pairing")) {
                    resolvePairingService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscoveringPairing = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscoveringPairing = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscoveringPairing = false
            }
        }
        pairingDiscoveryListener = listener
        try {
            nsdManager.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startConnectDiscovery() {
        if (isDiscoveringConnect || nsdManager == null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscoveringConnect = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("_adb-tls-connect") || serviceInfo.serviceType.contains("connect")) {
                    resolveConnectService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscoveringConnect = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscoveringConnect = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscoveringConnect = false
            }
        }
        connectDiscoveryListener = listener
        try {
            nsdManager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolvePairingService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                    val port = resolvedService.port
                    val host = resolvedService.host?.hostAddress ?: "127.0.0.1"
                    if (port > 0) {
                        mainHandler.post {
                            onPairingDiscovered?.invoke(port, host)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveConnectService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                    val port = resolvedService.port
                    val host = resolvedService.host?.hostAddress ?: "127.0.0.1"
                    if (port > 0) {
                        mainHandler.post {
                            onConnectDiscovered?.invoke(port, host)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopPairingDiscovery() {
        if (!isDiscoveringPairing || nsdManager == null) return
        try {
            pairingDiscoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (ignored: Exception) {}
        isDiscoveringPairing = false
        pairingDiscoveryListener = null
    }

    private fun stopConnectDiscovery() {
        if (!isDiscoveringConnect || nsdManager == null) return
        try {
            connectDiscoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (ignored: Exception) {}
        isDiscoveringConnect = false
        connectDiscoveryListener = null
    }
}
