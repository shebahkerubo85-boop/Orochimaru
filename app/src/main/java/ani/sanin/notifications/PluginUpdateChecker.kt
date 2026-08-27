package ani.sanin.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.sanin.R
import ani.sanin.cloudstream.CsInstalledSource
import ani.sanin.cloudstream.CsRepos
import ani.sanin.cloudstream.CsSource
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object PluginUpdateChecker {

    private const val NOTIFICATION_ID = -1101

    data class UpdateInfo(
        val id: String,
        val displayName: String,
        val type: String,
        val installedVersion: Int,
        val availableVersion: Int,
        val repoUrl: String
    )

    suspend fun checkAndNotify(context: Context) {
        withContext(Dispatchers.IO) {
            PrefManager.init(context)
            if (!PrefManager.getVal<Boolean>(PrefName.CheckUpdate)) return@withContext

            val autoUpdate = PrefManager.getVal<Boolean>(PrefName.AutoUpdateExtensions)

            val csUpdates = checkCloudStreamUpdates(context)
            val animeUpdates = checkAnimeExtensionUpdates()

            if (autoUpdate) {
                val updatedNames = mutableListOf<String>()

                // Auto-update CloudStream plugins
                for (info in csUpdates) {
                    val repoSource = findRepoSource(context, info)
                    if (repoSource != null) {
                        runCatching {
                            CsRepos.install(context, info.repoUrl, repoSource)
                            updatedNames.add(info.displayName)
                            Logger.log("PluginUpdateChecker: Auto-updated CS plugin ${info.displayName}")
                        }
                    }
                }

                // Auto-update Aniyomi extensions
                for (ext in animeUpdates) {
                    runCatching {
                        val manager: AnimeExtensionManager = Injekt.get()
                        manager.updateExtension(ext)
                        updatedNames.add(ext.name)
                        Logger.log("PluginUpdateChecker: Auto-updated anime extension ${ext.name}")
                    }
                }

                if (updatedNames.isNotEmpty()) {
                    showNotification(
                        context,
                        "Plugins Updated",
                        "Updated ${updatedNames.size} plugin(s): ${updatedNames.joinToString(", ")}"
                    )
                }
            } else {
                // Notification-only mode: show what has updates available
                val allUpdates = mutableListOf<String>()

                for (info in csUpdates) {
                    allUpdates.add("${info.displayName} (v${info.installedVersion} → v${info.availableVersion})")
                }
                for (ext in animeUpdates) {
                    allUpdates.add(ext.name)
                }

                if (allUpdates.isNotEmpty()) {
                    val summary = allUpdates.joinToString("\n")
                    showNotification(
                        context,
                        "${allUpdates.size} Plugin Update${if (allUpdates.size > 1) "s" else ""} Available",
                        summary
                    )
                }
            }
        }
    }

    private suspend fun checkCloudStreamUpdates(context: Context): List<UpdateInfo> = withContext(Dispatchers.IO) {
        val installed = CsRepos.installed(context)
        val updates = mutableListOf<UpdateInfo>()
        val seenIds = mutableSetOf<String>()

        for (source in installed) {
            if (source.id in seenIds) continue
            val repos = CsRepos.repos()
            for (repoUrl in repos) {
                runCatching {
                    val plugins = CsRepos.getRepoPlugins(repoUrl)
                    val available = plugins.find { it.id == source.id }
                    if (available != null && available.version > source.version) {
                        updates.add(
                            UpdateInfo(
                                id = source.id,
                                displayName = source.name,
                                type = source.type,
                                installedVersion = source.version,
                                availableVersion = available.version,
                                repoUrl = repoUrl
                            )
                        )
                        seenIds.add(source.id)
                    }
                }
            }
        }
        updates
    }

    private suspend fun checkAnimeExtensionUpdates(): List<eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Installed> {
        return try {
            val manager: AnimeExtensionManager = Injekt.get()
            val installed = manager.installedExtensionsFlow.first()
            installed.filter { it.hasUpdate }
        } catch (e: Exception) {
            Logger.log("PluginUpdateChecker: Failed to check anime extensions: ${e.message}")
            emptyList()
        }
    }

    private suspend fun findRepoSource(context: Context, info: UpdateInfo): CsSource? = withContext(Dispatchers.IO) {
        runCatching {
            val plugins = CsRepos.getRepoPlugins(info.repoUrl)
            plugins.find { it.id == info.id && it.version == info.availableVersion }
        }.getOrNull()
    }

    private fun showNotification(context: Context, title: String, text: String) {
        val intent = Intent(context, Class.forName("ani.sanin.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_EXTENSIONS_UPDATE)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, notification)
        }
    }
}
