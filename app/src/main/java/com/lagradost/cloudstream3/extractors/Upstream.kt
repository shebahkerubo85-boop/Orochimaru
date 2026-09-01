package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi

class Upstream : ExtractorApi() {
    override val mainUrl = ""
    override val name = "Upstream"
    override val requiresReferer = false
}
