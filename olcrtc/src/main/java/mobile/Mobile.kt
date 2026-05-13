package mobile

import android.util.Log

object Mobile {
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("gojni")
            loaded = true
            _1init()
            true  // setProviders() called before each start, not at load
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Mobile", "libgojni.so not found: ${e.message}")
            false
        }
    }

    // ---- lifecycle ----
    private external fun _1init()
    external fun setProviders()  // calling this each time before start

    external fun start(
        carrierName: String, roomID: String, clientID: String, keyHex: String,
        socksPort: Int, socksUser: String, socksPass: String
    ): String?

    external fun startWithTransport(
        carrierName: String, transportName: String, roomID: String, clientID: String,
        keyHex: String, socksPort: Int, socksUser: String, socksPass: String
    ): String?

    external fun stop()
    external fun isRunning(): Boolean
    external fun waitReady(timeoutMillis: Long): String?  // FIXED: added timeout param

    // ---- config ----
    external fun setTransport(transport: String)
    external fun setLink(link: String)
    external fun setDNS(dns: String)
    external fun setDebug(debug: Boolean)
    external fun setVP8Options(fps: Int, batchSize: Int)

    // ---- utilities ----
    external fun ping(
        carrierName: String, transportName: String, roomID: String, clientID: String,
        keyHex: String, socksPort: Int, timeoutMillis: Long, pingURL: String,
        vp8FPS: Long, vp8BatchSize: Long
    ): Long

    external fun check(
        carrierName: String, transportName: String, roomID: String, clientID: String,
        keyHex: String, socksPort: Int, timeoutMillis: Int, vp8FPS: Int, vp8BatchSize: Int
    ): Long

    // ---- protect / log (gomobile-style interfaces) ----
    external fun setProtector(protector: SocketProtectorInterface)
    external fun setLogWriter(writer: LogWriterInterface)
}

/**
 * Must match gomobile-generated mobile.SocketProtector interface.
 * JNI: `apply` creates a proxy implementing this interface.
 */
interface SocketProtectorInterface {
    fun protect(fd: Long): Boolean  // NOTE: Long, not Int! gomobile uses long for fd
}

/**
 * Must match gomobile-generated mobile.LogWriter interface.
 */
interface LogWriterInterface {
    fun writeLog(msg: String)
}
