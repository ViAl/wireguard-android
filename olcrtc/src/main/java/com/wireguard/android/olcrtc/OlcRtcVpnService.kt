package com.wireguard.android.olcrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import java.io.File

class OlcRtcVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    // JNI native declarations — instance methods (not in companion object)
    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    companion object {
        var currentInstance: OlcRtcVpnService? = null

        /**
         * Signal for Transport.startAndWait() to know when the TUN interface
         * and tun2socks are fully established.
         */
        @Volatile
        var isTunReady: Boolean = false
            internal set

        const val ACTION_START = "com.wireguard.android.olcrtc.START"
        const val ACTION_STOP = "com.wireguard.android.olcrtc.STOP"

        const val EXTRA_CONFIG_NAME = "olcrtc_name"
        const val EXTRA_CONFIG_CARRIER = "olcrtc_carrier"
        const val EXTRA_CONFIG_ROOM = "olcrtc_room"
        const val EXTRA_CONFIG_CLIENT = "olcrtc_client"
        const val EXTRA_CONFIG_KEY = "olcrtc_key"
        const val EXTRA_CONFIG_TRANSPORT = "olcrtc_transport"
        const val EXTRA_CONFIG_SOCKS_PORT = "olcrtc_socks_port"
        const val EXTRA_CONFIG_DNS = "olcrtc_dns"
        const val NOTIFICATION_CHANNEL_ID = "olcrtc_vpn"
        const val FOREGROUND_SERVICE_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = extractConfig(intent)
                if (config != null) {
                    startVpn(config)
                }
            }
            ACTION_STOP -> stopVpn()
        }
        return Service.START_NOT_STICKY
    }

    private fun extractConfig(intent: Intent): OlcRtcConfig? {
        val name = intent.getStringExtra(EXTRA_CONFIG_NAME) ?: return null
        val carrier = intent.getStringExtra(EXTRA_CONFIG_CARRIER) ?: return null
        val room = intent.getStringExtra(EXTRA_CONFIG_ROOM) ?: return null
        val client = intent.getStringExtra(EXTRA_CONFIG_CLIENT) ?: return null
        val key = intent.getStringExtra(EXTRA_CONFIG_KEY) ?: return null
        val transport = intent.getStringExtra(EXTRA_CONFIG_TRANSPORT) ?: "vp8channel"
        val socksPort = intent.getIntExtra(EXTRA_CONFIG_SOCKS_PORT, 10808)
        val dns = intent.getStringExtra(EXTRA_CONFIG_DNS) ?: "1.1.1.1:53"
        return OlcRtcConfig(
            name = name, carrier = carrier, roomId = room,
            clientId = client, keyHex = key, transport = transport,
            socksPort = socksPort, dnsServer = dns
        )
    }

    private fun startVpn(config: OlcRtcConfig) {
        if (isRunning) return

        // Force-load native libraries so R8 doesn't strip unused .so files
        try {
            System.loadLibrary("gojni")
            android.util.Log.d("OlcRtcVpnService", "libgojni.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("OlcRtcVpnService", "libgojni.so not available", e)
        }
        try {
            System.loadLibrary("hev-socks5-tunnel")
            android.util.Log.d("OlcRtcVpnService", "libhev-socks5-tunnel.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("OlcRtcVpnService", "libhev-socks5-tunnel.so not available", e)
        }
        try {
            System.loadLibrary("olcrtc_tun2socks")
            android.util.Log.d("OlcRtcVpnService", "libolcrtc_tun2socks.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("OlcRtcVpnService", "libolcrtc_tun2socks.so not available", e)
        }

        val builder = Builder()
        builder.setSession(config.name)
        builder.setMtu(1500)
        builder.addAddress("10.0.2.1", 24)
        builder.addRoute("0.0.0.0", 0)

        val dnsParts = config.dnsServer.split(":")
        builder.addDnsServer(dnsParts[0])

        config.excludedApplications.forEach { builder.addDisallowedApplication(it) }
        config.includedApplications.forEach { builder.addAllowedApplication(it) }

        createNotificationChannel()
        val notification = buildNotification(config.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_SERVICE_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }

        vpnInterface = builder.establish()
            ?: throw IllegalStateException("Failed to establish VPN interface")

        val fd = vpnInterface!!.fd
        android.util.Log.d("OlcRtcVpnService", "TUN fd=$fd established for ${config.name}")

        // Socket-level protection is handled by Transport via Mobile.setProtector
        // No process-wide bindProcessToNetwork — that would conflict with WireGuard

        // Generate hev-socks5-tunnel config and start tun2socks in background
        try {
            val hevConfig = generateHevConfig(config)
            val hevConfigFile = File(filesDir, "hev-socks5-tunnel.conf")
            hevConfigFile.writeText(hevConfig)
            android.util.Log.d("OlcRtcVpnService", "Saving Hev config to ${hevConfigFile.absolutePath}")

            Thread {
                try {
                    val result = startTun2socksNative(hevConfigFile.absolutePath, fd)
                    android.util.Log.d("OlcRtcVpnService", "tun2socks started: result=$result")
                } catch (e: Exception) {
                    android.util.Log.e("OlcRtcVpnService", "tun2socks failed", e)
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcVpnService", "Failed to configure tun2socks", e)
        }

        isRunning = true
        isTunReady = true
    }

    private fun stopVpn() {
        if (!isRunning) return
        isTunReady = false
        android.util.Log.d("OlcRtcVpnService", "Stopping VPN")

        // Stop tun2socks
        try {
            stopTun2socksNative()
            android.util.Log.d("OlcRtcVpnService", "tun2socks stopped")
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "stopTun2socks failed", e)
        }

        vpnInterface?.close()
        vpnInterface = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        currentInstance = null
        stopVpn()
        super.onDestroy()
    }

    private fun generateHevConfig(config: OlcRtcConfig): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: 1500")
        appendLine("socks5:")
        appendLine("  address: 127.0.0.1")
        appendLine("  port: ${config.socksPort}")
        config.socksUser?.let {
            if (it.isNotEmpty()) {
                appendLine("  username: $it")
                appendLine("  password: ${config.socksPass ?: ""}")
            }
        }
        appendLine("misc:")
        appendLine("  task-stack-size: 20480")
        appendLine("  event-loop-count: 2")
        appendLine("  udp-timeout: 300")
        appendLine("  tcp-timeout: 2000")
        appendLine("  resolve-timeout: 10000")
        appendLine("  fast-open: true")
        appendLine("  fore-interface: false")
        appendLine("  is-fragment: false")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "OlcRTC VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(tunnelName: String): Notification {
        val stopIntent = Intent(this, OlcRtcVpnService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OlcRTC: $tunnelName")
            .setContentText("VPN is active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }
}
