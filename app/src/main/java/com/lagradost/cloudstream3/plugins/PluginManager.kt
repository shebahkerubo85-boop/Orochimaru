package com.lagradost.cloudstream3.plugins

import android.content.Context
import java.io.File

object PluginManager {
    const val TAG = "PluginManager"

    var currentlyLoading: String? = null

    // Maps filepath to plugin
    val plugins: MutableMap<String, BasePlugin> =
        LinkedHashMap<String, BasePlugin>()

    fun getPluginPath(context: Context, apiName: String, version: String): File {
        return File(context.filesDir, "cs_plugins/${apiName}_$version.cs3")
    }

    fun getPlugins(): Map<String, BasePlugin> = plugins

    fun getPluginsOnline(): Array<PluginData> = emptyArray()

    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean = false

    fun unloadPlugin(url: String) {
        plugins.remove(url)
    }
}
