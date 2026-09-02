package com.v2ray.ang.core

import android.content.Context
import android.util.Log
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

    /** Persisted sing-box base path (== app filesDir) so we can dump start errors to a file
     *  even when logcat is unavailable (gomobile does not bridge Go logs to logcat). */
    private var basePath: String? = null

    /** Serializes the short-lived delay-test instances, they share process wide globals. */
    private val delayTestLock = Any()

    fun initCoreEnv(context: Context?) {
        val ctx = context?.applicationContext ?: return
        if (initialized.compareAndSet(false, true)) {
            try {
                val basePath = ctx.filesDir.absolutePath
                this.basePath = basePath
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
    fun startService(config: String, platform: PlatformInterface, context: Context) {
        if (running.get()) {
            LogUtil.w(AppConfig.TAG, "sing-box service already running, stopping first")
            stopService()
        }

        try {
            LogUtil.i(AppConfig.TAG, "Starting sing-box service...")

            // Persist sing-box's own log lines (delivered through writeDebugMessage) to a file as
            // they arrive. gomobile does NOT bridge Go stderr to logcat, and the `log.output` file is
            // buffered, so a crash that kills the process before a flush loses every line. Writing
            // here per-line (and flushing) captures the LAST thing sing-box did before it died -- the
            // single most useful clue for an otherwise-silent VPN-mode crash.
            val debugLog = File(context.filesDir, "singbox_debug.txt")
            val debugWriter = debugLog.bufferedWriter()
            val handler = object : CommandServerHandler {
                override fun serviceReload() {
                    LogUtil.d(AppConfig.TAG, "sing-box service reload requested")
                }

                override fun serviceStop() {
                    LogUtil.d(AppConfig.TAG, "sing-box service stop requested")
                    runCatching {
                        debugWriter.flush()
                        debugWriter.close()
                    }
                    runCatching {
                        File(context.filesDir, "service_stopped.txt")
                            .writeText("time=${System.currentTimeMillis()}\n")
                    }
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
                    runCatching {
                        debugWriter.append(message).append('\n')
                        debugWriter.flush()
                    }
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
            dumpStartError(context, e)
            throw e
        }
    }

    /**
     * Persists the exact start failure to filesDir/start_error.txt.
     *
     * gomobile does NOT bridge Go stdout/stderr into Android logcat, and the fork never creates
     * a CommandClient, so the exception thrown here is otherwise invisible. `context.filesDir`
     * is used directly (NOT the cached [basePath] from initCoreEnv, which can be null if
     * initCoreEnv was first called with a null context) so the file is always written. Retrieve
     * with `adb shell run-as com.v2ray.ang.fdroid cat files/start_error.txt`.
     */
    private fun dumpStartError(context: Context, e: Exception) {
        runCatching {
            val dir = context.filesDir.absolutePath
            val sb = StringBuilder()
            sb.append("time=").append(System.currentTimeMillis()).append("\n")
            sb.append("msg=").append(e.message).append("\n")
            sb.append("stack:\n").append(Log.getStackTraceString(e)).append("\n")
            File(dir, "start_error.txt").writeText(sb.toString())
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
