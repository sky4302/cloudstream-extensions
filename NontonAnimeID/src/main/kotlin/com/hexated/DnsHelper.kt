package com.hexated

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class CloudflareDoHDns(private val cacheTtlMinutes: Long = 10L) : Dns {
    private data class CachedRecord(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CachedRecord>()

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .dns(Dns.SYSTEM)
            .build()
    }

    private val ipPattern = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    override fun lookup(hostname: String): List<InetAddress> {
        if (ipPattern.matcher(hostname).matches() ||
            hostname.equals("localhost", ignoreCase = true) ||
            hostname.endsWith(".local", ignoreCase = true)
        ) {
            return try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: Exception) {
                throw UnknownHostException("Direct lookup failed for $hostname: ${e.message}")
            }
        }

        val now = System.currentTimeMillis()
        val cached = cache[hostname]
        if (cached != null && now < cached.expiresAt) {
            return cached.addresses
        }

        // Try Google DoH first
        val googleAddresses = queryGoogle(hostname)
        if (googleAddresses.isNotEmpty()) {
            cache[hostname] = CachedRecord(
                addresses = googleAddresses,
                expiresAt = now + TimeUnit.MINUTES.toMillis(cacheTtlMinutes)
            )
            return googleAddresses
        }

        // Fallback to Cloudflare DoH (1.1.1.1 or 1.0.0.1)
        var cfAddresses = queryCloudflare(hostname, "1.1.1.1")
        if (cfAddresses.isEmpty()) {
            cfAddresses = queryCloudflare(hostname, "1.0.0.1")
        }
        if (cfAddresses.isNotEmpty()) {
            cache[hostname] = CachedRecord(
                addresses = cfAddresses,
                expiresAt = now + TimeUnit.MINUTES.toMillis(cacheTtlMinutes)
            )
            return cfAddresses
        }

        // Fallback to System DNS filtered
        return try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname).filter { addr ->
                val ip = addr.hostAddress ?: ""
                !ip.startsWith("118.98.") && !ip.startsWith("36.86.") && !ip.startsWith("198.54.100.")
            }
            if (systemAddresses.isNotEmpty()) {
                systemAddresses
            } else {
                throw UnknownHostException("No valid unblocked addresses found for $hostname")
            }
        } catch (e: Exception) {
            throw UnknownHostException("DoH and System DNS lookup failed for $hostname: ${e.message}")
        }
    }

    private fun queryGoogle(hostname: String): List<InetAddress> {
        val url = "https://8.8.8.8/resolve?name=$hostname&type=A"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Host", "dns.google")
                .header("Accept", "application/json")
                .build()

            bootstrapClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val body = res.body?.string() ?: return emptyList()
                parseDnsJson(body)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun queryCloudflare(hostname: String, ip: String = "1.1.1.1"): List<InetAddress> {
        val url = "https://$ip/dns-query?name=$hostname&type=A"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Host", "cloudflare-dns.com")
                .header("Accept", "application/dns-json")
                .build()

            bootstrapClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val body = res.body?.string() ?: return emptyList()
                parseDnsJson(body)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseDnsJson(jsonStr: String): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        return try {
            val root = JSONObject(jsonStr)
            val answer = root.optJSONArray("Answer") ?: return emptyList()
            for (i in 0 until answer.length()) {
                val item = answer.optJSONObject(i) ?: continue
                if (item.optInt("type") == 1) { // Type 1 = A record
                    val ipStr = item.optString("data")
                    if (ipStr.isNotBlank() &&
                        !ipStr.startsWith("118.98.") &&
                        !ipStr.startsWith("36.86.") &&
                        !ipStr.startsWith("198.54.100.")
                    ) {
                        try {
                            result.add(InetAddress.getByName(ipStr))
                        } catch (_: Throwable) {}
                    }
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
