package com.wireguard.android.olcrtc

/**
 * JNI bridge to libgojni.so — the Go olcRTC native library built by gomobile.
 *
 * All methods map 1:1 to exported Java_mobile_Mobile_* functions from
 * github.com/openlibrecommunity/olcrtc/mobile.
 */
object MobileBridge {

    private var loaded = false

    /** Try to load the native library. Returns true on success. */
    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("gojni")
            loaded = true
            // Must call init and set providers before using
            _1init()
            setProviders()
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("MobileBridge", "libgojni.so not found: ${e.message}")
            false
        }
    }

    // ---- lifecycle ----

    /** Initializes the Go runtime (maps to Java_mobile_Mobile__1init). */
    private external fun _1init()

    /** Registers built-in carriers/links/transports. */
    private external fun setProviders()

    /**
     * Start the olcRTC client.
     * @param carrierName telemost, jazz, wbstream
     * @param roomID carrier-specific room ID
     * @param clientID must match server -client-id
     * @param keyHex 64-char hex key
     * @param socksPort local SOCKS5 port
     * @param socksUser auth user or empty
     * @param socksPass auth pass or empty
     */
    external fun start(
        carrierName: String,
        roomID: String,
        clientID: String,
        keyHex: String,
        socksPort: Int,
        socksUser: String,
        socksPass: String
    ): String?  // returns error message or null on success

    /** Explicit start with transport name. */
    external fun startWithTransport(
        carrierName: String,
        transportName: String,
        roomID: String,
        clientID: String,
        keyHex: String,
        socksPort: Int,
        socksUser: String,
        socksPass: String
    ): String?

    /** Stop the running client. */
    external fun stop()

    /** Check if client is running. */
    external fun isRunning(): Boolean

    /** Wait until client is ready (blocks). */
    external fun waitReady(): String?

    // ---- configuration (set before start) ----

    external fun setTransport(transport: String)
    external fun setLink(link: String)
    external fun setDNS(dns: String)
    external fun setDebug(debug: Boolean)
    external fun setVP8Options(fps: Int, batchSize: Int)

    // ---- utilities ----

    /**
     * Ping checks connectivity through the tunnel.
     * @return latency in ms or -1 on error
     */
    external fun ping(
        carrierName: String,
        transportName: String,
        roomID: String,
        clientID: String,
        keyHex: String,
        socksPort: Int,
        timeoutMillis: Long,
        pingURL: String,
        vp8FPS: Long,
        vp8BatchSize: Long
    ): Long

    /**
     * Check starts isolated client and returns ready time.
     * @return elapsed ms or -1 on error
     */
    external fun check(
        carrierName: String,
        transportName: String,
        roomID: String,
        clientID: String,
        keyHex: String,
        socksPort: Int,
        timeoutMillis: Int,
        vp8FPS: Int,
        vp8BatchSize: Int
    ): Long

    // ---- protect / log ----

    /** Set VPN socket protector. */
    external fun setProtector(protector: SocketProtectorProxy)

    /** Set log writer. */
    external fun setLogWriter(writer: LogWriterProxy)
}

/**
 * Proxy for Go's SocketProtector interface — prevents VPN loopback.
 */
class SocketProtectorProxy(private val impl: (Int) -> Boolean) {
    @Suppress("unused") // called from JNI
    fun protect(fd: Int): Boolean = impl(fd)
}

/**
 * Proxy for Go's LogWriter interface.
 */
class LogWriterProxy(private val impl: (String) -> Unit) {
    @Suppress("unused") // called from JNI
    fun writeLog(msg: String) {
        impl(msg)
    }
}
