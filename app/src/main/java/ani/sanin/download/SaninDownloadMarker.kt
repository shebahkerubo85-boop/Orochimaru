package ani.sanin.download

import ani.sanin.util.Logger
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Persistent set of download ids that the Sanin pipeline owns.
 *
 * Stored under a clearly-Sanin-namespaced key so it cannot collide
 * with any existing CloudStream key. The bridge adds an id when it
 * enqueues a Sanin download and the supervisor removes it when the
 * download reaches a terminal state (success, failure, cancel).
 *
 * The set is intentionally small and read on every queue pop, so
 * there is no risk of growing it without bound.
 */
object SaninDownloadMarker {

    private const val FOLDER = "sanin_active"
    private const val KEY = "ids"

    fun isMarked(id: Int): Boolean {
        val marked = read().contains(id)
        if (marked) Logger.log("SANIN_MARK: isMarked=$id true")
        return marked
    }

    fun add(id: Int) {
        val current = read()
        if (id in current) return
        Logger.log("SANIN_MARK: add $id (total ${current.size} -> ${current.size + 1})")
        CloudStreamApp.setKey(FOLDER, KEY, (current + id).sorted())
    }

    fun remove(id: Int) {
        val current = read()
        if (id !in current) return
        Logger.log("SANIN_MARK: remove $id (total ${current.size} -> ${current.size - 1})")
        val next = current.filterNot { it == id }
        if (next.isEmpty()) {
            CloudStreamApp.removeKey(FOLDER, KEY)
        } else {
            CloudStreamApp.setKey(FOLDER, KEY, next)
        }
    }

    fun all(): List<Int> = read()

    private fun read(): List<Int> {
        return CloudStreamApp.getKey<List<Int>>(FOLDER, KEY) ?: emptyList()
    }
}
