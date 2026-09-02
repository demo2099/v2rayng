package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File

/**
 * sing-box Configuration Manager
 *
 * Generates sing-box format JSON configurations from ProfileItem data.
 * Supports all protocols: AnyTLS, VMess, VLESS, Shadowsocks, Trojan, WireGuard, Hysteria2, SOCKS, HTTP.
 */
object SingBoxConfigManager {

    /** Outbound tag that the route table sends everything to. */
    private const val TAG_PROXY = "proxy"

    /** Outbound tag used as a fallback inside the speedtest configuration. */
    private const val TAG_DIRECT = "direct"

    /**
     * File (inside the app's private `files/`) the core writes its own log to.
     *
     * gomobile does **not** bridge Go's stdout/stderr into Android's logcat, so sing-box's logs
     * are completely invisible to `adb logcat`. `log.output` is therefore the only way to see why
     * an outbound dial fails. Read it with:
     *   adb shell run-as <pkg> cat files/singbox.log
     */
    private const val CORE_LOG_FILE = "singbox.log"

    /** Same, for the short-lived instance started by the latency (real-ping) test. */
    private const val CORE_SPEEDTEST_LOG_FILE = "singbox_speedtest.log"

    /** Last full config handed to the core. Read with: adb shell run-as <pkg> cat files/debug_last_config.json */
    private const val DEBUG_CONFIG_FILE = "debug_last_config.json"

    /** Last speedtest config handed to the core. */
    private const val DEBUG_SPEEDTEST_CONFIG_FILE = "debug_last_speedtest_config.json"

    /**
     * Generate a complete sing-box configuration for the given profile.
     *
     * @param context Android context
     * @param guid Profile GUID
     * @param vpnMode true for TUN mode (VPN), false for mixed proxy mode
     * @return ConfigResult with the generated JSON
     */
    fun getSingBoxConfig(context: Context, guid: String, vpnMode: Boolean = true): ConfigResult {
        try {
            val profile = MmkvManager.decodeServerConfig(guid)
                ?: return ConfigResult(false, guid, "Profile not found")

            val config = JsonObject().apply {
                add("log", buildLogConfig(context))
                add("dns", buildDnsConfig(context, vpnMode))
                add("inbounds", buildInboundsConfig(context, vpnMode))
                add("outbounds", buildOutboundsConfig(profile))
                add("route", buildRouteConfig(vpnMode))
            }

            val json = JsonUtil.toJsonPretty(config)
            LogUtil.d(AppConfig.TAG, "sing-box config generated: $json")
            // Mirror the exact JSON handed to the core into the app's private dir. logcat truncates
            // long lines and gomobile hides the core's own logs, so this dump is the ground truth
            // for what the core is actually being fed.
            dumpDebugFile(context, DEBUG_CONFIG_FILE, json ?: "")
            return ConfigResult(true, guid, json ?: "")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to generate sing-box config", e)
            return ConfigResult(false, guid, "Failed to generate config: ${e.message}")
        }
    }

    /**
     * Minimal configuration used by the real-ping (latency) test.
     *
     * It contains only a loopback `mixed` inbound on [port] plus the node's own outbound,
     * so a short-lived sing-box instance can measure a real HTTP request that actually
     * travels through the node. No DNS / routing / TUN is involved, which keeps the
     * instance cheap to start and stop.
     *
     * @param port local port the temporary instance should listen on
     */
    fun getSpeedtestConfig(context: Context, guid: String, port: Int): ConfigResult {
        try {
            val profile = MmkvManager.decodeServerConfig(guid)
                ?: return ConfigResult(false, guid, "Profile not found")

            val outbound = buildProtocolOutbound(profile)
                ?: return ConfigResult(false, guid, "Unsupported protocol: ${profile.configType}")
            // Route everything through this single outbound.
            outbound.addProperty("tag", TAG_PROXY)

            val config = JsonObject().apply {
                add("log", JsonObject().apply {
                    // The latency test is the cheapest way to reproduce a broken node: it dials the
                    // node directly with no TUN/routing involved. Capture its log to a file so a
                    // failing node reports the real dial error instead of a bare "-1".
                    addProperty("level", "info")
                    addProperty("timestamp", true)
                    addProperty("output", File(context.filesDir, CORE_SPEEDTEST_LOG_FILE).absolutePath)
                })
                add("inbounds", JsonArray().apply {
                    add(buildMixedInbound(port))
                })
                add("outbounds", JsonArray().apply {
                    add(outbound)
                    add(JsonObject().apply {
                        addProperty("type", "direct")
                        addProperty("tag", TAG_DIRECT)
                    })
                })
                add("route", JsonObject().apply {
                    add("rules", JsonArray())
                    addProperty("final", TAG_PROXY)
                })
            }

            val json = JsonUtil.toJsonPretty(config)
                ?: return ConfigResult(false, guid, "Failed to serialize sing-box config")

            // Start from a clean log file so each test run leaves only its own output behind.
            runCatching { File(context.filesDir, CORE_SPEEDTEST_LOG_FILE).delete() }
            dumpDebugFile(context, DEBUG_SPEEDTEST_CONFIG_FILE, json)
            return ConfigResult(true, guid, json)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to generate speedtest config", e)
            return ConfigResult(false, guid, "Failed to generate speedtest config: ${e.message}")
        }
    }

