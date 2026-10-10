package ani.sanin.download

import android.content.Context
import android.os.Build
import android.util.Log
import ani.sanin.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the optional standalone FFmpeg executable used by HLS downloads.
 *
 * The APK no longer bundles ffmpeg-kit. On a phone the user installs one ABI-specific executable
 * from the Worker-backed mirror; it lives in app-private files and can be deleted again. The TV
 * build does not use FFmpeg at all, so the entry is hidden there.
 */
object FfmpegRuntime {

    private const val TAG = "FfmpegRuntime"
    private const val VERSION = "android-2018"
    private const val BASE_URL = "https://sanin-ffmpeg.shemaus58.workers.dev"
    private const val MANIFEST_URL = "$BASE_URL/manifest.json"

    sealed interface State {
        data object Unsupported : State
        data object NotInstalled : State
        data class Downloading(
            val downloaded: Long,
            val total: Long,
            val progress: Float,
        ) : State

        data class Installed(
            val path: String,
            val sizeBytes: Long,
            val version: String,
        ) : State

        data class Error(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installLock = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<State>(State.Unsupported)
    val state: StateFlow<State> = _state.asStateFlow()

    private var appContext: Context? = null
    private var installJob: Job? = null

    val isSupported: Boolean
        get() = !BuildConfig.FLAVOR.contains("tv", ignoreCase = true) && selectedAbi() != null

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    fun refresh() {
        val context = appContext ?: return
        if (!isSupported) {
            _state.value = State.Unsupported
            return
        }
        val binary = binaryFile(context)
        _state.value = if (binary.isFile && binary.length() > 0L) {
            State.Installed(binary.absolutePath, binary.length(), VERSION)
        } else {
            State.NotInstalled
        }
    }

    fun install() {
        val context = appContext ?: return
        if (!isSupported || _state.value is State.Downloading) return
        if (binaryFile(context).isFile) {
            refresh()
            return
        }
        installJob?.cancel()
        installJob = scope.launch {
            installLock.withLock {
                _state.value = State.Downloading(0L, 0L, 0f)
                try {
                    installLocked(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "install failed", e)
                    deleteRuntime(context)
                    _state.value = State.Error(e.message ?: "Download failed")
                }
            }
        }
    }

    fun uninstall() {
        val context = appContext ?: return
        installJob?.cancel()
        installJob = null
        scope.launch {
            installLock.withLock {
                deleteRuntime(context)
            }
            refresh()
        }
    }

    /**
     * The executable used by the HLS remuxer. Callers are expected to have installed it from
     * Settings first; a missing runtime is a normal, user-actionable error rather than a crash.
     */
    fun requireBinary(): File {
        val context = appContext
            ?: throw DownloadException("FFmpeg runtime is not initialized")
        if (!isSupported) {
            throw DownloadException("HLS downloads are not supported in this build")
        }
        val binary = binaryFile(context)
        if (!binary.isFile || binary.length() <= 0L) {
            throw DownloadException(
                "FFmpeg is not installed. Open Settings -> Downloads and install it first.",
            )
        }
        return binary
    }

    /** Creates a PEM bundle from Android's trust store for the downloaded FFmpeg's OpenSSL. */
    fun certificateBundle(): File? {
        val context = appContext ?: return null
        val out = File(runtimeDir(context), "ca-bundle.pem")
        if (out.isFile && out.length() > 0L) return out
        return try {
            val candidates = listOf(
                File("/system/etc/security/cacerts"),
                File("/apex/com.android.conscrypt/cacerts"),
            )
            val certs = candidates
                .filter { it.isDirectory }
                .flatMap { dir -> dir.listFiles()?.sortedBy { it.name } ?: emptyList() }
                .filter { it.isFile }
            if (certs.isEmpty()) return null
            out.outputStream().buffered().use { output ->
                certs.forEach { cert ->
                    cert.inputStream().use { input -> input.copyTo(output) }
                    output.write('\n'.code)
                }
            }
            out.takeIf { it.length() > 0L }
        } catch (e: Exception) {
            Log.w(TAG, "could not build CA bundle", e)
            out.takeIf { it.isFile && it.length() > 0L }
        }
    }

    private suspend fun installLocked(context: Context) {
        val abi = selectedAbi() ?: throw DownloadException("Unsupported device ABI")
        val manifest = fetchManifest()
        val artifacts = manifest.optJSONObject("artifacts")
            ?: throw DownloadException("Invalid FFmpeg manifest")
        val artifact = artifacts.optJSONObject(abi)
            ?: throw DownloadException("No FFmpeg build for $abi")

        val url = artifact.optString("url").takeIf { it.isNotBlank() }
            ?: throw DownloadException("Invalid FFmpeg URL for $abi")
        val expectedSize = artifact.optLong("size", -1L)
        val expectedHash = artifact.optString("sha256").lowercase()
        if (expectedHash.isBlank()) throw DownloadException("Invalid FFmpeg checksum for $abi")

        val zip = File(context.cacheDir, "ffmpeg-$abi.zip.tmp")
        zip.delete()
        download(url, zip)
        if (expectedSize > 0L && zip.length() != expectedSize) {
            zip.delete()
            throw DownloadException("FFmpeg package size mismatch")
        }
        val actualHash = sha256(zip)
        if (actualHash != expectedHash) {
            zip.delete()
            throw DownloadException("FFmpeg package checksum mismatch")
        }

        val stageDir = File(context.cacheDir, "ffmpeg-stage-$abi")
        stageDir.deleteRecursively()
        stageDir.mkdirs()
        val stagedBinary = File(stageDir, "ffmpeg")
        extractExecutable(zip, stagedBinary)
        zip.delete()
        if (!stagedBinary.setExecutable(true, false)) {
            throw DownloadException("Could not mark FFmpeg executable")
        }
        verifyExecutable(stagedBinary)

        val finalDir = runtimeDir(context)
        finalDir.deleteRecursively()
        finalDir.mkdirs()
        val finalBinary = File(finalDir, "ffmpeg")
        if (!stagedBinary.renameTo(finalBinary)) {
            stagedBinary.copyTo(finalBinary, overwrite = true)
            stagedBinary.delete()
        }
        finalBinary.setExecutable(true, false)
        stageDir.deleteRecursively()
        _state.value = State.Installed(finalBinary.absolutePath, finalBinary.length(), VERSION)
        Log.i(TAG, "installed $abi ffmpeg (${finalBinary.length()} bytes)")
    }

    private fun fetchManifest(): JSONObject {
        val request = Request.Builder().url(MANIFEST_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DownloadException("FFmpeg manifest HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw DownloadException("Empty FFmpeg manifest")
            return JSONObject(body)
        }
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DownloadException("FFmpeg HTTP ${response.code}")
            val body = response.body ?: throw DownloadException("Empty FFmpeg response")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        done += read
                        val progress = if (total > 0L) {
                            (done.toFloat() / total).coerceIn(0f, 1f)
                        } else {
                            -1f
                        }
                        _state.value = State.Downloading(done, total, progress)
                    }
                }
            }
        }
    }

    private fun extractExecutable(zip: File, target: File) {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                if (entry.isDirectory) {
                    entry = stream.nextEntry
                    continue
                }
                val name = entry.name.replace('\\', '/').substringAfterLast('/')
                if (name == "ffmpeg") {
                    target.outputStream().buffered().use { output -> stream.copyTo(output) }
                    return
                }
                entry = stream.nextEntry
            }
        }
        throw DownloadException("FFmpeg executable missing from package")
    }

    private fun verifyExecutable(binary: File) {
        val process = try {
            ProcessBuilder(binary.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw DownloadException("FFmpeg could not start: ${e.message}")
        }
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw DownloadException("FFmpeg did not start in time")
        }
        if (process.exitValue() != 0) {
            throw DownloadException("FFmpeg check failed: ${output.takeLast(300).trim()}")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun selectedAbi(): String? {
        val abis = try {
            Build.SUPPORTED_ABIS
        } catch (_: Throwable) {
            return null
        }
        return abis.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" -> "arm64-v8a"
                "armeabi-v7a" -> "armeabi-v7a"
                else -> null
            }
        }
    }

    private fun runtimeDir(context: Context): File =
        File(context.filesDir, "ffmpeg/$VERSION").apply { mkdirs() }

    private fun binaryFile(context: Context): File = File(runtimeDir(context), "ffmpeg")

    private fun deleteRuntime(context: Context) {
        File(context.filesDir, "ffmpeg").deleteRecursively()
    }
}
