package com.hexated

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DhtprePmsm : VidhideExtractor() {
    override var mainUrl = "https://dhtpre.com"
}

class NetuPmsm : VidhideExtractor() {
    override var mainUrl = "https://netu.msmbot.club"
}

class Playerxupns : VidStack() {
    override var name = "Playerxupns"
    override var mainUrl = "https://playerx.upns.live"
    override var requiresReferer = true
}

class Playerxp2p : VidStack() {
    override var name = "Playerxp2p"
    override var mainUrl = "https://playerx.p2pstream.online"
    override var requiresReferer = true
}

class Playerxseek : VidStack() {
    override var name = "Playerxseek"
    override var mainUrl = "https://playerx.seekplays.online"
    override var requiresReferer = true
}

class Playerxrpms : VidStack() {
    override var name = "Playerxrpms"
    override var mainUrl = "https://playerx.rpmstream.online"
    override var requiresReferer = true
}

class Player4me : VidStack() {
    override var name = "Player4me"
    override var mainUrl = "https://playerx.player4me.online"
    override var requiresReferer = true
}

class Ezplayer : VidStack() {
    override var name = "Ezplayer"
    override var mainUrl = "https://playerx.ezplayer.stream"
    override var requiresReferer = true
}

class YandexcdnPmsm : VidhideExtractor() {
    override var mainUrl = "https://yandexcdn.com"
}

class Larhu : ExtractorApi() {
    override var name = "Larhu"
    override var mainUrl = "https://larhu.website"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try { app.get(url).text } catch (_: Exception) { return }

        val streamurl = Regex("""file\s*:\s*"([^"]+\.(m3u8|mp4))"""")
            .find(doc)
            ?.groupValues?.get(1)
            ?: return

        if (streamurl.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(name, streamurl, url).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamurl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

open class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyss.to"
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
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                ),
                referer = referer ?: mainUrl
            ).text
        } catch (_: Exception) {
            return
        }

        val datasMatch = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""").find(response)
        if (datasMatch != null) {
            val base64Data = datasMatch.groupValues[1]
            try {
                val rawDecoded = String(Base64.decode(base64Data, Base64.DEFAULT), Charsets.ISO_8859_1)
                val json = JSONObject(rawDecoded)
                val userId = json.opt("user_id")?.toString() ?: ""
                val slug = json.optString("slug")
                val md5Id = json.opt("md5_id")?.toString() ?: ""
                val media = json.optString("media")

                if (userId.isNotEmpty() && slug.isNotEmpty() && md5Id.isNotEmpty() && media.isNotEmpty()) {
                    val keyString = "$userId:$slug:$md5Id"
                    val md5Digest = MessageDigest.getInstance("MD5").digest(keyString.toByteArray(Charsets.UTF_8))
                    val md5Hex = md5Digest.joinToString("") { "%02x".format(it) }

                    val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
                    val ivBytes = keyBytes.copyOfRange(0, 16)

                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    val secretKey = SecretKeySpec(keyBytes, "AES")
                    val ivSpec = IvParameterSpec(ivBytes)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                    val mediaBytes = ByteArray(media.length) { media[it].code.toByte() }
                    val decryptedString = String(cipher.doFinal(mediaBytes), Charsets.UTF_8)

                    val decryptedJson = JSONObject(decryptedString)
                    val mp4 = decryptedJson.optJSONObject("mp4")
                    val sources = mp4?.optJSONArray("sources") ?: decryptedJson.optJSONArray("sources")
                    if (sources != null && sources.length() > 0) {
                        for (i in 0 until sources.length()) {
                            val srcObj = sources.optJSONObject(i) ?: continue
                            val srcUrl = srcObj.optString("url").trimEnd('/')
                            val path = srcObj.optString("path").trimStart('/')
                            val label = srcObj.optString("label").ifBlank { "HD" }
                            val streamUrl = if (path.isNotBlank()) "$srcUrl/$path" else srcUrl
                            val quality = getQualityFromName(label)

                            callback.invoke(
                                newExtractorLink(name, if (label.isNotBlank()) "$name $label" else name, streamUrl, INFER_TYPE) {
                                    this.referer = fixed
                                    this.quality = quality
                                }
                            )
                        }
                        return
                    }
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

class AbyssMsmbotExtractor : AbyssExtractor() {
    override val mainUrl = "https://abyss.msmbot.club"
}
