package com.lagradost.cloudstream3.utils

/** Minimal host of [ResumeWatchingResult]; the real CloudStream version extends SearchResponse. */
object DataStoreHelper {
    var currentAccount: Int = 0

    data class ResumeWatchingResult(
        val id: Int? = null,
        val parentId: Int? = null,
    )
}
