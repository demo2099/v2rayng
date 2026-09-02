package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.util.LogUtil

/**
 * Core Configuration Manager - sing-box implementation.
 *
 * Delegates all configuration generation to SingBoxConfigManager.
 * The old xray-core config generation (V2rayConfig, templates, etc.) has been removed.
 */
object CoreConfigManager {

    /**
     * Build the runtime configuration for normal startup.
     * Delegates to SingBoxConfigManager for sing-box format config.
     */
    fun getV2rayConfig(context: Context, guid: String): ConfigResult {
        return try {
            // Determine if VPN mode (TUN) or proxy-only mode (mixed)
            // VPN mode is determined by whether a VPN interface is available
            // Default to VPN mode; CoreServiceManager will pass the correct mode
            SingBoxConfigManager.getSingBoxConfig(context, guid, vpnMode = true)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get sing-box config", e)
            ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get config: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build a lightweight configuration for latency testing.
     */
    fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
        return try {
            SingBoxConfigManager.getSingBoxConfig(context, guid, vpnMode = false)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get sing-box config for speedtest", e)
            ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get config: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Runs a real latency test for a profile: starts a short-lived sing-box instance that
     * only carries this profile's outbound plus a loopback inbound, then issues one HTTP
     * request through it.
     *
     * @return round trip time in milliseconds, or -1 when the config cannot be built or
     *         the request fails.
     */
    fun measureOutboundDelay(context: Context, guid: String, url: String): Long {
        val port = SingBoxNativeManager.findFreePort()
        val configResult = SingBoxConfigManager.getSpeedtestConfig(context, guid, port)
        if (!configResult.status) {
            LogUtil.e(AppConfig.TAG, "Failed to build speedtest config: ${configResult.errorMessage}")
            return -1L
        }
        return SingBoxNativeManager.measureOutboundDelay(configResult.content, url, port)
    }

    /**
     * Get initial/test configuration.
     */
    fun getInitConfig(context: Context): String {
        return "{}"
    }

    fun getInitConfigWithTun(context: Context): String {
        return "{}"
    }
}
