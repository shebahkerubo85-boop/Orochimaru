package com.lagradost.cloudstream3.ui.player

/**
 * Stubbed out — torrent support removed to reduce APK size.
 * All public methods return safe no-op values.
 */
object Torrent {
    var hasAcceptedTorrentForThisSession: Boolean? = null

    fun deleteAllFiles(): Boolean = false

    fun clearAll(): Boolean = true

    fun hasServer(): Boolean = false

    suspend fun get(hash: String): Pair<String?, Boolean> = Pair(null, false)

    suspend fun transformLink(link: String): Pair<String, Boolean> = Pair(link, false)

    fun isTorrent(link: String): Boolean = false
}
