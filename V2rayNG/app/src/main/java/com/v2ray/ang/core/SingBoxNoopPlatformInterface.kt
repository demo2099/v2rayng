package com.v2ray.ang.core

import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetAddress

/**
 * Minimal [PlatformInterface] for the short-lived sing-box instance that backs the
 * real-ping (latency) test.
 *
 * That instance only exposes a local `mixed` inbound, so it never needs a TUN fd and
 * every VPN related hook is intentionally a no-op. Reusing [SingBoxPlatformInterface]
 * is not possible here because it requires a running [android.net.VpnService].
 */
class SingBoxNoopPlatformInterface : PlatformInterface {

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = false

    override fun autoDetectInterfaceControl(fd: Int) {
        // no TUN to protect
    }

    override fun openTun(options: TunOptions): Int = -1

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val owner = ConnectionOwner()
        owner.userId = -1
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // not needed for a loopback only instance
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // not needed for a loopback only instance
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false
            override fun next(): NetworkInterface = NetworkInterface()
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {
        // no-op
    }

    override fun systemCertificates(): StringIterator {
        return SingBoxPlatformInterface.StringArray(emptyList<String>().iterator())
    }

    override fun localDNSTransport(): LocalDNSTransport {
        return object : LocalDNSTransport {
            override fun raw(): Boolean = false

            override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
                try {
                    val addresses = InetAddress.getAllByName(domain)
                    ctx.success(addresses.mapNotNull { it.hostAddress }.joinToString("\n"))
                } catch (e: Exception) {
                    ctx.errorCode(3)
                }
            }

            override fun exchange(ctx: ExchangeContext, message: ByteArray) {
                ctx.errorCode(0)
            }
        }
    }

    override fun sendNotification(notification: Notification) {
        // no-op
    }
}
