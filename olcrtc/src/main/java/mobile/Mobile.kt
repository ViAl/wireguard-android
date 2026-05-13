package mobile

import android.util.Log

object Mobile {
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("gojni")
            loaded = true
            _init()
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Mobile", "libgojni.so not found: ${e.message}")
            false
        }
    }

    // ---- lifecycle ----
    private external fun _init()
    external fun setProviders()

    external fun start(
        carrierName: String, roomID: String, clientID: String, keyHex: String,
        socksPort: Long, socksUser: String, socksPass: String
    )

    external fun startWithTransport(
        carrierName: String, transportName: String, roomID: String, clientID: String,
        keyHex: String, socksPort: Long, socksUser: String, socksPass: String
    )

    external fun stop()
    external fun isRunning(): Boolean
    external fun waitReady(timeoutMillis: Long)

    // ---- config ----
    external fun setTransport(transport: String)
    external fun setLink(link: String)
    external fun setDNS(dns: String)
    external fun setDebug(debug: Boolean)
    external fun setVP8Options(fps: Long, batchSize: Long)

    // ---- utilities ----
    external fun ping(
        carrierName: String, transportName: String, roomID: String, clientID: String,
        keyHex: String, socksPort: Long, timeoutMillis: Long, pingURL: String,
        vp8FPS: Long, vp8BatchSize: Long
    ): Long

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
