package ani.sanin.addons.download

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ani.sanin.R
import ani.sanin.addons.AddonInstallReceiver
import ani.sanin.addons.AddonListener
import ani.sanin.addons.AddonLoader
import ani.sanin.addons.AddonManager
import ani.sanin.addons.LoadResult
import ani.sanin.media.AddonType
import ani.sanin.util.Logger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.InstallStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class DownloadAddonManager(
    private val context: Context
) : AddonManager<DownloadAddon.Installed>(context) {

    override var extension: DownloadAddon.Installed? = null
    override var name: String = "Download Addon"
    override var type = AddonType.DOWNLOAD

    private val _isInitialized = MutableLiveData(false)
    val isInitialized: LiveData<Boolean> = _isInitialized

    private var error: String? = null

    override suspend fun init() {
        error = null
        hasUpdate = false
        extension = DownloadAddon.Installed(
            name = "Built-in Download Addon",
            pkgName = context.packageName,
            versionName = "1.0.0",
            versionCode = 1,
            extension = NativeVideoDownloader(context),
            icon = androidx.core.content.ContextCompat.getDrawable(context, ani.sanin.R.mipmap.ic_launcher)
        )
        Logger.log("Download addon initialized successfully (built-in)")
        withContext(Dispatchers.Main) {
            _isInitialized.value = true
        }
    }

    override fun isAvailable(andEnabled: Boolean): Boolean {
        return true
    }

    override fun getVersion(): String? {
        return extension?.versionName
    }

    override fun getPackageName(): String? {
        return extension?.pkgName
    }

    override fun hadError(context: Context): String? {
        return if (isInitialized.value == true) {
            if (error != null) {
                error
            } else if (extension != null) {
                context.getString(R.string.loaded_successfully)
            } else {
                null
            }
        } else {
            null
        }
    }

    private inner class InstallationListener : AddonListener {
        override fun onAddonInstalled(result: LoadResult?) {
            if (result is DownloadLoadResult.Success) {
                extension = result.extension
                hasUpdate = false
                onListenerAction?.invoke(AddonListener.ListenerAction.INSTALL)
            }
        }

        override fun onAddonUpdated(result: LoadResult?) {
            if (result is DownloadLoadResult.Success) {
                extension = result.extension
                hasUpdate = false
                onListenerAction?.invoke(AddonListener.ListenerAction.UPDATE)
            }
        }

        override fun onAddonUninstalled(pkgName: String) {
            if (extension?.pkgName == pkgName) {
                extension = null
                hasUpdate = false
                onListenerAction?.invoke(AddonListener.ListenerAction.UNINSTALL)
            }
        }

    }

    override fun updateInstallStep(id: Long, step: InstallStep) {
        installer.updateInstallStep(id, step)
    }

    override fun setInstalling(id: Long) {
        installer.updateInstallStep(id, InstallStep.Installing)
    }


    companion object {

        const val DOWNLOAD_PACKAGE = "dantotsu.downloadAddon"
        const val DOWNLOAD_CLASS = "ani.sanin.downloadAddon.DownloadAddon"
        const val REPO = "rebelonion/Sanin-Download-Addon"
    }
}
