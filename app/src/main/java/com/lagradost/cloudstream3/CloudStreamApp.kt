package com.lagradost.cloudstream3

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey

/**
 * Host implementation of CloudStream's [CloudStreamApp] companion. Plugins link
 * against `CloudStreamApp.Companion.getContext()/setKey(...)`; the context is
 * wired into [com.lagradost.api.setContext] by [ani.sanin.cloudstream.CsRuntime]
 * before any plugin class is loaded.
 */
class CloudStreamApp {

    companion object {
        var exceptionHandler: Thread.UncaughtExceptionHandler? = null

        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): android.app.Activity? {
            return when (this) {
                is android.app.Activity -> this
                is android.content.ContextWrapper -> baseContext.getActivity()
                else -> null
            }
        }

        // Strong, stable reference to the application context, set once by the
        // host (CsRuntime.load / setContext). Kept STRONG (not a WeakReference)
        // exactly like Zangetsu's CloudStreamApp, so plugin prefs always read and
        // write against the process-lifetime app context. The settings flow
        // re-instantiates a plugin against a transient Activity; if that activity
        // ever leaked into this global here, its destruction would null the weak
        // ref and every subsequent plugin setKey/getKey would silently no-op —
        // the checkbox would never persist. Normalizing to applicationContext and
        // pinning it strongly makes the store stable across restarts.
        @Volatile
        private var _context: Context? = null
        var context: Context?
            get() = _context ?: (com.lagradost.api.getContext() as? Context)?.applicationContext
            private set(value) {
                if (value != null) pinContext(value)
            }

        /** Pin the plugin store to the process-lifetime application context. */
        fun pinContext(context: Context) {
            _context = context.applicationContext
            com.lagradost.api.setContext(_context)
        }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.getKey(path, valueType)
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        /** If fallbackWebView is true and a fragment is supplied then it will open a WebView with the URL if the browser fails. */
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
            context?.let { ctx ->
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Throwable) {
                }
            }
        }
    }
}
