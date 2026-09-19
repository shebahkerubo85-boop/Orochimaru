package ani.sanin.download

import kotlinx.coroutines.sync.Semaphore

/**
 * Shared global connection budget for Sanin segmented downloads.
 *
 * Active segment requests across all Sanin downloads must share this
 * pool. Using a [Semaphore] guarantees that permits are not leaked
 * (they are released by `finally`), and a single global instance means
 * downloads cannot accidentally create N * 4 connections when N
 * downloads each request 4.
 */
object SaninConnectionBudget {

    private const val DEFAULT_PERMITS = 4

    private val semaphore = Semaphore(DEFAULT_PERMITS)

    fun acquire() = semaphore.acquire()

    fun tryAcquire(): Boolean = semaphore.tryAcquire()

    fun release() = semaphore.release()

    fun availablePermits(): Int = semaphore.availablePermits

    fun isClosed(): Boolean = !semaphore.isNotClosed
}
