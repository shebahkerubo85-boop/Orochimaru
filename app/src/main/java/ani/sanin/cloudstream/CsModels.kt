package ani.sanin.cloudstream

import kotlinx.serialization.Serializable

@Serializable
data class CsSource(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val type: String = "anime",
    val lang: String = "en",
    val file: String,
    val nsfw: Boolean = false,
    val logo: String? = null
)

@Serializable
data class CsRepoManifest(
    val name: String,
    val description: String? = null,
    val sources: List<CsSource> = emptyList()
)

@Serializable
data class CsInstalledSource(
    val id: String,
    val name: String,
    val version: String,
    val type: String,
    val lang: String,
    val file: String,
    val repoUrl: String,
    val installedAt: Long = System.currentTimeMillis()
)
