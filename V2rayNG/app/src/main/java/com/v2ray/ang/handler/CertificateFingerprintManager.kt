package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CertSha256Result
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Certificate Fingerprint Manager - sing-box compatible implementation.
 *
 * Uses standard Java SSL/TLS APIs to fetch certificate fingerprints,
 * replacing the xray-core Libv2ray.fetchTlsCertSha256() calls.
 */
object CertificateFingerprintManager {
    private const val TIMEOUT_MS = 5000L

    fun fetchForManualFill(profile: ProfileItem): String? {
        if (!isFetchable(profile)) return null

        val server = profile.server?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it > 0 } ?: AppConfig.DEFAULT_PORT
        val sni = inferServerName(profile) ?: server

        return try {
            fetchCertSha256(server, port, sni)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Fetch cert SHA-256 failed", e)
            null
        }
    }

    private fun isFetchable(profile: ProfileItem): Boolean {
        return profile.configType == EConfigType.HYSTERIA2 || profile.security == AppConfig.TLS
    }

    private fun fetchCertSha256(host: String, port: Int, sni: String): String? {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        val factory: SSLSocketFactory = sslContext.socketFactory

        val conn = java.net.URL("https://$host:$port").openConnection() as HttpsURLConnection
        conn.apply {
            sslSocketFactory = factory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connectTimeout = TIMEOUT_MS.toInt()
            readTimeout = TIMEOUT_MS.toInt()
            requestMethod = "HEAD"
            setRequestProperty("Host", sni)
        }

        return try {
            conn.connect()
            val certs = conn.serverCertificates
            if (certs.isNotEmpty()) {
                val cert = certs[0] as X509Certificate
                val digest = MessageDigest.getInstance("SHA-256")
                val sha256 = digest.digest(cert.encoded)
                sha256.joinToString("") { "%02x".format(it) }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun inferServerName(profile: ProfileItem): String? {
        val sni = profile.sni?.takeIf { it.isNotBlank() }
        return sni?.takeUnless { Utils.isPureIpAddress(it) }
    }
}
