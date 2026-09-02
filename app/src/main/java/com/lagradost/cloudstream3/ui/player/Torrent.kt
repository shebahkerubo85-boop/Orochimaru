package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Stubbed out — torrent support removed to reduce APK size.
 * All public methods return safe no-op values.
 */
object Torrent {
    var hasAcceptedTorrentForThisSession: Boolean? = null

    fun deleteAllFiles(): Boolean = false

    fun clearAll(): Boolean = true

    fun hasServer(): Boolean = false

    /** Returns a TorrentStatus-like object with safe defaults */
    suspend fun get(hash: String): TorrentStatus {
        return TorrentStatus(
            title = "",
            poster = "",
            data = null,
            timestamp = 0,
            name = null,
            hash = hash,
            stat = 0,
            statString = "",
            loadedSize = null,
            torrentSize = null,
            preloadedBytes = null,
            preloadSize = null,
            downloadSpeed = null,
            uploadSpeed = null,
            totalPeers = null,
            pendingPeers = null,
            activePeers = 0,
            connectedSeeders = null,
            halfOpenPeers = null,
            bytesWritten = null,
            bytesWrittenData = null,
            bytesRead = null,
            bytesReadData = null,
            bytesReadUsefulData = null,
            chunksWritten = null,
            chunksRead = null,
            chunksReadUseful = null,
            chunksReadWasted = null,
            piecesDirtiedGood = null,
            piecesDirtiedBad = null,
            durationSeconds = null,
            bitRate = null,
            fileStats = null,
            trackers = null,
        )
    }

    /** Returns the link unchanged and an empty status */
    suspend fun transformLink(link: ExtractorLink): Pair<ExtractorLink, TorrentStatus> {
        return Pair(link, get(""))
    }

    fun isTorrent(link: String): Boolean = false

    data class TorrentStatus(
        val title: String,
        val poster: String,
        val data: String?,
        val timestamp: Long,
        val name: String?,
        val hash: String?,
        val stat: Int,
        val statString: String,
        val loadedSize: Long?,
        val torrentSize: Long?,
        val preloadedBytes: Long?,
        val preloadSize: Long?,
        val downloadSpeed: Double?,
        val uploadSpeed: Double?,
        val totalPeers: Int?,
        val pendingPeers: Int?,
        val activePeers: Int?,
        val connectedSeeders: Int?,
        val halfOpenPeers: Int?,
        val bytesWritten: Long?,
        val bytesWrittenData: Long?,
        val bytesRead: Long?,
        val bytesReadData: Long?,
        val bytesReadUsefulData: Long?,
        val chunksWritten: Long?,
        val chunksRead: Long?,
        val chunksReadUseful: Long?,
        val chunksReadWasted: Long?,
        val piecesDirtiedGood: Long?,
        val piecesDirtiedBad: Long?,
        val durationSeconds: Double?,
        val bitRate: String?,
        val fileStats: List<TorrentFileStat>?,
        val trackers: List<String>?,
    )

    data class TorrentFileStat(
        val id: Int?,
        val path: String?,
        val length: Long?,
    )
}
