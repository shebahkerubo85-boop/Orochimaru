package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Repository(
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("description") @SerialName("description") val description: String?,
    @JsonProperty("manifestVersion") @SerialName("manifestVersion") val manifestVersion: Int,
    @JsonProperty("pluginLists") @SerialName("pluginLists") val pluginLists: List<String>,
)

@Serializable
data class PluginWrapper(
    @JsonProperty("repository") @SerialName("repository") val repository: Repository,
    @JsonProperty("repositoryData") @SerialName("repositoryData") val repositoryData: RepositoryData,
    @JsonProperty("plugin") @SerialName("plugin") val plugin: SitePlugin
) {
    companion object {
        private val localRepository = Repository("", "", "", 1, emptyList())
        private val localRepositoryData = RepositoryData("", "", "")
        fun getLocalPluginWrapper(plugin: SitePlugin): PluginWrapper {
            return PluginWrapper(
                localRepository,
                localRepositoryData,
                plugin
            )
        }
    }
}
