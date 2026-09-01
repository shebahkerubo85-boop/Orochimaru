package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi

class StreamHLS : ExtractorApi() {
    override val mainUrl = ""
    override val name = "StreamHLS"
    override val requiresReferer = false
}
