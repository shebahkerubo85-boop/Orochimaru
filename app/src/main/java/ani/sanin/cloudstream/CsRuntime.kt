package ani.sanin.cloudstream

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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
        setContext(context.applicationContext)
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
     * Some plugins (e.g. StremioX) capture the hosting `Activity` at `load()` time
     * and reuse it inside openSettings. Our plugins are load()ed against the
     * application context, so that captured activity is null and the sheet never
     * shows. To fix it we re-instantiate the plugin with [activity] as its load
     * context — so its openSettings captures a real activity — then immediately
     * undo the duplicate MainAPI registration that re-loading causes (we only want
     * the freshly-bound openSettings). Falls back to the already-loaded plugin's
     * opener for plugins that bind the activity at call time.
     */
    fun openSettingsFor(
        context: Context,
        source: CsInstalledSource,
        activity: AppCompatActivity = context as? AppCompatActivity,
    ): ((android.content.Context) -> Unit)? {
        if (!plugins.containsKey(source.id)) {
            if (!load(context, source)) return null
        }
        val plugin = plugins[source.id] as? Plugin ?: return null
        val cached = plugin.openSettings ?: return null

        return { ctx ->
            val act = activity ?: throw IllegalStateException(
                "No live AppCompatActivity to host ${source.name} settings"
            )
            val rebound = runCatching { freshSettingsOpener(context, source, act) }
                .onFailure {
                    Log.e(TAG, "Falling back to cached opener for ${source.name} (fresh bind failed)", it)
                }
                .getOrNull()

            val invoke: (android.content.Context) -> Unit = rebound ?: cached
            try {
                invoke(act)
            } catch (t: Throwable) {
                // Plugin threw inside its own settings code. Log the FULL stack
                // (the toast callers show only carries the message) so the exact
                // frame is recoverable from logcat, then rethrow for the toast.
                Log.e(TAG, "Plugin ${source.name} failed to open settings", t)
                throw t
            }
        }
    }

    /** Re-instantiate the plugin in [file] against [activity] and return its
     *  freshly-bound `openSettings`, undoing the duplicate MainAPI registration
     *  (we only want the opener, not a second copy of every provider). */
    private fun freshSettingsOpener(
        context: Context,
        source: CsInstalledSource,
        activity: AppCompatActivity,
    ): ((android.content.Context) -> Unit)? {
        val file = CsRepos.installedFile(context, source)
        if (!file.exists()) return null

        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val manifest = loader.getResourceAsStream("manifest.json")?.use { stream ->
            InputStreamReader(stream).use { reader ->
                parseJson<BasePlugin.Manifest>(reader.readText())
            }
        } ?: return null
        val className = manifest.pluginClassName ?: return null
        val instance = loader.loadClass(className).getDeclaredConstructor().newInstance() as? Plugin
            ?: return null
        instance.filename = file.absolutePath
        if (manifest.requiresResources) {
            instance.resources = buildPluginResources(context, file)
        }
        val providers = APIHolder.allProviders
        val before = synchronized(providers) { providers.toList() }
        instance.load(activity) // binds openSettings against the real activity
        synchronized(providers) {
            providers.filter { it !in before }.forEach { dup ->
                providers.remove(dup)
                runCatching { APIHolder.removePluginMapping(dup) }
            }
        }
        return instance.openSettings
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
