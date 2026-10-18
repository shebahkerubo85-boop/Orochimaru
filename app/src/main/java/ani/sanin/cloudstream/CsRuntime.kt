package ani.sanin.cloudstream

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.PathClassLoader
import java.io.File
import java.io.InputStreamReader

class CsPluginException(message: String) : Exception(message)

/**
 * Hosts installed CloudStream `.cs3` plugins. A `.cs3` file is a compiled
 * dex archive that links against the vendored `com.lagradost.cloudstream3`
 * library, so it can not run on the JS engine. We load it with a
 * [PathClassLoader], instantiate its [BasePlugin] entry point, and let it
 * register its [MainAPI] providers into [APIHolder].
 */
object CsRuntime {

    private const val TAG = "CsRuntime"

    private val plugins = mutableMapOf<String, BasePlugin>()

    /** Returns the providers registered by a plugin, keyed by source id. */
    private val apis = mutableMapOf<String, List<MainAPI>>()

    /** Details of the most recent load failure (exception class + message), if any. */
    var lastError: String? = null
        private set

    @Synchronized
    fun load(context: Context, source: CsInstalledSource): Boolean {
        // Pin the plugin store to the process-lifetime application context BEFORE
        // any plugin class is loaded (mirrors Zangetsu's PluginHost init). See
        // CloudStreamApp.context: it must never point at a transient activity.
        setContext(context.applicationContext)
        CloudStreamApp.pinContext(context.applicationContext)
        if (plugins.containsKey(source.id)) return true

        val file = CsRepos.installedFile(context, source)
        if (!file.exists()) return false

        return runCatching {
            // Android 8+ refuses to dex-load a writable file ("Writable dex file ... is not allowed"),
            // so mirror CloudStream: make the plugin file read-only before creating the class loader.
            if (!file.setReadOnly()) {
                Log.e(TAG, "Failed to set read-only on ${file.name}")
            }
            val loader = PathClassLoader(file.absolutePath, context.classLoader)

            val manifest: BasePlugin.Manifest =
                loader.getResourceAsStream("manifest.json")?.use { stream ->
                    InputStreamReader(stream).use { reader ->
                        parseJson<BasePlugin.Manifest>(reader.readText())
                    }
                } ?: throw CsPluginException("No manifest.json found in ${file.name}")

            val className = manifest.pluginClassName
                ?: throw CsPluginException("No pluginClassName in ${file.name} manifest")

            @Suppress("UNCHECKED_CAST")
            val pluginClass = loader.loadClass(className) as Class<out BasePlugin>
            Log.i(TAG, "Loaded class $className for ${source.name}")
            // Most plugins register their MainAPI providers in the constructor (init block),
            // so snapshot the provider list before instantiating.
            val before = APIHolder.allProviders.size
            val instance = pluginClass.getDeclaredConstructor().newInstance()
            instance.filename = file.absolutePath
            Log.i(TAG, "Instantiated ${instance::class.java.name}, providers before=$before")
            if (manifest.requiresResources && instance is Plugin) {
                // Plugin was built with requiresResources: give it a Resources
                // wrapper backed by its own asset path (same as CloudStream).
                instance.resources = buildPluginResources(context, file)
            }
            if (instance is Plugin) {
                instance.load(context)
            } else {
                instance.load()
            }
            val after = APIHolder.allProviders.size
            val registered = APIHolder.allProviders.subList(before, after).toList()
            lastError = null
            plugins[source.id] = instance
            apis[source.id] = registered
            Log.i(TAG, "Loaded ${source.name} (${registered.size} providers, providers after=$after)")
            true
        }.getOrElse { t ->
            lastError = t::class.java.simpleName + (t.message?.let { ": $it" } ?: "")
            Log.e(TAG, "Failed to load ${source.name}: ${lastError}", t)
            plugins.remove(source.id)
            apis.remove(source.id)
            false
        }
    }

    /** All MainAPI providers registered by this source, loading the plugin first. */
    fun apisFor(context: Context, source: CsInstalledSource): List<MainAPI> {
        if (!plugins.containsKey(source.id)) {
            if (!load(context, source)) return emptyList()
        }
        return apis[source.id].orEmpty()
    }

    fun isLoaded(sourceId: String): Boolean = plugins.containsKey(sourceId)

