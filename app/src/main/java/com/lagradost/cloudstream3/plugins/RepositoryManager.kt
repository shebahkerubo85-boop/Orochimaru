package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData

object RepositoryManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"

    fun getRepositories(): Array<RepositoryData> = emptyArray()

    suspend fun getRepoPlugins(repositoryData: RepositoryData): Array<PluginWrapper> = emptyArray()
}
