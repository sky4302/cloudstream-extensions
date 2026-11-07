package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Hxfile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class EmbedKotakAnimeid : Hxfile() {
    override var name = "EmbedKotakAnimeid"
    override var mainUrl = "https://embed2.kotakanimeid.com"
    override val requiresReferer = true
}

class KotakAnimeidCom : Hxfile() {
    override var name = "KotakAnimeid"
    override var mainUrl = "https://kotakanimeid.com"
    override val requiresReferer = true
}

class Kotaksb : Hxfile() {
    override var name = "Kotaksb"
    override var mainUrl = "https://kotaksb.fun"
    override val requiresReferer = true
}

class Nontonanimeid : Hxfile() {
    override var name = "Nontonanimeid"
    override var mainUrl = "https://nontonanimeid.com"
    override val requiresReferer = true
}

class Gdplayer : ExtractorApi() {
    override var name = "Gdplayer"
    override var mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    data class Response(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("sources") val sources: ArrayList<Sources>? = null
    ) {
        data class Sources(
            @JsonProperty("file") val file: String? = null,
            @JsonProperty("type") val type: String? = null
        )
    }

    private fun getQuality(str: String): Int {
        return Regex("(\\d{3,4})p?").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val html = document.html()
        val kaken = html.substringAfter("kaken = \"", "").substringBefore("\"").takeIf { it.isNotBlank() } ?: return

        val apiUrl = "$mainUrl/api/?$kaken=&_=${System.currentTimeMillis()}"
        val response = app.get(
            apiUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Response>() ?: return

        response.sources?.forEach { source ->
            val file = source.file ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = file,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = getQuality(response.title.orEmpty())
                }
            )
        }
    }
}

class DailymotionExtractor : ExtractorApi() {
    override var name = "Dailymotion"
    override var mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

    data class DmMetadata(
        @JsonProperty("qualities") val qualities: DmQualities? = null
    ) {
        data class DmQualities(
            @JsonProperty("auto") val auto: ArrayList<DmSource>? = null
        ) {
            data class DmSource(
                @JsonProperty("url") val url: String? = null
            )
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("(?:video|embed/video)/([a-zA-Z0-9]+)").find(url)?.groupValues?.getOrNull(1) ?: return
        val metadata = app.get("https://www.dailymotion.com/player/metadata/video/$videoId").parsedSafe<DmMetadata>() ?: return
        val streamUrl = metadata.qualities?.auto?.firstOrNull()?.url ?: return

        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = streamUrl,
            referer = "https://www.dailymotion.com/"
        ).forEach(callback)
    }
}
