package com.v2ray.ang.core

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

class SingBoxPlatformInterface(
    private val service: VpnService,
    private val packagesToProxy: Set<String>? = null,
    private val bypassPackages: Set<String>? = null
) : PlatformInterface {

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var underlyingNetwork: Network? = null

    private val connectivity: ConnectivityManager
        get() = service.getSystemService(ConnectivityManager::class.java)

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        service.protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        return try {
            openTunInner(options)
        } catch (e: Exception) {
            // gomobile never forwards Go/JNI exceptions to logcat; capture the precise TUN
            // failure to a file so it can be pulled with run-as even when logcat stays empty.
            runCatching {
                val sb = StringBuilder()
                sb.append("time=").append(System.currentTimeMillis()).append("\n")
                sb.append("msg=").append(e.message).append("\n")
                sb.append("stack:\n").append(Log.getStackTraceString(e)).append("\n")
                File(service.filesDir, "tun_error.txt").writeText(sb.toString())
            }
            throw e
        }
    }

    private fun openTunInner(options: TunOptions): Int {
        val builder = service.Builder()
        builder.setMtu(options.mtu)

        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val prefix: RoutePrefix = inet4.next()
            builder.addAddress(InetAddress.getByName(prefix.address()), prefix.prefix())
        }
        val inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val prefix: RoutePrefix = inet6.next()
            builder.addAddress(InetAddress.getByName(prefix.address()), prefix.prefix())
        }

        if (options.autoRoute) {
            val r4 = options.inet4RouteAddress
            while (r4.hasNext()) {
                val a: RoutePrefix = r4.next()
                builder.addRoute(InetAddress.getByName(a.address()), a.prefix())
            }
            val r6 = options.inet6RouteAddress
            while (r6.hasNext()) {
                val a: RoutePrefix = r6.next()
                builder.addRoute(InetAddress.getByName(a.address()), a.prefix())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val ex4 = options.inet4RouteExcludeAddress
                while (ex4.hasNext()) {
                    val a: RoutePrefix = ex4.next()
                    try {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(a.address()), a.prefix()))
                    } catch (e: Exception) {
                        LogUtil.w(AppConfig.TAG, "Failed to exclude IPv4 route: ${a.address()}/${a.prefix()}")
                    }
                }
                val ex6 = options.inet6RouteExcludeAddress
                while (ex6.hasNext()) {
                    val a: RoutePrefix = ex6.next()
                    try {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(a.address()), a.prefix()))
                    } catch (e: Exception) {
                        LogUtil.w(AppConfig.TAG, "Failed to exclude IPv6 route: ${a.address()}/${a.prefix()}")
                    }
                }
            }

            try {
                val dnsStr = options.dnsServerAddress
                val dnsAddr = dnsStr.getValue()
                if (!dnsAddr.isNullOrEmpty()) {
                    for (addr in dnsAddr.split("\n")) {
                        if (addr.isNotBlank()) {
                            builder.addDnsServer(InetAddress.getByName(addr.trim()))
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Failed to add DNS server from TunOptions", e)
            }
        }

        val includePkgs = options.includePackage
        while (includePkgs.hasNext()) {
            val pkg = includePkgs.next()
            try {
                builder.addAllowedApplication(pkg)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Failed to allow package: $pkg")
            }
        }
        val excludePkgs = options.excludePackage
        while (excludePkgs.hasNext()) {
            val pkg = excludePkgs.next()
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Failed to disallow package: $pkg")
            }
        }

        try {
            builder.addDisallowedApplication(service.packageName)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to disallow own package", e)
        }

        val pfd = builder.establish() ?: throw Exception("VPN not prepared or permission denied")
        return pfd.fd
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val owner = ConnectionOwner()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val uid = connectivity.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort)
                )
                owner.userId = uid
                val packages = service.packageManager.getPackagesForUid(uid)
                if (!packages.isNullOrEmpty()) {
                    owner.setAndroidPackageNames(StringArray(packages.iterator()))
                }
                return owner
            } catch (e: Exception) {
                LogUtil.d(AppConfig.TAG, "findConnectionOwner failed: ${e.message}")
            }
        }
        owner.userId = -1
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = connectivity
        underlyingNetwork = cm.activeNetwork
        underlyingNetwork?.let { notifyInterfaceUpdate(it, listener) }

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                underlyingNetwork = network
                notifyInterfaceUpdate(network, listener)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                underlyingNetwork = network
                notifyInterfaceUpdate(network, listener)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
                underlyingNetwork = network
                val ifName = lp.interfaceName ?: ""
                val ifIndex = getInterfaceIndex(ifName)
                listener.updateDefaultInterface(ifName, ifIndex, false, false)
            }

            override fun onLost(network: Network) {
                if (underlyingNetwork == network) {
                    underlyingNetwork = null
                }
                listener.updateDefaultInterface("", -1, false, false)
            }
        }

        try {
            cm.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to register network callback", e)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallback?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
            networkCallback = null
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = connectivity
        val networks = cm.allNetworks
        val systemInterfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        val interfaces = mutableListOf<LibboxNetworkInterface>()

        for (network in networks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val sysIf = systemInterfaces.find { it.name == lp.interfaceName } ?: continue

            val boxIf = LibboxNetworkInterface()
            boxIf.name = lp.interfaceName
            boxIf.index = sysIf.index
            try {
                boxIf.mtu = sysIf.mtu
            } catch (_: Exception) {
            }
            boxIf.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                else -> 3
            }
            boxIf.dnsServer = StringArray(lp.dnsServers.mapNotNull { it.hostAddress }.iterator())
            boxIf.addresses = StringArray(sysIf.interfaceAddresses.map { ia ->
                "${ia.address.hostAddress}/${ia.networkPrefixLength}"
            }.iterator())

            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (sysIf.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (sysIf.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (sysIf.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            boxIf.flags = flags
            boxIf.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxIf)
        }

        return object : NetworkInterfaceIterator {
            private val iter = interfaces.iterator()
            override fun hasNext(): Boolean = iter.hasNext()
            override fun next(): LibboxNetworkInterface = iter.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}

    override fun systemCertificates(): StringIterator {
        return StringArray(emptyList<String>().iterator())
    }

    override fun localDNSTransport(): LocalDNSTransport {
        return object : LocalDNSTransport {
            override fun raw(): Boolean = false

            override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
                try {
                    val net = underlyingNetwork ?: connectivity.activeNetwork
                    val addresses = if (net != null) {
                        net.getAllByName(domain)
                    } else {
                        InetAddress.getAllByName(domain)
                    }
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

    override fun sendNotification(notification: Notification) {}

    private fun notifyInterfaceUpdate(network: Network, listener: InterfaceUpdateListener) {
        val cm = connectivity
        val lp = cm.getLinkProperties(network) ?: return
        val ifName = lp.interfaceName ?: ""
        val ifIndex = getInterfaceIndex(ifName)
        listener.updateDefaultInterface(ifName, ifIndex, false, false)
    }

    private fun getInterfaceIndex(name: String): Int {
        try {
            val nif = NetworkInterface.getByName(name) ?: return -1
            return nif.index
        } catch (_: Exception) {
            return -1
        }
    }

    class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }
}
