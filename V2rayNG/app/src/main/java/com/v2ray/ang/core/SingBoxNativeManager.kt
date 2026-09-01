package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sing-box Native Library Manager
 *
 * Thread-safe singleton wrapper for libbox (sing-box) native methods.
 * Replaces CoreNativeManager (xray-core) for full sing-box integration.
 */
object SingBoxNativeManager {
    private val initialized = AtomicBoolean(false)
    private var boxService: io.nekohasekai.libbox.BoxService? = null
    private val running = AtomicBoolean(false)

    /**
     * Initialize sing-box core environment.
     * Must be called once in Application.onCreate().
     */
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

    /**
     * Get sing-box core version.
     */
    fun getLibVersion(): String {
        return try {
            Libbox.version()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to check sing-box version", e)
            "Unknown"
        }
    }

    /**
     * Validate a sing-box configuration JSON string.
     */
    fun checkConfig(config: String): Boolean {
        return try {
            Libbox.checkConfig(config)
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "sing-box config check failed: ${e.message}")
            false
        }
    }

    /**
     * Start the sing-box service with the given configuration.
     *
     * @param config sing-box JSON configuration string
     * @param platform PlatformInterface implementation for Android TUN/network
     */
    @Synchronized
    fun startService(config: String, platform: io.nekohasekai.libbox.PlatformInterface) {
        if (running.get()) {
            LogUtil.w(AppConfig.TAG, "sing-box service already running, stopping first")
            stopService()
        }

        try {
            LogUtil.i(AppConfig.TAG, "Starting sing-box service...")
            val service = Libbox.newService(config, platform)
            boxService = service
            service.start()
            running.set(true)
            LogUtil.i(AppConfig.TAG, "sing-box service started successfully")
        } catch (e: Exception) {
            running.set(false)
            LogUtil.e(AppConfig.TAG, "Failed to start sing-box service", e)
            throw e
        }
    }

    /**
     * Stop the running sing-box service.
     */
    @Synchronized
    fun stopService() {
        try {
            boxService?.close()
            LogUtil.i(AppConfig.TAG, "sing-box service stopped")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to stop sing-box service", e)
        } finally {
            boxService = null
            running.set(false)
        }
    }

    /**
     * Check if the sing-box service is running.
     */
    fun isRunning(): Boolean = running.get()

    /**
     * Format a JSON configuration string (pretty print).
     */
    fun formatConfig(config: String): String {
        return try {
            Libbox.formatConfig(config)
        } catch (e: Exception) {
            config
        }
    }
}