    /**
     * Returns the loaded plugin's custom-settings callback, if any.
     *
     * Mirrors Zangetsu's working host (com.spyou.watch_app.cloudstream.PluginHost):
     * the caller is a dedicated, transparent `CloudStreamSettingsActivity` carrying
     * a MaterialComponents theme — NOT the busy, Material3 theme main activity,
     * which crashes a plugin's BottomSheetDialog (ComponentDialog NPE).
     *
     * Two things force us to re-instantiate the plugin against the LIVE activity
     * on every open instead of reusing an opener cached earlier:
     *
     *  - Fragment/BottomSheet plugins (Ultima, CNCVerse, ...) capture their host
     *    Activity's `supportFragmentManager` inside `load()` and later call
     *    `DialogFragment.show(fm)`. Every settings tap creates a NEW
     *    [CloudStreamSettingsActivity], so an opener cached from a previous one
     *    points at a destroyed FragmentManager and throws
     *    `IllegalStateException: FragmentManager has been destroyed`.
     *  - Plugins built against an Activity context often only populate
     *    [Plugin.openSettings] inside `load(activity)`; loading them against the
     *    application context (as [load] does) leaves it null, which used to be
     *    mistaken for "this plugin has no settings".
     *
     * Re-instantiating re-runs the plugin's `load()`, so the duplicate MainAPI /
     * ExtractorApi registrations it makes are dropped again by
     * [freshSettingsOpener] — we only want the freshly-bound opener. If the fresh
     * bind fails we fall back to the already-loaded plugin's opener for plugins
     * that bind the activity at call time instead of at load time.
     */
    fun openSettingsFor(
        context: Context,
        source: CsInstalledSource,
        activity: AppCompatActivity? = context as? AppCompatActivity,
    ): ((android.content.Context) -> Unit)? {
        if (!plugins.containsKey(source.id)) {
            if (!load(context, source)) return null
        }
        val plugin = plugins[source.id] as? Plugin ?: return null
        val act = activity ?: (context as? AppCompatActivity)

        val fresh = act?.let { live ->
            runCatching { freshSettingsOpener(context, source, live) }
                .onFailure { t ->
                    Log.e(TAG, "Fresh settings bind failed for ${source.name}", t)
                    lastError = "Fresh settings bind failed: ${t.message}"
                }
                .getOrNull()
        }
        val bound = fresh ?: run {
            Log.w(TAG, "Falling back to loaded plugin.openSettings for ${source.name} (fresh bind null/failed); resources=${plugin.resources != null}")
            lastError = if (fresh == null && plugin.openSettings == null) "No settings opener bound" else lastError
            plugin.openSettings
        } ?: return null

        return { ctx ->
            try {
                bound(ctx)
            } catch (t: Throwable) {
                Log.e(TAG, "Plugin ${source.name} failed to open settings", t)
                throw t
            }
        }
    }

    /** Re-instantiate the plugin in [file] against [activity] and return its
     *  freshly-bound `openSettings`, undoing the duplicate MainAPI/ExtractorApi
     *  registration re-running `load()` causes (we only want the opener, not a
     *  second copy of every provider).
     *
     *  The undo runs in a `finally` so a plugin that throws mid-`load()` still
     *  can't leave half-registered providers behind. Removal is by identity, not
     *  by `filename`: both copies of a provider carry the same `sourcePlugin`, so
     *  filename-based cleanup would evict the original registrations too. */
    private fun freshSettingsOpener(
        context: Context,
        source: CsInstalledSource,
        activity: AppCompatActivity,
    ): ((android.content.Context) -> Unit)? {
        val file = CsRepos.installedFile(context, source)
        if (!file.exists()) return null
        // Same read-only requirement as load(): Android 8+ refuses writable dex files.
        if (!file.setReadOnly()) {
            Log.e(TAG, "Failed to set read-only on ${file.name}")
        }

        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val manifest = loader.getResourceAsStream("manifest.json")?.use { stream ->
            InputStreamReader(stream).use { reader ->
                parseJson<BasePlugin.Manifest>(reader.readText())
            }
        } ?: return null
        val className = manifest.pluginClassName ?: return null

        val providers = APIHolder.allProviders
        val extractors = extractorApis
        // Snapshot BEFORE the constructor runs: providers register in init blocks.
        val beforeProviders = providers.withLock { providers.toList() }
        val beforeExtractors = extractors.withLock { extractors.toList() }

        var instance: Plugin? = null
        try {
            val created = loader.loadClass(className).getDeclaredConstructor().newInstance() as? Plugin
                ?: return null
            instance = created
            created.filename = file.absolutePath
            if (manifest.requiresResources) {
                created.resources = buildPluginResources(context, file)
                Log.d(TAG, "Built plugin resources for ${source.name} (pkg=${manifest.name}, hasRes=${created.resources!=null})")
            } else {
                Log.d(TAG, "No resources required for ${source.name}")
            }
            // load() may set openSettings and THEN throw (e.g. a plugin that also
            // kicks off a network call). Swallow it: the authoritative copy of this
            // plugin was already loaded successfully by load(), and an opener set
            // before the throw is still worth keeping — reporting "has no settings"
            // would be wrong. The invoke site catches whatever the opener raises.
            runCatching { created.load(activity) } // binds openSettings against the real activity
                .onFailure { t ->
                    Log.e(TAG, "Plugin ${source.name} load() failed during settings bind", t)
                }
        } finally {
            providers.withLock {
                providers.filter { it !in beforeProviders }.forEach { dup ->
                    providers.remove(dup)
                    runCatching { APIHolder.removePluginMapping(dup) }
                }
            }
            extractors.withLock {
                extractors.filter { it !in beforeExtractors }.forEach { dup ->
                    extractors.remove(dup)
                }
            }
        }
        return instance?.openSettings
    }

    private fun buildPluginResources(context: Context, file: File): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(assets, file.absolutePath)
        @Suppress("DEPRECATION")
        return Resources(
            assets,
            context.resources.displayMetrics,
            context.resources.configuration
        )
    }

    @Synchronized
    fun unload(sourceId: String) {
        plugins.remove(sourceId)?.let { plugin ->
            runCatching { plugin.beforeUnload() }
        }
        apis.remove(sourceId)
    }
}
