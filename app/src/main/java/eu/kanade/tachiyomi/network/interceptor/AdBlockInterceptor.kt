package eu.kanade.tachiyomi.network.interceptor

import ani.sanin.cloudstream.AdBlocker
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Blocks HTTP requests to known ad / redirect domains used by CNCVerse
 * (Cricify) plugin. Only active when the "HTTP/Intent Interceptor" toggle
 * is enabled in Settings > Common. When disabled, passes everything through.
 */
class AdBlockInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!PrefManager.getVal<Boolean>(PrefName.AdBlockInterceptor)) {
            return chain.proceed(chain.request())
        }

        val host = chain.request().url.host.lowercase()
        if (AdBlocker.isAdHost(host)) {
            return Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("Blocked by AdBlockInterceptor")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        return chain.proceed(chain.request())
    }
}
