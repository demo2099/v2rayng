package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.delay
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference

/**
 * Core Service Manager - sing-box implementation.
 *
 * Manages the lifecycle of the sing-box core. Replaces the xray-core based CoreServiceManager.
 */
object CoreServiceManager {

    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var networkMonitor: NetworkMonitor? = null
    private var platformInterface: SingBoxPlatformInterface? = null

    @Volatile
    private var isReloading = false

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            SingBoxNativeManager.initCoreEnv(service)
        }

    /**
     * Checks if the sing-box service is running.
     */
    fun isRunning() = SingBoxNativeManager.isRunning()

    /**
     * Gets the name of the currently running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the sing-box core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            return false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, com.v2ray.ang.util.Utils.receiverFlags())

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        startNetworkMonitor(service)
    }

    @Throws(Exception::class)
    private fun launchCore(service: Service, vpnInterface: ParcelFileDescriptor?, isReload: Boolean = false) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting sing-box for ${config.remarks}")

        // Determine if we're in VPN mode (TUN) or proxy-only mode (mixed)
        val vpnMode = vpnInterface != null

        // Generate sing-box config
        val result = SingBoxConfigManager.getSingBoxConfig(service, guid, vpnMode)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to generate sing-box config" })
        }

        currentConfig = config

        // Create PlatformInterface with per-app proxy settings
        val perAppEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == true
        val apps = if (perAppEnabled) MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET) else null
        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS) == true

        platformInterface = SingBoxPlatformInterface(
            service = service as android.net.VpnService,
            packagesToProxy = if (perAppEnabled && !bypassApps) apps else null,
            bypassPackages = if (perAppEnabled && bypassApps) apps else null
        )

        NotificationManager.showNotification(currentConfig)

        // Start sing-box
        SingBoxNativeManager.startService(result.content, platformInterface!!)

        if (!isRunning()) {
            error("sing-box failed to start")
        }

        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: sing-box started successfully")
    }

    /**
     * Stops the sing-box core service.
     */
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null

        if (isRunning()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    SingBoxNativeManager.stopService()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop sing-box", e)
                }
            }
        }

        platformInterface = null

        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
    }

    private fun reloadCore(): Boolean {
        if (isReloading) return false
        val service = getService() ?: return false
        if (!isRunning()) return false

        return try {
            val tunFd = currentVpnInterface

            isReloading = true
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")

            SingBoxNativeManager.stopService()
            launchCore(service, tunFd, isReload = true)

            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            false
        } finally {
            isReloading = false
        }
    }

    /**
     * Traffic stats are not directly available from sing-box BoxService.
     * Returns empty list for now; could be implemented via CommandClient in the future.
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        return emptyList()
    }

    /**
     * Measures the connection delay.
     * Uses a simple HTTP request through the proxy instead of sing-box's measureDelay.
     */
    private fun measureV2rayDelay() {
        if (!isRunning()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                // Simple delay test via HTTP request
                val url = SettingsManager.getDelayTestUrl()
                val startTime = System.currentTimeMillis()
                val connection = java.net.URL(url).openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()
                time = System.currentTimeMillis() - startTime
                connection.disconnect()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":").orEmpty()
            }

            val endpoint = if (time >= 0) SpeedtestManager.getRemoteIPInfo() else null
            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
                country = endpoint?.country,
                ipAddress = endpoint?.ipAddress,
            )
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)
        }
    }

    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            serviceControl.stopService()
                            delay(500L)
                            LauncherManager.startService(serviceControl.getService())
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
