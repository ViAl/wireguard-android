package mobile

import android.util.Log

object Mobile {
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("gojni")
            loaded = true
            // _1init() is deliberately NOT called.
            // libgojni.so's _1init() tries to FindClass() gomobile-generated proxy classes
            // (proxySocketProtector, proxyLogWriter) that don't exist in our hand-written setup.
            // This only matters if Go→Java callbacks are used, which we handle via interfaces.
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Mobile", "libgojni.so not found: ${e.message}")
            false
        }
    }

    // ---- lifecycle ----
    private external fun _1init()
    external fun setProviders()

    @Throws(Exception::class)
    external fun start(
        carrierName: String, roomID: String, clientID: String, keyHex: String,
        socksPort: Long, socksUser: String, socksPass: String
    )

    @Throws(Exception::class)
    external fun startWithTransport(
        carrierName: String, transportName: String, roomID: String, clientID: String,
        keyHex: String, socksPort: Long, socksUser: String, socksPass: String
    )

    external fun stop()
    external fun isRunning(): Boolean
    @Throws(Exception::class)
    external fun waitReady(timeoutMillis: Long)

    // ---- config ----
    external fun setTransport(transport: String)
    external fun setLink(link: String)
    external fun setDNS(dns: String)
    external fun setDebug(debug: Boolean)
    external fun setVP8Options(fps: Long, batchSize: Long)

    // ---- utilities ----
    @Throws(Exception::class)
    external fun ping(
        carrierName: String, transportName: String, roomID: String, clientID: String,
        keyHex: String, socksPort: Long, timeoutMillis: Long, pingURL: String,
        vp8FPS: Long, vp8BatchSize: Long
    ): Long

    @Throws(Exception::class)
    external fun check(
        carrierName: String, transportName: String, roomID: String, clientID: String,
        keyHex: String, socksPort: Long, timeoutMillis: Long, vp8FPS: Long, vp8BatchSize: Long
    ): Long

    // ---- protect / log ----
    external fun setProtector(protector: SocketProtector)
    external fun setLogWriter(writer: LogWriter)
}

interface SocketProtector {
    fun protect(fd: Long): Boolean
}

interface LogWriter {
    fun writeLog(msg: String)
}
