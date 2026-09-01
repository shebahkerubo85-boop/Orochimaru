package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi

class StreamM4u : ExtractorApi() {
    override val mainUrl = ""
    override val name = "StreamM4u"
    override val requiresReferer = false
}
