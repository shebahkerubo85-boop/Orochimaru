package ani.sanin.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ani.sanin.App
import ani.sanin.BuildConfig
import ani.sanin.snackString
import java.io.File
import java.util.Date

/**
 * Dedicated, always-available log file for the AnimeJL provider.
 * Unlike the global [Logger], this does not depend on the "Log to file"
 * preference, so AnimeJL diagnostics can always be captured and shared.
 */
object AnimeJLLog {

    private const val MAX_SIZE = 1024L * 1024L * 3 // 3 MB

    val file: File?
        get() = runCatching {
            val dir = App.instance?.getExternalFilesDir(null) ?: return@runCatching null
            File(dir, "animejl_log.txt").also { f ->
                if (f.exists() && f.length() > MAX_SIZE) {
                    f.delete()
                    f.createNewFile()
                } else if (!f.exists()) {
                    f.createNewFile()
                }
            }
        }.getOrNull()

    fun write(message: String) {
        runCatching { file?.appendText("${Date()} | $message\n") }
    }

    fun share(context: Context) {
        val f = file
        if (f == null || !f.exists() || f.length() == 0L) {
            snackString("No AnimeJL log file found")
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}.provider",
                    f
                )
            )
            putExtra(Intent.EXTRA_SUBJECT, "AnimeJL Log")
            putExtra(Intent.EXTRA_TEXT, "AnimeJL Log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share AnimeJL log"))
    }
}
