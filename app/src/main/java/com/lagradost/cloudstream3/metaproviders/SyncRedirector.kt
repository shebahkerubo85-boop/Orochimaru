package com.lagradost.cloudstream3.metaproviders

import com.lagradost.cloudstream3.LoadResponse

object SyncRedirector {
    suspend fun redirect(
        url: String,
        api: com.lagradost.cloudstream3.APIName?
    ): LoadResponse? {
        return null
    }
}
