package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi

class YoutubeNoCookieExtractor : ExtractorApi() {
    override val mainUrl = ""
    override val name = "YoutubeNoCookieExtractor"
    override val requiresReferer = false
}
