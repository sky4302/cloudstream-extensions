package com.hexated

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

class Furher : Filesim() {
    override var name = "Furher"
    override var mainUrl = "https://furher.in"
}

class Furher2 : Filesim() {
    override var name = "Furher2"
    override var mainUrl = "https://furher.in"
}

class Turbovidhls : Filesim() {
    override var name = "Turbovidhls"
    override var mainUrl = "https://turbovidhls.com"
}

class Co4nxtrl : Filesim() {
    override var name = "Co4nxtrl"
    override var mainUrl = "https://co4nxtrl.com"
    override val requiresReferer = true
}

open class Hownetwork : ExtractorApi() {
    override var name = "Hownetwork"
    override var mainUrl = "https://stream.hownetwork.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfter("id=", "").substringBefore("&")
        if (id.isBlank()) return

        val response = try {
            app.post(
                "$mainUrl/api.php?id=$id",
                data = mapOf(
                    "r" to (referer ?: ""),
                    "d" to mainUrl
                ),
                referer = url,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                )
            ).text
        } catch (_: Exception) {
            return
        }

        val json = try { JSONObject(response) } catch (_: Exception) { return }
        val file = json.optString("file")
        if (file.isBlank()) return

        if (file.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(name, file, referer ?: url).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = file,
                    type = INFER_TYPE
                ) {
                    this.referer = referer ?: url
                }
            )
        }
    }
}

class Cloudhownetwork : Hownetwork() {
    override var mainUrl = "https://cloud.hownetwork.xyz"
}

open class VideoNode : ExtractorApi() {
    override var name = "VideoNode"
    override var mainUrl = "https://videonode.de"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.substringBefore("?")
        val hostMatch = Regex("""(?:iframe\d*|player)/([^/]+)/([^/?#]+)""").find(cleanUrl)
        val host = hostMatch?.groupValues?.getOrNull(1) ?: "p2p"
        val id = hostMatch?.groupValues?.getOrNull(2) ?: cleanUrl.substringAfterLast("/")
        val pageRef = referer ?: "https://tv12.lk21official.cc/"

        val apiRes = try {
            app.post(
                "$mainUrl/api.php",
                data = mapOf("host" to host, "id" to id),
                referer = url,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                )
            ).text
        } catch (_: Exception) {
            null
        }

        if (!apiRes.isNullOrBlank()) {
            val embedUrl = try { JSONObject(apiRes).optString("embedUrl") } catch (_: Exception) { null }
            if (!embedUrl.isNullOrBlank()) {
                if (embedUrl.contains("playcdn.de")) {
                    val slug = embedUrl.substringBefore("?").trimEnd('/').substringAfterLast("/")
                    val verifyUrl = "https://playcdn.de/verify/$slug"
                    try {
                        val verifyRes = app.get(
                            verifyUrl,
                            referer = embedUrl,
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "User-Agent" to USER_AGENT
                            )
                        ).text
                        val json = JSONObject(verifyRes)
                        val fileUrl = json.optString("fileUrl")
                        if (fileUrl.isNotBlank()) {
                            M3u8Helper.generateM3u8(name, fileUrl, "https://playcdn.de/").forEach(callback)
                            return
                        }
                    } catch (_: Exception) {}
                } else if (embedUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, embedUrl, mainUrl).forEach(callback)
                    return
                } else {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                    return
                }
            }
        }

        // Direct page scraping fallback if api.php didn't yield a stream
        try {
            val doc = app.get(
                url,
                referer = pageRef,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).document

            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank() && !src.startsWith("about:") && !src.startsWith("javascript:")) {
                    loadExtractor(src, url, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }
}

open class LKAbyass : ExtractorApi() {
    override var name = "Abyass"
    override var mainUrl = "https://abyss.msmbot.club"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val response = try {
            app.get(
                fixed,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to "https://playhydrax.com",
                    "Referer" to "https://playhydrax.com/"
                ),
                referer = referer ?: "https://playhydrax.com/"
            ).text
        } catch (_: Exception) {
            return
        }

        val datasMatch = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""").find(response)
        if (datasMatch != null) {
            val base64Data = datasMatch.groupValues[1]

            // Try dec-abyss endpoint
            try {
                val decReqBody = JSONObject().put("text", base64Data).toString().toRequestBody("application/json".toMediaTypeOrNull())
                val decResponse = app.post(
                    "https://enc-dec.app/api/dec-abyss",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to "https://playhydrax.com",
                        "Referer" to "https://playhydrax.com/"
                    ),
                    requestBody = decReqBody
                ).text

                val decJson = JSONObject(decResponse)
                val result = decJson.optJSONObject("result")
                val sources = result?.optJSONArray("sources")
                if (sources != null && sources.length() > 0) {
                    for (i in 0 until sources.length()) {
                        val s = sources.getJSONObject(i)
                        val streamUrl = s.optString("url")
                        val qualityStr = s.optString("type")
                        if (streamUrl.isNotBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = if (qualityStr.isNotBlank()) "$name $qualityStr" else name,
                                    url = streamUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://playhydrax.com/"
                                    this.quality = getQualityFromName(qualityStr)
                                }
                            )
                        }
                    }
                    return
                }
            } catch (_: Exception) {}
        }

        Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(response)
            .map { it.value }
            .distinct()
            .forEach { stream ->
                callback.invoke(
                    newExtractorLink(name, name, stream, INFER_TYPE) {
                        this.referer = fixed
                    }
                )
            }
    }
}
