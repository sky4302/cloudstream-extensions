package com.hexated

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Otakudesu : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "ongoing-anime/page/" to "Anime Ongoing",
        "complete-anime/page/" to "Anime Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}$page/", timeout = 30L).document
        val home = document.select("div.venz ul li, div.rseries div.rapi ul li").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = this.selectFirst("h2.jdlflm, h2, .jdlarea h2")?.text()?.trim()
            ?: a.text().trim()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-src")
        )

        val epText = this.selectFirst("div.epz, div.epztipe")?.text()?.trim().orEmpty()
        val epNum = Regex("""(\d+)""").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (epNum != null) addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=anime", timeout = 30L).document
        return document.select("ul.chivsrc li").mapNotNull { li ->
            val a = li.selectFirst("h2 a") ?: li.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val poster = fixUrlNull(li.selectFirst("img")?.attr("src"))
            val rating = li.selectFirst("div.set:contains(Rating)")?.text()
                ?.substringAfter(":")?.trim()?.toDoubleOrNull()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 30L).document

        val title = document.selectFirst("div.infozingle p:contains(Judul) span")?.text()?.trim()
            ?: document.selectFirst("div.jdlarea h1, h1.entry-title")?.text()?.trim().orEmpty()

        val poster = fixUrlNull(
            document.selectFirst("div.fotoanime img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val synopsis = document.select("div.sinopc p").joinToString("\n\n") { it.text().trim() }
            .ifBlank { document.selectFirst("meta[property='og:description']")?.attr("content") }

        val genres = document.select("div.infozingle p:contains(Genre) a").map { it.text().trim() }
            .filter { it.isNotBlank() }

        val rating = document.selectFirst("div.infozingle p:contains(Skor) span")?.text()?.trim()

        val isMovie = url.contains("/anime-movie/") || genres.any { it.contains("Movie", true) }

        val episodes = mutableListOf<Episode>()
        document.select("div.episodelist ul li").forEach { li ->
            val a = li.selectFirst("span a[href]") ?: li.selectFirst("a[href]") ?: return@forEach
            val epHref = fixUrl(a.attr("href"))
            if (!epHref.contains("/episode/")) return@forEach

            val epName = a.text().trim()
            val epNum = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(epHref)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

            episodes.add(
                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster
                }
            )
        }

        return if (isMovie && episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = genres
                if (rating != null) addScore(rating, 10)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed().distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = genres
                if (rating != null) addScore(rating, 10)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = 30L).document
        val html = document.html()

        // 1. Direct embeds in page
        document.select("div.responsive-embed-stream iframe, div#pembed iframe, iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.startsWith("about:") && !src.contains("ads", true)) {
                resolveOtakuStream(src, data, subtitleCallback, callback)
            }
        }

        // 2. Resolve mirrorstream options
        val actions = Regex("""action:\s*["']([a-f0-9]{32})["']""").findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        val nonceAction = if (actions.size >= 2) actions[1] else "aa1208d27f29ca340c92c66d1926f13f"
        val streamAction = if (actions.isNotEmpty()) actions[0] else "2a3505c93b0035d3f455df82bf976b84"

        val nonce = try {
            val nonceRes = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf("action" to nonceAction),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
            JSONObject(nonceRes).optString("data", "")
        } catch (_: Exception) {
            ""
        }

        if (nonce.isNotBlank()) {
            document.select("div.mirrorstream ul li a[data-content]").amap { a ->
                val b64 = a.attr("data-content")
                try {
                    val rawJson = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                    val json = JSONObject(rawJson)
                    val id = json.opt("id")?.toString() ?: ""
                    val i = json.opt("i")?.toString() ?: ""
                    val q = json.optString("q", "")

                    val res = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to streamAction,
                            "nonce" to nonce,
                            "id" to id,
                            "i" to i,
                            "q" to q
                        ),
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).text

                    val embedB64 = JSONObject(res).optString("data", "")
                    if (embedB64.isNotBlank()) {
                        val decodedHtml = String(Base64.decode(embedB64, Base64.DEFAULT), Charsets.UTF_8)
                        val iframeSrc = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")
                        if (!iframeSrc.isNullOrBlank()) {
                            resolveOtakuStream(iframeSrc, data, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Resolve download mirrors (Hxfile, KrakenFiles, Mega, etc.)
        document.select("div.download ul li a[href*='link.desustream.com']").amap { a ->
            val redirectLink = a.attr("href")
            try {
                val realUrl = app.get(redirectLink, referer = data, timeout = 15L).url
                if (realUrl.isNotBlank() && !realUrl.contains("desustream.com", true)) {
                    loadExtractor(realUrl, data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        return true
    }

    private suspend fun resolveOtakuStream(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url

        if (fixed.contains("desustream.net") || fixed.contains("desustream.info") || fixed.contains("desustream.me")) {
            try {
                val embedText = app.get(fixed, referer = referer, timeout = 20L).text

                // Check for Blogger video iframe
                val bloggerMatch = Regex("""src=["'](https?://www\.blogger\.com/video\.g\?[^"']+)["']""").find(embedText)
                if (bloggerMatch != null) {
                    loadExtractor(bloggerMatch.groupValues[1], fixed, subtitleCallback, callback)
                }

                // Check for Archive.org / direct MP4 playerjs
                val fileMatch = Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(embedText)
                if (fileMatch != null) {
                    val streamUrl = fileMatch.groupValues[1]
                    callback.invoke(
                        newExtractorLink("Otakudesu", "DesuStream", streamUrl, INFER_TYPE) {
                            this.referer = fixed
                        }
                    )
                }

                // Check for secondary iframe inside player
                val innerIframe = Jsoup.parse(embedText).selectFirst("iframe#myIframe, iframe")?.attr("src")
                if (!innerIframe.isNullOrBlank() && innerIframe != fixed) {
                    loadExtractor(innerIframe, fixed, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        } else {
            loadExtractor(fixed, referer, subtitleCallback, callback)
        }
    }
}
