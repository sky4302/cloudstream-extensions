package com.hexated

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DrakorIDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DrakorID())
    }
}
