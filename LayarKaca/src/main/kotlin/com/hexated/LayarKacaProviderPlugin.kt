package com.hexated

import android.content.Context
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LayarKacaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LayarKacaProvider())
        registerExtractorAPI(VideoNode())
        registerExtractorAPI(Hownetwork())
        registerExtractorAPI(Cloudhownetwork())
        registerExtractorAPI(Furher())
        registerExtractorAPI(Furher2())
        registerExtractorAPI(Turbovidhls())
        registerExtractorAPI(Co4nxtrl())
        registerExtractorAPI(LKAbyass())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(FilemoonV2())
        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Voe())
        registerExtractorAPI(Mp4Upload())
    }
}
