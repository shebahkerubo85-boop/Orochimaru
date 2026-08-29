package ani.sanin.cloudstream

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
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
     * CloudStream plugins resolve the fragment manager for their settings sheet in
     * two ways: either they cache an `Activity` on themselves during `load()` (e.g.
     * Ultima's `activity` field), or they read CloudStream's global context statics.
     * Both go stale after the host activity is destroyed (navigation or rotation,
     * which shows up as "fragment manager has been destroyed"). Before invoking the
     * plugin's settings callback, point every activity source at the live activity
     * so the sheet is committed to a visible window.
     */
    private fun syncPluginActivity(plugin: BasePlugin, context: Context) {
        val activity = resolveActivity(context) ?: return
        val target = activity as? AppCompatActivity ?: return

        // CloudStream globals that plugins read to obtain the current activity.
        runCatching { com.lagradost.cloudstream3.CommonActivity.setActivityInstance(target) }
        runCatching { setContext(target) }

        // Refresh every Activity-typed field on the plugin instance (any name), so
        // plugins that captured the load-time activity pick up the live one.
        runCatching {
            var clazz: Class<*>? = plugin.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (field in clazz.declaredFields) {
                    if (!Activity::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    runCatching { field.set(plugin, target) }
                }
                clazz = clazz.superclass
            }
        }
    }

    private fun resolveActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx != null) {
            when (ctx) {
                is Activity -> return ctx
                is android.content.ContextWrapper -> ctx = ctx.baseContext
                else -> return null
            }
        }
        return null
    }

    /**
     * Returns the loaded plugin's custom-settings callback, if any.
     *
     * Settings plugins resolve the fragment manager for their sheet in two ways:
     * they capture the hosting `Activity` at `load()` time (Zangetsu's comment:
     * "Some plugins (e.g. StremioX) capture context as? AppCompatActivity at
     * LOAD time and reuse it in openSettings"), or they read the `ctx` they are
     * handed at call time (Ultima). Either way the sheet must be committed to a
     * visible, non-destroyed window, so mirror what working hosts (Zangetsu)
     * do:
     *
     *  - resolve the live `AppCompatActivity` at invocation time (not build
     *    time, so rotation between screenshots cannot point us at a destroyed
     *    window);
     *  - point CloudStream's statics (CommonActivity + ContextHelper) at it,
     *    exactly like Zangetsu's host init does;
     *  - re-instantiate the plugin against it so load()-captured activities are
     *    real, undoing the duplicate MainAPI registration;
     *  - fall back to the cached opener, synced to the live activity.
     */
    fun openSettingsFor(context: Context, source: CsInstalledSource): ((android.content.Context) -> Unit)? {
        if (!plugins.containsKey(source.id)) {
            if (!load(context, source)) return null
        }
        val plugin = plugins[source.id] as? Plugin ?: return null
        val cached = plugin.openSettings ?: return null

        return { ctx ->
            // Hand the plugin a real Activity, never a wrapped context. Plugins
            // cast the openSettings context to AppCompatActivity; a fragment's
            // requireContext() can be a ContextThemeWrapper, which makes the
            // cast null and the sheet silently never opens.
            val live = (resolveActivity(ctx) as? AppCompatActivity)
                ?: (resolveActivity(context) as? AppCompatActivity)
                ?: throw IllegalStateException("No live Activity to host ${source.name} settings")

            // Zangetsu host init: point CloudStream's globals at the live window
            // so any plugin path that reads them (CommonActivity, CloudStreamApp,
            // ContextHelper) sees a real, visible activity.
            runCatching { CommonActivity.setActivityInstance(live) }
            runCatching { setContext(live) }

            // Zangetsu's primary path: re-instantiate against the live activity
            // so plugins that captured the activity at load() bind their sheet
            // to a real window. Undo the duplicate MainAPI registration.
            val rebound = runCatching { freshSettingsOpener(context, source, live) }
                .onFailure {
                    Log.e(TAG, "Falling back to cached opener for ${source.name} (fresh bind failed)", it)
                }
                .getOrNull()

            val invoke: (android.content.Context) -> Unit = rebound ?: { act ->
                syncPluginActivity(plugin, act)
                cached(act)
            }
            try {
                invoke(live)
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
        // Let CloudStream's global statics resolve to this activity during load.
        runCatching { setContext(activity) }
        runCatching { CommonActivity.setActivityInstance(activity) }

        val before = APIHolder.allProviders.toList()
        try {
            instance.load(activity)
        } catch (t: Throwable) {
            // Never leave a partially-registered duplicate set behind.
            val dupes = APIHolder.allProviders.filter { it !in before }
            APIHolder.allProviders.removeAll(dupes)
            dupes.forEach { runCatching { APIHolder.removePluginMapping(it) } }
            return null
        }
        val dupes = APIHolder.allProviders.filter { it !in before }
        APIHolder.allProviders.removeAll(dupes)
        dupes.forEach { runCatching { APIHolder.removePluginMapping(it) } }
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
