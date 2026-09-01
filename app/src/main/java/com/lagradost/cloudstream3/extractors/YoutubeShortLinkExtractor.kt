package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi

class YoutubeShortLinkExtractor : ExtractorApi() {
    override val mainUrl = ""
    override val name = "YoutubeShortLinkExtractor"
    override val requiresReferer = false
}
