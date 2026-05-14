package com.wireguard.android.rtc.native

import com.wireguard.android.rtc.RtcEngine
import com.wireguard.android.rtc.RtcRunInfo
import com.wireguard.android.rtc.config.OlcRtcTunnelConfig
import java.lang.reflect.Proxy

class OlcRtcNativeEngine(
    private val logSink: (String) -> Unit,
    private val socksPort: Int = DEFAULT_SOCKS_PORT,
) : RtcEngine {
    private val mobileClass: Class<*> by lazy {
        findMobileClass() ?: throw OlcRtcNativeUnavailableException(
            "OlcRTC native artifacts are missing. Add olcrtc-classes.jar and libgojni.so to rtc/src/main."
        )
    }

    override fun start(config: OlcRtcTunnelConfig): RtcRunInfo {
        ensureNativeLoaded()
        configureLogWriterIfAvailable()
        invokeIfExists("setTransport", arrayOf(String::class.java), arrayOf(config.transport.uriValue))
        invokeIfExists("setDebug", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(false))

        val masked = config.maskedKey()
        logSink("Using carrier=${config.carrier.uriValue}, transport=${config.transport.uriValue}, roomId=${config.roomId}, clientId=${config.clientId}, key=$masked")
        logSink("Calling native OlcRTC start")

        val started = invokeStart(config)
        if (!started) throw IllegalStateException("OlcRTC did not report running state")

        waitReadyIfAvailable()
        logSink("OlcRTC ready")
        return RtcRunInfo(config.effectiveDisplayName, socksPort)
    }

    override fun stop() {
        ensureNativeLoaded()
        invokeRequired("stop", emptyArray(), emptyArray())
        logSink("OlcRTC stopped")
    }

    override fun isRunning(): Boolean {
        return runCatching {
            ensureNativeLoaded()
            val result = invokeIfExists("isRunning", emptyArray(), emptyArray())
            result as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun ensureNativeLoaded() {
        try {
            System.loadLibrary("gojni")
        } catch (e: UnsatisfiedLinkError) {
            throw OlcRtcNativeUnavailableException(
                "OlcRTC native library is missing for this ABI. Add libgojni.so to rtc/src/main/jniLibs/<abi>/.",
                e,
            )
        }
    }

    private fun invokeStart(config: OlcRtcTunnelConfig): Boolean {
        val withTransport = invokeIfExists(
            "startWithTransport",
            arrayOf(String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, Long::class.javaPrimitiveType!!, String::class.java, String::class.java),
            arrayOf(config.carrier.uriValue, config.transport.uriValue, config.roomId, config.clientId, config.keyHex, socksPort.toLong(), "", ""),
        )
        if (withTransport != null) return isRunning()

        invokeRequired(
            "start",
            arrayOf(String::class.java, String::class.java, String::class.java, String::class.java),
            arrayOf(config.carrier.uriValue, config.roomId, config.clientId, config.keyHex),
        )
        return isRunning()
    }

    private fun waitReadyIfAvailable() {
        invokeIfExists("waitReady", arrayOf(Long::class.javaPrimitiveType!!), arrayOf(READY_TIMEOUT_MS))
    }

    private fun configureLogWriterIfAvailable() {
        val writerInterface = mobileClass.classLoader?.loadClass("mobile.LogWriter") ?: return
        val proxy = Proxy.newProxyInstance(mobileClass.classLoader, arrayOf(writerInterface)) { _, method, args ->
            if (method.name == "write") {
                val message = args?.firstOrNull()?.toString().orEmpty()
                if (message.isNotBlank()) logSink("native: ${message.trim()}")
            }
            null
        }
        invokeIfExists("setLogWriter", arrayOf(writerInterface), arrayOf(proxy))
    }

    private fun invokeRequired(name: String, paramTypes: Array<Class<*>>, args: Array<Any?>): Any? {
        return invokeIfExists(name, paramTypes, args)
            ?: throw OlcRtcNativeUnavailableException("Required OlcRTC API method missing: $name")
    }

    private fun invokeIfExists(name: String, paramTypes: Array<Class<*>>, args: Array<Any?>): Any? {
        val method = runCatching { mobileClass.getMethod(name, *paramTypes) }.getOrNull() ?: return null
        return method.invoke(null, *args)
    }

    private fun findMobileClass(): Class<*>? {
        val cl = javaClass.classLoader
        return runCatching { Class.forName("mobile.Mobile", false, cl) }.getOrNull()
            ?: runCatching { Class.forName("go.mobile.Mobile", false, cl) }.getOrNull()
    }

    companion object {
        private const val READY_TIMEOUT_MS = 15_000L
        private const val DEFAULT_SOCKS_PORT = 10808
    }
}
