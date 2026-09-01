package com.lagradost.cloudstream3

import android.content.Intent
import com.lagradost.cloudstream3.utils.Event
import java.io.File

/**
 * Minimal host stub for upstream cloudstream3 references.
 * The real Activity lives in ani.sanin.MainActivity.
 */
class MainActivity {
    companion object {
        var activityResultLauncher: androidx.activity.result.ActivityResultLauncher<Intent>? = null

        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()

        var nextSearchQuery: String? = null

        const val API_NAME_EXTRA_KEY = "API_NAME_EXTRA_KEY"

        private const val FILE_DELETE_KEY = "FILES_TO_DELETE_KEY"

        private var filesToDelete: MutableSet<String> = mutableSetOf()

        fun deleteFileOnExit(file: File) {
            filesToDelete.add(file.absolutePath)
        }

        fun deleteFilesOnExit(context: android.content.Context) {
            val prefs = context.getSharedPreferences("cloudstream_exit", 0)
            val existing = prefs.getStringSet(FILE_DELETE_KEY, emptySet()) ?: emptySet()
            prefs.edit().putStringSet(FILE_DELETE_KEY, existing + filesToDelete).apply()
        }

        fun cleanDeletedFiles(context: android.content.Context) {
            val prefs = context.getSharedPreferences("cloudstream_exit", 0)
            val files = prefs.getStringSet(FILE_DELETE_KEY, emptySet()) ?: emptySet()
            files.forEach { path ->
                try { File(path).delete() } catch (_: Exception) {}
            }
            prefs.edit().remove(FILE_DELETE_KEY).apply()
        }

        fun handleAppIntentUrl(context: android.content.Context, intent: Intent?) {}
    }
}
