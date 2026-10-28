package com.hexated

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PencuriMovieSubMalayPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PencuriMovieSubMalay())
        registerExtractorAPI(DhtprePmsm())
        registerExtractorAPI(NetuPmsm())
        registerExtractorAPI(Playerxupns())
        registerExtractorAPI(Playerxp2p())
        registerExtractorAPI(Playerxseek())
        registerExtractorAPI(Playerxrpms())
        registerExtractorAPI(Player4me())
        registerExtractorAPI(Ezplayer())
        registerExtractorAPI(YandexcdnPmsm())
        registerExtractorAPI(Larhu())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(AbyssMsmbotExtractor())
    }
}