    // ==================== Log ====================

    private fun buildLogConfig(context: Context): JsonObject = JsonObject().apply {
        // "debug" (instead of "info") so we capture the TUN stack creation, route setup and the
        // first outbound dial — the exact sequence that runs right before the silent VPN-mode crash.
        // Delivered both to `log.output` (buffered) and, line-by-line, to singbox_debug.txt via the
        // CommandServerHandler.writeDebugMessage sink.
        addProperty("level", "debug")
        addProperty("timestamp", true)
        // gomobile does not bridge Go's stdout/stderr into logcat, so `log.level` alone would
        // produce nothing readable. Writing to a file is the only way to get sing-box's logs off
        // the device. Pull with: adb shell run-as <pkg> cat files/singbox.log
        addProperty("output", File(context.filesDir, CORE_LOG_FILE).absolutePath)
    }

    /**
     * Write [content] to `files/[name]` inside the app's private dir so it can be pulled with
     * `adb shell run-as <pkg> cat files/<name>`. Never throws - diagnostics must not break startup.
     */
    private fun dumpDebugFile(context: Context, name: String, content: String) {
        runCatching {
            File(context.filesDir, name).writeText(content)
        }.onFailure {
            LogUtil.w(AppConfig.TAG, "Failed to dump debug file $name: ${it.message}")
        }
    }

    // ==================== DNS ====================

