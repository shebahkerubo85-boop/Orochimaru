package com.lagradost.cloudstream3.utils

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@SuppressLint("ThreadConstraint")
@AnyThread
actual fun runOnMainThreadNative(@MainThread work: () -> Unit) {
    val mainLooper = Looper.getMainLooper()
    if (mainLooper.isCurrentThread) {
        work()
    } else {
        Handler(mainLooper).post(work)
    }
}

actual val workerDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual typealias WorkerThread = androidx.annotation.WorkerThread

fun runOnMainThread(work: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        work()
    } else {
        Handler(Looper.getMainLooper()).post(work)
    }
}

object Coroutines {
    val main: CoroutineDispatcher = Dispatchers.Main
    val io: CoroutineDispatcher = Dispatchers.IO

    fun mainWork(work: suspend () -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { work() }
    }

    fun <T> atomicListOf(vararg elements: T): java.util.concurrent.CopyOnWriteArrayList<T> =
        java.util.concurrent.CopyOnWriteArrayList(elements.toList())

    suspend fun runOnMainThread(suspend work: suspend () -> Unit) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { work() }
    }
}
