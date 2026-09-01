package com.lagradost.cloudstream3.metaproviders

object SyncRedirector {
    suspend fun redirect(
        url: String,
        api: Any?
    ): String {
        return url
    }
}
