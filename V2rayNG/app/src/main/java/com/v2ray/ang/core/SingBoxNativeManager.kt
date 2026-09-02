package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object SingBoxNativeManager {
    private const val DELAY_TEST_TIMEOUT_MS = 10_000
    private const val DELAY_TEST_STARTUP_TIMEOUT_MS = 5_000
    private const val DELAY_TEST_PORT_RANGE_START = 20_000
    private const val LOOPBACK_HOST = "127.0.0.1"

    private val initialized = AtomicBoolean(false)
    private var commandServer: CommandServer? = null
    private val running = AtomicBoolean(false)

    /** Serializes the short-lived delay-test instances, they share process wide globals. */
    private val delayTestLock = Any()

    fun initCoreEnv(context: Context?) {
        if (initialized.compareAndSet(false, true)) {
            try {
                val ctx = context?.applicationContext ?: return
                val basePath = ctx.filesDir.absolutePath
                val workingPath = "$basePath/sing-box"
                val tempPath = ctx.cacheDir.absolutePath

                File(workingPath).mkdirs()

                val options = SetupOptions()
                options.basePath = basePath
                options.workingPath = workingPath
                options.tempPath = tempPath
                options.logMaxLines = 300
                options.fixAndroidStack = true

                Libbox.setup(options)
                LogUtil.i(AppConfig.TAG, "sing-box core environment initialized successfully")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to initialize sing-box core environment", e)
                initialized.set(false)
                throw e
            }
        } else {
            LogUtil.d(AppConfig.TAG, "sing-box core environment already initialized, skipping")
        }
    }

    fun getLibVersion(): String {
        return try {
            Libbox.version()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to check sing-box version", e)
            "Unknown"
        }
    }

    fun checkConfig(config: String): Boolean {
        return try {
            Libbox.checkConfig(config)
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "sing-box config check failed: ${e.message}")
            false
        }
    }

    @Synchronized
    fun startService(config: String, platform: PlatformInterface) {
        if (running.get()) {
            LogUtil.w(AppConfig.TAG, "sing-box service already running, stopping first")
            stopService()
        }

        try {
            LogUtil.i(AppConfig.TAG, "Starting sing-box service...")

            val handler = object : CommandServerHandler {
                override fun serviceReload() {
                    LogUtil.d(AppConfig.TAG, "sing-box service reload requested")
                }

                override fun serviceStop() {
                    LogUtil.d(AppConfig.TAG, "sing-box service stop requested")
                    stopService()
                }

                override fun getSystemProxyStatus(): SystemProxyStatus {
                    val status = SystemProxyStatus()
                    status.available = false
                    status.enabled = false
                    return status
                }

                override fun setSystemProxyEnabled(enabled: Boolean) {}

                override fun writeDebugMessage(message: String) {
                    LogUtil.d(AppConfig.TAG, "sing-box: $message")
                }
            }

            val server = CommandServer(handler, platform)
            server.start()
            server.startOrReloadService(config, OverrideOptions())
            commandServer = server
            running.set(true)
            LogUtil.i(AppConfig.TAG, "sing-box service started successfully")
        } catch (e: Exception) {
            running.set(false)
            LogUtil.e(AppConfig.TAG, "Failed to start sing-box service", e)
            throw e
        }
    }

    @Synchronized
    fun stopService() {
        try {
            commandServer?.closeService()
            commandServer?.close()
            LogUtil.i(AppConfig.TAG, "sing-box service stopped")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to stop sing-box service", e)
        } finally {
            commandServer = null
            running.set(false)
        }
    }

    fun isRunning(): Boolean = running.get()

    // ==================== Delay test (real ping) ====================

    /**
     * Measures the real latency of a node: starts a short-lived sing-box instance that
     * exposes a loopback `mixed` inbound, then issues a single HTTP request through it.
     *
     * libbox has no `measureOutboundDelay` equivalent, so this replaces the previous
     * implementation, which issued a **direct** HttpURLConnection request and therefore
     * reported the device's own connectivity (and returned -1 whenever the delay test URL
     * was not reachable directly) instead of the node's latency.
     *
     * [CommandServer.start] is deliberately **not** called: `start()` binds
     * `<basePath>/command.sock` and deletes any existing socket file first, which would
     * hijack the socket belonging to the main VPN service. `startOrReloadService` does not
     * depend on the gRPC listener, so the temporary instance stays out of the way.
     *
     * @param configContent speedtest configuration produced by
     *                      [SingBoxConfigManager.getSpeedtestConfig]
     * @param url latency test URL
     * @param proxyPort loopback port the temporary instance listens on
     * @return round trip time in milliseconds, or -1 on failure
     */
    fun measureOutboundDelay(
        configContent: String,
        url: String,
        proxyPort: Int,
        timeoutMs: Int = DELAY_TEST_TIMEOUT_MS
    ): Long {
        if (!initialized.get()) {
            LogUtil.e(AppConfig.TAG, "Delay-Test: sing-box environment is not initialized")
            return -1L
        }
        return synchronized(delayTestLock) {
            runDelayTest(configContent, url, proxyPort, timeoutMs)
        }
    }

    private fun runDelayTest(configContent: String, url: String, proxyPort: Int, timeoutMs: Int): Long {
        var server: CommandServer? = null
        try {
            if (!checkConfig(configContent)) {
                LogUtil.e(AppConfig.TAG, "Delay-Test: sing-box rejected the config")
                return -1L
            }

            server = Libbox.newCommandServer(DelayTestCommandServerHandler(), SingBoxNoopPlatformInterface())
            server.startOrReloadService(configContent, OverrideOptions())

            if (!waitForLoopbackPort(proxyPort)) {
                LogUtil.e(AppConfig.TAG, "Delay-Test: local port $proxyPort never started listening")
                return -1L
            }

            val startedAt = System.currentTimeMillis()
            val responseCode = requestThroughProxy(url, proxyPort, timeoutMs)
            if (responseCode !in 200..399) {
                LogUtil.e(AppConfig.TAG, "Delay-Test: unexpected response code $responseCode")
                return -1L
            }
            return System.currentTimeMillis() - startedAt
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Delay-Test: ${e.message}")
            return -1L
        } finally {
            closeDelayTestServer(server)
        }
    }

    private fun waitForLoopbackPort(port: Int, timeoutMs: Int = DELAY_TEST_STARTUP_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(LOOPBACK_HOST, port), 300)
                }
                return true
            } catch (_: Exception) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    private fun requestThroughProxy(url: String, port: Int, timeoutMs: Int): Int {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK_HOST, port))
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        return try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connect()
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun closeDelayTestServer(server: CommandServer?) {
        try {
            server?.closeService()
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "Delay-Test: closeService failed: ${e.message}")
        }
        try {
            server?.close()
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "Delay-Test: close failed: ${e.message}")
        }
    }

    /** Picks a free TCP port for the temporary delay-test instance. */
    fun findFreePort(): Int {
        return try {
            Libbox.availablePort(DELAY_TEST_PORT_RANGE_START)
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "Delay-Test: availablePort failed, falling back to ServerSocket: ${e.message}")
            ServerSocket(0).use { it.localPort }
        }
    }

    private class DelayTestCommandServerHandler : CommandServerHandler {
        override fun serviceReload() {}

        override fun serviceStop() {}

        override fun getSystemProxyStatus(): SystemProxyStatus {
            val status = SystemProxyStatus()
            status.available = false
            status.enabled = false
            return status
        }

        override fun setSystemProxyEnabled(enabled: Boolean) {}

        override fun writeDebugMessage(message: String) {
            LogUtil.d(AppConfig.TAG, "sing-box(delay-test): $message")
        }
    }

    fun formatConfig(config: String): String {
        return try {
            Libbox.formatConfig(config).getValue()
        } catch (e: Exception) {
            config
        }
    }
}
