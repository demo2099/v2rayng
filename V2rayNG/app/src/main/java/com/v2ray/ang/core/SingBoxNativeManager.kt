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
import java.util.concurrent.atomic.AtomicBoolean

object SingBoxNativeManager {
    private val initialized = AtomicBoolean(false)
    private var commandServer: CommandServer? = null
    private val running = AtomicBoolean(false)

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

    fun formatConfig(config: String): String {
        return try {
            Libbox.formatConfig(config).getValue()
        } catch (e: Exception) {
            config
        }
    }
}
