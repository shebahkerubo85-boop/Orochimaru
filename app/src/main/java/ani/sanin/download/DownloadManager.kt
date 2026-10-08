package ani.sanin.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File

/**
 * Process-wide download engine. Owns the queue, the completed index and the small worker pool that
 * drives [Downloader]. The UI observes [queue] and [completed]; everything else goes through the
 * public methods here.
 */
object DownloadManager {

    private lateinit var appContext: Context
    private lateinit var downloader: Downloader
    private lateinit var notifier: DownloadNotifier
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val items = mutableListOf<DownloadItem>()
    private var completedItems = mutableListOf<DownloadedItem>()
    private val activeJobs = mutableMapOf<String, Job>()

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()

    private val _completed = MutableStateFlow<List<DownloadedItem>>(emptyList())
    val completed: StateFlow<List<DownloadedItem>> = _completed.asStateFlow()

    private var lastPersist = 0L

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        downloader = Downloader(appContext, Downloader.defaultClient())
        notifier = DownloadNotifier(appContext)
        items.clear()
        items.addAll(DownloadStore.loadQueue())
        completedItems = DownloadStore.loadCompleted().toMutableList()
        // Downloads that were mid-flight when the process died go back to queued.
        items.forEach {
            if (it.status == DownloadStatus.DOWNLOADING) it.status = DownloadStatus.QUEUED
        }
        publish()
    }

    // ---------------------------------------------------------------- queries

    fun isDownloaded(mediaId: Int, episodeNumber: String): Boolean =
        completedItems.any { it.mediaId == mediaId && it.episodeNumber == episodeNumber }

    fun downloadedFor(mediaId: Int): List<DownloadedItem> =
        completedItems.filter { it.mediaId == mediaId }

    fun findCompleted(mediaId: Int, episodeNumber: String): DownloadedItem? =
        completedItems.firstOrNull { it.mediaId == mediaId && it.episodeNumber == episodeNumber }

    fun itemFor(mediaId: Int, episodeNumber: String): DownloadItem? =
        items.firstOrNull { it.mediaId == mediaId && it.episodeNumber == episodeNumber }

    // ---------------------------------------------------------------- mutations

    fun enqueue(item: DownloadItem) {
        if (isDownloaded(item.mediaId, item.episodeNumber)) return
        items.removeAll { it.id == item.id }
        items.add(item)
        persist()
        publish()
        pump()
    }

    fun pause(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.status == DownloadStatus.DOWNLOADING) {
            item.status = DownloadStatus.PAUSED
            activeJobs.remove(id)?.cancel()
        } else if (item.status == DownloadStatus.QUEUED) {
            item.status = DownloadStatus.PAUSED
        }
        persist()
        publish()
    }

    fun resume(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        item.status = DownloadStatus.QUEUED
        item.error = null
        persist()
        publish()
        pump()
    }

    fun retry(id: String) = resume(id)

    fun cancel(id: String) {
        activeJobs.remove(id)?.cancel()
        items.removeAll { it.id == id }
        persist()
        publish()
        pump()
    }

    fun removeCompleted(id: String, deleteFile: Boolean = true) {
        val item = completedItems.firstOrNull { it.id == id } ?: return
        if (deleteFile) deleteDownloadedFile(item)
        completedItems.removeAll { it.id == id }
        persistCompleted()
        publishCompleted()
    }

    fun clearCompleted(deleteFiles: Boolean = true) {
        if (deleteFiles) completedItems.forEach { deleteDownloadedFile(it) }
        completedItems.clear()
        persistCompleted()
        publishCompleted()
    }

    fun deleteAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        items.clear()
        clearCompleted(true)
    }

    private fun deleteDownloadedFile(item: DownloadedItem) {
        try {
            if (item.path.startsWith("content://")) {
                android.net.Uri.parse(item.path).let { uri ->
                    android.provider.DocumentsContract.deleteDocument(
                        appContext.contentResolver,
                        uri,
                    )
                }
            } else {
                File(item.path).takeIf { it.exists() }?.delete()
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- engine

    private fun maxConcurrent(): Int =
        PrefManager.getVal<Int>(PrefName.DownloadConcurrency).coerceIn(1, 4)

    private fun pump() {
        if (!mutex.tryLock()) return
        try {
            while (activeJobs.size < maxConcurrent()) {
                val next = items.firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
                start(next)
            }
            if (items.none { it.isActive } && activeJobs.isEmpty()) {
                notifier.clearActive()
                DownloadService.stop(appContext)
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun start(item: DownloadItem) {
        if (PrefManager.getVal<Boolean>(PrefName.DownloadOnlyOverWifi) && !isOnWifi()) {
            item.status = DownloadStatus.PAUSED
            item.error = "Waiting for Wi-Fi"
            persist()
            publish()
            return
        }
        DownloadService.start(appContext)
        item.status = DownloadStatus.DOWNLOADING
        item.error = null
        val job = scope.launch(start = CoroutineStart.LAZY) { run(item) }
        activeJobs[item.id] = job
        job.start()
    }

    private suspend fun run(item: DownloadItem) {
        publish()
        var temp: File? = null
        try {
            var lastPublish = 0L
            temp = downloader.download(
                item,
                onProgress = { done, total, fraction ->
                    item.downloadedBytes = done
                    when {
                        total > 0 -> {
                            item.totalBytes = total
                            item.progress = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                        fraction >= 0f -> {
                            item.progress = fraction.coerceIn(0f, 1f)
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPublish > 300L) {
                        lastPublish = now
                        publish()
                        notifier.progress(item)
                    }
                },
                isActive = { item.status == DownloadStatus.DOWNLOADING },
            )

            val size = temp.length()
            val locator = DownloadStorageHelper.commit(appContext, item, temp)
                ?: throw DownloadException("Could not save file")

            item.progress = 1f
            item.totalBytes = size
            item.downloadedBytes = size
            item.status = DownloadStatus.FINISHED
            addCompleted(item, locator, size)
            items.removeAll { it.id == item.id }
            persist()
            publish()
            notifier.completed(item)
            Log.i("AnimeDownload", "saved ${item.mediaName} ep${item.episodeNumber} ($size bytes) -> $locator")
        } catch (e: DownloadCancelledException) {
            temp?.takeIf { it.exists() }?.delete()
            item.status = DownloadStatus.PAUSED
            persist()
            publish()
        } catch (e: kotlinx.coroutines.CancellationException) {
            temp?.takeIf { it.exists() }?.delete()
            throw e
        } catch (e: Exception) {
            temp?.takeIf { it.exists() }?.delete()
            item.status = DownloadStatus.ERROR
            item.error = e.message ?: "Download failed"
            persist()
            publish()
            notifier.error(item, item.error)
            Log.e("AnimeDownload", "failed ${item.mediaName} ep${item.episodeNumber}: ${item.error}", e)
        } finally {
            activeJobs.remove(item.id)
            publish()
            pump()
        }
    }

    private fun addCompleted(item: DownloadItem, path: String, size: Long) {
        completedItems.removeAll { it.id == item.id }
        completedItems.add(
            0,
            DownloadedItem(
                id = item.id,
                mediaId = item.mediaId,
                mediaName = item.mediaName,
                cover = item.cover,
                episodeNumber = item.episodeNumber,
                episodeTitle = item.episodeTitle,
                path = path,
                sizeBytes = size,
                timestamp = System.currentTimeMillis(),
            ),
        )
        persistCompleted()
        publishCompleted()
    }

    // ---------------------------------------------------------------- helpers

    private fun isOnWifi(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun publish() {
        _queue.value = items.map { it.copy() }
        val now = System.currentTimeMillis()
        if (now - lastPersist > 1500L) {
            lastPersist = now
            persist()
        }
    }

    private fun publishCompleted() {
        _completed.value = completedItems.toList()
    }

    private fun persist() = DownloadStore.saveQueue(items)

    private fun persistCompleted() = DownloadStore.saveCompleted(completedItems)
}