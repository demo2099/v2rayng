package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VPN Service for sing-box integration.
 *
 * With sing-box, the TUN interface is created by sing-box through PlatformInterface.openTun().
 * This service only handles VPN permission and lifecycle management.
 */
@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private var isRunning = false
    private val isStartingLock = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
        unlockStart()
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        if (isSystemVpnStart) {
            unlockStart()
        }
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return START_NOT_STICKY
        }
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        if (!prepareVpnService()) {
            unlockStart()
            stopSelf()
            return START_NOT_STICKY
        }
        startService()
        return START_STICKY
    }

    override fun getService(): Service = this

    override fun startService() {
        // sing-box creates the TUN through PlatformInterface.openTun() callback
        // We just need to start the core loop
        if (!CoreServiceManager.startCoreLoop(null)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }

        isRunning = true
        RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Prepares VPN permission.
     * sing-box handles TUN creation through PlatformInterface.openTun().
     */
    private fun prepareVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }
        return true
    }

    private fun stopAllService(isForced: Boolean = true) {
        unlockStart()
        isRunning = false

        RootLanSharing.stopClientSharing(this)
        CoreServiceManager.stopCoreLoop()

        if (isForced) {
            stopSelf()
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
            }
        }
    }

    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}