    private fun buildDnsConfig(context: Context, vpnMode: Boolean): JsonObject = JsonObject().apply {
        val servers = JsonArray()

        // Remote DNS (through proxy)
        servers.add(JsonObject().apply {
            addProperty("tag", "remote")
            addProperty("type", "https")
            addProperty("server", SettingsManager.getRemoteDnsServers().firstOrNull() ?: "https://1.1.1.1/dns-query")
            addProperty("detour", "proxy")
        })

        // Local DNS (direct)
        servers.add(JsonObject().apply {
            addProperty("tag", "local")
            addProperty("type", "https")
            addProperty("server", SettingsManager.getDomesticDnsServers().firstOrNull() ?: "https://dns.alidns.com/dns-query")
            addProperty("detour", "direct")
        })

        add("servers", servers)

        // DNS rules
        val rules = JsonArray()

        // Chinese domains use local DNS
        rules.add(JsonObject().apply {
            addProperty("domain_suffix", ".cn")
            addProperty("server", "local")
        })

        // Private IPs use local DNS
        rules.add(JsonObject().apply {
            val cidrList = JsonArray()
            cidrList.add("10.0.0.0/8")
            cidrList.add("172.16.0.0/12")
            cidrList.add("192.168.0.0/16")
            add("ip_cidr", cidrList)
            addProperty("server", "local")
        })

        add("rules", rules)
        addProperty("final", "remote")
        addProperty("strategy", if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6)) "prefer_ipv6" else "prefer_ipv4")
        addProperty("independent_cache", true)
    }

    // ==================== Inbounds ====================

    private fun buildInboundsConfig(context: Context, vpnMode: Boolean): JsonArray = JsonArray().apply {
        if (vpnMode) {
            // TUN inbound for VPN mode
            add(buildTunInbound())
        }

        // A local mixed (SOCKS + HTTP) inbound is always exposed, even in VPN mode.
        // Upstream xray-core based v2rayNG does the same: it keeps a local SOCKS port so
        // that other apps, the built-in delay test and the "my IP" lookup can reach the
        // proxy. The app itself bypasses the TUN (addDisallowedApplication), so
        // connecting to 127.0.0.1 from inside the app reaches this listener.
        val socksPort = SettingsManager.getSocksPort()
        if (socksPort > 0) {
            add(buildMixedInbound(socksPort))
        }
    }

    private fun buildTunInbound(): JsonObject = JsonObject().apply {
        addProperty("type", "tun")
        addProperty("tag", "tun-in")
        addProperty("interface_name", "singtun0")

        // Address
        val addresses = JsonArray()
        addresses.add("172.19.0.1/30")
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            addresses.add("fdfe:dcba:9876::1/126")
        }
        add("address", addresses)

        addProperty("mtu", SettingsManager.getVpnMtu())
        addProperty("auto_route", true)
        addProperty("strict_route", false)
        // "gvisor" is required here. Switching to "mixed"/"system" made EVERY node time out on
        // device: on Android the OS TCP stack cannot be driven through the VpnService fd, so
        // outbound dials never complete. Verified empirically - do not switch to mixed/system.
        addProperty("stack", "gvisor")
        addProperty("endpoint_independent_nat", true)

        // Per-app proxy
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == true) {
            val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
            val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS) == true

            if (!apps.isNullOrEmpty()) {
                if (bypassApps) {
                    val exclude = JsonArray()
                    apps.forEach { exclude.add(it) }
                    add("exclude_package", exclude)
                } else {
                    val include = JsonArray()
                    apps.forEach { include.add(it) }
                    add("include_package", include)
                }
            }
        }
    }

    private fun buildMixedInbound(port: Int): JsonObject = JsonObject().apply {
        addProperty("type", "mixed")
        addProperty("tag", "mixed-in")
        addProperty("listen", "127.0.0.1")
        addProperty("listen_port", port)
    }

    // ==================== Outbounds ====================

    private fun buildOutboundsConfig(profile: ProfileItem): JsonArray = JsonArray().apply {
        // Primary outbound
        val outbound = buildProtocolOutbound(profile)
        if (outbound != null) {
            add(outbound)
        }

        // Selector outbound
        add(JsonObject().apply {
            addProperty("type", "selector")
            addProperty("tag", "proxy")
            val outbounds = JsonArray()
            if (outbound != null) {
                outbounds.add(outbound.get("tag")?.asString ?: "proxy-out")
            }
            outbounds.add("direct")
            add("outbounds", outbounds)
            addProperty("default", outbound?.get("tag")?.asString ?: "direct")
        })

        // Direct outbound
        add(JsonObject().apply {
            addProperty("type", "direct")
            addProperty("tag", "direct")
        })

        // Block outbound
        add(JsonObject().apply {
            addProperty("type", "block")
            addProperty("tag", "block")
        })
    }

    private fun buildProtocolOutbound(profile: ProfileItem): JsonObject? {
        return when (profile.configType) {
            EConfigType.ANYTLS -> buildAnytlsOutbound(profile)
            EConfigType.VMESS -> buildVmessOutbound(profile)
            EConfigType.VLESS -> buildVlessOutbound(profile)
            EConfigType.SHADOWSOCKS -> buildShadowsocksOutbound(profile)
            EConfigType.TROJAN -> buildTrojanOutbound(profile)
            EConfigType.WIREGUARD -> buildWireGuardOutbound(profile)
            EConfigType.HYSTERIA2 -> buildHysteria2Outbound(profile)
            EConfigType.SOCKS -> buildSocksOutbound(profile)
            EConfigType.HTTP -> buildHttpOutbound(profile)
            else -> null
        }
    }

    // ==================== AnyTLS ====================

    private fun buildAnytlsOutbound(profile: ProfileItem): JsonObject = JsonObject().apply {
        addProperty("type", "anytls")
        addProperty("tag", profile.remarks ?: "anytls-out")
        addProperty("server", profile.server)
        addProperty("server_port", profile.serverPort?.toIntOrNull() ?: 443)
        addProperty("password", profile.password)

        // NOTE: do **not** add a "network" field here. sing-box has no `network` dial field (the
        // valid ones are detour / bind_interface / connect_timeout / tcp_fast_open / udp_fragment /
        // domain_resolver / network_strategy / network_type / ...). A bogus field is silently
        // ignored at best and risks strict-parse rejection at worst.

        // TLS settings
        add("tls", buildTlsSettings(profile))
    }

    // ==================== VMess ====================

    private fun buildVmessOutbound(profile: ProfileItem): JsonObject = JsonObject().apply {
        addProperty("type", "vmess")
        addProperty("tag", profile.remarks ?: "vmess-out")
        addProperty("server", profile.server)
        addProperty("server_port", profile.serverPort?.toIntOrNull() ?: 443)
        addProperty("uuid", profile.password) // VMess uses password field for UUID
        addProperty("security", profile.method ?: "auto")
        addProperty("alter_id", 0)

        // Transport
        add("transport", buildTransportSettings(profile))

        // TLS if secured
        if (profile.security.isNotNullEmpty()) {
            add("tls", buildTlsSettings(profile))
        }
    }

    // ==================== VLESS ====================

    private fun buildVlessOutbound(profile: ProfileItem): JsonObject = JsonObject().apply {
        addProperty("type", "vless")
        addProperty("tag", profile.remarks ?: "vless-out")
        addProperty("server", profile.server)
        addProperty("server_port", profile.serverPort?.toIntOrNull() ?: 443)
        addProperty("uuid", profile.password) // VLESS uses password field for UUID

        if (profile.flow.isNotNullEmpty()) {
            addProperty("flow", profile.flow)
        }

        // Transport
        add("transport", buildTransportSettings(profile))

        // TLS/REALITY settings
        add("tls", buildTlsSettings(profile))
    }

    // ==================== Shadowsocks ====================

    private fun buildShadowsocksOutbound(profile: ProfileItem): JsonObject = JsonObject().apply {
        addProperty("type", "shadowsocks")
        addProperty("tag", profile.remarks ?: "ss-out")
        addProperty("server", profile.server)
        addProperty("server_port", profile.serverPort?.toIntOrNull() ?: 8388)
        addProperty("method", profile.method ?: "2022-blake3-aes-128-gcm")
        addProperty("password", profile.password)
    }

    // ==================== Trojan ====================

    private fun buildTrojanOutbound(profile: ProfileItem): JsonObject = JsonObject().apply {
        addProperty("type", "trojan")
        addProperty("tag", profile.remarks ?: "trojan-out")
        addProperty("server", profile.server)
        addProperty("server_port", profile.serverPort?.toIntOrNull() ?: 443)
        addProperty("password", profile.password)

        // Transport
        add("transport", buildTransportSettings(profile))

        // TLS
        add("tls", buildTlsSettings(profile))
    }

    // ==================== WireGuard ====================

    private fun buildWireGuardOutbound(profile: ProfileItem): JsonObject = JsonObject().apply {
        addProperty("type", "wireguard")
        addProperty("tag", profile.remarks ?: "wg-out")
        addProperty("server", profile.server)
        addProperty("server_port", profile.serverPort?.toIntOrNull() ?: 2408)
        addProperty("private_key", profile.secretKey)
        addProperty("peer_public_key", profile.publicKey)

        if (profile.preSharedKey.isNotNullEmpty()) {
            addProperty("pre_shared_key", profile.preSharedKey)
        }

        // Local addresses
        val localAddresses = JsonArray()
        profile.localAddress?.split(",")?.forEach { addr ->
            localAddresses.add(addr.trim())
        }
        add("local_address", localAddresses)

        // Reserved
        if (profile.reserved.isNotNullEmpty()) {
            val reserved = JsonArray()
            profile.reserved?.split(",")?.forEach { r ->
                reserved.add(r.trim().toIntOrNull() ?: 0)
            }
            add("reserved", reserved)
        }

        if (profile.mtu != null) {
            addProperty("mtu", profile.mtu)
        }
    }

    // ==================== Hysteria2 ====================

    private fun buildHysteria2Outbound(profile: ProfileItem): JsonObject = JsonObject().apply {
        addProperty("type", "hysteria2")
        addProperty("tag", profile.remarks ?: "hy2-out")
        addProperty("server", profile.server)
        addProperty("server_port", profile.serverPort?.toIntOrNull() ?: 8443)
        addProperty("password", profile.password)

        // Bandwidth
        val upBw = profile.bandwidthUp?.replace("[^0-9]".toRegex(), "")?.toIntOrNull() ?: 100
        val downBw = profile.bandwidthDown?.replace("[^0-9]".toRegex(), "")?.toIntOrNull() ?: 100
        addProperty("up_mbps", upBw)
        addProperty("down_mbps", downBw)

        // Obfuscation
        if (profile.obfsPassword.isNotNullEmpty()) {
            add("obfs", JsonObject().apply {
                addProperty("type", "salamander")
                addProperty("password", profile.obfsPassword)
            })
        }

        // TLS
        add("tls", buildTlsSettings(profile))
    }

    // ==================== SOCKS ====================

    private fun buildSocksOutbound(profile: ProfileItem): JsonObject = JsonObject().apply {
        addProperty("type", "socks")
        addProperty("tag", profile.remarks ?: "socks-out")
        addProperty("server", profile.server)
        addProperty("server_port", profile.serverPort?.toIntOrNull() ?: 1080)

        if (profile.username.isNotNullEmpty()) {
            addProperty("username", profile.username)
        }
        if (profile.password.isNotNullEmpty()) {
            addProperty("password", profile.password)
        }
    }

    // ==================== HTTP ====================

    private fun buildHttpOutbound(profile: ProfileItem): JsonObject = JsonObject().apply {
        addProperty("type", "http")
        addProperty("tag", profile.remarks ?: "http-out")
        addProperty("server", profile.server)
        addProperty("server_port", profile.serverPort?.toIntOrNull() ?: 80)

        if (profile.username.isNotNullEmpty()) {
            addProperty("username", profile.username)
        }
        if (profile.password.isNotNullEmpty()) {
            addProperty("password", profile.password)
        }
    }

    // ==================== TLS Settings ====================

    private fun buildTlsSettings(profile: ProfileItem): JsonObject = JsonObject().apply {
        val hasTls = profile.security.isNotNullEmpty() || profile.sni.isNotNullEmpty()
        val hasReality = profile.publicKey.isNotNullEmpty()

        addProperty("enabled", true)

        if (profile.sni.isNotNullEmpty()) {
            addProperty("server_name", profile.sni)
        }

        if (profile.insecure == true) {
            addProperty("insecure", true)
        }

        if (profile.alpn.isNotNullEmpty()) {
            val alpn = JsonArray()
            profile.alpn?.split(",")?.forEach { alpn.add(it.trim()) }
            add("alpn", alpn)
        }

        // uTLS fingerprint
        if (profile.fingerPrint.isNotNullEmpty()) {
            add("utls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("fingerprint", profile.fingerPrint)
            })
        }

        // REALITY
        if (hasReality) {
            add("reality", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("public_key", profile.publicKey)
                if (profile.shortId.isNotNullEmpty()) {
                    addProperty("short_id", profile.shortId)
                }
            })
        }
    }

    // ==================== Transport Settings ====================

    private fun buildTransportSettings(profile: ProfileItem): JsonObject = JsonObject().apply {
        val network = NetworkType.fromString(profile.network)

        when (network) {
            NetworkType.WS -> {
                addProperty("type", "ws")
                if (profile.path.isNotNullEmpty()) {
                    addProperty("path", profile.path)
                }
                if (profile.host.isNotNullEmpty()) {
                    val headers = JsonObject()
                    headers.addProperty("Host", profile.host)
                    add("headers", headers)
                }
            }

            NetworkType.GRPC -> {
                addProperty("type", "grpc")
                if (profile.serviceName.isNotNullEmpty()) {
                    addProperty("service_name", profile.serviceName)
                }
            }

            NetworkType.HTTP, NetworkType.H2 -> {
                addProperty("type", "http")
                if (profile.host.isNotNullEmpty()) {
                    val hosts = JsonArray()
                    hosts.add(profile.host)
                    add("host", hosts)
                }
                if (profile.path.isNotNullEmpty()) {
                    addProperty("path", profile.path)
                }
                addProperty("method", "GET")
            }

            NetworkType.HTTP_UPGRADE -> {
                addProperty("type", "httpupgrade")
                if (profile.host.isNotNullEmpty()) {
                    addProperty("host", profile.host)
                }
                if (profile.path.isNotNullEmpty()) {
                    addProperty("path", profile.path)
                }
            }

            else -> {
                // TCP or unknown: default to http transport for compatibility
                addProperty("type", "tcp")
            }
        }
    }

    // ==================== Route Config ====================

    private fun buildRouteConfig(vpnMode: Boolean): JsonObject = JsonObject().apply {
        val rules = JsonArray()

        // Sniffing rule
        rules.add(JsonObject().apply {
            addProperty("action", "sniff")
            addProperty("timeout", "1s")
        })

        // DNS hijack
        rules.add(JsonObject().apply {
            addProperty("protocol", "dns")
            addProperty("action", "hijack-dns")
        })

        // Private IP direct
        rules.add(JsonObject().apply {
            addProperty("ip_is_private", true)
            addProperty("outbound", "direct")
        })

        // Block QUIC
        rules.add(JsonObject().apply {
            val portList = JsonArray()
            portList.add(443)
            add("port", portList)
            val network = JsonArray()
            network.add("udp")
            add("network", network)
            addProperty("action", "reject")
        })

        add("rules", rules)
        addProperty("final", "proxy")
        // sing-box 1.13 removed `auto_detect_interface`; outbounds that dial domains need an
        // explicit resolver, otherwise `startOrReloadService` rejects the config.
        addProperty("default_domain_resolver", "local")
    }
}
