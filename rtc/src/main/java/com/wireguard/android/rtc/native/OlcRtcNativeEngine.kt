package com.wireguard.android.rtc.native

import android.os.Build
import com.wireguard.android.rtc.RtcEngine
import com.wireguard.android.rtc.RtcRunInfo
import com.wireguard.android.rtc.config.OlcRtcTunnelConfig
import java.lang.reflect.InvocationTargetException
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
        invokeOptional("setTransport", arrayOf(String::class.java), arrayOf(config.transport.uriValue))
        invokeOptional("setDebug", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(false))

        logSink("Calling native OlcRTC start")
        startNative(config)
        waitReadyIfAvailable()

        val runningSignal = isRunning()
        if (!runningSignal) logSink("isRunning API unavailable/false after start; relying on start/waitReady completion")
        logSink("OlcRTC ready")
        return RtcRunInfo(config.effectiveDisplayName, socksPort)
    }

    override fun stop() {
        ensureNativeLoaded()
        invokeRequired("stop", emptyArray(), emptyArray())
        logSink("OlcRTC stopped")
    }

    override fun isRunning(): Boolean {
        ensureNativeLoaded()
        return when (val result = invokeOptional("isRunning", emptyArray(), emptyArray())) {
            InvokeResult.Missing -> false
            is InvokeResult.Called -> result.value as? Boolean ?: false
        }
    }

    private fun startNative(config: OlcRtcTunnelConfig) {
        when (invokeOptional(
            "startWithTransport",
            arrayOf(String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, Long::class.javaPrimitiveType!!, String::class.java, String::class.java),
            arrayOf(config.carrier.uriValue, config.transport.uriValue, config.roomId, config.clientId, config.keyHex, socksPort.toLong(), "", ""),
        )) {
            InvokeResult.Missing -> {
                logSink("startWithTransport API not available; falling back to start")
                invokeRequired(
                    "start",
                    arrayOf(String::class.java, String::class.java, String::class.java, String::class.java),
                    arrayOf(config.carrier.uriValue, config.roomId, config.clientId, config.keyHex),
                )
            }
            is InvokeResult.Called -> return
        }
    }

    private fun waitReadyIfAvailable() {
        when (val result = invokeOptional("waitReady", arrayOf(Long::class.javaPrimitiveType!!), arrayOf(READY_TIMEOUT_MS))) {
            InvokeResult.Missing -> logSink("waitReady API not available; assuming start call completed")
            is InvokeResult.Called -> if (result.value is Boolean && !result.value) {
                throw IllegalStateException("OlcRTC start timed out while waiting for readiness")
            }
        }
    }

    private fun ensureNativeLoaded() {
        try {
            System.loadLibrary("gojni")
        } catch (e: UnsatisfiedLinkError) {
            val deviceAbis = Build.SUPPORTED_ABIS?.joinToString() ?: "unknown"
            throw OlcRtcNativeUnavailableException(
                "OlcRTC library is missing for this ABI. Device ABIs: $deviceAbis. " +
                    "Place libgojni.so under rtc/src/main/jniLibs/<abi>/ (for example arm64-v8a and x86_64).",
                e,
            )
        }
    }

    private fun configureLogWriterIfAvailable() {
        val writerInterface = findLogWriterInterface() ?: return
        val proxy = Proxy.newProxyInstance(mobileClass.classLoader, arrayOf(writerInterface)) { _, method, args ->
            if (method.name == "write" || method.name == "writeLog") {
                val message = args?.firstOrNull()?.toString().orEmpty()
                if (message.isNotBlank()) logSink("native: ${message.trim()}")
            }
            null
        }
        invokeOptional("setLogWriter", arrayOf(writerInterface), arrayOf(proxy))
    }


    private fun findLogWriterInterface(): Class<*>? {
        val cl = mobileClass.classLoader ?: return null
        return runCatching { cl.loadClass("mobile.LogWriter") }.getOrNull()
            ?: runCatching { cl.loadClass("go.mobile.LogWriter") }.getOrNull()
    }

    private fun invokeRequired(name: String, paramTypes: Array<Class<*>>, args: Array<Any?>): Any? {
        return when (val result = invokeOptional(name, paramTypes, args)) {
            InvokeResult.Missing -> throw OlcRtcNativeUnavailableException("Required OlcRTC API method missing: $name")
            is InvokeResult.Called -> result.value
        }
    }

    private fun invokeOptional(name: String, paramTypes: Array<Class<*>>, args: Array<Any?>): InvokeResult {
        val method = runCatching { mobileClass.getMethod(name, *paramTypes) }.getOrNull() ?: return InvokeResult.Missing
        return try {
            InvokeResult.Called(method.invoke(null, *args))
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e.cause ?: e)
        }
    }

    private fun findMobileClass(): Class<*>? {
        val cl = javaClass.classLoader
        return runCatching { Class.forName("mobile.Mobile", false, cl) }.getOrNull()
            ?: runCatching { Class.forName("go.mobile.Mobile", false, cl) }.getOrNull()
    }

    private sealed class InvokeResult {
        data object Missing : InvokeResult()
        data class Called(val value: Any?) : InvokeResult()
    }

    companion object {
        private const val READY_TIMEOUT_MS = 15_000L
        private const val DEFAULT_SOCKS_PORT = 10808
    }
}
