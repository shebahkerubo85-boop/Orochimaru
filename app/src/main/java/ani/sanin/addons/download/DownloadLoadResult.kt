package ani.sanin.addons.download

import ani.sanin.addons.LoadResult

open class DownloadLoadResult : LoadResult() {
    class Success(val extension: DownloadAddon.Installed) : DownloadLoadResult()
}