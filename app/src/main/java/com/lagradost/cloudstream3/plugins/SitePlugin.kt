package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Status int as the following:
 * 0: Down
 * 1: Ok
 * 2: Slow
 * 3: Beta only
 */
@Serializable
data class SitePlugin(
    @JsonProperty("url") @SerialName("url") val url: String,
    @JsonProperty("status") @SerialName("status") val status: Int,
    @JsonProperty("version") @SerialName("version") val version: Int,
    @JsonProperty("apiVersion") @SerialName("apiVersion") val apiVersion: Int,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("internalName") @SerialName("internalName") val internalName: String,
    @JsonProperty("authors") @SerialName("authors") val authors: List<String>,
    @JsonProperty("description") @SerialName("description") val description: String?,
    @JsonProperty("repositoryUrl") @SerialName("repositoryUrl") val repositoryUrl: String?,
    @JsonProperty("tvTypes") @SerialName("tvTypes") val tvTypes: List<String>?,
    @JsonProperty("language") @SerialName("language") val language: String?,
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("fileSize") @SerialName("fileSize") val fileSize: Long?,
    @JsonProperty("fileHash") @SerialName("fileHash") val fileHash: String?,
)
